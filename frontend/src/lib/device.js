// A random id this browser keeps, sent with attendance requests as X-Device-Id.
// The server stores only its hash and ties it to one student.
//
// crypto.getRandomValues is used (not crypto.randomUUID) because randomUUID only exists
// on HTTPS or localhost, and phones on the campus network may reach a dev server over plain HTTP.
const KEY = 'classos.device';

export function deviceId() {
  try {
    const existing = localStorage.getItem(KEY);
    if (existing && /^[a-f0-9]{32}$/.test(existing)) return existing;
    const bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    const id = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
    localStorage.setItem(KEY, id);
    return id;
  } catch {
    // Storage blocked (some private modes): marking will fail with a clear device message.
    return null;
  }
}

export function deviceHeaders() {
  const id = deviceId();
  return id ? { 'X-Device-Id': id } : {};
}
