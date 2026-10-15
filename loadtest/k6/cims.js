// CIMS load test (NFR-PER-01/02) with k6: https://k6.io
//
//   k6 run -e BASE_URL=https://demo.staging.cims.co.tz -e TENANT=DEMO -e PROFILE=smoke loadtest/k6/cims.js
//
// Profiles:  smoke   10 users for 1 minute (does the script and the installation work)
//            normal  200 users for 10 minutes (an ordinary working day of one large institution)
//            peak    ramp to 2,000 users (application deadline / results day of one large institution, NFR-PER-02)
// The mix: 70% applicants and visitors on the public portal, 20% staff working lists and dashboards, 10% students on
// their portal (when STUDENT_USER is given). Thresholds are NFR-PER-01: 95% of requests within 2 s, 99% within 4 s, and
// fewer than 1% errors. For peak, NFR-PER-02 allows 50% more (3 s / 6 s).
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8085';
const TENANT = __ENV.TENANT || 'DEMO';
const PROFILE = __ENV.PROFILE || 'smoke';
const STAFF = { username: __ENV.STAFF_USER || 'admin', password: __ENV.STAFF_PASSWORD || 'Admin@2026' };
const STUDENT = __ENV.STUDENT_USER ? { registrationNo: __ENV.STUDENT_USER, password: __ENV.STUDENT_PASSWORD } : null;
const peak = PROFILE === 'peak';

const stages = {
  smoke: [{ duration: '10s', target: 10 }, { duration: '50s', target: 10 }],
  normal: [{ duration: '2m', target: 200 }, { duration: '8m', target: 200 }, { duration: '30s', target: 0 }],
  peak: [{ duration: '5m', target: 500 }, { duration: '5m', target: 2000 }, { duration: '10m', target: 2000 }, { duration: '2m', target: 0 }],
}[PROFILE];

const share = (n) => stages.map((s) => ({ duration: s.duration, target: Math.max(1, Math.round(s.target * n)) }));

export const options = {
  scenarios: {
    portal: { executor: 'ramping-vus', exec: 'portal', stages: share(STUDENT ? 0.7 : 0.8) },
    staff: { executor: 'ramping-vus', exec: 'staff', stages: share(0.2) },
    ...(STUDENT ? { students: { executor: 'ramping-vus', exec: 'student', stages: share(0.1) } } : {}),
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: peak ? ['p(95)<3000', 'p(99)<6000'] : ['p(95)<2000', 'p(99)<4000'],
    'http_req_duration{kind:list}': peak ? ['p(95)<3000'] : ['p(95)<2000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const login = new Trend('login_duration', true);
const headers = (token) => ({ headers: { 'X-Tenant': TENANT, 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) } });

// one sign-in per virtual user, reused (as a person stays signed in)
let staffToken = null;
let studentToken = null;

function signIn(path, body) {
  const r = http.post(`${BASE}${path}`, JSON.stringify(body), { ...headers(), tags: { kind: 'login' } });
  login.add(r.timings.duration);
  check(r, { 'signed in': (x) => x.status === 200 });
  return r.status === 200 ? r.json('accessToken') : null;
}

export function portal() {
  group('public portal', () => {
    const a = http.get(`${BASE}/api/public/institution`, { ...headers(), tags: { kind: 'page' } });
    check(a, { 'institution 200': (x) => x.status === 200 });
    const w = http.get(`${BASE}/api/public/windows`, { ...headers(), tags: { kind: 'list' } });
    check(w, { 'windows 200': (x) => x.status === 200 });
    http.get(`${BASE}/api/public/windows/upcoming`, { ...headers(), tags: { kind: 'list' } });
  });
  sleep(2 + Math.random() * 4);   // reading the page
}

export function staff() {
  if (!staffToken) staffToken = signIn('/api/auth/login', STAFF);
  if (!staffToken) { sleep(5); return; }
  const h = headers(staffToken);
  group('staff back office', () => {
    check(http.get(`${BASE}/api/dashboard`, { ...h, tags: { kind: 'page' } }), { 'dashboard 200': (x) => x.status === 200 });
    check(http.get(`${BASE}/api/registry/students?page=0&size=25`, { ...h, tags: { kind: 'list' } }), { 'students 200': (x) => x.status === 200 });
    check(http.get(`${BASE}/api/admission/windows`, { ...h, tags: { kind: 'list' } }), { 'windows 200': (x) => x.status === 200 });
    http.get(`${BASE}/api/dashboard/overview`, { ...h, tags: { kind: 'page' } });
  });
  sleep(3 + Math.random() * 7);
}

export function student() {
  if (!studentToken) studentToken = signIn('/api/student-auth/login', STUDENT);
  if (!studentToken) { sleep(5); return; }
  const h = headers(studentToken);
  group('student portal', () => {
    check(http.get(`${BASE}/api/student/dashboard`, { ...h, tags: { kind: 'page' } }), { 'student dashboard 200': (x) => x.status === 200 });
    http.get(`${BASE}/api/student/account`, { ...h, tags: { kind: 'page' } });
    http.get(`${BASE}/api/student/notices`, { ...h, tags: { kind: 'list' } });
  });
  sleep(4 + Math.random() * 8);
}
