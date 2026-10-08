// Types mirroring the CIMS backend DTOs.

export interface Problem {
  title?: string;
  status: number;
  detail?: string;
  code?: string;
  errors?: Record<string, string>;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  user: { id: number; fullName: string; userType: string; roles: string[]; tenant: string };
}

export interface Institution {
  code: string;
  name: string;
  shortName?: string;
  nactvetRegNo?: string;
  contactEmail?: string;
  contactPhone?: string;
  branding: { primaryColor?: string; accentColor?: string; tagline?: string; logoUrl?: string };
}

export type ConditionType =
  | 'CSEE_MIN_PASSES'
  | 'CSEE_SUBJECT'
  | 'ACSEE_MIN_PRINCIPAL_PASSES'
  | 'ACSEE_SUBJECT'
  | 'PRIOR_AWARD';

export interface Condition {
  type: ConditionType;
  count?: number | null;
  minGrade?: string | null;
  subjects?: string[] | null;
  family?: string | null;
  minLevel?: number | null;
}

export interface EntryRules {
  summary: string;
  pathways: { label: string; conditions: Condition[] }[];
}

export interface WindowProgramme {
  id: number;
  programmeId: number;
  programmeCode: string;
  programmeName: string;
  awardFamily: 'NVA' | 'NTA';
  awardLevel: number;
  awardName: string;
  department: string;
  capacity: number;
  requirementSummary?: string;
  entryRules?: EntryRules;
}

export interface AdmissionWindow {
  id: number;
  name: string;
  academicYearId: number;
  academicYear: string;
  intakeId: number;
  intake: string;
  opensAt: string;
  closesAt: string;
  applicationFee: number;
  maxChoices: number;
  offerAcceptanceDays: number;
  joiningInstructions?: JoiningInstructions;
  status: 'DRAFT' | 'PUBLISHED' | 'CLOSED';
  open: boolean;
  instructions?: string;
  programmes?: WindowProgramme[];
}

export interface EligibilityResult {
  eligible: boolean;
  matchedPathway?: string;
  summary?: string;
  pathways: {
    label: string;
    satisfied: boolean;
    conditions: { requirement: string; satisfied: boolean; found: string }[];
  }[];
}

export interface Profile {
  id: number;
  firstName: string;
  middleName?: string;
  surname: string;
  fullName: string;
  sex?: 'M' | 'F';
  dateOfBirth?: string;
  nationality?: string;
  phone: string;
  email?: string;
  region?: string;
  district?: string;
  postalAddress?: string;
  disability?: string;
  nextOfKinName?: string;
  nextOfKinPhone?: string;
  nextOfKinRelation?: string;
  missingFields: string[];
}

export interface Qualification {
  id: number;
  type: 'CSEE' | 'ACSEE' | 'NTA' | 'NVA';
  indexNumber: string;
  examYear?: number;
  awardLevel?: number;
  centreName?: string;
  division?: string;
  points?: number;
  source: string;
  verified: boolean;
  subjects: { code: string; name: string; grade: string }[];
}

export interface Choice {
  id: number;
  order: number;
  windowProgrammeId: number;
  programmeCode: string;
  programmeName: string;
  awardName: string;
  eligible: boolean;
  eligibility: EligibilityResult;
}

export interface Application {
  id: number;
  referenceNo?: string;
  status: string;
  windowId: number;
  windowName: string;
  academicYear: string;
  intake: string;
  windowClosesAt: string;
  applicationFee: number;
  maxChoices: number;
  feePaid: boolean;
  submittedAt?: string;
  choices: Choice[];
  blockers: string[];
  selectedProgramme?: string;
  offer?: Offer;
  letter?: AdmissionLetterInfo;
  student?: { registrationNo: string; status: string };
}

export interface AdmissionLetterInfo {
  letterNo: string;
  edition: number;
  verificationCode: string;
  issuedAt: string;
}

