// Many teachers try to book the same room for the same time at the same moment.
//
// Passes only if: exactly ONE booking is created, every other request gets 409 (never a
// 500 or a second booking), and p95 latency stays under 1 s while requests queue on the
// room's row lock.
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { BASE, adminToken, importUsers, loginMany, must, params, runId } from './lib.js';

const N = Number(__ENV.TEACHERS || 40);
const created = new Counter('bookings_created');
const refused = new Counter('bookings_refused_409');
const unexpected = new Counter('bookings_unexpected');

export const options = {
  setupTimeout: '5m',
  scenarios: {
    contention: { executor: 'shared-iterations', vus: N, iterations: N, maxDuration: '2m' },
  },
  thresholds: {
    bookings_created: ['count==1'],
    bookings_unexpected: ['count==0'],
    'http_req_duration{name:book}': ['p(95)<1000'],
    'checks{check:exactly one booking stored}': ['rate==1'],
  },
};

export function setup() {
  const run = runId();
  const admin = adminToken();
  const room = must(http.post(`${BASE}/api/admin/setup/rooms`, JSON.stringify({
    code: `LT-R-${run}`.toUpperCase().slice(0, 20), building: 'Load test', capacity: 40, roomType: 'LAB' }),
    params(admin)), [201], 'room').json();
  const password = `loadtest-${run}`;
  const emails = [];
  for (let i = 0; i < N; i++) emails.push(`lt-t${i}-${run}@loadtest.local`);
  importUsers(admin, emails.map((email, i) => ({ email, name: `Load Teacher ${i}`, role: 'TEACHER' })), password);
  // Two days ahead, so the date is in the future in every time zone.
  const date = new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 10);
  return { admin, roomId: room.id, date, tokens: loginMany(emails, password) };
}

export default function (data) {
  const i = exec.scenario.iterationInTest;
  const res = http.post(`${BASE}/api/bookings`, JSON.stringify({
    roomId: data.roomId, date: data.date, startTime: '14:00', endTime: '15:00', purpose: `Load test ${i}` }),
    params(data.tokens[i], 'book'));
  if (res.status === 201) created.add(1);
  else if (res.status === 409) refused.add(1);
  else { unexpected.add(1); console.warn(`teacher ${i}: HTTP ${res.status} ${res.body}`); }
}

export function teardown(data) {
  const day = must(http.get(`${BASE}/api/bookings/room-day?roomId=${data.roomId}&date=${data.date}`, params(data.admin)),
    [200], 'room day').json();
  console.log(`Room ${data.roomId} on ${data.date}: ${day.busy.length} booking(s) stored`);
  check(day, { 'exactly one booking stored': (d) => d.busy.length === 1 });
}
