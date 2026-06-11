import { useState } from 'react';
import { getAuth } from './api';
import AuthPage from './AuthPage';
import ChatPage from './ChatPage';

export default function App() {
  const [auth, setAuthState] = useState(getAuth());

  return auth
    ? <ChatPage auth={auth} onLogout={() => setAuthState(null)} />
    : <AuthPage onAuthed={setAuthState} />;
}
