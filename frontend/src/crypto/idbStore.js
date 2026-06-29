// Browser IndexedDB store. Implements the e2e manager's identity/session
// interface (IndexedDB structured-clones Uint8Arrays + Maps natively) and adds
// local plaintext history, since the server only ever holds ciphertext.

const DB_NAME = 'chatapp-e2e';
const DB_VERSION = 2;
// 'contentKeys' (added v2) caches each image's content key, keyed by server
// message id, so the lazily-fetched ciphertext can be decrypted on render
// without re-advancing (and re-consuming) the ratchet.
const STORES = ['identities', 'sessions', 'history', 'contentKeys'];

let dbPromise = null;
function db() {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const d = req.result;
      for (const s of STORES) if (!d.objectStoreNames.contains(s)) d.createObjectStore(s);
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

async function get(store, key) {
  const d = await db();
  return new Promise((resolve, reject) => {
    const r = d.transaction(store, 'readonly').objectStore(store).get(key);
    r.onsuccess = () => resolve(r.result ?? null);
    r.onerror = () => reject(r.error);
  });
}
async function put(store, key, value) {
  const d = await db();
  return new Promise((resolve, reject) => {
    const tx = d.transaction(store, 'readwrite');
    tx.objectStore(store).put(value, key);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

const sessKey = (u, p) => `${u}|${p}`;

export const idbStore = {
  // ---- e2e manager interface ----
  getIdentity: (username) => get('identities', username),
  setIdentity: (username, identity) => put('identities', username, identity),
  getSession: (username, peerId) => get('sessions', sessKey(username, peerId)),
  setSession: (username, peerId, state) => put('sessions', sessKey(username, peerId), state),

  // ---- per-image content keys (for decrypting lazily-fetched image bytes) ----
  getContentKey: (username, messageId) => get('contentKeys', sessKey(username, messageId)),
  setContentKey: (username, messageId, key) => put('contentKeys', sessKey(username, messageId), key),

  // ---- local plaintext history (per conversation) ----
  async getHistory(username, peerId) {
    return (await get('history', sessKey(username, peerId))) ?? { messages: [], processedIds: [] };
  },
  async appendMessage(username, peerId, message) {
    const h = await this.getHistory(username, peerId);
    h.messages.push(message);
    if (message.serverId != null && !h.processedIds.includes(message.serverId)) {
      h.processedIds.push(message.serverId);
    }
    await put('history', sessKey(username, peerId), h);
  },
  async markProcessed(username, peerId, serverId) {
    const h = await this.getHistory(username, peerId);
    if (!h.processedIds.includes(serverId)) {
      h.processedIds.push(serverId);
      await put('history', sessKey(username, peerId), h);
    }
  },
  async isProcessed(username, peerId, serverId) {
    const h = await this.getHistory(username, peerId);
    return h.processedIds.includes(serverId);
  },
};
