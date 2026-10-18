/**
 * Makes sure the demo institution has a student to sign in with: a fresh database (as in CI) has staff but no students.
 * The student is made the way a real one is, through the API: sign-up by Form Four index number at (simulated) NECTA,
 * application and fee, selection, (simulated) NACTVET verification, offer accepted, Registry hand-over and first registration.
 * Its registration number and password reach the tests through E2E_STUDENT_LOGIN / E2E_STUDENT_PASSWORD.
 */
const BASE = (process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173').replace(/\/$/, '');
const PASSWORD = 'Kifaru#Mto47';
const PHONE = '0754990004';

type Json = Record<string, any>;

async function call(method: string, path: string, token?: string, body?: unknown): Promise<Json> {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'X-Tenant': 'DEMO', 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : {};
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status}: ${text.slice(0, 300)}`);
  return data;
}

const staff = async (u: string, p: string) => (await call('POST', '/api/auth/login', undefined, { username: u, password: p })).accessToken as string;

async function studentLogin(login: string): Promise<boolean> {
  try {
    await call('POST', '/api/student-auth/login', undefined, { login, password: PASSWORD });
    return true;
  } catch {
    return false;
  }
}

/** A 2025 CSEE index number the simulated NECTA gives four passes including Basic Mathematics, and NACTVET verifies (ends in 5). */
async function eligibleIndex(): Promise<string> {
  for (let n = 5; n < 3000; n += 10) {
    const index = `S0404/${String(n).padStart(4, '0')}`;
    const r = await call('POST', '/api/applicant-auth/necta-lookup', undefined, { indexNumber: index, examYear: 2025 });
    const subjects: { name: string; grade: string }[] = r.subjects ?? [];
    const passes = subjects.filter((s) => 'ABCD'.includes(s.grade));
    if (passes.length >= 4 && passes.some((s) => s.name === 'BASIC MATHEMATICS')) return index;
  }
  throw new Error('no eligible simulated index number');
}

export default async function globalSetup() {
  // already made (a local database, or a second run): sign in by mobile number
  if (await studentLogin(PHONE)) {
    process.env.E2E_STUDENT_LOGIN = PHONE;
    process.env.E2E_STUDENT_PASSWORD = PASSWORD;
    return;
  }
  const admissions = await staff('admissions', 'Admissions@2026');
  const window = (await call('GET', '/api/admission/windows', admissions)).find((w: Json) => w.open) ?? (await call('GET', '/api/admission/windows', admissions))[0];
  const detail = await call('GET', `/api/admission/windows/${window.id}`, admissions);
  const nta4 = detail.programmes.find((p: Json) => p.awardFamily === 'NTA' && p.awardLevel === 4) ?? detail.programmes[0];

  const index = await eligibleIndex();
  const reg = await call('POST', '/api/applicant-auth/register', undefined,
    { indexNumber: index, examYear: 2025, phone: PHONE, password: PASSWORD, acceptPrivacyNotice: true });
  const applicant = (await call('POST', '/api/applicant-auth/verify', undefined, { phone: PHONE, code: reg.devOtp })).accessToken;
  await call('PUT', '/api/applicant/profile', applicant, {
    firstName: 'E2E', surname: 'Student', sex: 'F', dateOfBirth: '2006-01-01', nationality: 'Tanzanian', region: 'Dodoma',
    nextOfKinName: 'Parent', nextOfKinPhone: '0713000111',
  });
  const app = await call('POST', '/api/applicant/applications', applicant, { windowId: window.id });
  if (detail.applicationFee > 0) {
    await call('POST', `/api/applicant/applications/${app.id}/fee`, applicant);
    await call('POST', `/api/applicant/applications/${app.id}/fee/simulate-payment`, applicant);
  }
  await call('PUT', `/api/applicant/applications/${app.id}/choices`, applicant, { windowProgrammeIds: [nta4.id] });
  await call('POST', `/api/applicant/applications/${app.id}/submit`, applicant);

  const academic = await staff('academic', 'Academic@2026');
  const round = await call('POST', `/api/admission/windows/${window.id}/selection-rounds`, admissions);
  await call('POST', `/api/admission/selection-rounds/${round.round.id}/approve`, academic, { committeeMinuteRef: 'AC/E2E/1' });
  const batch = await call('POST', `/api/admission/windows/${window.id}/nactvet-submissions`, admissions, { channel: 'API', paymentReference: '991100223344' });
  await call('POST', `/api/admission/nactvet-submissions/${batch.id}/fetch-outcomes`, admissions);
  await call('POST', `/api/applicant/applications/${app.id}/offer/accept`, applicant, {});

  const registry = await staff('registry', 'Registry@2026');
  const handover = await call('POST', `/api/registry/windows/${window.id}/handover`, registry, {});
  // first registration needs the first instalment paid (registration clearance): pay the student's fees (simulated)
  const student = (await call('POST', '/api/student-auth/login', undefined, { login: PHONE, password: PASSWORD })).accessToken;
  for (const inv of (await call('GET', '/api/student/account', student)).invoices ?? []) {
    if (inv.balance > 0) await call('POST', `/api/student/invoices/${inv.id}/simulate-payment`, student, {});
  }
  for (const s of handover.students ?? []) {
    const d = await call('GET', `/api/registry/students/${s.studentId}`, registry);
    const items: string[] = (d.firstRegistration?.documents ?? []).map((x: Json) => x.item);
    const documents = (items.length ? items : (detail.joiningInstructions?.documentsToBring ?? ['Original CSEE certificate']))
      .map((item: string) => ({ item, verified: true }));
    await call('PUT', `/api/registry/students/${s.studentId}/first-registration`, registry, { documents });
    await call('POST', `/api/registry/students/${s.studentId}/first-registration/approve`, registry);
  }
  if (!(await studentLogin(PHONE))) throw new Error('the e2e student could not sign in after hand-over');
  process.env.E2E_STUDENT_LOGIN = PHONE;
  process.env.E2E_STUDENT_PASSWORD = PASSWORD;
}
