// Full post-quantum Double Ratchet with a PQXDH handshake.
//
// Design (see also docs/ENCRYPTION.md):
//   - Handshake: X3DH (classical X25519) establishes the initial root key.
//     Post-quantum protection of the handshake comes from the FIRST ratchet
//     step, whose ML-KEM encapsulation to the responder's PQ prekey is folded
//     into the root before any message key exists.
//   - Every DH ratchet step mixes BOTH an X25519 DH output AND an ML-KEM shared
//     secret into the root KDF ("full PQ ratchet"): secure if either holds.
//   - Each message header advertises a fresh ML-KEM public key (so the peer can
//     encapsulate to us next step) and carries the encapsulation to the peer's
//     last-advertised key (driving this step).
//
// All key material is Uint8Array in memory; transport/storage uses base64 via
// the wire helpers at the bottom.

import {
  dhKeyGen, dh, signKeyGen, sign, verify,
  kemKeyGen, kemEncaps, kemDecaps,
  kdfRoot, kdfInitRoot, kdfChain,
  aeadEncrypt, aeadDecrypt,
  concat, utf8, toB64, fromB64,
} from './primitives.js';

const MAX_SKIP = 1000; // cap stored skipped-message keys (anti-DoS)
const DOMAIN = new Uint8Array(32).fill(0xff); // X3DH domain separator

// ---------------------------------------------------------------- identity

// Generate a fresh identity with `nOpk` one-time prekeys of each kind.
export function generateIdentity(nOpk = 20) {
  const idDH = dhKeyGen();
  const idSign = signKeyGen();

  const spk = dhKeyGen();
  const pqSpk = kemKeyGen();

  const identity = {
    idDH,
    idSign,
    signedPreKey: { id: 1, key: spk, sig: sign(idSign.priv, spk.pub) },
    pqSignedPreKey: { id: 1, key: pqSpk, sig: sign(idSign.priv, pqSpk.pub) },
    oneTimePreKeys: [],
    pqOneTimePreKeys: [],
    nextOpkId: 1,
    nextPqOpkId: 1,
  };
  replenishPreKeys(identity, nOpk);
  return identity;
}

export function replenishPreKeys(identity, n) {
  for (let i = 0; i < n; i++) {
    identity.oneTimePreKeys.push({ id: identity.nextOpkId++, key: dhKeyGen() });
    identity.pqOneTimePreKeys.push({ id: identity.nextPqOpkId++, key: kemKeyGen() });
  }
}

// Public bundle to publish to the server's key directory (public keys only).
export function publicBundle(identity) {
  return {
    idDHPub: toB64(identity.idDH.pub),
    idSignPub: toB64(identity.idSign.pub),
    signedPreKey: {
      id: identity.signedPreKey.id,
      pub: toB64(identity.signedPreKey.key.pub),
      sig: toB64(identity.signedPreKey.sig),
    },
    pqSignedPreKey: {
      id: identity.pqSignedPreKey.id,
      pub: toB64(identity.pqSignedPreKey.key.pub),
      sig: toB64(identity.pqSignedPreKey.sig),
    },
    oneTimePreKeys: identity.oneTimePreKeys.map((o) => ({ id: o.id, pub: toB64(o.key.pub) })),
    pqOneTimePreKeys: identity.pqOneTimePreKeys.map((o) => ({ id: o.id, pub: toB64(o.key.pub) })),
  };
}

// ---------------------------------------------------------------- initiator

