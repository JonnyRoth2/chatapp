// In-memory store for tests. Uses structuredClone on read/write to faithfully
// simulate IndexedDB persistence (proves ratchet state survives serialization,
// including Uint8Arrays and the skipped-keys Map).

export function createMemStore() {
  const identities = new Map();
  const sessions = new Map();
  const key = (u, p) => `${u}|${p}`;

  return {
    async getIdentity(username) {
      const v = identities.get(username);
      return v ? structuredClone(v) : null;
    },
    async setIdentity(username, identity) {
      identities.set(username, structuredClone(identity));
    },
    async getSession(username, peerId) {
      const v = sessions.get(key(username, peerId));
      return v ? structuredClone(v) : null;
    },
    async setSession(username, peerId, state) {
      sessions.set(key(username, peerId), structuredClone(state));
    },
  };
}
