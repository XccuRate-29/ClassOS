// A whole class scans the attendance QR code at the same moment.
//
// Setup (through the real API): a department, course, section of N students, a teacher,
// an offering, and an open attendance session. Then every student marks at once.
//
// Passes only if: every mark is ACCEPTED, p95 mark latency < 500 ms, and after closing,
// the session shows exactly N present (no lost or duplicated records).
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { BASE, adminToken, importUsers, login, loginMany, must, params, runId } from './lib.js';

const N = Number(__ENV.STUDENTS || 60);
const accepted = new Counter('marks_accepted');

export const options = {
  setupTimeout: '5m',
  scenarios: {
    burst: { executor: 'shared-iterations', vus: N, iterations: N, maxDuration: '2m' },
  },
  thresholds: {
    'http_req_duration{name:mark}': ['p(95)<500'],
    'http_req_failed{name:mark}': ['rate==0'],
    'checks{check:marked present}': ['rate==1'],
    'checks{check:every student recorded exactly once}': ['rate==1'],
  },
};

export function setup() {
  const run = runId();
  const admin = adminToken();
  const A = params(admin);
  const term = must(http.get(`${BASE}/api/admin/setup/terms`, A), [200], 'terms').json().find((t) => t.current);
  if (!term) fail('Make a term current first (Setup, Terms tab).');

  const dept = must(http.post(`${BASE}/api/admin/setup/departments`,
    JSON.stringify({ code: `LT${run}`.toUpperCase().slice(0, 16), name: 'Load test' }), A), [201], 'department').json();
  const course = must(http.post(`${BASE}/api/admin/setup/courses`, JSON.stringify({
    departmentId: dept.id, code: `LT-${run}`.toUpperCase().slice(0, 20), title: 'Load test course',
    credits: 1, lectureHoursPerWeek: 1, labHoursPerWeek: 0 }), A), [201], 'course').json();
  const section = must(http.post(`${BASE}/api/admin/setup/sections`, JSON.stringify({
    termId: term.id, departmentId: dept.id, name: `LT-${run}`.slice(0, 20), semester: 1, strength: N }), A), [201], 'section').json();

  const password = `loadtest-${run}`;
  const teacherEmail = `lt-teacher-${run}@loadtest.local`;
  const students = [];
  for (let i = 0; i < N; i++) students.push(`lt-s${i}-${run}@loadtest.local`);
  importUsers(admin, [{ email: teacherEmail, name: 'Load Teacher', role: 'TEACHER' }]
    .concat(students.map((email, i) => ({ email, name: `Load Student ${i}`, role: 'STUDENT' }))), password);
  must(http.post(`${BASE}/api/admin/setup/sections/${section.id}/students`, JSON.stringify({ emails: students }), A), [200], 'enrol');

  const teacherId = must(http.get(`${BASE}/api/admin/setup/teachers`, A), [200], 'teachers').json()
    .find((t) => t.email === teacherEmail).id;
  const offering = must(http.post(`${BASE}/api/admin/setup/offerings`, JSON.stringify({
    termId: term.id, courseId: course.id, sectionId: section.id, teacherId }), A), [201], 'offering').json();

  const teacher = login(teacherEmail, password);
  const session = must(http.post(`${BASE}/api/teacher/attendance/sessions`, JSON.stringify({ offeringId: offering.id }),
    params(teacher)), [201], 'open session').json();
  const tokens = loginMany(students, password);
  return { teacher, sessionId: session.id, tokens, run };
}

export default function (data) {
  const i = exec.scenario.iterationInTest;  // 0..N-1, each student exactly once
  // Like a real scan: read the code currently on the teacher's screen, then mark.
  const code = http.get(`${BASE}/api/teacher/attendance/sessions/${data.sessionId}/code`, params(data.teacher, 'code')).json('code');
  const p = params(data.tokens[i], 'mark');
  p.headers['X-Device-Id'] = `lt${data.run}phone${i}`.replace(/[^A-Za-z0-9]/g, '').padEnd(16, '0');
  const res = http.post(`${BASE}/api/student/attendance/mark`, JSON.stringify({ sessionId: data.sessionId, code, method: 'QR' }), p);
  const ok = check(res, { 'marked present': (r) => r.status === 200 && r.json('outcome') === 'ACCEPTED' });
  if (ok) accepted.add(1);
  else console.warn(`student ${i}: HTTP ${res.status} ${res.body}`);
}

export function teardown(data) {
  const d = must(http.post(`${BASE}/api/teacher/attendance/sessions/${data.sessionId}/close`, null, params(data.teacher)),
    [200], 'close').json();
  console.log(`Closed session ${data.sessionId}: ${d.presentCount} of ${d.enrolledCount} present`);
  check(d, { 'every student recorded exactly once': (x) => x.presentCount === N && x.enrolledCount === N });
}