export interface JoiningInstructions {
  reportingDate: string;
  reportingTime?: string;
  reportingVenue: string;
  registrationDeadline?: string;
  documentsToBring: string[];
  itemsToBring: string[];
  feesNote?: string;
  notes: string[];
  signatoryName: string;
  signatoryTitle: string;
}

export interface LetterVerification {
  documentType: string;
  status: 'VALID' | 'SUPERSEDED' | 'REVOKED';
  institution: string;
  institutionNactvetNo?: string;
  letterNo: string;
  edition: number;
  issuedAt: string;
  holderName: string;
  programme: string;
  award: string;
  academicYear: string;
  intake: string;
  reportingDate: string;
  nactvetVerificationNo?: string;
  revokedAt?: string;
  currentEdition?: number;
}

export interface Offer {
  verificationNo?: string;
  verifiedAt?: string;
  multipleAdmission: boolean;
  expiresAt?: string;
  open: boolean;
  respondedAt?: string;
  rejectionReason?: string;
  confirmationAttemptsLeft: number;
  devConfirmationCode?: string;
}

export interface Fee {
  invoiceId?: number;
  invoiceNo?: string;
  amount: number;
  paid: number;
  invoiceStatus?: string;
  controlNo?: string;
  controlNoExpiresAt?: string;
  feePaid: boolean;
}