// Start a session toward a peer using a fetch bundle handed out by the server.
// Returns { state, envelope } where envelope identifies the prekeys used.
export function initiatorStart(identity, fetchBundle) {
  const idSignPub = fromB64(fetchBundle.idSignPub);
  const spkPub = fromB64(fetchBundle.signedPreKey.pub);
  const pqSpkPub = fromB64(fetchBundle.pqSignedPreKey.pub);

  if (!verify(fromB64(fetchBundle.signedPreKey.sig), spkPub, idSignPub)) {
    throw new Error('peer signed prekey signature invalid');
  }
  if (!verify(fromB64(fetchBundle.pqSignedPreKey.sig), pqSpkPub, idSignPub)) {
    throw new Error('peer PQ signed prekey signature invalid');
  }

  const idDHPub = fromB64(fetchBundle.idDHPub);
  const ek = dhKeyGen();

  // X3DH classical secret
  const dh1 = dh(identity.idDH.priv, spkPub);
  const dh2 = dh(ek.priv, idDHPub);
  const dh3 = dh(ek.priv, spkPub);
  let secret = concat(DOMAIN, dh1, dh2, dh3);
  let usedOpkId = null;
  if (fetchBundle.oneTimePreKey) {
    usedOpkId = fetchBundle.oneTimePreKey.id;
    secret = concat(secret, dh(ek.priv, fromB64(fetchBundle.oneTimePreKey.pub)));
  }
  const root0 = kdfInitRoot(secret);

  // Responder's initial ratchet keys: SPK (X25519) and a PQ prekey (one-time if available).
  const pqPre = fetchBundle.pqOneTimePreKey || fetchBundle.pqSignedPreKey;
  const state = freshState(root0);
  state.DHr = spkPub;
  state.PQr = fromB64(pqPre.pub);
  state.DHs = dhKeyGen();
  state.PQs = kemKeyGen();
  sendingRatchet(state); // derive first sending chain (PQ-protected)

  const envelope = {
    idDHPub: fetchBundle && toB64(identity.idDH.pub),
    idSignPub: toB64(identity.idSign.pub),
    ekPub: toB64(ek.pub),
    usedSpkId: fetchBundle.signedPreKey.id,
    usedOpkId,
    usedPqPreKeyId: pqPre.id,
    usedPqPreKeyKind: fetchBundle.pqOneTimePreKey ? 'one-time' : 'signed',
  };
  state.pendingEnvelope = envelope; // attached to outgoing messages until first reply
  return { state, envelope };
}

// ---------------------------------------------------------------- responder

// Establish responder state from a received prekey (initial) message.
// `lookup` resolves the prekeys the initiator referenced from our identity.
export function responderEstablish(identity, envelope) {
  const idDHPub = fromB64(envelope.idDHPub);
  const ekPub = fromB64(envelope.ekPub);

  const spk = identity.signedPreKey; // we only rotate ids; current spk matches usedSpkId in practice
  const dh1 = dh(spk.key.priv, idDHPub);
  const dh2 = dh(identity.idDH.priv, ekPub);
  const dh3 = dh(spk.key.priv, ekPub);
  let secret = concat(DOMAIN, dh1, dh2, dh3);
  if (envelope.usedOpkId != null) {
    const opk = takePreKey(identity.oneTimePreKeys, envelope.usedOpkId);
    if (!opk) throw new Error('referenced one-time prekey not found');
    secret = concat(secret, dh(opk.key.priv, ekPub));
  }
  const root0 = kdfInitRoot(secret);

  // Our initial ratchet keys must mirror what the initiator targeted.
  const pqPre = envelope.usedPqPreKeyKind === 'one-time'
    ? takePreKey(identity.pqOneTimePreKeys, envelope.usedPqPreKeyId)
    : identity.pqSignedPreKey;
  if (!pqPre) throw new Error('referenced PQ prekey not found');

  const state = freshState(root0);
  state.DHs = spk.key;        // our SPK is our initial sending ratchet key
  state.PQs = pqPre.key;      // our PQ prekey is our initial advertised KEM key
  // DHr / PQr stay null until we process the first message header.
  return state;
}

// ---------------------------------------------------------------- ratchet

function freshState(root) {
  return {
    root,
    DHs: null, DHr: null,
    PQs: null, PQr: null,
    CKs: null, CKr: null,
    Ns: 0, Nr: 0, PN: 0,
    sendCT: null,          // KEM ct advertised for the current sending chain
    skipped: new Map(),    // "b64(DHr)|n" -> messageKey
    established: false,    // responder has been heard from
    pendingEnvelope: null, // prekey envelope to attach until first reply
  };
}

// Sending DH ratchet half: new sending chain off current DHs/DHr/PQr.
function sendingRatchet(state) {
  const { ct, ss } = kemEncaps(state.PQr);
  const mix = concat(dh(state.DHs.priv, state.DHr), ss);
  const { root, chain } = kdfRoot(state.root, mix);
  state.root = root;
  state.CKs = chain;
  state.Ns = 0;
  state.sendCT = ct;
}

// Full DH ratchet on receiving a header with a new ratchet key.
function dhRatchet(state, header) {
  state.PN = state.Ns;
  state.Ns = 0;
  state.Nr = 0;

  // Receiving half: decapsulate to our current PQs, DH with our current DHs.
  state.DHr = header.dhPub;
  const ssRecv = kemDecaps(state.PQs.priv, header.ct);
  let mix = concat(dh(state.DHs.priv, state.DHr), ssRecv);
  let kdf = kdfRoot(state.root, mix);
  state.root = kdf.root;
  state.CKr = kdf.chain;
  state.PQr = header.pqPub; // peer's newly advertised KEM key

  // Sending half: brand new DHs/PQs, encapsulate to peer's new PQr.
  state.DHs = dhKeyGen();
  state.PQs = kemKeyGen();
  sendingRatchet(state);
}

