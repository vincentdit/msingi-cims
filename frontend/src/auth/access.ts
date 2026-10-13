/**
 * Which staff roles use which back-office pages. It mirrors the permissions the API enforces, so a menu only offers pages
 * the signed-in person can open (the API still decides; this keeps people away from "access denied").
 */
export const ROLES = {
  ADMIN: 'INSTITUTION_ADMIN',
  MANAGEMENT: 'MANAGEMENT_VIEWER',
  ACADEMIC: 'DEPUTY_PRINCIPAL_ACADEMIC',
  ADMISSIONS: 'ADMISSIONS_OFFICER',
  REGISTRAR: 'REGISTRAR',
  HOD: 'HEAD_OF_DEPARTMENT',
  COORDINATOR: 'PROGRAMME_COORDINATOR',
  LECTURER: 'LECTURER',
  ASSESSOR: 'ASSESSOR',
  EXAMS: 'EXAMINATIONS_OFFICER',
  BURSAR: 'BURSAR',
  ACCOUNTANT: 'ACCOUNTANT',
  WARDEN: 'HOSTEL_WARDEN',
  IPT: 'IPT_COORDINATOR',
  CLEARANCE: 'CLEARANCE_OFFICER',
  AUDITOR: 'AUDITOR',
} as const;

const R = ROLES;
const EXAMS_READERS = [R.ADMIN, R.ACADEMIC, R.HOD, R.EXAMS, R.MANAGEMENT];
const TEACHING = [...EXAMS_READERS, R.LECTURER, R.ASSESSOR, R.COORDINATOR];

export type PageKey =
  | 'dashboard' | 'approvals' | 'reports' | 'windows' | 'applications' | 'programmes' | 'students' | 'statusChanges' | 'semesterRegistrations' | 'graduations'
  | 'calendar' | 'curricula' | 'teaching' | 'marks' | 'eligibility' | 'results' | 'amendments' | 'regulations'
  | 'clearance' | 'hostels' | 'ipt' | 'finance' | 'notices' | 'messages' | 'users' | 'audit' | 'institution';

export const PAGE_ROLES: Record<PageKey, readonly string[]> = {
  dashboard: [R.ADMIN, R.MANAGEMENT, R.ACADEMIC, R.ADMISSIONS, R.REGISTRAR, R.HOD, R.EXAMS, R.BURSAR, R.ACCOUNTANT, R.AUDITOR],
  approvals: [R.ADMIN, R.ACADEMIC, R.HOD, R.EXAMS],
  reports: [R.ADMIN, R.MANAGEMENT, R.ACADEMIC, R.ADMISSIONS, R.REGISTRAR, R.HOD, R.EXAMS, R.BURSAR, R.ACCOUNTANT, R.WARDEN, R.IPT, R.AUDITOR],
  windows: [R.ADMIN, R.ADMISSIONS, R.ACADEMIC, R.REGISTRAR, R.MANAGEMENT, R.AUDITOR],
  applications: [R.ADMIN, R.ADMISSIONS, R.ACADEMIC, R.REGISTRAR, R.MANAGEMENT, R.AUDITOR],
  programmes: [R.ADMIN, R.ACADEMIC, R.ADMISSIONS, R.REGISTRAR, R.HOD, R.EXAMS, R.MANAGEMENT, R.AUDITOR],
  students: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.ADMISSIONS, R.HOD, R.EXAMS, R.MANAGEMENT],
  statusChanges: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.HOD, R.MANAGEMENT],
  semesterRegistrations: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.HOD, R.MANAGEMENT],
  graduations: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.MANAGEMENT],
  calendar: [R.ADMIN, R.ACADEMIC, R.HOD, R.COORDINATOR, R.REGISTRAR, R.ADMISSIONS, R.EXAMS, R.MANAGEMENT, R.AUDITOR],
  curricula: [R.ADMIN, R.ACADEMIC, R.HOD, R.COORDINATOR, R.REGISTRAR, R.EXAMS, R.MANAGEMENT, R.AUDITOR],
  teaching: TEACHING,
  marks: TEACHING,
  eligibility: EXAMS_READERS,
  results: EXAMS_READERS,
  amendments: [R.ADMIN, R.EXAMS, R.ACADEMIC],
  regulations: EXAMS_READERS,
  clearance: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.MANAGEMENT, R.HOD, R.CLEARANCE, R.WARDEN],
  hostels: [R.ADMIN, R.WARDEN, R.REGISTRAR, R.BURSAR, R.ACCOUNTANT, R.MANAGEMENT],
  ipt: [R.ADMIN, R.IPT, R.REGISTRAR, R.ACADEMIC, R.HOD, R.EXAMS, R.MANAGEMENT, R.COORDINATOR, R.LECTURER, R.ASSESSOR],
  finance: [R.ADMIN, R.BURSAR, R.ACCOUNTANT, R.MANAGEMENT, R.AUDITOR],
  notices: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.BURSAR, R.ACCOUNTANT, R.EXAMS, R.WARDEN, R.ADMISSIONS, R.MANAGEMENT],
  messages: [R.ADMIN, R.REGISTRAR, R.ADMISSIONS, R.BURSAR, R.ACADEMIC, R.AUDITOR, R.MANAGEMENT],
  users: [R.ADMIN, R.AUDITOR],
  audit: [R.AUDITOR, R.ADMIN],
  institution: [R.ADMIN],
};

export const can = (roles: readonly string[] | undefined, page: PageKey) => !!roles?.some((r) => PAGE_ROLES[page].includes(r));

/** The page a path belongs to (the first segment after /staff). */
const PATH_PAGE: Record<string, PageKey> = {
  '': 'dashboard', approvals: 'approvals', reports: 'reports', windows: 'windows', applications: 'applications', programmes: 'programmes', students: 'students', 'status-changes': 'statusChanges',
  'semester-registrations': 'semesterRegistrations', graduations: 'graduations', calendar: 'calendar', curricula: 'curricula',
  teaching: 'teaching', marks: 'marks', eligibility: 'eligibility', results: 'results', amendments: 'amendments', regulations: 'regulations',
  clearance: 'clearance', hostels: 'hostels', ipt: 'ipt', finance: 'finance', notices: 'notices', messages: 'messages', users: 'users', audit: 'audit',
  institution: 'institution',
};

export function pageOf(pathname: string): PageKey | undefined {
  const seg = pathname.replace(/^\/staff\/?/, '').split('/')[0];
  return PATH_PAGE[seg];
}

/** Role names as people use them. */
export const ROLE_LABEL: Record<string, string> = {
  INSTITUTION_ADMIN: 'Institution Administrator', MANAGEMENT_VIEWER: 'Principal / Management', DEPUTY_PRINCIPAL_ACADEMIC: 'Deputy Principal (Academic)',
  ADMISSIONS_OFFICER: 'Admissions Officer', REGISTRAR: 'Registrar', HEAD_OF_DEPARTMENT: 'Head of Department', PROGRAMME_COORDINATOR: 'Programme Coordinator',
  LECTURER: 'Lecturer', ASSESSOR: 'Assessor (NVA)', EXAMINATIONS_OFFICER: 'Examinations Officer', BURSAR: 'Bursar', ACCOUNTANT: 'Accountant',
  HOSTEL_WARDEN: 'Hostel Warden', IPT_COORDINATOR: 'IPT Coordinator', CLEARANCE_OFFICER: 'Clearance Officer', AUDITOR: 'Auditor',
};
