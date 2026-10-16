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
  user: { id: number; fullName: string; userType: string; roles: string[]; tenant: string; mustChangePassword?: boolean; mfaSetupRequired?: boolean; language?: 'en' | 'sw' | null };
  /** Present instead of a token when the staff member signs in with an authenticator app: send the code with this challenge. */
  mfa?: { challenge: string; smsAvailable: boolean };
}

export interface Institution {
  code: string;
  name: string;
  shortName?: string;
  nactvetRegNo?: string;
  contactEmail?: string;
  contactPhone?: string;
  branding: { primaryColor?: string; accentColor?: string; tagline?: string; logoUrl?: string };
  /** The institution's own campus photo, when the administrator has uploaded one. */
  campusPhoto?: InstitutionPhoto;
  /** How fees are paid: GEPG (public institutions) or COLLECTION (private and faith-based: the college's own accounts). */
  payment?: { mode: 'GEPG' | 'COLLECTION'; channels: PaymentChannel[] };
  /** Minutes without activity before each kind of user is signed out. */
  idleMinutes?: { staff: number; student: number; applicant: number };
}

/** One account a private or faith-based institution is paid into. */
export interface PaymentChannel {
  id: number; provider: string; label: string; operator?: string; kind: 'MOBILE_MONEY' | 'BANK'; ussd?: string;
  accountName: string; accountNumber: string; branch?: string; instructions?: string; active: boolean; sortOrder: number;
}

export interface InstitutionPhoto {
  kind: string;
  url: string;
  caption?: string;
  contentType: string;
  sizeBytes: number;
  width?: number;
  height?: number;
  uploadedByName: string;
  updatedAt?: string;
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
  /** What applicants see: hidden draft, published but not yet open, open, or closed. */
  state: WindowState;
  open: boolean;
  instructions?: string;
  programmes?: WindowProgramme[];
  /** Applicants who asked for a control number and have not paid are reminded by SMS (BP-02). */
  feeReminders: boolean;
}

export type WindowState = 'DRAFT' | 'SCHEDULED' | 'OPEN' | 'CLOSED';

export interface WindowEvent {
  id: number; action: 'CREATED' | 'PUBLISHED' | 'OPENED' | 'CLOSED' | 'REOPENED' | 'EXTENDED' | 'RESCHEDULED'; reason?: string;
  opensAt?: string; closesAt?: string; previousClosesAt?: string; applicantsNotified: number; automatic: boolean; byName: string; at: string;
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
  /** The Form Four index number and year the applicant registered with; names then come from NECTA. */
  nectaIndexNumber?: string;
  nectaExamYear?: number;
  namesFromNecta: boolean;
  ward?: string;
  street?: string;
  nextOfKinEmail?: string;
  nextOfKinAddress?: string;
  nextOfKinRegion?: string;
  nextOfKinDistrict?: string;
  nextOfKinWard?: string;
  nextOfKinStreet?: string;
  primarySchool?: string;
  primarySchoolCode?: string;
  olevelSchool?: string;
  olevelFromNecta?: boolean;
  alevelSchool?: string;
  alevelFromNecta?: boolean;
}

export interface Qualification {
  id: number;
  type: 'CSEE' | 'ACSEE' | 'NTA' | 'NVA' | 'OTHER';
  indexNumber: string;
  examYear?: number;
  awardLevel?: number;
  centreName?: string;
  division?: string;
  points?: number;
  source: string;
  verified: boolean;
  subjects: { code: string; name: string; grade: string }[];
  candidateName?: string;
  /** The Form Four results the applicant registered with; they cannot be removed. */
  registration: boolean;
  awardName?: string;
  institution?: string;
  gpa?: number;
  classAward?: string;
  otherLevel?: string;
  /** The Admissions Office's check of a declared qualification (NECTA / NACTVET results are VERIFIED at source). */
  checkStatus: 'PENDING' | 'VERIFIED' | 'REJECTED';
  checkNote?: string;
  checkedByName?: string;
  checkedAt?: string;
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
  /** Submitted but still open to changes: the window is open and selection has not started. */
  amendable: boolean;
  amendedAt?: string;
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
  status: 'VALID' | 'SUPERSEDED' | 'REVOKED' | 'EXPIRED';
  validUntil?: string;
  studentStatus?: string;
  institution: string;
  institutionNactvetNo?: string;
  letterNo: string;
  edition: number;
  issuedAt: string;
  holderName: string;
  programme: string;
  award: string;
  academicYear?: string;
  intake?: string;
  reportingDate?: string;
  nactvetVerificationNo?: string;
  revokedAt?: string;
  currentEdition?: number;
  /** statements of results (documentType STATEMENT_OF_RESULTS) */
  registrationNo?: string;
  cgpa?: number;
  classification?: string;
  creditsEarned?: number;
  lastSemester?: string;
  /** award certificates (documentType AWARD_CERTIFICATE) */
  graduation?: string;
  ceremonyDate?: string;
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
  awardFamily: 'NTA' | 'NVA';
  /** The level the programme admits at. */
  entryLevel: number;
  /** At the level the applicant's highest qualification leads to (as in NOAS). */
  atEntryLevel: boolean;
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
  history: { at: string; action: string; label: string; by?: string; details?: Record<string, unknown> }[];
}

