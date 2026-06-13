// Low-level crypto primitives over the audited @noble/* libraries.
// Everything here works identically in the browser and in Node.
//
// Curves:   X25519 (DH), Ed25519 (prekey signatures)
// KEM:      ML-KEM-768 (post-quantum key encapsulation, FIPS 203)
// KDF:      HKDF-SHA256 (root), HMAC-SHA256 (symmetric chain)
// AEAD:     AES-256-GCM

import { x25519, ed25519 } from '@noble/curves/ed25519.js';
import { ml_kem768 } from '@noble/post-quantum/ml-kem.js';
import { hkdf } from '@noble/hashes/hkdf.js';
import { hmac } from '@noble/hashes/hmac.js';
import { sha256 } from '@noble/hashes/sha2.js';
import { randomBytes } from '@noble/hashes/utils.js';
import { gcm } from '@noble/ciphers/aes.js';

export { randomBytes };

// noble v2 renamed randomPrivateKey -> randomSecretKey; support both.
function curveSecret(curve) {
  return curve.utils.randomSecretKey ? curve.utils.randomSecretKey() : curve.utils.randomPrivateKey();
}

// ---- X25519 (Diffie-Hellman) ----
export function dhKeyGen() {
  const priv = curveSecret(x25519);
  return { priv, pub: x25519.getPublicKey(priv) };
}
export function dh(priv, pub) {
  return x25519.getSharedSecret(priv, pub);
}

// ---- Ed25519 (signatures over prekeys) ----
export function signKeyGen() {
  const priv = curveSecret(ed25519);
  return { priv, pub: ed25519.getPublicKey(priv) };
}
export function sign(priv, msg) {
  return ed25519.sign(msg, priv);
}
export function verify(sig, msg, pub) {
  try {
    return ed25519.verify(sig, msg, pub);
  } catch {
    return false;
  }
}

// ---- ML-KEM-768 (post-quantum KEM) ----
export function kemKeyGen() {
  const { publicKey, secretKey } = ml_kem768.keygen();
  return { pub: publicKey, priv: secretKey };
}
export function kemEncaps(pub) {
  const { cipherText, sharedSecret } = ml_kem768.encapsulate(pub);
  return { ct: cipherText, ss: sharedSecret };
}
export function kemDecaps(priv, ct) {
  return ml_kem768.decapsulate(ct, priv);
}

// ---- KDFs ----
const INFO_ROOT = utf8('PQDR-root');
const INFO_X3DH = utf8('PQDR-x3dh');

// Root KDF: fold a DH+KEM mix into the root key, producing a fresh root + chain key.
export function kdfRoot(rootKey, mix) {
  const out = hkdf(sha256, mix, rootKey, INFO_ROOT, 64);
  return { root: out.slice(0, 32), chain: out.slice(32, 64) };
}

// Initial root key derived from the X3DH/PQXDH shared secret.
export function kdfInitRoot(secret) {
  return hkdf(sha256, secret, new Uint8Array(32), INFO_X3DH, 32);
}

// Symmetric chain KDF: chain key -> (message key, next chain key).
export function kdfChain(chainKey) {
  return {
    messageKey: hmac(sha256, chainKey, new Uint8Array([0x01])),
    nextChain: hmac(sha256, chainKey, new Uint8Array([0x02])),
  };
}

// ---- AEAD (AES-256-GCM); output is nonce(12) || ciphertext+tag ----
export function aeadEncrypt(key, plaintext, aad) {
  const nonce = randomBytes(12);
  const ct = gcm(key, nonce, aad).encrypt(plaintext);
  return concat(nonce, ct);
}
export function aeadDecrypt(key, data, aad) {
  const nonce = data.slice(0, 12);
  const ct = data.slice(12);
  return gcm(key, nonce, aad).decrypt(ct); // throws on tamper / wrong key
}

// ---- byte helpers ----
export function concat(...arrs) {
  let n = 0;
  for (const a of arrs) n += a.length;
  const out = new Uint8Array(n);
  let i = 0;
  for (const a of arrs) { out.set(a, i); i += a.length; }
  return out;
}

export function utf8(s) {
  return new TextEncoder().encode(s);
}

// Portable base64 (Node Buffer or browser btoa/atob).
export function toB64(bytes) {
  if (typeof Buffer !== 'undefined') return Buffer.from(bytes).toString('base64');
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin);
}
export function fromB64(s) {
  if (typeof Buffer !== 'undefined') return new Uint8Array(Buffer.from(s, 'base64'));
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

export function bytesEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}