export interface ProgrammeEligibility {
  windowProgrammeId: number;
  programmeCode: string;
  programmeName: string;
  awardName: string;
  department: string;
  capacity: number;
  eligible: boolean;
  eligibility: EligibilityResult;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ApplicationSummary {
  id: number;
  referenceNo?: string;
  status: string;
  applicantName: string;
  phone: string;
  windowId: number;
  windowName: string;
  feePaid: boolean;
  submittedAt?: string;
  createdAt: string;
}

export interface ApplicationDetail {
  application: Application;
  applicant: Profile;
  qualifications: Qualification[];
  fee: Fee;
}

export interface Programme {
  id: number;
  code: string;
  nactvetCode?: string;
  name: string;
  awardFamily: 'NVA' | 'NTA';
  awardLevel: number;
  awardName: string;
  durationSemesters: number;
  accreditedCapacity?: number;
  description?: string;
  active: boolean;
  departmentId: number;
  departmentName: string;
}

export interface Department {
  id: number;
  code: string;
  name: string;
  active: boolean;
}

export interface WindowStats {
  windowId: number;
  total: number;
  draft: number;
  paymentPending: number;
  submitted: number;
}

export interface SelectionRoundSummary {
  id: number;
  roundNo: number;
  status: 'PROPOSED' | 'APPROVED' | 'CANCELLED';
  proposedAt: string;
  proposedByName: string;
  approvedAt?: string;
  approvedByName?: string;
  committeeMinuteRef?: string;
  selected: number;
  notSelected: number;
  excluded: number;
}

export interface SelectionRow {
  applicationId: number;
  referenceNo?: string;
  applicantName: string;
  phone: string;
  sex?: string;
  region?: string;
  meritScore: number;
  choiceOrder?: number;
  rank?: number;
  windowProgrammeId?: number;
  programmeName?: string;
  exclusionReason?: string;
  excludedByName?: string;
}

export interface SelectionProgrammeResult {
  windowProgrammeId: number;
  programmeCode: string;
  programmeName: string;
  capacity: number;
  filledInEarlierRounds: number;
  availableThisRound: number;
  applicants: number;
  selectedCount: number;
  selectedBySex: Record<string, number>;
  selected: SelectionRow[];
}

export interface SelectionRoundDetail {
  round: SelectionRoundSummary;
  meritFormula: string;
  totals: { candidates: number; selected: number; notSelected: number; excluded: number };
  programmes: SelectionProgrammeResult[];
  notSelected: SelectionRow[];
  excluded: SelectionRow[];
  selectedBySex: Record<string, number>;
  selectedByRegion: Record<string, number>;
  canApprove: boolean;
}

// ---- NACTVET verification and offers (BP-04)
export interface VerificationReadyRow {
  applicationId: number;
  referenceNo: string;
  applicantName: string;
  programmeName?: string;
  indexNumber?: string;
  remarks?: string;
  issues: string[];
}

export interface NactvetSubmission {
  id: number;
  batchNo: number;
  channel: 'API' | 'FILE';
  status: 'SUBMITTED' | 'COMPLETED';
  externalRef?: string;
  submittedAt: string;
  submittedByName: string;
  lastOutcomeAt?: string;
  completedAt?: string;
  total: number;
  pending: number;
  verified: number;
  queried: number;
  rejected: number;
}

export interface VerificationApplicantRow {
  applicationId: number;
  referenceNo: string;
  applicantName: string;
  phone: string;
  sex?: string;
  programmeName?: string;
  status: string;
  indexNumber?: string;
  verificationNo?: string;
  multipleAdmission: boolean;
  reason?: string;
  remarks?: string;
  verifiedAt?: string;
  offerExpiresAt?: string;
  offerRespondedAt?: string;
  declineReason?: string;
  offerExpired: boolean;
  letterNo?: string;
  letterEdition?: number;
  letterIssuedAt?: string;
}

export interface VerificationOverview {
  windowId: number;
  offerAcceptanceDays: number;
  institutionNactvetNo?: string;
  counts: Record<string, number>;
  acceptanceRate?: number;
  joiningInstructionsSet: boolean;
  lettersPending: number;
  ready: VerificationReadyRow[];
  submissions: NactvetSubmission[];
  applicants: VerificationApplicantRow[];
}

export interface OutcomeResult {
  applied: number;
  verified: number;
  queried: number;
  rejected: number;
  submission: NactvetSubmission;
}

// ---- Registry (BP-05)
export interface RegistrySettings {
  registrationNoPattern: string;
  usingDefault: boolean;
  example: string;
}

export interface HandoverRow {
  applicationId: number;
  referenceNo: string;
  applicantName: string;
  programmeName?: string;
  letterNo?: string;
  acceptedAt?: string;
  studentId?: number;
  registrationNo?: string;
  studentStatus?: string;
}

export interface HandoverOverview {
  windowId: number;
  windowName: string;
  accepted: number;
  handedOver: number;
  pending: number;
  registrationNoPattern: string;
  registrationNoExample: string;
  rows: HandoverRow[];
}

export interface StudentSummary {
  id: number;
  registrationNo: string;
  fullName: string;
  sex: string;
  phone: string;
  programmeId: number;
  programmeCode: string;
  programmeName: string;
  academicYear: string;
  intake: string;
  yearOfStudy: number;
  status: string;
}

export interface FirstRegistration {
  status: 'PENDING' | 'APPROVED';
  documents: { item: string; verified: boolean }[];
  reportedAt?: string;
  remarks?: string;
  checkedByName?: string;
  approvedAt?: string;
  approvedByName?: string;
}

export interface StudentDetail {
  summary: StudentSummary;
  firstName: string;
  middleName?: string;
  surname: string;
  dateOfBirth: string;
  nationality: string;
  email?: string;
  region?: string;
  district?: string;
  postalAddress?: string;
  disability?: string;
  nextOfKinName?: string;
  nextOfKinPhone?: string;
  nextOfKinRelation?: string;
  awardName: string;
  applicationRef?: string;
  nactvetVerificationNo?: string;
  admissionLetterNo?: string;
  admittedAt: string;
  admittedByName: string;
  firstRegistration?: FirstRegistration;
}

// ---- Fees (BP-06)
export interface AcademicYear {
  id: number;
  code: string;
  startDate: string;
  endDate: string;
  current: boolean;
}

export interface FeeItem {
  id: number;
  code: string;
  name: string;
  category: string;
  gfsCode?: string;
  allocationPriority: number;
  active: boolean;
}

export interface FeeStructureLine {
  feeItemId: number;
  feeItemCode: string;
  feeItemName: string;
  category: string;
  amount: number;
  foreignAmount?: number;
  instalment: number;
}

export interface FeeStructure {
  id: number;
  academicYearId: number;
  academicYear: string;
  programmeId: number;
  programmeCode: string;
  programmeName: string;
  yearOfStudy: number;
  structureVersion: number;
  status: 'DRAFT' | 'APPROVED' | 'SUPERSEDED';
  notes?: string;
  createdByName: string;
  approvedAt?: string;
  approvedByName?: string;
  total: number;
  foreignTotal: number;
  firstInstalment: number;
  lines: FeeStructureLine[];
  canApprove: boolean;
}

export interface ClearanceGateRule {
  gate: string;
  enabled: boolean;
  minPercentPaid: number;
  firstInstalmentRequired: boolean;
  appliedNow: boolean;
}

export interface AccountInvoice {
  id: number;
  invoiceNo: string;
  purpose: string;
  description: string;
  payerType: string;
  total: number;
  paid: number;
  balance: number;
  status: string;
  dueDate?: string;
  issuedAt: string;
  controlNo?: string;
  controlNoExpiresAt?: string;
  lines: { feeItemCode: string; description: string; amount: number; paid: number; balance: number; instalment: number }[];
}

export interface AccountPayment {
  receiptNo: string;
  paidAt: string;
  amount: number;
  credit: number;
  channel?: string;
  pspName?: string;
  invoiceNo: string;
  controlNo?: string;
}

export interface Statement {
  holderName: string;
  registrationNo?: string;
  billed: number;
  paid: number;
  balance: number;
  credit: number;
  invoices: AccountInvoice[];
  payments: AccountPayment[];
}

export interface Clearance {
  gate: string;
  enabled: boolean;
  cleared: boolean;
  academicYear: string;
  invoiceTotal?: number;
  paid?: number;
  balance?: number;
  minPercentPaid?: number;
  paidPercent?: number;
  firstInstalmentRequired: boolean;
  firstInstalmentBalance?: number;
  amountNeeded?: number;
  invoiceId?: number;
  invoiceNo?: string;
  reason?: string;
}

export interface AmountRow { key: string; label: string; amount: number; count: number }

export interface CollectionsReport {
  from: string;
  to: string;
  total: number;
  credit: number;
  payments: number;
  byFeeItem: AmountRow[];
  byDay: AmountRow[];
  byChannel: AmountRow[];
  rows: { receiptNo: string; paidAt: string; payerName: string; invoiceNo: string; purpose: string; amount: number; credit: number; channel?: string; pspName?: string }[];
}

export interface DebtorsReport {
  asAt: string;
  totalBalance: number;
  count: number;
  byBucket: AmountRow[];
  rows: { invoiceId: number; invoiceNo: string; payerType: string; payerName: string; registrationNo?: string; phone?: string; description: string;
    total: number; paid: number; balance: number; dueDate?: string; daysOverdue: number; bucket: string }[];
}

export interface ReconciliationItem {
  id: number; lineNo?: number; kind: string; status: 'MATCHED' | 'OPEN' | 'RESOLVED';
  gepgReceiptNo?: string; billId?: string; controlNo?: string; pspName?: string; pspReceiptNo?: string; channel?: string; payerPhone?: string;
  gepgAmount?: number; cimsAmount?: number; paidAt?: string; paymentId?: number; paidDateDiffers: boolean;
  resolution?: string; note?: string; resolvedAt?: string; resolvedByName?: string; payerName?: string; receiptNo?: string;
}

export interface Reconciliation {
  id: number; date: string; source: string; fileName?: string; status: 'OPEN' | 'CLOSED';
  gepgCount: number; gepgTotal: number; cimsCount: number; cimsTotal: number; matchedCount: number; exceptionCount: number;
  openExceptions: number; matchRate?: number; importedAt: string; importedByName: string; closedAt?: string; closedByName?: string;
  items: ReconciliationItem[];
}

export interface ReconciliationMonth {
  month: string; collected: number; payments: number; daysWithPayments: number; daysClosed: number; monthEnded: boolean;
  canSignOff: boolean; signOffBlocker?: string; signedAt?: string; signedByName?: string; signOffNote?: string;
  days: { date: string; cimsCount: number; cimsTotal: number; reconciliationId?: number; status?: 'OPEN' | 'CLOSED';
    exceptionCount?: number; openExceptions?: number; matchedCount?: number; gepgCount?: number }[];
}

// ------------------------------------------------------------------ student portal (FR-STU-01/02, FR-REG-08)

export interface StudentNotice {
  id: number; title: string; body: string; category: string; pinned: boolean; publishAt: string; publishedByName?: string; read: boolean;
}

export interface ChangeRequest {
  id: number; studentId: number; registrationNo: string; studentName: string; programmeName: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
  changes: { field: string; label: string; from?: string; to?: string }[];
  reason: string; needsDocuments: boolean; submittedAt: string; decidedAt?: string; decidedByName?: string; decisionNote?: string;
}

export interface StudentDashboard {
  institutionName: string; registrationNo: string; fullName: string; firstName: string; programmeName: string; awardName: string;
  yearOfStudy: number; academicYear: string; status: string; firstRegistrationStatus?: string;
  billed: number; paid: number; balance: number; credit: number;
  nextPayment?: { invoiceId: number; invoiceNo: string; description: string; balance: number; dueDate?: string; controlNo?: string };
  clearance: Clearance[]; unreadNotices: number; latestNotices: StudentNotice[]; pendingRequest?: ChangeRequest;
}

export interface StudentProfile {
  registrationNo: string; firstName: string; middleName?: string; surname: string; sex: string; dateOfBirth: string; nationality: string;
  phone: string; email?: string; region?: string; district?: string; postalAddress?: string; disability?: string;
  nextOfKinName?: string; nextOfKinPhone?: string; nextOfKinRelation?: string;
  programmeName: string; programmeCode: string; awardName: string; academicYear: string; intake: string; yearOfStudy: number; status: string;
  nactvetVerificationNo?: string; admissionLetterNo?: string;
  editableFields: Record<string, string>; requests: ChangeRequest[];
}

export interface StaffNotice {
  id: number; title: string; body: string; category: string; programmeId?: number; programmeName?: string; yearOfStudy?: number;
  audience: string; pinned: boolean; status: 'DRAFT' | 'PUBLISHED' | 'WITHDRAWN'; publishAt?: string; expiresAt?: string; live: boolean;
  smsSentCount: number; audienceCount: number; readCount: number; createdByName: string; publishedByName?: string;
  withdrawnAt?: string; withdrawnByName?: string; createdAt: string;
}

// ------------------------------------------------------------------ messaging (FR-COM-01 to 03)

export interface CountRow { key: string; messages: number; parts: number }

export interface MessagingOverview {
  defaultLanguage: 'EN' | 'SW'; senderId?: string; pricePerSms: number; lowBalanceWarning: number; purchased: number; used: number;
  balance: number; lowBalance: boolean; thisMonthByStatus: CountRow[]; thisMonthByKind: CountRow[]; thisMonthCost: number;
  deliveryRate?: number; topups: { id: number; units: number; amountPaid: number; reference: string; recordedAt: string; recordedByName: string }[];
  gatewayMode: string;
}

export interface MessageTemplateView {
  code: string; name: string; when: string; group: string; variables: string[]; required: string[]; bodyEn: string; bodySw: string;
  enabled: boolean; customised: boolean; defaultEn: string; defaultSw: string; sampleEn: string; sampleSw: string; partsEn: number;
  partsSw: number; updatedByName?: string; updatedAt?: string;
}

export interface AudienceFilter {
  group: 'STUDENTS' | 'APPLICANTS' | 'PHONES'; studentStatuses?: string[]; programmeId?: number | null; yearOfStudy?: number | null;
  withFeeBalance?: boolean; windowId?: number | null; applicationStatuses?: string[]; phones?: string;
}

export interface CampaignPreview {
  audienceLabel: string; recipients: number; skippedNoPhone: number; skippedDuplicates: number; invalidPhones: string[]; placeholders: string[];
  samples: { name?: string; phone: string; text: string; parts: number; encoding: string }[]; totalParts: number; pricePerSms: number;
  estimatedCost: number; balance: number; enoughBalance: boolean; unicode: boolean;
}

export interface CampaignView {
  id: number; title: string; body: string; audienceLabel: string; recipients: number; smsParts: number; estimatedCost: number;
  sentAt: string; sentByName: string; byStatus: Record<string, number>;
}

export interface MessageView {
  id: number; phone: string; recipientName?: string; kind: string; templateCode?: string; campaignId?: number; language: string; body: string;
  parts: number; encoding: string; status: 'QUEUED' | 'SENT' | 'DELIVERED' | 'FAILED'; attempts: number; error?: string; queuedAt: string;
  sentAt?: string; deliveredAt?: string;
}

// ------------------------------------------------------------------ reports and dashboards (FR-RPT-01/02)

export type ReportColumnType = 'TEXT' | 'NUMBER' | 'MONEY' | 'PERCENT' | 'DATE' | 'DATETIME';

export interface ReportCatalogueEntry { code: string; area: string; title: string; description: string; personalData: boolean; filters: string[] }

export interface ReportResult {
  code: string; area: string; title: string; description: string; filterLines: string[];
  columns: { key: string; label: string; type: ReportColumnType }[]; rows: unknown[][]; totals?: unknown[]; notes: string[];
  rowCount: number; truncated: boolean; institution: string; generatedAt: string; generatedBy: string;
}

export interface DashPoint { key: string; label: string; value: number }
export interface DashMoney { key: string; label: string; amount: number; count: number }
export interface DashProgramme { code: string; name: string; value: number; capacity: number; accepted: number }

export interface DashboardData {
  admissions?: { windowId?: number; windowName?: string; windowStatus?: string; closesAt?: string; funnel: DashPoint[]; dailySubmissions: DashPoint[];
    programmes: DashProgramme[]; female: number; male: number };
  registry?: { current: number; registered: number; awaitingRegistration: number; female: number; male: number; byStatus: DashPoint[];
    byProgramme: DashProgramme[]; pendingChangeRequests: number };
  finance?: { academicYearId?: number; academicYear?: string; billed: number; collected: number; outstanding: number; collectionRate?: number;
    collectedThisMonth: number; collectedToday: number; dailyCollections: DashMoney[]; ageing: DashMoney[]; daysNotReconciled: number; openExceptions: number };
}

// ------------------------------------------------------------------ curricula, calendar and semester registration (FR-ACA-02/03/04, FR-REG-03)

export type SemesterWindow = 'NOT_OPEN' | 'OPEN' | 'LATE' | 'CLOSED';

export interface Semester {
  id: number;
  academicYearId: number;
  academicYear: string;
  semesterNo: number;
  name: string;
  startDate: string;
  endDate: string;
  registrationOpens: string;
  registrationCloses: string;
  lateRegistrationCloses?: string;
  window: SemesterWindow;
}

export const CALENDAR_EVENT_KINDS = ['REGISTRATION', 'TEACHING', 'MARKS_ENTRY', 'EXAMINATIONS', 'RESULTS', 'HOLIDAY', 'GRADUATION', 'OTHER'] as const;

export interface CalendarEvent {
  id: number;
  academicYearId: number;
  semesterId?: number;
  semesterName?: string;
  kind: (typeof CALENDAR_EVENT_KINDS)[number];
  title: string;
  startDate: string;
  endDate: string;
}

export interface AcademicCalendar {
  academicYearId: number;
  academicYear: string;
  semesters: Semester[];
  events: CalendarEvent[];
}

export interface CourseModule {
  id: number;
  code: string;
  title: string;
  credits: number;
  ntaLevel?: number;
  assessmentType: 'GRADED' | 'COMPETENCE';
  departmentId?: number;
  departmentName?: string;
  description?: string;
  active: boolean;
  usedInCurricula: number;
}

export interface CurriculumModuleSlot {
  id?: number;
  moduleId: number;
  code: string;
  title: string;
  credits: number;
  ntaLevel?: number;
  assessmentType: string;
  yearOfStudy: number;
  semesterNo: number;
  category: 'CORE' | 'ELECTIVE';
}

export interface ElectiveRule {
  yearOfStudy: number;
  semesterNo: number;
  minElectives: number;
  maxElectives: number;
}

export interface CurriculumSlot {
  yearOfStudy: number;
  semesterNo: number;
  coreCredits: number;
  core: number;
  electives: number;
  minElectives?: number;
  maxElectives?: number;
  problems: string[];
}

export interface Curriculum {
  id: number;
  programmeId: number;
  programmeCode: string;
  programmeName: string;
  programmeSemesters: number;
  curriculumVersion: number;
  effectiveYearId: number;
  effectiveYear: string;
  status: 'DRAFT' | 'APPROVED' | 'RETIRED';
  notes?: string;
  createdByName?: string;
  approvedAt?: string;
  approvedByName?: string;
  retiredAt?: string;
  canApprove: boolean;
  studentsOnIt: number;
  modules: CurriculumModuleSlot[];
  electiveRules: ElectiveRule[];
  slots: CurriculumSlot[];
  problems: string[];
}

export interface CurriculumSummary {
  id: number;
  programmeId: number;
  programmeCode: string;
  programmeName: string;
  curriculumVersion: number;
  effectiveYear: string;
  status: 'DRAFT' | 'APPROVED' | 'RETIRED';
  modules: number;
  totalCredits: number;
  approvedAt?: string;
}

export interface SemesterInfo {
  id: number;
  name: string;
  academicYear: string;
  semesterNo: number;
  startDate: string;
  endDate: string;
  registrationOpens: string;
  registrationCloses: string;
  lateRegistrationCloses?: string;
  window: SemesterWindow;
}

export interface ModuleOption {
  moduleId: number;
  code: string;
  title: string;
  credits: number;
  category: 'CORE' | 'ELECTIVE';
  assessmentType: string;
  selected: boolean;
}

export interface RegisteredModule {
  moduleId: number;
  code: string;
  title: string;
  credits: number;
  category: 'CORE' | 'ELECTIVE';
  attempt: string;
}

export interface SemesterRegistration {
  id: number;
  studentId: number;
  registrationNo: string;
  studentName: string;
  programmeCode: string;
  programmeName: string;
  semesterId: number;
  semesterName: string;
  yearOfStudy: number;
  status: 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
  late: boolean;
  totalCredits: number;
  clearance?: Record<string, unknown>;
  detailsConfirmed: boolean;
  submittedAt: string;
  submittedByName?: string;
  decidedAt?: string;
  decidedByName?: string;
  decisionNote?: string;
  curriculum?: string;
  modules: RegisteredModule[];
}

export type StudentRegistrationState = 'NO_SEMESTER' | 'NOT_ACTIVE' | 'NO_CURRICULUM' | 'NOT_OPEN' | 'OPEN' | 'LATE' | 'CLOSED' | 'NOTHING_TO_REGISTER';

export interface StudentSemesterRegistration {
  semester?: SemesterInfo;
  state: StudentRegistrationState;
  message?: string;
  yearOfStudy: number;
  curriculum?: string;
  core: ModuleOption[];
  electives: ModuleOption[];
  minElectives: number;
  maxElectives: number;
  clearance?: Clearance;
  registration?: SemesterRegistration;
  history: SemesterRegistration[];
}

export interface SemesterRegistrationSummary {
  semesterId: number;
  semesterName: string;
  window: SemesterWindow;
  currentStudents: number;
  notRegistered: number;
  byStatus: Record<string, number>;
}
