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
