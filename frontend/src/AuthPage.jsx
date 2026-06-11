import { useState } from 'react';
import { api, setAuth } from './api';

export default function AuthPage({ onAuthed }) {
  const [mode, setMode] = useState('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const auth = await api(`/api/auth/${mode}`, {
        method: 'POST',
        body: { username, password },
      });
      setAuth(auth);
      onAuthed(auth);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-page">
      <form className="terminal-window auth-card" onSubmit={submit}>
        <div className="title-bar">
          <span className="dots">● ● ●</span>
          <span>chatapp — {mode}</span>
        </div>
        <div className="terminal-body">
          <pre className="banner">{String.raw`     ____.                            _________ .__            __   
    |    | ____   ____   ____ ___.__. \_   ___ \|  |__ _____ _/  |_ 
    |    |/  _ \ /    \ /    <   |  | /    \  \/|  |  \\__  \\   __\
/\__|    (  <_> )   |  \   |  \___  | \     \___|   Y  \/ __ \|  |  
\________|\____/|___|  /___|  / ____|  \______  /___|  (____  /__|  
                     \/     \/\/              \/     \/     \/      `}
          </pre>
          <p className="muted">// messages self-destruct after 24 hours</p>

          <label>
            <span className="prompt">$ username:</span>
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              required
              minLength={3}
              maxLength={32}
              spellCheck={false}
            />
          </label>
          <label>
            <span className="prompt">$ password:</span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              required
              minLength={mode === 'register' ? 8 : 1}
            />
          </label>
          {mode === 'register' && (
            <p className="muted small">// minimum 8 characters</p>
          )}

          {error && <p className="error">! {error}</p>}

          <button type="submit" disabled={busy}>
            [{mode === 'login' ? ' LOGIN ' : ' CREATE ACCOUNT '}]
          </button>

          <button
            type="button"
            className="link"
            onClick={() => { setMode(mode === 'login' ? 'register' : 'login'); setError(''); }}
          >
            {mode === 'login' ? '> new user? register' : '> have an account? login'}
          </button>
        </div>
      </form>
    </div>
  );
}
