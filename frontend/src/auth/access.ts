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
  SPONSOR: 'SPONSOR_OFFICER',
  AUDITOR: 'AUDITOR',
  LIBRARIAN: 'LIBRARIAN',
  HR: 'HR_OFFICER',
  STORES: 'STORES_OFFICER',
  DEAN: 'DEAN_OF_STUDENTS',
  QA: 'QUALITY_ASSURANCE_OFFICER',
  PROCUREMENT: 'PROCUREMENT_OFFICER',
} as const;

const R = ROLES;
const EXAMS_READERS = [R.ADMIN, R.ACADEMIC, R.HOD, R.EXAMS, R.MANAGEMENT];
const TEACHING = [...EXAMS_READERS, R.LECTURER, R.ASSESSOR, R.COORDINATOR];

export type PageKey =
  | 'dashboard' | 'approvals' | 'reports' | 'windows' | 'applications' | 'programmes' | 'students' | 'statusChanges' | 'transfers' | 'semesterRegistrations' | 'healthInsurance' | 'graduations'
  | 'calendar' | 'curricula' | 'myTeaching' | 'timetable' | 'examTimetable' | 'teaching' | 'competence' | 'marks' | 'eligibility' | 'results' | 'amendments' | 'regulations'
  | 'examsOffice' | 'appeals' | 'clearance' | 'hostels' | 'ipt' | 'financeOverview' | 'sponsors' | 'waivers' | 'finance' | 'notices' | 'messages' | 'users' | 'audit' | 'institution' | 'security' | 'letters' | 'imports' | 'alumni' | 'integrations' | 'helpdesk' | 'help' | 'subscription'
  | 'almanac' | 'organisation' | 'nactvetReturns' | 'verifications' | 'library' | 'hr' | 'leave' | 'assets'
  | 'payroll' | 'payslips' | 'quality' | 'welfare' | 'procurement';

