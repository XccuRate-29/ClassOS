// Shared helpers for the ClassOS k6 load tests.
import http from 'k6/http';
import { fail } from 'k6';

export const BASE = __ENV.BASE_URL || 'http://localhost:8088';

export function params(token, tagName) {
  const p = { headers: { 'Content-Type': 'application/json' } };
  if (token) p.headers.Authorization = `Bearer ${token}`;
  if (tagName) p.tags = { name: tagName };
  return p;
}

/** Fails the whole test (in setup) if the status is not one of the expected ones. */
export function must(res, expected, what) {
  if (expected.indexOf(res.status) < 0) fail(`${what}: HTTP ${res.status} ${res.body}`);
  return res;
}

export function login(email, password) {
  const r = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password }), params(null, 'login'));
  if (r.status !== 200) fail(`sign-in failed for ${email}: HTTP ${r.status} ${r.body}`);
  return r.json('accessToken');
}

/** Signs many accounts in, in parallel batches (BCrypt makes each sign-in deliberately slow). */
export function loginMany(emails, password, batchSize = 20) {
  const tokens = [];
  for (let i = 0; i < emails.length; i += batchSize) {
    const batch = emails.slice(i, i + batchSize).map((email) => [
      'POST', `${BASE}/api/auth/login`, JSON.stringify({ email, password }), params(null, 'login')]);
    http.batch(batch).forEach((r, k) => {
      if (r.status !== 200) fail(`sign-in failed for ${emails[i + k]}: HTTP ${r.status}`);
      tokens.push(r.json('accessToken'));
    });
  }
  return tokens;
}

export function adminToken() {
  if (!__ENV.ADMIN_EMAIL || !__ENV.ADMIN_PASSWORD) fail('Set ADMIN_EMAIL and ADMIN_PASSWORD (see loadtest/README.md).');
  return login(__ENV.ADMIN_EMAIL, __ENV.ADMIN_PASSWORD);
}

/** Creates users through the CSV import API; returns nothing, fails on any skipped line. */
export function importUsers(admin, rows, password) {
  const csv = ['email,full name,role'].concat(rows.map((r) => `${r.email},${r.name},${r.role}`)).join('\n');
  const res = must(http.post(`${BASE}/api/admin/users/import`, JSON.stringify({ csv, temporaryPassword: password }),
    params(admin)), [200], 'import users').json();
  if (res.created !== rows.length) fail(`import created ${res.created} of ${rows.length}: ${JSON.stringify(res.errors)}`);
}

/** Short unique suffix so repeated runs never collide. */
export function runId() {
  return Date.now().toString(36);
}