export interface ApplicantRow {
  id: number; referenceNo?: string; status: string; applicantId: number; applicantName: string; sex?: string; phone?: string;
  indexNumber?: string; region?: string; windowId: number; windowName: string; feePaid: boolean; firstChoice?: string; choices: number;
  checksPending: number; submittedAt?: string; createdAt: string; amendedAt?: string;
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
  /** The level the programme admits at (as set, or worked out from the award level and duration). */
  entryLevel: number;
  entryLevelSet: boolean;
}

export interface Department {
  id: number;
  code: string;
  name: string;
  facultyId?: number;
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
  campusId?: number; campus?: string; studentCategoryId?: number; studentCategory?: string;
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
  intakeId?: number; intakeName?: string; studentCategoryId?: number; studentCategoryName?: string;
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
  sponsorPending?: number;
  sponsors?: { sponsor: string; academicYear: string; coveragePercent: number; capAmount?: number; reference?: string }[];
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
  next: { kind: string; severity: 'success' | 'info' | 'warning' | 'error'; values: Record<string, string>; link?: string }[];
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

export type ReportColumnType = 'TEXT' | 'NUMBER' | 'DECIMAL' | 'MONEY' | 'PERCENT' | 'DATE' | 'DATETIME';

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
  marksEntryOpens?: string;
  marksEntryCloses?: string;
  window: SemesterWindow;
  /** Charged when a student registers in the late period (BP-05); 0 for none. */
  lateRegistrationFee: number;
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
  lateRegistrationFee: number;
}

