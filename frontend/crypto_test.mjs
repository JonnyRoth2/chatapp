// End-to-end test of the PQ Double Ratchet, run in Node.
// Simulates the network by JSON round-tripping every wire message.

import {
  generateIdentity, publicBundle, pickFetchBundle,
  initiatorStart, packMessage, unpackMessage,
} from './src/crypto/session.js';
import { sealContent, openContent, frameImage, unframeImage } from './src/crypto/content.js';

let pass = 0, fail = 0;
const enc = new TextEncoder();
const dec = new TextDecoder();
function check(cond, label) {
  if (cond) { pass++; console.log(`  ok  ${label}`); }
  else { fail++; console.log(`  FAIL ${label}`); }
}
const wire = (w) => JSON.parse(JSON.stringify(w)); // simulate transport

// ---- setup: two users publish bundles, server hands Alice Bob's bundle ----
const alice = generateIdentity(5);
const bob = generateIdentity(5);
const bobPublic = publicBundle(bob);
const bobFetch = pickFetchBundle(bobPublic);

// ---- Alice starts a session and sends the first (prekey) message ----
const { state: aliceState } = initiatorStart(alice, bobFetch);
let bobState = null;

const m0 = wire(packMessage(aliceState, enc.encode('hello bob — this is PQ encrypted')));
check(m0.type === 'prekey' && !!m0.envelope, 'first message is a prekey message w/ envelope');

const r0 = unpackMessage(bobState, bob, m0);
bobState = r0.state;
check(dec.decode(r0.plaintext) === 'hello bob — this is PQ encrypted', 'bob decrypts first message');

// ---- Bob replies (triggers ratchet on Alice) ----
const b0 = wire(packMessage(bobState, enc.encode('hey alice, got it')));
check(b0.type === 'msg', 'bob reply is a normal message');
const ar0 = unpackMessage(aliceState, alice, b0);
check(dec.decode(ar0.plaintext) === 'hey alice, got it', 'alice decrypts bob reply');

// ---- several back-and-forth turns (ratchet must keep alternating) ----
let okTurns = true;
for (let i = 0; i < 6; i++) {
  const am = wire(packMessage(aliceState, enc.encode(`A${i}`)));
  if (dec.decode(unpackMessage(bobState, bob, am).plaintext) !== `A${i}`) okTurns = false;
  const bm = wire(packMessage(bobState, enc.encode(`B${i}`)));
  if (dec.decode(unpackMessage(aliceState, alice, bm).plaintext) !== `B${i}`) okTurns = false;
}
check(okTurns, '6 alternating round-trips after handshake');

// ---- out-of-order delivery within a chain (skipped message keys) ----
const o1 = wire(packMessage(aliceState, enc.encode('first')));
const o2 = wire(packMessage(aliceState, enc.encode('second')));
const o3 = wire(packMessage(aliceState, enc.encode('third')));
// deliver 3, then 1, then 2
const d3 = dec.decode(unpackMessage(bobState, bob, o3).plaintext);
const d1 = dec.decode(unpackMessage(bobState, bob, o1).plaintext);
const d2 = dec.decode(unpackMessage(bobState, bob, o2).plaintext);
check(d3 === 'third' && d1 === 'first' && d2 === 'second', 'out-of-order delivery (skipped keys)');

// ---- binary payload round-trips (used later to wrap image keys) ----
const blob = new Uint8Array(256);
for (let i = 0; i < blob.length; i++) blob[i] = (i * 7) & 0xff;
const bm = wire(packMessage(aliceState, blob));
const got = unpackMessage(bobState, bob, bm).plaintext;
check(got.length === 256 && got.every((v, i) => v === blob[i]), 'binary payload round-trips');

// ---- tamper detection: flip a ciphertext byte -> decryption must throw ----
const tampered = wire(packMessage(aliceState, enc.encode('integrity')));
const raw = Buffer.from(tampered.body, 'base64');
raw[raw.length - 1] ^= 0x01;
tampered.body = raw.toString('base64');
let threw = false;
try { unpackMessage(bobState, bob, tampered); } catch { threw = true; }
check(threw, 'tampered ciphertext is rejected (AEAD integrity)');

// ---- forged signature in bundle is rejected ----
let sigRejected = false;
try {
  const badBundle = pickFetchBundle(publicBundle(bob));
  badBundle.signedPreKey = { ...badBundle.signedPreKey, sig: Buffer.alloc(64).toString('base64') };
  initiatorStart(alice, badBundle);
} catch { sigRejected = true; }
check(sigRejected, 'forged signed-prekey signature is rejected');

// ---- hybrid content encryption: group / image fan-out (server-blind) ----
// Alice seals a payload ONCE under a content key, then wraps that key for each
// recipient over their pairwise ratchet. Every recipient opens the same blob.
const carol = generateIdentity(5);
const carolFetch = pickFetchBundle(publicBundle(carol));
const { state: aliceToCarol } = initiatorStart(alice, carolFetch);
let carolState = null;

const groupText = 'group hello — sealed once, opened by all';
const sealed = sealContent(enc.encode(groupText));
const envForBob = wire(packMessage(aliceState, sealed.cek));     // wrap CEK -> Bob
const envForCarol = wire(packMessage(aliceToCarol, sealed.cek)); // wrap CEK -> Carol
// the sealed blob travels as opaque bytes (base64 over the wire)
const blobBytes = new Uint8Array(Buffer.from(Buffer.from(sealed.ciphertext).toString('base64'), 'base64'));

const bobCek = unpackMessage(bobState, bob, envForBob).plaintext;
const cr = unpackMessage(carolState, carol, envForCarol); carolState = cr.state;
const carolCek = cr.plaintext;
check(dec.decode(openContent(bobCek, blobBytes)) === groupText, 'bob opens group blob via his wrapped key');
check(dec.decode(openContent(carolCek, blobBytes)) === groupText, 'carol opens same blob via her wrapped key');

let wrongKeyRejected = false;
try { openContent(carolCek.slice().fill(0), blobBytes); } catch { wrongKeyRejected = true; }
check(wrongKeyRejected, 'a wrong/zeroed content key cannot open the blob (AEAD)');

// ---- image framing: MIME type travels inside the ciphertext ----
const imgBytes = new Uint8Array(512);
for (let i = 0; i < imgBytes.length; i++) imgBytes[i] = (i * 13) & 0xff;
const framed = sealContent(frameImage('image/png', imgBytes));
const wrappedKey = wire(packMessage(aliceState, framed.cek));
const recvCek = unpackMessage(bobState, bob, wrappedKey).plaintext;
const opened = unframeImage(openContent(recvCek, framed.ciphertext));
check(opened.contentType === 'image/png'
  && opened.bytes.length === 512
  && opened.bytes.every((v, i) => v === imgBytes[i]),
  'image frame (type + bytes) round-trips through seal + wrapped key');

console.log(`\n${fail === 0 ? 'ALL CRYPTO TESTS PASS' : 'CRYPTO TESTS FAILED'} — ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
