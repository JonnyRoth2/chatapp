import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { api, apiUpload, fetchImageObjectUrl, setAuth, WS_URL } from './api';

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
      .then((u) => {
        if (cancelled) URL.revokeObjectURL(u);
        else { objectUrl = u; setUrl(u); }
      })
      .catch(() => !cancelled && setFailed(true));
    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [messageId]);

  if (failed) return <span className="muted">[image unavailable]</span>;
  if (!url) return <span className="muted">[loading image…]</span>;
  return <img className="chat-image" src={url} alt="shared image" />;
}

// conversation identity helpers: 'dm-<userId>' / 'g-<groupId>'
const dmKey = (userId) => `dm-${userId}`;
const groupKey = (groupId) => `g-${groupId}`;
const convKey = (conv) => (conv.kind === 'dm' ? dmKey(conv.userId) : groupKey(conv.id));

export default function ChatPage({ auth, onLogout }) {
  const [contacts, setContacts] = useState([]);
  const [groups, setGroups] = useState([]);
  const [active, setActive] = useState(null); // {kind:'dm',userId,username} | {kind:'group',id,name,members}
  const [messages, setMessages] = useState([]);
  const [unread, setUnread] = useState({}); // convKey -> count
  const [draft, setDraft] = useState('');
  const [addKey, setAddKey] = useState('');
  const [newGroupName, setNewGroupName] = useState('');
  const [memberKey, setMemberKey] = useState('');
  const [error, setError] = useState('');
  const [connected, setConnected] = useState(false);
  const [copied, setCopied] = useState(false);

  const clientRef = useRef(null);
  const activeRef = useRef(null);
  const bottomRef = useRef(null);
  const fileRef = useRef(null);
  activeRef.current = active;

  useEffect(() => {
    api('/api/contacts').then(setContacts).catch((e) => setError(e.message));
    api('/api/groups').then(setGroups).catch((e) => setError(e.message));

    const client = new Client({
      brokerURL: WS_URL,
      connectHeaders: { Authorization: `Bearer ${auth.token}` },
      reconnectDelay: 3000,
      onConnect: () => {
        setConnected(true);
        client.subscribe('/user/queue/messages', (frame) => {
          const msg = JSON.parse(frame.body);
          const current = activeRef.current;
          let key;
          let isActive;
          if (msg.groupId != null) {
            key = groupKey(msg.groupId);
            isActive = current?.kind === 'group' && current.id === msg.groupId;
          } else {
            const otherId = msg.senderUsername === auth.username ? msg.recipientId : msg.senderId;
            key = dmKey(otherId);
            isActive = current?.kind === 'dm' && current.userId === otherId;
          }
          if (isActive) {
            setMessages((prev) => [...prev, msg]);
          } else {
            setUnread((prev) => ({ ...prev, [key]: (prev[key] || 0) + 1 }));
          }
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

  async function openConversation(conv) {
    setActive(conv);
    setError('');
    setMemberKey('');
    setUnread((prev) => ({ ...prev, [convKey(conv)]: 0 }));
    try {
      const path = conv.kind === 'dm'
        ? `/api/messages/${conv.userId}`
        : `/api/groups/${conv.id}/messages`;
      setMessages(await api(path));
    } catch (e) {
      setError(e.message);
    }
  }

  function sendMessage(e) {
    e.preventDefault();
    const content = draft.trim();
    if (!content || !active || !connected) return;
    const target = active.kind === 'dm'
      ? { toUserId: active.userId }
      : { groupId: active.id };
    clientRef.current.publish({
      destination: '/app/chat',
      body: JSON.stringify({ ...target, content }),
    });
    setDraft('');
  }

  async function sendImage(e) {
    const file = e.target.files?.[0];
    e.target.value = ''; // allow re-selecting the same file
    if (!file || !active) return;
    if (file.size > 5 * 1024 * 1024) {
      setError('Image too large (max 5MB)');
      return;
    }
    setError('');
    try {
      const path = active.kind === 'dm'
        ? `/api/messages/${active.userId}/image`
        : `/api/groups/${active.id}/image`;
      // the STOMP echo delivers it back to us, so no manual append here
      await apiUpload(path, file);
    } catch (err) {
      setError(err.message);
    }
  }

  async function addContact(e) {
    e.preventDefault();
    setError('');
    try {
      const contact = await api('/api/contacts', {
        method: 'POST',
        body: { additionKey: addKey.trim() },
      });
      setContacts((prev) => [...prev, contact]);
      setAddKey('');
    } catch (err) {
      setError(err.message);
    }
  }

  async function createGroup(e) {
    e.preventDefault();
    setError('');
    try {
      const group = await api('/api/groups', {
        method: 'POST',
        body: { name: newGroupName.trim() },
      });
      setGroups((prev) => [...prev, group]);
      setNewGroupName('');
      openConversation({ kind: 'group', ...group });
    } catch (err) {
      setError(err.message);
    }
  }

  async function addGroupMember(e) {
    e.preventDefault();
    if (active?.kind !== 'group') return;
    setError('');
    try {
      const updated = await api(`/api/groups/${active.id}/members`, {
        method: 'POST',
        body: { additionKey: memberKey.trim() },
      });
      setGroups((prev) => prev.map((g) => (g.id === updated.id ? updated : g)));
      setActive({ kind: 'group', ...updated });
      setMemberKey('');
    } catch (err) {
      setError(err.message);
    }
  }

  async function leaveGroup() {
    if (active?.kind !== 'group') return;
    if (!window.confirm(`Leave #${active.name}?`)) return;
    setError('');
    try {
      await api(`/api/groups/${active.id}/members/me`, { method: 'DELETE' });
      setGroups((prev) => prev.filter((g) => g.id !== active.id));
      setActive(null);
      setMessages([]);
    } catch (err) {
      setError(err.message);
    }
  }

  async function deleteGroup() {
    if (active?.kind !== 'group') return;
    if (!window.confirm(`Delete #${active.name} for everyone? This removes all its messages.`)) return;
    setError('');
    try {
      await api(`/api/groups/${active.id}`, { method: 'DELETE' });
      setGroups((prev) => prev.filter((g) => g.id !== active.id));
      setActive(null);
      setMessages([]);
    } catch (err) {
      setError(err.message);
    }
  }

  function copyKey() {
    navigator.clipboard.writeText(auth.additionKey).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  }

  function logout() {
    setAuth(null);
    onLogout();
  }

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
        </div>

        <form className="add-contact" onSubmit={addContact}>
          <span className="prompt">+</span>
          <input
            placeholder="enter addition key"
            value={addKey}
            onChange={(e) => setAddKey(e.target.value)}
            spellCheck={false}
          />
          <button type="submit" disabled={!addKey.trim()}>[add]</button>
        </form>

        <div className="section-label">── contacts ──</div>
        <ul className="contact-list">
          {contacts.map((c) => {
            const key = dmKey(c.userId);
            const isActive = active?.kind === 'dm' && active.userId === c.userId;
            return (
              <li key={key}>
                <button
                  className={isActive ? 'contact active' : 'contact'}
                  onClick={() => openConversation({ kind: 'dm', ...c })}
                >
                  <span>{isActive ? '>' : ' '} {c.username}</span>
                  {unread[key] > 0 && <span className="badge">[{unread[key]}]</span>}
                </button>
              </li>
            );
          })}
          {contacts.length === 0 && (
            <li className="muted small empty">// no contacts yet — share your key</li>
          )}
        </ul>

        <div className="section-label">── groups ──</div>
        <form className="add-contact" onSubmit={createGroup}>
          <span className="prompt">#</span>
          <input
            placeholder="new group name"
            value={newGroupName}
            onChange={(e) => setNewGroupName(e.target.value)}
            maxLength={64}
            spellCheck={false}
          />
          <button type="submit" disabled={!newGroupName.trim()}>[mk]</button>
        </form>
        <ul className="contact-list groups">
          {groups.map((g) => {
            const key = groupKey(g.id);
            const isActive = active?.kind === 'group' && active.id === g.id;
            return (
              <li key={key}>
                <button
                  className={isActive ? 'contact active' : 'contact'}
                  onClick={() => openConversation({ kind: 'group', ...g })}
                >
                  <span>{isActive ? '>' : ' '} #{g.name} <span className="muted small">({g.members.length})</span></span>
                  {unread[key] > 0 && <span className="badge">[{unread[key]}]</span>}
                </button>
              </li>
            );
          })}
          {groups.length === 0 && (
            <li className="muted small empty">// no groups yet — make one above</li>
          )}
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
                  {active.kind === 'dm' ? `#${active.username}` : `#${active.name}`}
                </span>
                <span className="header-actions">
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
              {active.kind === 'group' && (
                <div className="group-meta">
                  <span className="muted small">
                    {active.members.length}/32: {active.members.map((m) => m.username).join(', ')}
                  </span>
                  <form className="member-add" onSubmit={addGroupMember}>
                    <input
                      placeholder="add member by key"
                      value={memberKey}
                      onChange={(e) => setMemberKey(e.target.value)}
                      spellCheck={false}
                    />
                    <button type="submit" disabled={!memberKey.trim()}>[+]</button>
                  </form>
                </div>
              )}
            </header>
            <div className="messages">
              {messages.map((m) => (
                <div
                  key={m.id}
                  className={m.senderUsername === auth.username ? 'line mine' : 'line theirs'}
                >
                  <span className="time">[{fmtTime(m.createdAt)}]</span>
                  <span className="nick">&lt;{m.senderUsername}&gt;</span>
                  {m.type === 'IMAGE'
                    ? <AuthImage messageId={m.id} />
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
              <input
                ref={fileRef}
                type="file"
                accept="image/png,image/jpeg,image/gif,image/webp"
                onChange={sendImage}
                hidden
              />
              <button type="button" disabled={!connected} onClick={() => fileRef.current?.click()}>
                [img]
              </button>
              <button type="submit" disabled={!connected || !draft.trim()}>[send]</button>
            </form>
          </>
        )}
        {error && <p className="error floating">! {error}</p>}
      </main>
    </div>
  );
}
