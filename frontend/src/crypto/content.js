// Hybrid content encryption used for group messages and all images.
//
// The content is sealed once under a fresh random content key (CEK) with
// AES-256-GCM; the CEK is then wrapped per-recipient through the pairwise
// Double Ratchet (see e2e.js encrypt/decrypt). The server only ever stores the
// sealed blob plus opaque per-recipient wrapped keys — never a key or plaintext.

import { aeadEncrypt, aeadDecrypt, randomBytes, utf8, concat } from './primitives.js';

// Domain separator so a content blob can never be confused with ratchet output.
const CONTENT_AAD = utf8('chatapp-content-v1');

/** Seal bytes under a fresh content key. Returns { cek, ciphertext } (both Uint8Array). */
export function sealContent(plaintextBytes) {
  const cek = randomBytes(32);
  return { cek, ciphertext: aeadEncrypt(cek, plaintextBytes, CONTENT_AAD) };
}

/** Open a sealed blob with its content key. Throws on tamper / wrong key. */
export function openContent(cek, ciphertext) {
  return aeadDecrypt(cek, ciphertext, CONTENT_AAD);
}

// Bundle an image's MIME type with its bytes so the type is encrypted too and
// the server sees only opaque ciphertext. Layout: [ctLen:1][contentType][bytes].
export function frameImage(contentType, bytes) {
  const ct = utf8(contentType || 'application/octet-stream');
  if (ct.length > 255) throw new Error('content type too long');
  return concat(new Uint8Array([ct.length]), ct, bytes);
}

export function unframeImage(plaintext) {
  const ctLen = plaintext[0];
  const contentType = new TextDecoder().decode(plaintext.slice(1, 1 + ctLen));
  return { contentType, bytes: plaintext.slice(1 + ctLen) };
}