/** The late-registration fee billed for a registration made in the late period. */
export interface LateFee {
  invoiceId: number;
  invoiceNo: string;
  amount: number;
  balance: number;
  status: string;
  controlNo?: string;
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
  lateFee?: LateFee;
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

// ------------------------------------------------------------------ examinations and results (FR-ACA-05, FR-EXM-01..07/10)

export interface GradeBand {
  grade: string;
  minMark: number;
  maxMark: number;
  points: number;
  remark?: string;
  passing: boolean;
}

export interface ClassificationBand {
  name: string;
  minGpa: number;
  maxGpa: number;
}

export interface Regulation {
  id: number;
  name: string;
  awardFamily: 'NTA' | 'NVA';
  levelFrom: number;
  levelTo: number;
  effectiveYearId: number;
  effectiveYear: string;
  caWeight: number;
  seWeight: number;
  caPassPercent: number;
  sePassPercent: number;
  caPassMark: number;
  sePassMark: number;
  gpaDecimals: number;
  gpaRounding: 'ROUND' | 'TRUNCATE';
  suppMaxFailedPercent: number;
  suppMaxGrade?: string;
  discontinueGpaBelow?: number;
  notes?: string;
  createdByName?: string;
  inUse: boolean;
  gradeBands: GradeBand[];
  classifications: ClassificationBand[];
}

export interface Person {
  id: number;
  fullName: string;
}

export interface StaffOption extends Person {
  roles: string[];
}

export type OfferingStatus = 'DRAFT' | 'SUBMITTED' | 'HOD_APPROVED' | 'BOARD_APPROVED';

export interface OfferingSummary {
  id: number;
  semesterId: number;
  semesterName: string;
  moduleId: number;
  code: string;
  title: string;
  credits: number;
  departmentName?: string;
  status: OfferingStatus;
  students: number;
  complete: number;
  lecturers: Person[];
  regulation: string;
  returnedNote?: string;
  mine: boolean;
}

export interface AssessmentComponent {
  id?: number;
  name: string;
  category: 'CA' | 'SE';
  weight: number;
  maxScore: number;
  /** This item's own marks entry window, when the Examinations Officer set one. */
  entryOpens?: string;
  entryCloses?: string;
  entryNote?: string;
}

export interface ApprovalStage { order: number; name: string; role: string }
export interface StageApproval { order: number; name: string; byName: string; at: string; note?: string }

export interface ScoreCell {
  score?: number;
  absent: boolean;
}

export interface MarksRow {
  moduleRegistrationId: number;
  studentId: number;
  registrationNo: string;
  name: string;
  programmeCode: string;
  category: string;
  scores: Record<string, ScoreCell>;
  caTotal?: number;
  seTotal?: number;
  total?: number;
  grade?: string;
  points?: number;
  remark: 'PASS' | 'FAIL' | 'INCOMPLETE' | 'ABSENT';
  failReason?: string;
  /** From the latest eligibility list; absent when no list has been generated. */
  examEligible?: boolean | null;
}

export interface MarksStats {
  students: number;
  graded: number;
  passed: number;
  passRate?: number;
  mean?: number;
  distribution: Record<string, number>;
  flags: string[];
}

export interface Offering {
  id: number;
  semesterId: number;
  semesterName: string;
  academicYear: string;
  moduleId: number;
  code: string;
  title: string;
  credits: number;
  departmentName?: string;
  status: OfferingStatus;
  lecturers: Person[];
  regulationId: number;
  regulation: string;
  caWeight: number;
  seWeight: number;
  caPassMark: number;
  sePassMark: number;
  gradeBands: GradeBand[];
  marksEntryOpens?: string;
  marksEntryCloses?: string;
  marksWindowNote: string;
  useDepartmentBoard: boolean;
  components: AssessmentComponent[];
  rows: MarksRow[];
  stats: MarksStats;
  submittedByName?: string;
  submittedAt?: string;
  hodByName?: string;
  hodAt?: string;
  boardByName?: string;
  boardAt?: string;
  returnedNote?: string;
  returnedByName?: string;
  returnedAt?: string;
  can: {
    editPlan: boolean; enterCa: boolean; enterSe: boolean; submit: boolean; hodApprove: boolean; boardApprove: boolean;
    returnToLecturer: boolean; assign: boolean;
  };
  stages: ApprovalStage[];
  waitingAtStage?: string;
  stageApprovals: StageApproval[];
}

export interface UploadResult {
  updated: number;
  errors: { row: number; message: string }[];
}

export type Decision = 'PASS' | 'SUPPLEMENTARY' | 'SPECIAL_EXAM' | 'INCOMPLETE' | 'REPEAT' | 'DISCONTINUE' | 'CARRY_OVER';
export const DECISIONS: Decision[] = ['PASS', 'SUPPLEMENTARY', 'SPECIAL_EXAM', 'INCOMPLETE', 'CARRY_OVER', 'REPEAT', 'DISCONTINUE'];
export type SheetRound = 'MAIN' | 'RESIT';
export type SheetStatus = 'NOT_COMPUTED' | 'COMPUTED' | 'RECOMMENDED' | 'CONFIRMED' | 'PUBLISHED';

export interface SheetSummary {
  id?: number;
  semesterId: number;
  programmeId: number;
  programmeCode: string;
  programmeName: string;
  yearOfStudy: number;
  round: SheetRound;
  students: number;
  status: SheetStatus;
  modulesPending: string[];
  computedAt?: string;
  publishedAt?: string;
  decisions: Record<string, number>;
}

export interface SheetRow {
  studentId: number;
  registrationNo: string;
  name: string;
  sex?: string;
  cells: Record<string, { grade?: string; total?: number; remark: string; attempt?: 'SUPPLEMENTARY' | 'SPECIAL' }>;
  creditsRegistered: number;
  creditsPassed: number;
  gpa?: number;
  cgpa?: number;
  modulesFailed: number;
  proposedDecision: Decision;
  decision: Decision;
  decisionReason?: string;
  classification?: string;
}

export interface ResultSheet {
  id: number;
  semesterId: number;
  semesterName: string;
  academicYear: string;
  programmeId: number;
  programmeCode: string;
  programmeName: string;
  yearOfStudy: number;
  round: SheetRound;
  status: Exclude<SheetStatus, 'NOT_COMPUTED'>;
  regulation: string;
  modules: { moduleId: number; code: string; title: string; credits: number }[];
  rows: SheetRow[];
  decisions: Record<string, number>;
  computedByName?: string;
  computedAt?: string;
  recommendedByName?: string;
  recommendedAt?: string;
  committeeNote?: string;
  confirmedByName?: string;
  confirmedAt?: string;
  publishedByName?: string;
  publishedAt?: string;
  returnedNote?: string;
  returnedByName?: string;
  returnedAt?: string;
  nactvetSubmissions: NactvetResultSubmission[];
  can: { compute: boolean; decide: boolean; recommend: boolean; confirm: boolean; publish: boolean; returnSheet: boolean; approveStage: boolean };
  stages: ApprovalStage[];
  waitingAtStage?: string;
  stageApprovals: StageApproval[];
}

export interface StudentSemesterResult {
  id: number;
  semesterName: string;
  academicYear: string;
  yearOfStudy: number;
  round: SheetRound;
  publishedAt: string;
  withheld: boolean;
  withheldReason?: string;
  modules: { code: string; title: string; credits: number; grade?: string; points?: number; remark: string; attempt?: 'SUPPLEMENTARY' | 'SPECIAL' }[];
  creditsRegistered: number;
  creditsPassed?: number;
  gpa?: number;
  cgpa?: number;
  decision?: Decision;
}

// ------------------------------------------------------------------ resits, amendments, transcripts, NACTVET (FR-EXM-08/11/13)

export interface NactvetResultSubmission {
  id: number;
  status: 'SUBMITTED' | 'ACCEPTED' | 'QUERIED';
  reference?: string;
  note?: string;
  students: number;
  submittedByName: string;
  submittedAt: string;
  responseByName?: string;
  responseAt?: string;
  /** FILE: uploaded on the NACTVET portal; API: sent to NACTVET's results service. */
  channel: 'FILE' | 'API';
  externalRef?: string;
  queries: { registrationNo: string; reason: string }[];
}

export interface ResitSession {
  id: number;
  semesterId: number;
  semesterName: string;
  academicYear: string;
  supplementaryFee: number;
  specialFee: number;
  registrationCloses: string;
  examPeriod?: string;
  registrationOpen: boolean;
  eligibleStudents: number;
  eligibleModules: number;
  registered: number;
  modules: { offeringId: number; code: string; title: string; supplementary: number; special: number; awaitingPayment: number; resitStatus: string }[];
}

export interface EligibleResit {
  moduleResultId: number;
  code: string;
  title: string;
  credits: number;
  kind: 'SUPPLEMENTARY' | 'SPECIAL';
  firstGrade?: string;
  firstRemark: string;
  fee: number;
  resitId?: number;
  resitStatus?: string;
  paid: boolean;
  grade?: string;
  remark?: string;
}

export interface StudentResits {
  sessionId?: number;
  semesterName?: string;
  academicYear?: string;
  registrationCloses?: string;
  examPeriod?: string;
  registrationOpen: boolean;
  modules: EligibleResit[];
  invoiceId?: number;
  invoiceNo?: string;
  controlNo?: string;
  amountDue?: number;
}

export interface ResitRow {
  resitId: number;
  studentId: number;
  registrationNo: string;
  name: string;
  programmeCode: string;
  kind: 'SUPPLEMENTARY' | 'SPECIAL';
  paid: boolean;
  caTotal?: number;
  firstGrade?: string;
  score?: number;
  absent: boolean;
  seTotal?: number;
  total?: number;
  grade?: string;
  capped: boolean;
  remark?: string;
  failReason?: string;
}

export interface ResitMarks {
  offeringId: number;
  code: string;
  title: string;
  status: 'DRAFT' | 'SUBMITTED' | 'APPROVED';
  submittedByName?: string;
  submittedAt?: string;
  approvedByName?: string;
  approvedAt?: string;
  returnedNote?: string;
  seWeight: number;
  suppMaxGrade?: string;
  rows: ResitRow[];
  canEnter: boolean;
  canSubmit: boolean;
  canApprove: boolean;
  canReturn: boolean;
}

export interface Amendment {
  id: number;
  moduleResultId: number;
  studentId: number;
  registrationNo: string;
  studentName: string;
  moduleCode: string;
  moduleTitle: string;
  semester: string;
  oldCaTotal?: number;
  oldSeTotal?: number;
  oldTotal?: number;
  oldGrade?: string;
  oldRemark?: string;
  newCaTotal: number;
  newSeTotal: number;
  newTotal?: number;
  newGrade?: string;
  newRemark?: string;
  reason: string;
  evidenceRef?: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  requestedByName: string;
  requestedAt: string;
  decidedByName?: string;
  decidedAt?: string;
  decisionNote?: string;
  canDecide: boolean;
}

export interface StudentModuleRecord {
  moduleResultId: number;
  semester: string;
  code: string;
  title: string;
  caTotal?: number;
  seTotal?: number;
  total?: number;
  grade?: string;
  remark: string;
  locked: boolean;
  pendingAmendment: boolean;
  resitKind?: 'SUPPLEMENTARY' | 'SPECIAL';
  resitGrade?: string;
  resitRemark?: string;
}

export interface AcademicDocumentView {
  id: number;
  kind: string;
  documentNo: string;
  verificationCode: string;
  status: 'VALID' | 'SUPERSEDED' | 'REVOKED';
  issuedAt: string;
  issuedByName: string;
  cgpa?: number;
  semesters: number;
}

// ---------------------------------------------------------------- teaching, attendance and examination eligibility

export type AttendanceStatus = 'PRESENT' | 'LATE' | 'ABSENT' | 'EXCUSED';
export type SessionKind = 'LECTURE' | 'TUTORIAL' | 'PRACTICAL' | 'SEMINAR';

export interface ExamSettings {
  useDepartmentBoard: boolean;
  minAttendancePercent: number;
  eligibilityRequiresCa: boolean;
  eligibilityRequiresClearance: boolean;
  attendanceAlertAfterSessions: number;
  appealWindowDays: number;
  nvaMaxAttempts: number;
  appealFee: number;
}

export interface RegisterLine {
  moduleRegistrationId: number;
  studentId: number;
  registrationNo: string;
  name: string;
  programmeCode: string;
  status?: AttendanceStatus | null;
  note?: string | null;
  source?: 'REGISTER' | 'SELF';
  checkedInAt?: string;
  sharedDevice?: boolean;
}

export interface SessionRequest {
  clientRef: string;
  sessionDate: string;
  startTime: string;
  durationMinutes: number;
  kind: SessionKind;
  topic?: string;
  marks: { moduleRegistrationId: number; status: AttendanceStatus; note?: string }[];
}

export interface SessionSummary {
  id: number;
  sessionDate: string;
  startTime: string;
  durationMinutes: number;
  kind: SessionKind;
  topic?: string;
  present: number;
  late: number;
  absent: number;
  excused: number;
  recordedByName: string;
  recordedAt: string;
  selfCheckIns: number;
  checkInOpen: boolean;
}

/** Self check-in of a session (FR-TCH-03). */
export interface CheckInState {
  open: boolean; openedAt: string; closesAt: string; lateAfterMinutes: number; closedAt?: string; checkedIn: number; classSize: number;
  sharedDevices: number;
}

export interface CheckInScreen {
  sessionId: number; moduleCode: string; moduleTitle: string; code?: string; secondsLeft: number; stepSeconds: number; checkInUrl?: string;
  qrPng?: string; state: CheckInState; recent: RegisterLine[];
}

export interface CheckInDone { sessionId: number; moduleCode: string; moduleTitle: string; status: AttendanceStatus; checkedInAt: string; already: boolean }

/** The lecturer's landing page: their modules this semester and what to do next. */
export interface MyModule {
  offeringId: number; code: string; title: string; credits: number; departmentName?: string; coLecturers: string[]; programmes: string[];
  students: number; sessions: number; minutesTaught: number; lastSession?: string; averagePercent?: number; below: number; status: OfferingStatus;
  returnedNote?: string; returnedByName?: string; planSet: boolean; caPercent?: number; sePercent?: number; complete: number;
  eligibilityGenerated: boolean; notEligible: number; resitStatus?: string; resitsToMark: number; resitReturnedNote?: string;
  openCheckInSessionId?: number; canRecord: boolean;
}

export interface MyTeaching {
  semesters: { id: number; name: string; academicYear: string; startDate?: string; endDate?: string }[];
  semester?: { id: number; name: string; academicYear: string; startDate?: string; endDate?: string };
  marksEntryOpens?: string; marksEntryCloses?: string; marksWindow: 'NOT_SET' | 'UPCOMING' | 'OPEN' | 'CLOSED'; daysToClose?: number;
  threshold: number; students: number; sessions: number; hoursTaught: number; modules: MyModule[];
  competence: { id: number; code: string; title: string; status: 'DRAFT' | 'SUBMITTED' | 'VERIFIED'; students: number; tasks: number; competent: number;
    notYetCompetent: number; incomplete: number; returnedNote?: string }[];
  atRisk: { offeringId: number; moduleCode: string; studentId: number; registrationNo: string; name: string; percent?: number; attended: number; counted: number; warned: boolean }[];
  remarks: { id: number; moduleCode: string; moduleTitle: string; kind: 'REMARK' | 'APPEAL'; assignedAt: string }[];
  next: { severity: 'success' | 'info' | 'warning' | 'error'; text: string; link?: string }[];
}

export interface ClassSessionView {
  id: number;
  offeringId: number;
  sessionDate: string;
  startTime: string;
  durationMinutes: number;
  kind: SessionKind;
  topic?: string;
  recordedByName: string;
  recordedAt: string;
  editable: boolean;
  checkIn?: CheckInState;
  register: RegisterLine[];
}

export interface StudentAttendance {
  moduleRegistrationId: number;
  studentId: number;
  registrationNo: string;
  name: string;
  programmeCode: string;
  counted: number;
  attended: number;
  absent: number;
  excused: number;
  percent?: number | null;
  below: boolean;
  alertedAt?: string | null;
  eligible?: boolean | null;
  exempted: boolean;
}

export interface AttendanceView {
  offeringId: number;
  semesterId: number;
  semesterName: string;
  code: string;
  title: string;
  departmentName?: string;
  status: OfferingStatus;
  lecturers: string[];
  threshold: number;
  sessions: number;
  minutesTaught: number;
  averagePercent?: number | null;
  below: number;
  canRecord: boolean;
  sessionList: SessionSummary[];
  students: StudentAttendance[];
}

export interface OfferingAttendance {
  offeringId: number;
  code: string;
  title: string;
  departmentName?: string;
  lecturers: string[];
  students: number;
  sessions: number;
  minutesTaught: number;
  averagePercent?: number | null;
  below: number;
  mine: boolean;
  lastSession?: string | null;
}

export interface WorkloadModule {
  offeringId: number;
  code: string;
  title: string;
  credits: number;
  students: number;
  sessions: number;
  minutesTaught: number;
  sharedWith: number;
}

export interface Workload {
  userId: number;
  fullName: string;
  roles: string[];
  modules: number;
  credits: number;
  students: number;
  sessions: number;
  hoursTaught: number;
  offerings: WorkloadModule[];
}

export interface WorkloadView {
  semesterId: number;
  staff: Workload[];
  unallocated: WorkloadModule[];
}

export interface EligibilityRules {
  minAttendancePercent: number;
  requiresCa: boolean;
  requiresClearance: boolean;
}

export interface GenerateEligibilityResult {
  offerings: number;
  students: number;
  eligible: number;
  notEligible: number;
  skipped: number;
  notes: string[];
}

export interface OfferingEligibility {
  offeringId: number;
  code: string;
  title: string;
  departmentName?: string;
  status: OfferingStatus;
  students: number;
  eligible: number;
  notEligible: number;
  exempted: number;
  pendingExemptions: number;
  generatedAt?: string | null;
  generatedByName?: string | null;
}

export interface EligibilityOverview {
  semesterId: number;
  rules: EligibilityRules;
  canGenerate: boolean;
  canDecide: boolean;
  offerings: OfferingEligibility[];
}

export interface ExemptionView {
  id: number;
  offeringId: number;
  moduleCode: string;
  moduleRegistrationId: number;
  studentId: number;
  registrationNo?: string;
  studentName?: string;
  reason: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  requestedByName: string;
  requestedAt: string;
  decidedByName?: string;
  decidedAt?: string;
  decisionNote?: string;
  reasonsNotMet: string[];
  canDecide: boolean;
}

export interface EligibilityLine {
  moduleRegistrationId: number;
  studentId: number;
  registrationNo: string;
  name: string;
  programmeCode: string;
  attendancePercent?: number | null;
  sessionsCounted: number;
  sessionsAttended: number;
  attendanceMet: boolean;
  caMet: boolean;
  clearanceMet: boolean;
  rulesMet: boolean;
  exempted: boolean;
  eligible: boolean;
  reasons: string[];
  exemption?: ExemptionView | null;
}

export interface OfferingEligibilityView {
  offeringId: number;
  semesterId: number;
  semesterName: string;
  code: string;
  title: string;
  departmentName?: string;
  status: OfferingStatus;
  rules: EligibilityRules;
  generatedAt?: string | null;
  generatedByName?: string | null;
  canRequest: boolean;
  canDecide: boolean;
  students: EligibilityLine[];
}

export interface MyModuleAttendance {
  offeringId?: number | null;
  code: string;
  title: string;
  counted: number;
  attended: number;
  absent: number;
  excused: number;
  percent?: number | null;
  below: boolean;
  eligible?: boolean | null;
  exempted: boolean;
  reasons: string[];
}

export interface MyAttendance {
  semesterId?: number | null;
  semesterName?: string | null;
  threshold: number;
  listPublished: boolean;
  modules: MyModuleAttendance[];
}

// ---------------------------------------------------------------- clearance and graduation

export type ClearanceItemStatus = 'PENDING' | 'CLEARED' | 'HELD';
export type OfficeKind = 'DEPARTMENT' | 'FINANCE' | 'STAFF';

export interface ClearanceOffice {
  id: number;
  code: string;
  name: string;
  kind: OfficeKind;
  instructions?: string;
  sortOrder: number;
  active: boolean;
  staff: { id: number; fullName: string }[];
  pending: number;
  held: number;
  canDecide: boolean;
}

export interface AwardOutcome {
  finalist: boolean;
  qualified: boolean;
  reason?: string;
  awardName?: string;
  classification?: string;
  cgpa?: number;
  creditsEarned?: number;
  completedSemester?: string;
  semestersCompleted: number;
  semestersRequired: number;
}

export interface ClearanceItem {
  id: number;
  officeId: number;
  officeCode: string;
  officeName: string;
  kind: OfficeKind;
  instructions?: string;
  status: ClearanceItemStatus;
  note?: string;
  decidedByName?: string;
  decidedAt?: string;
  canDecide: boolean;
}

export interface ClearanceDetail {
  id: number;
  studentId: number;
  registrationNo: string;
  studentName: string;
  programmeCode: string;
  programmeName: string;
  status: 'IN_PROGRESS' | 'CLEARED';
  startedByName: string;
  startedAt: string;
  clearedAt?: string;
  cleared: number;
  total: number;
  items: ClearanceItem[];
}

export interface ClearanceSummary {
  id: number;
  studentId: number;
  registrationNo: string;
  studentName: string;
  programmeCode: string;
  status: 'IN_PROGRESS' | 'CLEARED';
  startedAt: string;
  clearedAt?: string;
  cleared: number;
  held: number;
  total: number;
}

export interface QueueLine {
  itemId: number;
  clearanceId: number;
  studentId: number;
  registrationNo: string;
  studentName: string;
  programmeCode: string;
  departmentName?: string;
  status: ClearanceItemStatus;
  note?: string;
  decidedByName?: string;
  decidedAt?: string;
  startedAt: string;
  canDecide: boolean;
}

export interface Finalist {
  studentId: number;
  registrationNo: string;
  studentName: string;
  programmeCode: string;
  award: AwardOutcome;
  clearanceStatus?: 'IN_PROGRESS' | 'CLEARED' | null;
  clearanceId?: number | null;
  graduation?: string | null;
}

export interface MyClearance {
  award: AwardOutcome;
  clearance?: ClearanceDetail | null;
  graduation?: { name: string; ceremonyDate?: string; status: GraduationStatus; awardName: string; classification?: string } | null;
}

export type GraduationStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED';

export interface GraduationSummary {
  id: number;
  name: string;
  ceremonyDate?: string;
  status: GraduationStatus;
  graduands: number;
  certificates: number;
  nactvetStatus?: string;
  createdAt: string;
}

export interface Graduand {
  id: number;
  studentId: number;
  registrationNo: string;
  studentName: string;
  sex?: string;
  programmeCode: string;
  programmeName: string;
  awardName: string;
  classification?: string;
  cgpa?: number;
  creditsEarned?: number;
  completedSemester: string;
  certificateNo?: string;
  verificationCode?: string;
  certificateIssuedAt?: string;
  printCount: number;
  lastPrintedAt?: string;
}

export interface NotReady {
  studentId: number;
  registrationNo: string;
  studentName: string;
  programmeCode: string;
  reason: string;
}

export interface GraduationDetail {
  id: number;
  name: string;
  ceremonyDate?: string;
  status: GraduationStatus;
  createdByName: string;
  generatedByName?: string;
  generatedAt?: string;
  submittedByName?: string;
  submittedAt?: string;
  approvedByName?: string;
  approvedAt?: string;
  boardMinuteRef?: string;
  returnedNote?: string;
  returnedByName?: string;
  nactvetStatus?: 'SUBMITTED' | 'ACCEPTED' | 'QUERIED';
  nactvetReference?: string;
  nactvetNote?: string;
  nactvetSubmittedAt?: string;
  graduands: Graduand[];
  can: { edit: boolean; generate: boolean; submit: boolean; approve: boolean; issue: boolean; nactvet: boolean };
}

export interface GraduandGenerateResult {
  added: number;
  removed: number;
  total: number;
  notReady: NotReady[];
}

// ---------------------------------------------------------------- hostels

export type RoomType = 'SINGLE' | 'DOUBLE' | 'TRIPLE' | 'QUAD' | 'DORMITORY';

export interface HostelFeeCategory { id: number; code: string; name: string; amount: number; active: boolean }

export interface HostelSummary {
  id: number; code: string; name: string; gender: 'FEMALE' | 'MALE' | 'MIXED'; location?: string; active: boolean;
  rooms: number; beds: number; available: number; occupied: number; allocatedUnpaid: number; outOfService: number;
}

export interface HostelBedView {
  id: number; label: string; status: 'AVAILABLE' | 'MAINTENANCE'; allocationId?: number; occupantRegNo?: string; occupantName?: string;
  allocationStatus?: 'ALLOCATED' | 'CHECKED_IN';
}

export interface HostelRoomView {
  id: number; hostelId: number; hostelName: string; block: string; floor: number; roomNo: string; roomType: RoomType; gender: 'FEMALE' | 'MALE';
  feeCategoryId: number; feeCategory: string; fee: number; accessible: boolean; status: 'AVAILABLE' | 'MAINTENANCE' | 'CLOSED'; beds: HostelBedView[];
}

export interface HostelDetail { hostel: HostelSummary; rooms: HostelRoomView[]; canEdit: boolean; wardens: { id: number; fullName: string }[]; canAssignWardens: boolean }

export interface HostelRepair {
  id: number; repairNo: string; hostelId: number; hostel?: string; room?: string; category: string; priority: 'NORMAL' | 'URGENT'; description: string;
  status: 'OPEN' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'; reportedByName: string; reportedAt: string; studentName?: string; registrationNo?: string; note?: string;
  updatedByName?: string; resolvedAt?: string; canUpdate: boolean;
}

export interface HostelWindow {
  id: number; academicYearId: number; academicYear: string; name: string; opensOn: string; closesOn: string; occupancyStarts?: string;
  occupancyEnds?: string; paymentDays: number; firstYearsFirst: boolean; specialNeedsFirst: boolean; paidFeesFirst: boolean; open: boolean;
  applications: number; allocated: number; waitlisted: number; checkedIn: number;
}

export interface HostelAllocationView {
  id: number; applicationId: number; studentId: number; registrationNo: string; studentName: string; sex?: string; bedId: number; hostel: string;
  room: string; bed: string; status: 'ALLOCATED' | 'CHECKED_IN' | 'CHECKED_OUT' | 'RELEASED' | 'CANCELLED'; fee: number; invoiceId?: number;
  invoiceNo?: string; invoiceStatus?: string; balance: number; controlNo?: string; payBy: string; method: 'AUTO' | 'MANUAL'; allocatedByName: string;
  allocatedAt: string; checkedInAt?: string; checkedInByName?: string; checkInCondition?: string; checkedOutAt?: string; checkedOutByName?: string;
  checkOutCondition?: string; damages?: string; damageCharge?: number; damageInvoiceNo?: string; releasedAt?: string; releaseReason?: string;
  /** Refund of the unused part raised at check-out or cancellation; the bursary decides it. */
  refund?: { adjustmentNo: string; amount: number; status: string } | null;
  bedChanges: number;
}

/** A move to another bed and what it did to the fee. */
export interface HostelBedChange {
  id: number; fromBed: string; toBed: string; oldFee: number; newFee: number; difference: number; invoiceNo?: string; adjustmentNo?: string;
  adjustmentKind?: string; adjustmentStatus?: string; reason: string; changedByName: string; changedAt: string;
}

export interface HostelApplicationView {
  id: number; windowId: number; studentId: number; registrationNo: string; studentName: string; sex?: string; programmeCode: string;
  yearOfStudy: number; preferences: string[]; roomType?: RoomType; specialNeeds: boolean; specialNeedsNote?: string;
  status: 'SUBMITTED' | 'ALLOCATED' | 'WAITLISTED' | 'RELEASED' | 'CANCELLED'; submittedAt: string; feesCleared: boolean; priority: number;
  allocation?: HostelAllocationView | null;
}

export interface FreeBed { bedId: number; hostel: string; room: string; bed: string; roomType: RoomType; gender: string; accessible: boolean; fee: number }

export interface MyHostel {
  sex?: string; openWindows: HostelWindow[]; hostels: { id: number; name: string; gender: string }[];
  application?: HostelApplicationView | null; history: HostelAllocationView[]; blocker?: string | null;
}

// ---------------------------------------------------------------- industrial practical training

export interface IptOrganisation {
  id: number; name: string; sector?: string; region?: string; district?: string; address?: string; contactName?: string; contactTitle?: string;
  contactPhone?: string; contactEmail?: string; capacity?: number | null; active: boolean; proposedByStudent: boolean; placements: number;
}

export interface IptPeriod {
  id: number; name: string; semesterId: number; semester: string; moduleId?: number | null; moduleCode?: string | null; moduleTitle?: string | null;
  startsOn: string; endsOn: string; requestsClose?: string | null; workplaceWeight: number; institutionWeight: number; logbookWeight: number;
  minLogbookWeeks: number; eligible: number; requested: number; confirmed: number; completed: number; resultsTransferredAt?: string | null;
  resultsTransferredBy?: string | null; requestsOpen: boolean;
}

export interface IptEligibleStudent {
  studentId: number; registrationNo: string; name: string; programmeCode: string; yearOfStudy: number; phone?: string;
  placementId?: number | null; placementStatus?: string | null;
}

export type IptStatus = 'REQUESTED' | 'CONFIRMED' | 'COMPLETED' | 'CANCELLED' | 'DECLINED';

export interface IptPlacementSummary {
  id: number; periodId: number; studentId: number; registrationNo: string; studentName: string; programmeCode: string; organisationId: number;
  organisation: string; region?: string; status: IptStatus; startsOn: string; endsOn: string; workplaceSupervisorName?: string;
  institutionSupervisorId?: number | null; institutionSupervisorName?: string | null; logbookWeeks: number; logbookApproved: number;
  logbookPending: number; scores: number; totalMark?: number | null; transferredAt?: string | null; letterNo?: string | null;
}

export interface IptLogbookEntry {
  id: number; weekNo: number; weekStarting: string; activities: string; skills?: string; challenges?: string; hours?: number;
  status: 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'RETURNED'; submittedAt?: string; reviewerComment?: string; reviewedByName?: string; reviewedAt?: string;
}

export interface IptPlacementView {
  summary: IptPlacementSummary; unit?: string; workplaceSupervisorTitle?: string; workplaceSupervisorPhone?: string; workplaceSupervisorEmail?: string;
  studentNote?: string; decisionNote?: string; requestedAt: string; confirmedByName?: string; confirmedAt?: string; letterIssuedAt?: string;
  organisationContact?: string; organisationPhone?: string; organisationAddress?: string; logbook: IptLogbookEntry[];
  visits: { id: number; visitedOn: string; mode: 'VISIT' | 'PHONE' | 'ONLINE'; notes: string; recordedByName: string; recordedAt: string }[];
  assessments: { kind: 'WORKPLACE' | 'INSTITUTION' | 'LOGBOOK'; score: number; comments?: string; assessedByName: string; assessedAt: string }[];
  workplaceWeight: number; institutionWeight: number; logbookWeight: number;
  can: { manage: boolean; supervise: boolean; writeLogbook: boolean };
}

export interface MyIpt {
  periods: IptPeriod[]; organisations: { id: number; name: string; region?: string }[]; placement?: IptPlacementView | null; history: IptPlacementSummary[];
}

export type RoleScope = 'INSTITUTION' | 'DEPARTMENT' | 'PROGRAMME' | 'SPONSOR';
export interface RoleInfo { role: string; label: string; description: string; scopes: RoleScope[] }
export interface RoleGrant { role: string; label?: string; scope: RoleScope; scopeId?: number | null; scopeName?: string }
export interface StaffUser {
  id: number; username: string; fullName: string; jobTitle?: string; email?: string; phone?: string;
  status: 'ACTIVE' | 'DISABLED' | 'LOCKED' | 'PENDING_VERIFICATION'; mustChangePassword: boolean; lastLoginAt?: string; createdAt: string;
  createdByName?: string; roles: RoleGrant[]; self: boolean; mfaEnabled?: boolean;
}
export interface IssuedPassword { user: StaffUser; temporaryPassword: string }
export interface StaffUserSummary { total: number; active: number; disabled: number; locked: number; mustChangePassword: number }

export interface AuditEntry {
  id: number; occurredAt: string; actorUserId?: number; actorName?: string; action: string; entity: string; entityId?: number;
  area: string; details?: Record<string, unknown>; ipAddress?: string;
}
export interface AuditCount { key: string; label: string; count: number }
export interface AuditFacets { areas: string[]; entities: AuditCount[]; actions: AuditCount[]; actors: AuditCount[] }
export interface AuditSummary {
  from: string; to: string; actions: number; people: number; signIns: number; failedSignIns: number; lockedAccounts: number;
  accountChanges: number; days: { date: string; count: number }[]; topActors: AuditCount[]; topAreas: AuditCount[];
}

export interface OverviewProgramme {
  id: number; code: string; name: string; award: string; students: number; female: number; male: number; newStudents: number;
  places?: number; intakeRate?: number; billed: number; collected: number; outstanding: number; collectionRate?: number;
  assessed: number; passed: number; passRate?: number; applicants: number; windowPlaces: number; applicantsPerPlace?: number; attention: string[];
}
export interface Overview {
  year?: { id: number; code: string; current: boolean };
  years: { id: number; code: string; current: boolean }[];
  totals: {
    students: number; female: number; male: number; newStudents: number; places: number; intakeRate?: number; billed: number; collected: number;
    outstanding: number; collectionRate?: number; assessed: number; passed: number; passRate?: number; applicants: number; windowPlaces: number;
    applicantsPerPlace?: number; windowName?: string;
  };
  programmes: OverviewProgramme[];
  intakeByYear: { key: string; label: string; value: number }[];
  collectionsByMonth: { key: string; label: string; value: number }[];
  thresholds: { lowIntake: number; lowCollection: number; lowPass: number; lowDemand: number };
}

export interface ApprovalItem {
  kind: string; label: string; body: string; id: number; title: string; detail: string; submittedBy?: string; submittedAt?: string;
  link: string; canAct: boolean; note?: string;
}
export interface ApprovalInbox { items: ApprovalItem[]; total: number; actionable: number }

// ---------------------------------------------------------------- campuses, faculties, categories, transfers (BR-13, BR-22)

export interface Campus { id: number; code: string; name: string; region?: string; district?: string; address?: string; main: boolean; active: boolean; programmes: number }
export interface NamedItem { id: number; code: string; name: string; active: boolean }
export interface TransferOptions {
  programmeId: number; programme: string; campusId?: number; campus?: string; yearOfStudy: number; status: string; openTransfer: boolean;
  programmes: { id: number; code: string; name: string; years: number; campusIds: number[] }[];
  campuses: { id: number; name: string; main: boolean }[];
}
export interface Transfer {
  id: number; studentId: number; registrationNo: string; studentName: string; kind: 'PROGRAMME' | 'CAMPUS' | 'BOTH';
  fromProgramme: string; toProgramme: string; fromCampus?: string; toCampus?: string; fromYear: number; toYear: number;
  reason: string; effectiveDate: string; state: 'REQUESTED' | 'RECOMMENDED' | 'APPROVED' | 'REJECTED' | 'CANCELLED';
  requestedByStudent: boolean; requestedByName: string; requestedAt: string; recommendedByName?: string; recommendedAt?: string;
  recommendationNote?: string; decidedByName?: string; decidedAt?: string; decisionNote?: string;
  canRecommend: boolean; canDecide: boolean; canCancel: boolean;
}
