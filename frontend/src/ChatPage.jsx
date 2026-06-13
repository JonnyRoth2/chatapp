import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { api, apiUpload, fetchImageObjectUrl, setAuth, WS_URL } from './api';
import { createE2E, safetyNumber } from './crypto/e2e';
import { idbStore } from './crypto/idbStore';
import { concat, toB64, fromB64 } from './crypto/primitives';
import { sha256 } from '@noble/hashes/sha2.js';

const te = new TextEncoder();
const td = new TextDecoder();

function fmtTime(iso) {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** <img> can't carry an Authorization header, so fetch with the JWT instead. */
function AuthImage({ messageId }) {
  const [url, setUrl] = useState(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let objectUrl = null;
    let cancelled = false;
    fetchImageObjectUrl(messageId)
      .then((u) => { if (cancelled) URL.revokeObjectURL(u); else { objectUrl = u; setUrl(u); } })
      .catch(() => !cancelled && setFailed(true));
    return () => { cancelled = true; if (objectUrl) URL.revokeObjectURL(objectUrl); };
  }, [messageId]);
  if (failed) return <span className="muted">[image unavailable]</span>;
  if (!url) return <span className="muted">[loading image…]</span>;
  return <img className="chat-image" src={url} alt="shared image" />;
}

const dmKey = (userId) => `dm-${userId}`;
const groupKey = (groupId) => `g-${groupId}`;
const convKey = (conv) => (conv.kind === 'dm' ? dmKey(conv.userId) : groupKey(conv.id));

// Normalize a server group message into the unified display shape.
function normalizeGroup(m, myName) {
  return {
    id: m.id,
    mine: m.senderUsername === myName,
    senderUsername: m.senderUsername,
    type: m.type === 'IMAGE' ? 'image' : 'text',
    content: m.content,
    imageId: m.type === 'IMAGE' ? m.id : null,
    createdAt: m.createdAt,
  };
}