export const PAGE_ROLES: Record<PageKey, readonly string[]> = {
  // the roles the dashboard has figures for (admissions, registry or finance); others start from their own board
  dashboard: [R.ADMIN, R.MANAGEMENT, R.ACADEMIC, R.ADMISSIONS, R.REGISTRAR, R.HOD, R.BURSAR, R.ACCOUNTANT, R.AUDITOR],
  approvals: [R.ADMIN, R.ACADEMIC, R.HOD, R.EXAMS, R.BURSAR, R.MANAGEMENT],
  reports: [R.ADMIN, R.MANAGEMENT, R.ACADEMIC, R.ADMISSIONS, R.REGISTRAR, R.HOD, R.EXAMS, R.BURSAR, R.ACCOUNTANT, R.WARDEN, R.IPT, R.AUDITOR],
  windows: [R.ADMIN, R.ADMISSIONS, R.ACADEMIC, R.REGISTRAR, R.MANAGEMENT, R.AUDITOR],
  applications: [R.ADMIN, R.ADMISSIONS, R.ACADEMIC, R.REGISTRAR, R.MANAGEMENT, R.AUDITOR],
  programmes: [R.ADMIN, R.ACADEMIC, R.ADMISSIONS, R.REGISTRAR, R.HOD, R.EXAMS, R.MANAGEMENT, R.AUDITOR],
  students: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.ADMISSIONS, R.HOD, R.EXAMS, R.MANAGEMENT],
  statusChanges: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.HOD, R.MANAGEMENT],
  transfers: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.HOD, R.MANAGEMENT],
  semesterRegistrations: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.HOD, R.MANAGEMENT],
  healthInsurance: [R.ADMIN, R.REGISTRAR, R.BURSAR, R.ACCOUNTANT, R.ACADEMIC, R.MANAGEMENT, R.AUDITOR],
  graduations: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.MANAGEMENT],
  nactvetReturns: [R.ADMIN, R.REGISTRAR, R.EXAMS, R.ACADEMIC, R.MANAGEMENT],
  verifications: [R.ADMIN, R.REGISTRAR, R.EXAMS, R.MANAGEMENT],
  calendar: [R.ADMIN, R.ACADEMIC, R.HOD, R.COORDINATOR, R.REGISTRAR, R.ADMISSIONS, R.EXAMS, R.MANAGEMENT, R.AUDITOR],
  curricula: [R.ADMIN, R.ACADEMIC, R.HOD, R.COORDINATOR, R.REGISTRAR, R.EXAMS, R.MANAGEMENT, R.AUDITOR],
  myTeaching: [R.LECTURER, R.ASSESSOR, R.HOD, R.COORDINATOR, R.EXAMS],
  timetable: [...TEACHING, R.REGISTRAR],
  examTimetable: [...TEACHING, R.REGISTRAR],
  teaching: TEACHING,
  competence: TEACHING,
  marks: TEACHING,
  examsOffice: [R.ADMIN, R.EXAMS, R.ACADEMIC, R.MANAGEMENT],
  appeals: [R.ADMIN, R.EXAMS, R.ACADEMIC, R.MANAGEMENT, R.HOD, R.LECTURER, R.ASSESSOR, R.COORDINATOR],
  eligibility: EXAMS_READERS,
  results: EXAMS_READERS,
  amendments: [R.ADMIN, R.EXAMS, R.ACADEMIC],
  regulations: EXAMS_READERS,
  clearance: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.MANAGEMENT, R.HOD, R.CLEARANCE, R.WARDEN],
  hostels: [R.ADMIN, R.WARDEN, R.REGISTRAR, R.BURSAR, R.ACCOUNTANT, R.MANAGEMENT],
  ipt: [R.ADMIN, R.IPT, R.REGISTRAR, R.ACADEMIC, R.HOD, R.EXAMS, R.MANAGEMENT, R.COORDINATOR, R.LECTURER, R.ASSESSOR],
  financeOverview: [R.ADMIN, R.BURSAR, R.ACCOUNTANT, R.MANAGEMENT, R.AUDITOR],
  sponsors: [R.ADMIN, R.BURSAR, R.ACCOUNTANT, R.MANAGEMENT, R.AUDITOR, R.SPONSOR],
  waivers: [R.ADMIN, R.BURSAR, R.ACCOUNTANT, R.MANAGEMENT, R.AUDITOR],
  finance: [R.ADMIN, R.BURSAR, R.ACCOUNTANT, R.MANAGEMENT, R.AUDITOR],
  notices: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.BURSAR, R.ACCOUNTANT, R.EXAMS, R.WARDEN, R.ADMISSIONS, R.MANAGEMENT],
  messages: [R.ADMIN, R.REGISTRAR, R.ADMISSIONS, R.BURSAR, R.ACADEMIC, R.AUDITOR, R.MANAGEMENT],
  users: [R.ADMIN, R.AUDITOR],
  audit: [R.AUDITOR, R.ADMIN],
  institution: [R.ADMIN],
  security: [R.ADMIN, R.REGISTRAR],
  letters: [R.ADMIN, R.ADMISSIONS, R.IPT, R.REGISTRAR],
  imports: [R.ADMIN, R.REGISTRAR, R.EXAMS, R.BURSAR, R.ACCOUNTANT, R.ADMISSIONS],
  alumni: [R.ADMIN, R.REGISTRAR, R.ACADEMIC, R.MANAGEMENT],
  integrations: [R.ADMIN, R.BURSAR, R.ACCOUNTANT, R.REGISTRAR, R.ADMISSIONS, R.EXAMS],
  // the offices that answer helpdesk requests (each sees its own categories), and those who oversee them
  helpdesk: [R.ADMIN, R.ADMISSIONS, R.REGISTRAR, R.BURSAR, R.ACCOUNTANT, R.EXAMS, R.WARDEN, R.IPT, R.CLEARANCE, R.SPONSOR, R.ACADEMIC, R.AUDITOR, R.MANAGEMENT],
  // every member of staff can ask for help
  help: Object.values(R),
  // what the institution pays Msingi for CIMS
  subscription: [R.ADMIN],
  // the institution's year and its organisation: every member of staff reads them (the administrator, and for the almanac the
  // Deputy Principal (Academic), keep them)
  almanac: Object.values(R),
  organisation: Object.values(R),
  library: [R.ADMIN, R.LIBRARIAN, R.MANAGEMENT, R.AUDITOR],
  hr: [R.ADMIN, R.HR, R.MANAGEMENT, R.AUDITOR],
  // every member of staff asks for leave here; Heads of Department and HR also decide
  leave: Object.values(R),
  assets: [R.ADMIN, R.STORES, R.BURSAR, R.ACCOUNTANT, R.MANAGEMENT, R.AUDITOR],
  // salaries: HR and the Accountant prepare, the Bursar or the Principal approves; every member of staff reads their own payslips
  payroll: [R.ADMIN, R.HR, R.ACCOUNTANT, R.BURSAR, R.MANAGEMENT, R.AUDITOR],
  payslips: Object.values(R),
  // students' evaluation of modules: the QA office and management; Heads for their department; lecturers for their own modules
  quality: [R.ADMIN, R.QA, R.ACADEMIC, R.MANAGEMENT, R.AUDITOR, R.HOD, R.LECTURER],
  // every member of staff can report an incident or refer a student; the Dean of Students handles them
  welfare: Object.values(R),
  // every member of staff can ask to buy; procurement, finance and stores do the rest
  procurement: Object.values(R),
};

