import { useEffect, useRef, useState } from 'react';
import { Client } from '@stomp/stompjs';
import { api, setAuth, WS_URL } from './api';

function fmtTime(iso) {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

export default function ChatPage({ auth, onLogout }) {
  const [contacts, setContacts] = useState([]);
  const [active, setActive] = useState(null); // contact {userId, username}
  const [messages, setMessages] = useState([]);
  const [unread, setUnread] = useState({}); // userId -> count
  const [draft, setDraft] = useState('');
  const [addKey, setAddKey] = useState('');
  const [error, setError] = useState('');
  const [connected, setConnected] = useState(false);
  const [copied, setCopied] = useState(false);

  const clientRef = useRef(null);
  const activeRef = useRef(null);
  const bottomRef = useRef(null);
  activeRef.current = active;

  useEffect(() => {
    api('/api/contacts').then(setContacts).catch((e) => setError(e.message));

    const client = new Client({
      brokerURL: WS_URL,
      connectHeaders: { Authorization: `Bearer ${auth.token}` },
      reconnectDelay: 3000,
      onConnect: () => {
        setConnected(true);
        client.subscribe('/user/queue/messages', (frame) => {
          const msg = JSON.parse(frame.body);
          const current = activeRef.current;
          const otherId = msg.senderUsername === auth.username ? msg.recipientId : msg.senderId;
          if (current && otherId === current.userId) {
            setMessages((prev) => [...prev, msg]);
          } else {
            setUnread((prev) => ({ ...prev, [otherId]: (prev[otherId] || 0) + 1 }));
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

  async function openConversation(contact) {
    setActive(contact);
    setError('');
    setUnread((prev) => ({ ...prev, [contact.userId]: 0 }));
    try {
      setMessages(await api(`/api/messages/${contact.userId}`));
    } catch (e) {
      setError(e.message);
    }
  }

  function sendMessage(e) {
    e.preventDefault();
    const content = draft.trim();
    if (!content || !active || !connected) return;
    clientRef.current.publish({
      destination: '/app/chat',
      body: JSON.stringify({ toUserId: active.userId, content }),
    });
    setDraft('');
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
          {contacts.map((c) => (
            <li key={c.userId}>
              <button
                className={active?.userId === c.userId ? 'contact active' : 'contact'}
                onClick={() => openConversation(c)}
              >
                <span>{active?.userId === c.userId ? '>' : ' '} {c.username}</span>
                {unread[c.userId] > 0 && <span className="badge">[{unread[c.userId]}]</span>}
              </button>
            </li>
          ))}
          {contacts.length === 0 && (
            <li className="muted small empty">// no contacts yet — share your key</li>
          )}
        </ul>
      </aside>

      <main className="conversation">
        {!active ? (
          <div className="placeholder muted">// select a contact to open a channel</div>
        ) : (
          <>
            <header>
              <span className="prompt">#{active.username}</span>
              <span className={connected ? 'status on' : 'status off'}>
                {connected ? '[LIVE]' : '[RECONNECTING…]'}
              </span>
            </header>
            <div className="messages">
              {messages.map((m) => (
                <div
                  key={m.id}
                  className={m.senderUsername === auth.username ? 'line mine' : 'line theirs'}
                >
                  <span className="time">[{fmtTime(m.createdAt)}]</span>
                  <span className="nick">&lt;{m.senderUsername}&gt;</span>
                  <span className="content">{m.content}</span>
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
              <button type="submit" disabled={!connected || !draft.trim()}>[send]</button>
            </form>
          </>
        )}
        {error && <p className="error floating">! {error}</p>}
      </main>
    </div>
  );
}
