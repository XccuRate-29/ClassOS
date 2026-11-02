// Ordinary daytime traffic: students and teachers opening their pages.
// Uses the dev-seed accounts (DEV_SEED_ENABLED=true), so set DEV_SEED_PASSWORD.
//
// Passes if fewer than 1% of requests fail and p95 latency is under 300 ms per page.
import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import exec from 'k6/execution';
import { BASE, loginMany, params } from './lib.js';

const PEAK = Number(__ENV.VUS || 100);
const STUDENTS = [];
for (const s of ['3a', '3b', '3c', '5a', '5b']) {
  for (const k of [1, 2]) STUDENTS.push(`student.cse-${s}-${k}@classos.local`);
}
const TEACHERS = ['anita.rawat', 'vikram.negi', 'priya.bisht', 'rahul.joshi', 'meera.pant', 'sanjay.kumar',
  'neha.bhatt', 'arjun.rana'].map((n) => `${n}@classos.local`);

export const options = {
  setupTimeout: '2m',
  scenarios: {
    day: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: PEAK },
        { duration: '1m', target: PEAK },
        { duration: '20s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:timetable}': ['p(95)<300'],
    'http_req_duration{name:courses}': ['p(95)<300'],
    'http_req_duration{name:summary}': ['p(95)<300'],
    'http_req_duration{name:queue}': ['p(95)<300'],
  },
};

export function setup() {
  if (!__ENV.DEV_SEED_PASSWORD) fail('Set DEV_SEED_PASSWORD; this test uses the sample department.');
  return {
    students: loginMany(STUDENTS, __ENV.DEV_SEED_PASSWORD),
    teachers: loginMany(TEACHERS, __ENV.DEV_SEED_PASSWORD),
  };
}

export default function (data) {
  const vu = exec.vu.idInTest;
  if (vu % 5 === 0) {  // about 1 in 5 virtual users is a teacher
    const t = data.teachers[vu % data.teachers.length];
    check(http.get(`${BASE}/api/timetable/me`, params(t, 'timetable')), { ok: (r) => r.status === 200 });
    check(http.get(`${BASE}/api/teacher/grading/queue?policy=ROUND_ROBIN`, params(t, 'queue')), { ok: (r) => r.status === 200 });
  } else {
    const s = data.students[vu % data.students.length];
    check(http.get(`${BASE}/api/timetable/me`, params(s, 'timetable')), { ok: (r) => r.status === 200 });
    check(http.get(`${BASE}/api/workspace/courses`, params(s, 'courses')), { ok: (r) => r.status === 200 });
    check(http.get(`${BASE}/api/student/attendance/summary`, params(s, 'summary')), { ok: (r) => r.status === 200 });
  }
  sleep(1 + Math.random() * 2);  // a person reading the page
}