export const can = (roles: readonly string[] | undefined, page: PageKey) => !!roles?.some((r) => PAGE_ROLES[page].includes(r));

/** The page a path belongs to (the first segment after /staff). */
const PATH_PAGE: Record<string, PageKey> = {
  '': 'dashboard', approvals: 'approvals', reports: 'reports', windows: 'windows', applications: 'applications', programmes: 'programmes', students: 'students', 'status-changes': 'statusChanges', transfers: 'transfers',
  'semester-registrations': 'semesterRegistrations', 'health-insurance': 'healthInsurance', graduations: 'graduations', calendar: 'calendar', curricula: 'curricula',
  'exams-office': 'examsOffice', 'my-teaching': 'myTeaching', timetable: 'timetable', 'exam-timetable': 'examTimetable', competence: 'competence', appeals: 'appeals', teaching: 'teaching', marks: 'marks', eligibility: 'eligibility', results: 'results', amendments: 'amendments', regulations: 'regulations',
  clearance: 'clearance', hostels: 'hostels', ipt: 'ipt', 'finance-overview': 'financeOverview', sponsors: 'sponsors', waivers: 'waivers', finance: 'finance', notices: 'notices', messages: 'messages', users: 'users', audit: 'audit',
  institution: 'institution', security: 'security', letters: 'letters', imports: 'imports', alumni: 'alumni', 'results-trends': 'results', integrations: 'integrations', helpdesk: 'helpdesk', help: 'help', 'msingi-subscription': 'subscription',
  almanac: 'almanac', organisation: 'organisation', 'nactvet-returns': 'nactvetReturns', verifications: 'verifications',
  library: 'library', hr: 'hr', leave: 'leave', assets: 'assets',
  payroll: 'payroll', payslips: 'payslips', quality: 'quality', welfare: 'welfare', procurement: 'procurement',
};

export function pageOf(pathname: string): PageKey | undefined {
  const seg = pathname.replace(/^\/staff\/?/, '').split('/')[0];
  return PATH_PAGE[seg];
}

/**
 * Pages inside a page whose roles are narrower than the page's own (the API refuses the others): the admission window's
 * selection and NACTVET steps are not open to the Auditor, the hand-over not to the Auditor either.
 */
const SUBPAGE_ROLES: { re: RegExp; roles: readonly string[] }[] = [
  { re: /^\/staff\/windows\/[^/]+\/(selection|verification|handover)$/, roles: [R.ADMIN, R.ADMISSIONS, R.ACADEMIC, R.REGISTRAR, R.MANAGEMENT] },
];

/** Whether the signed-in person may open a back-office address (a path, with or without ?query). Other portals' addresses are always allowed. */
export function canOpen(roles: readonly string[] | undefined, to: string): boolean {
  const path = to.split(/[?#]/)[0].replace(/\/+$/, '') || '/';
  if (!path.startsWith('/staff')) return true;
  if (path === '/staff/password' || path === '/staff/login') return true;
  const page = pageOf(path);
  if (page && !can(roles, page)) return false;
  const sub = SUBPAGE_ROLES.find((x) => x.re.test(path));
  return !sub || !!roles?.some((r) => sub.roles.includes(r));
}

/** Role names as people use them. */
export const ROLE_LABEL: Record<string, string> = {
  INSTITUTION_ADMIN: 'Institution Administrator', MANAGEMENT_VIEWER: 'Principal / Management', DEPUTY_PRINCIPAL_ACADEMIC: 'Deputy Principal (Academic)',
  ADMISSIONS_OFFICER: 'Admissions Officer', REGISTRAR: 'Registrar', HEAD_OF_DEPARTMENT: 'Head of Department', PROGRAMME_COORDINATOR: 'Programme Coordinator',
  LECTURER: 'Lecturer', ASSESSOR: 'Assessor (NVA)', EXAMINATIONS_OFFICER: 'Examinations Officer', BURSAR: 'Bursar', ACCOUNTANT: 'Accountant',
  HOSTEL_WARDEN: 'Hostel Warden', IPT_COORDINATOR: 'IPT Coordinator', CLEARANCE_OFFICER: 'Clearance Officer', SPONSOR_OFFICER: 'Sponsor Officer', AUDITOR: 'Auditor',
  LIBRARIAN: 'Librarian', HR_OFFICER: 'Human Resources Officer', STORES_OFFICER: 'Stores and Assets Officer',
  DEAN_OF_STUDENTS: 'Dean of Students', QUALITY_ASSURANCE_OFFICER: 'Quality Assurance Officer', PROCUREMENT_OFFICER: 'Procurement Officer',
};
