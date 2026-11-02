import { tokenStore } from './tokenStore.js';

export class ApiError extends Error {
  /** data holds the full error body, e.g. data.conflicts / data.warnings from the timetable API. */
  constructor(status, message, fields = {}, data = null) {
    super(message);
    this.status = status;
    this.fields = fields;
    this.data = data;
  }
}

let onUnauthorized = () => {};

/** AuthContext registers its logout here, so an expired token signs the user out everywhere. */
export function setUnauthorizedHandler(fn) {
  onUnauthorized = fn;
}

const fallbackMessages = {
  400: 'Some fields need fixing.',
  401: 'Your session has ended. Sign in again.',
  403: "Your role doesn't have access to this.",
  404: "That item doesn't exist.",
  409: 'This conflicts with existing data.',
};

async function request(path, { method = 'GET', body, headers: extra = {} } = {}) {
  const token = tokenStore.get();
  const headers = { ...extra };
  const isForm = body instanceof FormData;  // file uploads: the browser sets the multipart boundary itself
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json';
  if (token) headers.Authorization = `Bearer ${token}`;

  let res;
  try {
    res = await fetch(`/api${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "Can't reach the ClassOS server. Check that the backend is running on port 8080.");
  }

  const text = await res.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }

  if (!res.ok) {
    // A 401 while holding a token means the token expired or was rejected.
    // A 401 without a token is just a failed login, which the form handles.
    if (res.status === 401 && token) onUnauthorized();
    const message = data?.detail || fallbackMessages[res.status] || `Request failed (${res.status}).`;
    throw new ApiError(res.status, message, data?.fields || {}, data);
  }
  return data;
}

export const api = {
  get: (path, opts = {}) => request(path, opts),
  post: (path, body, opts = {}) => request(path, { ...opts, method: 'POST', body }),
  put: (path, body) => request(path, { method: 'PUT', body }),
  patch: (path, body) => request(path, { method: 'PATCH', body }),
  del: (path) => request(path, { method: 'DELETE' }),
  upload: (path, formData) => request(path, { method: 'POST', body: formData }),
  download,
};

/**
 * Downloads a protected file. A plain <a href> cannot send the Authorization header,
 * so the file is fetched with the token and handed to the browser as a blob.
 */
async function download(path) {
  const token = tokenStore.get();
  let res;
  try {
    res = await fetch(`/api${path}`, { headers: token ? { Authorization: `Bearer ${token}` } : {} });
  } catch {
    throw new ApiError(0, "Can't reach the ClassOS server.");
  }
  if (!res.ok) {
    if (res.status === 401 && token) onUnauthorized();
    let detail = '';
    try { detail = (await res.json()).detail; } catch { /* not JSON */ }
    throw new ApiError(res.status, detail || fallbackMessages[res.status] || 'Download failed.');
  }
  const disposition = res.headers.get('Content-Disposition') || '';
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  const name = star ? decodeURIComponent(star[1]) : plain ? plain[1] : 'download';
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