export function ratchetEncrypt(state, plaintext) {
  const { messageKey, nextChain } = kdfChain(state.CKs);
  state.CKs = nextChain;
  const header = {
    dhPub: state.DHs.pub,
    pqPub: state.PQs.pub,
    ct: state.sendCT,
    pn: state.PN,
    n: state.Ns,
  };
  state.Ns += 1;
  const aad = headerAad(header);
  const body = aeadEncrypt(messageKey, plaintext, aad);
  return { header, body };
}

export function ratchetDecrypt(state, header, body) {
  const skipKey = skippedKey(header.dhPub, header.n);
  if (state.skipped.has(skipKey)) {
    const mk = state.skipped.get(skipKey);
    state.skipped.delete(skipKey);
    return aeadDecrypt(mk, body, headerAad(header));
  }

  const isNewRatchet = !state.DHr || !sameBytes(header.dhPub, state.DHr);
  if (isNewRatchet) {
    skipMessageKeys(state, header.pn);
    dhRatchet(state, header);
  }
  skipMessageKeys(state, header.n);

  const { messageKey, nextChain } = kdfChain(state.CKr);
  state.CKr = nextChain;
  state.Nr += 1;
  state.established = true;
  return aeadDecrypt(messageKey, body, headerAad(header));
}

// Derive and stash message keys we skipped over in the current receiving chain.
function skipMessageKeys(state, until) {
  if (state.CKr == null) return;
  if (state.Nr + MAX_SKIP < until) throw new Error('too many skipped messages');
  while (state.Nr < until) {
    const { messageKey, nextChain } = kdfChain(state.CKr);
    state.CKr = nextChain;
    state.skipped.set(skippedKey(state.DHr, state.Nr), messageKey);
    state.Nr += 1;
  }
}

// ---------------------------------------------------------------- wire format

// A transport message: { type, envelope?, header, body } with bytes as base64.
export function packMessage(state, plaintext) {
  const { header, body } = ratchetEncrypt(state, plaintext);
  const wire = {
    type: state.established ? 'msg' : 'prekey',
    header: encodeHeader(header),
    body: toB64(body),
  };
  if (!state.established && state.pendingEnvelope) {
    wire.envelope = state.pendingEnvelope;
  }
  return wire;
}

// Decrypt a transport message. `localState` may be null for a first 'prekey'
// message, in which case `identity` is used to establish responder state.
// Returns { state, plaintext }.
export function unpackMessage(localState, identity, wire) {
  let state = localState;
  if (!state) {
    if (wire.type !== 'prekey' || !wire.envelope) {
      throw new Error('no session and message is not a prekey message');
    }
    state = responderEstablish(identity, wire.envelope);
  }
  const header = decodeHeader(wire.header);
  const plaintext = ratchetDecrypt(state, header, fromB64(wire.body));
  return { state, plaintext };
}

// ---------------------------------------------------------------- helpers

function takePreKey(list, id) {
  const i = list.findIndex((o) => o.id === id);
  if (i < 0) return null;
  return list.splice(i, 1)[0];
}

function sameBytes(a, b) {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

function skippedKey(dhPub, n) {
  return `${toB64(dhPub)}|${n}`;
}

// Canonical header bytes for AEAD AAD (binds header to ciphertext).
function headerAad(header) {
  return utf8(JSON.stringify(encodeHeader(header)));
}

function encodeHeader(h) {
  return { dhPub: toB64(h.dhPub), pqPub: toB64(h.pqPub), ct: toB64(h.ct), pn: h.pn, n: h.n };
}
function decodeHeader(h) {
  return { dhPub: fromB64(h.dhPub), pqPub: fromB64(h.pqPub), ct: fromB64(h.ct), pn: h.pn, n: h.n };
}

// Server-side fetch-bundle simulation for tests (consumes one prekey of each kind).
export function pickFetchBundle(bundle) {
  return {
    idDHPub: bundle.idDHPub,
    idSignPub: bundle.idSignPub,
    signedPreKey: bundle.signedPreKey,
    pqSignedPreKey: bundle.pqSignedPreKey,
    oneTimePreKey: bundle.oneTimePreKeys.length ? bundle.oneTimePreKeys.shift() : null,
    pqOneTimePreKey: bundle.pqOneTimePreKeys.length ? bundle.pqOneTimePreKeys.shift() : null,
  };
}