export default function ChatPage({ auth, onLogout }) {
  const [contacts, setContacts] = useState([]);
  const [groups, setGroups] = useState([]);
  const [active, setActive] = useState(null);
  const [messages, setMessages] = useState([]);
  const [unread, setUnread] = useState({});
  const [draft, setDraft] = useState('');
  const [addKey, setAddKey] = useState('');
  const [newGroupName, setNewGroupName] = useState('');
  const [memberKey, setMemberKey] = useState('');
  const [error, setError] = useState('');
  const [connected, setConnected] = useState(false);
  const [copied, setCopied] = useState(false);
  const [e2eReady, setE2eReady] = useState(false);
  const [safety, setSafety] = useState(null);
  const [showSafety, setShowSafety] = useState(false);

  const clientRef = useRef(null);
  const activeRef = useRef(null);
  const bottomRef = useRef(null);
  const fileRef = useRef(null);
  const e2eRef = useRef(null);
  const e2eReadyRef = useRef(null);
  activeRef.current = active;

  // ---- set up encryption identity + websocket ----
  useEffect(() => {
    const mgr = createE2E({ store: idbStore, apiBase: '', token: auth.token, username: auth.username });
    e2eRef.current = mgr;
    e2eReadyRef.current = mgr.ensureIdentity()
      .then(() => setE2eReady(true))
      .catch((e) => setError('encryption setup failed: ' + e.message));

    api('/api/contacts').then(setContacts).catch((e) => setError(e.message));
    api('/api/groups').then(setGroups).catch((e) => setError(e.message));

    const client = new Client({
      brokerURL: WS_URL,
      connectHeaders: { Authorization: `Bearer ${auth.token}` },
      reconnectDelay: 3000,
      onConnect: () => {
        setConnected(true);
        client.subscribe('/user/queue/messages', (frame) => {
          handleIncoming(JSON.parse(frame.body)).catch((e) => console.error('incoming', e));
        });
      },
      onDisconnect: () => setConnected(false),
      onWebSocketClose: () => setConnected(false),
    });
    client.activate();
    clientRef.current = client;
    return () => client.deactivate();
  }, [auth]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  function route(key, peerId, display) {
    const cur = activeRef.current;
    const isActive = cur && convKey(cur) === key;
    if (isActive) setMessages((prev) => [...prev, display]);
    else setUnread((prev) => ({ ...prev, [key]: (prev[key] || 0) + 1 }));
  }

  async function handleIncoming(msg) {
    // ---- group messages: plaintext (not E2E in this version) ----
    if (msg.groupId != null) {
      route(groupKey(msg.groupId), null, normalizeGroup(msg, auth.username));
      return;
    }
    // ---- direct messages: end-to-end encrypted ----
    if (msg.senderId === auth.userId) return; // our own echo; shown optimistically
    await e2eReadyRef.current;
    if (await idbStore.isProcessed(auth.username, msg.senderId, msg.id)) return;

    let text;
    try {
      const wire = JSON.parse(msg.content);
      text = td.decode(await e2eRef.current.decrypt(msg.senderId, wire));
    } catch (e) {
      text = '[unable to decrypt]';
    }
    const display = {
      id: msg.id, mine: false, senderUsername: msg.senderUsername,
      type: 'text', content: text, createdAt: msg.createdAt,
    };
    await idbStore.appendMessage(auth.username, msg.senderId, { serverId: msg.id, ...display });
    route(dmKey(msg.senderId), msg.senderId, display);
  }

  async function openConversation(conv) {
    setActive(conv);
    setError('');
    setMemberKey('');
    setShowSafety(false);
    setSafety(null);
    setUnread((prev) => ({ ...prev, [convKey(conv)]: 0 }));

    if (conv.kind === 'group') {
      try {
        const raw = await api(`/api/groups/${conv.id}/messages`);
        setMessages(raw.map((m) => normalizeGroup(m, auth.username)));
      } catch (e) { setError(e.message); }
      return;
    }

    // DM: decrypt any unprocessed server messages (offline delivery), then show local history.
    await e2eReadyRef.current;
    try {
      const raw = await api(`/api/messages/${conv.userId}`);
      for (const m of raw) {
        if (m.senderId === auth.userId) continue;       // our own — already stored locally
        if (m.type === 'IMAGE') continue;               // encrypted images: next step
        if (await idbStore.isProcessed(auth.username, conv.userId, m.id)) continue;
        try {
          const pt = await e2eRef.current.decrypt(conv.userId, JSON.parse(m.content));
          await idbStore.appendMessage(auth.username, conv.userId, {
            serverId: m.id, id: m.id, mine: false, senderUsername: m.senderUsername,
            type: 'text', content: td.decode(pt), createdAt: m.createdAt,
          });
        } catch { /* skip undecryptable */ }
      }
    } catch (e) { setError(e.message); }

    const h = await idbStore.getHistory(auth.username, conv.userId);
    setMessages([...h.messages].sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt)));
    computeSafety(conv.userId);
  }

  async function computeSafety(peerId) {
    try {
      const id = await api(`/api/keys/${peerId}/identity`);
      const peerFp = toB64(sha256(concat(fromB64(id.idDHPub), fromB64(id.idSignPub))));
      setSafety(safetyNumber(e2eRef.current.fingerprint(), peerFp));
    } catch { setSafety(null); }
  }

  async function sendMessage(e) {
    e.preventDefault();
    const content = draft.trim();
    if (!content || !active || !connected) return;
    setDraft('');

    if (active.kind === 'group') {
      clientRef.current.publish({
        destination: '/app/chat',
        body: JSON.stringify({ groupId: active.id, content }),
      });
      return;
    }

    // DM: encrypt, store plaintext locally, send ciphertext.
    try {
      await e2eReadyRef.current;
      const wire = await e2eRef.current.encrypt(active.userId, te.encode(content));
      const display = {
        id: `local-${Date.now()}`, mine: true, senderUsername: auth.username,
        type: 'text', content, createdAt: new Date().toISOString(),
      };
      await idbStore.appendMessage(auth.username, active.userId, display);
      setMessages((prev) => [...prev, display]);
      clientRef.current.publish({
        destination: '/app/chat',
        body: JSON.stringify({ toUserId: active.userId, content: JSON.stringify(wire) }),
      });
    } catch (err) {
      setError('send failed: ' + err.message);
    }
  }

  async function sendImage(e) {
    const file = e.target.files?.[0];
    e.target.value = '';
    if (!file || !active || active.kind !== 'group') return; // DM images: next step
    if (file.size > 5 * 1024 * 1024) { setError('Image too large (max 5MB)'); return; }
    setError('');
    try {
      await apiUpload(`/api/groups/${active.id}/image`, file);
    } catch (err) { setError(err.message); }
  }

  async function addContact(e) {
    e.preventDefault();
    setError('');
    try {
      const contact = await api('/api/contacts', { method: 'POST', body: { additionKey: addKey.trim() } });
      setContacts((prev) => [...prev, contact]);
      setAddKey('');
    } catch (err) { setError(err.message); }
  }

  async function createGroup(e) {
    e.preventDefault();
    setError('');
    try {
      const group = await api('/api/groups', { method: 'POST', body: { name: newGroupName.trim() } });
      setGroups((prev) => [...prev, group]);
      setNewGroupName('');
      openConversation({ kind: 'group', ...group });
    } catch (err) { setError(err.message); }
  }

  async function addGroupMember(e) {
    e.preventDefault();
    if (active?.kind !== 'group') return;
    setError('');
    try {
      const updated = await api(`/api/groups/${active.id}/members`, { method: 'POST', body: { additionKey: memberKey.trim() } });
      setGroups((prev) => prev.map((g) => (g.id === updated.id ? updated : g)));
      setActive({ kind: 'group', ...updated });
      setMemberKey('');
    } catch (err) { setError(err.message); }
  }

  async function leaveGroup() {
    if (active?.kind !== 'group') return;
    if (!window.confirm(`Leave #${active.name}?`)) return;
    setError('');
    try {
      await api(`/api/groups/${active.id}/members/me`, { method: 'DELETE' });
      setGroups((prev) => prev.filter((g) => g.id !== active.id));
      setActive(null); setMessages([]);
    } catch (err) { setError(err.message); }
  }

  async function deleteGroup() {
    if (active?.kind !== 'group') return;
    if (!window.confirm(`Delete #${active.name} for everyone? This removes all its messages.`)) return;
    setError('');
    try {
      await api(`/api/groups/${active.id}`, { method: 'DELETE' });
      setGroups((prev) => prev.filter((g) => g.id !== active.id));
      setActive(null); setMessages([]);
    } catch (err) { setError(err.message); }
  }

  function copyKey() {
    navigator.clipboard.writeText(auth.additionKey).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }

  function logout() { setAuth(null); onLogout(); }

  return (
    <div className="chat-layout">
      <aside className="sidebar">
        <div className="me">
          <div className="me-line">
            <span className="prompt">{auth.username}@chatapp</span>
            <button className="link" onClick={logout}>[exit]</button>
          </div>
          <button className="key-chip" onClick={copyKey} title="Click to copy — share this so people can add you">
            key: {copied ? '** copied **' : auth.additionKey}
          </button>
          <span className="muted small">{e2eReady ? '🔒 keys ready' : '… setting up keys'}</span>
        </div>

        <form className="add-contact" onSubmit={addContact}>
          <span className="prompt">+</span>
          <input placeholder="enter addition key" value={addKey} onChange={(e) => setAddKey(e.target.value)} spellCheck={false} />
          <button type="submit" disabled={!addKey.trim()}>[add]</button>
        </form>

        <div className="section-label">── contacts (e2e 🔒) ──</div>
        <ul className="contact-list">
          {contacts.map((c) => {
            const key = dmKey(c.userId);
            const isActive = active?.kind === 'dm' && active.userId === c.userId;
            return (
              <li key={key}>
                <button className={isActive ? 'contact active' : 'contact'} onClick={() => openConversation({ kind: 'dm', ...c })}>
                  <span>{isActive ? '>' : ' '} {c.username}</span>
                  {unread[key] > 0 && <span className="badge">[{unread[key]}]</span>}
                </button>
              </li>
            );
          })}
          {contacts.length === 0 && <li className="muted small empty">// no contacts yet — share your key</li>}
        </ul>

        <div className="section-label">── groups (plaintext) ──</div>
        <form className="add-contact" onSubmit={createGroup}>
          <span className="prompt">#</span>
          <input placeholder="new group name" value={newGroupName} onChange={(e) => setNewGroupName(e.target.value)} maxLength={64} spellCheck={false} />
          <button type="submit" disabled={!newGroupName.trim()}>[mk]</button>
        </form>
        <ul className="contact-list groups">
          {groups.map((g) => {
            const key = groupKey(g.id);
            const isActive = active?.kind === 'group' && active.id === g.id;
            return (
              <li key={key}>
                <button className={isActive ? 'contact active' : 'contact'} onClick={() => openConversation({ kind: 'group', ...g })}>
                  <span>{isActive ? '>' : ' '} #{g.name} <span className="muted small">({g.members.length})</span></span>
                  {unread[key] > 0 && <span className="badge">[{unread[key]}]</span>}
                </button>
              </li>
            );
          })}
          {groups.length === 0 && <li className="muted small empty">// no groups yet — make one above</li>}
        </ul>
      </aside>

      <main className="conversation">
        {!active ? (
          <div className="placeholder muted">// select a contact or group to open a channel</div>
        ) : (
          <>
            <header>
              <div className="header-main">
                <span className="prompt">
                  {active.kind === 'dm' ? `🔒 #${active.username}` : `#${active.name}`}
                </span>
                <span className="header-actions">
                  {active.kind === 'dm' && safety && (
                    <button className="link" onClick={() => setShowSafety((v) => !v)}>[verify]</button>
                  )}
                  {active.kind === 'group' && (
                    <>
                      <button className="link" onClick={leaveGroup}>[leave]</button>
                      {active.creatorUsername === auth.username && (
                        <button className="link danger" onClick={deleteGroup}>[del]</button>
                      )}
                    </>
                  )}
                  <span className={connected ? 'status on' : 'status off'}>
                    {connected ? '[LIVE]' : '[RECONNECTING…]'}
                  </span>
                </span>
              </div>
              {active.kind === 'dm' && showSafety && safety && (
                <div className="safety">
                  <span className="muted small">safety number — compare out-of-band to verify {active.username}:</span>
                  <code>{safety}</code>
                </div>
              )}
              {active.kind === 'group' && (
                <div className="group-meta">
                  <span className="muted small">
                    {active.members.length}/32: {active.members.map((m) => m.username).join(', ')}
                  </span>
                  <form className="member-add" onSubmit={addGroupMember}>
                    <input placeholder="add member by key" value={memberKey} onChange={(e) => setMemberKey(e.target.value)} spellCheck={false} />
                    <button type="submit" disabled={!memberKey.trim()}>[+]</button>
                  </form>
                </div>
              )}
            </header>
            <div className="messages">
              {messages.map((m) => (
                <div key={m.id} className={m.mine ? 'line mine' : 'line theirs'}>
                  <span className="time">[{fmtTime(m.createdAt)}]</span>
                  <span className="nick">&lt;{m.senderUsername}&gt;</span>
                  {m.type === 'image'
                    ? <AuthImage messageId={m.imageId} />
                    : <span className="content">{m.content}</span>}
                </div>
              ))}
              {messages.length === 0 && (
                <div className="placeholder muted">// no messages in the last 24 hours</div>
              )}
              <div ref={bottomRef} />
            </div>
            <form className="composer" onSubmit={sendMessage}>
              <span className="prompt">&gt;</span>
              <input
                placeholder={connected ? 'type a message…' : 'connecting…'}
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                maxLength={2000}
                disabled={!connected}
                spellCheck={false}
              />
              <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/gif,image/webp" onChange={sendImage} hidden />
              {active.kind === 'group' && (
                <button type="button" disabled={!connected} onClick={() => fileRef.current?.click()}>[img]</button>
              )}
              <button type="submit" disabled={!connected || !draft.trim()}>[send]</button>
            </form>
          </>
        )}
        {error && <p className="error floating">! {error}</p>}
      </main>
    </div>
  );
}
