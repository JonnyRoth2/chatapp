// Session manager: bridges the ratchet crypto, a pluggable key/session store,
// and the server key directory. Store-agnostic so the same logic runs against
// an in-memory store (tests) or IndexedDB (browser).
//
// Store interface (all async):
//   getIdentity(username) / setIdentity(username, identity)
//   getSession(username, peerId) / setSession(username, peerId, state)

import {
  generateIdentity, publicBundle, replenishPreKeys,
  initiatorStart, packMessage, unpackMessage,
} from './session.js';
import { concat, toB64 } from './primitives.js';
import { sha256 } from '@noble/hashes/sha2.js';

const TARGET_OPK = 20;   // prekeys to publish per batch
const LOW_WATERMARK = 5; // replenish when the server has fewer than this

export function createE2E({ store, apiBase, token, username }) {
  const authHeaders = () => ({ Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' });
  let identity = null;

  async function publishBundle() {
    const r = await fetch(`${apiBase}/api/keys/bundle`, {
      method: 'POST', headers: authHeaders(), body: JSON.stringify(publicBundle(identity)),
    });
    if (!r.ok) throw new Error(`publish bundle failed (${r.status})`);
  }

  // Load our identity from local storage, or generate + publish a fresh one.
  async function ensureIdentity() {
    identity = await store.getIdentity(username);
    if (!identity) {
      identity = generateIdentity(TARGET_OPK);
      await publishBundle();
      await store.setIdentity(username, identity);
      return;
    }
    // top up one-time prekeys if the server is running low
    try {
      const c = await (await fetch(`${apiBase}/api/keys/me/count`, { headers: authHeaders() })).json();
      if (Math.min(c.classical, c.pq) < LOW_WATERMARK) {
        replenishPreKeys(identity, TARGET_OPK);
        await publishBundle();
        await store.setIdentity(username, identity);
      }
    } catch { /* offline replenish is best-effort */ }
  }

  async function fetchPeerBundle(peerId) {
    const r = await fetch(`${apiBase}/api/keys/${peerId}`, { headers: authHeaders() });
    if (r.status === 404) throw new Error('Contact has no encryption keys yet (have they opened the app?)');
    if (!r.ok) throw new Error(`fetch peer bundle failed (${r.status})`);
    return r.json();
  }

  // Encrypt bytes for a peer, establishing a session on first use.
  async function encrypt(peerId, plaintextBytes) {
    let state = await store.getSession(username, peerId);
    if (!state) {
      const bundle = await fetchPeerBundle(peerId);
      state = initiatorStart(identity, bundle).state;
    }
    const wire = packMessage(state, plaintextBytes);
    await store.setSession(username, peerId, state);
    return wire;
  }

  // Decrypt a wire message from a peer; establishes responder state on a first
  // prekey message. Returns the plaintext bytes.
  async function decrypt(peerId, wire) {
    const state = await store.getSession(username, peerId);
    const res = unpackMessage(state, identity, wire);
    await store.setSession(username, peerId, res.state);
    // responder establishment consumes one-time prekeys from identity
    await store.setIdentity(username, identity);
    return res.plaintext;
  }

  function hasSession(peerId) {
    return store.getSession(username, peerId).then((s) => !!s);
  }

  // Stable per-identity fingerprint (used to build safety numbers).
  function fingerprint() {
    if (!identity) return null;
    return toB64(sha256(concat(identity.idDH.pub, identity.idSign.pub)));
  }

  return {
    ensureIdentity, encrypt, decrypt, hasSession, fingerprint,
    get identity() { return identity; },
  };
}

// Safety number for a conversation: order-independent digest of both
// identity fingerprints, rendered as 12 groups of 5 digits (Signal-style).
export function safetyNumber(fpA, fpB) {
  const [x, y] = [fpA, fpB].sort();
  const digest = sha256(concat(new TextEncoder().encode(x), new TextEncoder().encode(y)));
  let out = '';
  for (let i = 0; i < 30; i++) out += (digest[i] % 10).toString();
  return out.match(/.{1,5}/g).join(' ');
}
