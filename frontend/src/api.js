// Same-origin: the Vite dev server proxies /api and /ws to the backend.
const API_BASE = '';

export function getAuth() {
  try {
    return JSON.parse(localStorage.getItem('auth'));
  } catch {
    return null;
  }
}

export function setAuth(auth) {
  if (auth) localStorage.setItem('auth', JSON.stringify(auth));
  else localStorage.removeItem('auth');
}

export async function api(path, { method = 'GET', body } = {}) {
  const auth = getAuth();
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(auth ? { Authorization: `Bearer ${auth.token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let message = `Request failed (${res.status})`;
    try {
      const data = await res.json();
      message = data.message || data.error || message;
    } catch { /* keep default */ }
    const err = new Error(message);
    err.status = res.status;
    throw err;
  }
  return res.status === 204 ? null : res.json();
}

/** Multipart upload (encrypted image bytes + extra form fields like key envelopes). */
export async function apiUpload(path, file, fields = {}) {
  const auth = getAuth();
  const form = new FormData();
  form.append('file', file);
  for (const [k, v] of Object.entries(fields)) form.append(k, v);
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: auth ? { Authorization: `Bearer ${auth.token}` } : {},
    body: form,
  });
  if (!res.ok) {
    let message = `Upload failed (${res.status})`;
    try {
      const data = await res.json();
      message = data.message || data.error || message;
    } catch { /* keep default */ }
    throw new Error(message);
  }
  return res.json();
}

/** Fetch encrypted image bytes with the JWT; caller decrypts with the content key. */
export async function fetchImageBytes(messageId) {
  const auth = getAuth();
  const res = await fetch(`${API_BASE}/api/messages/image/${messageId}`, {
    headers: auth ? { Authorization: `Bearer ${auth.token}` } : {},
  });
  if (!res.ok) throw new Error(`Image failed to load (${res.status})`);
  return new Uint8Array(await res.arrayBuffer());
}

const wsProtocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
export const WS_URL = `${wsProtocol}://${window.location.host}/ws`;
