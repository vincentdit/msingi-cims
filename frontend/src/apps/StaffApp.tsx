/** The staff part of CIMS, loaded only when someone opens it (keeps the admission portal small on phones). */
import { HelpdeskPage, HelpdeskTicketPage, StaffHelpPage } from '../pages/staff/HelpdeskPages';
import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from '../auth/session';
import StaffLayout from '../components/StaffLayout';
import InstitutionPage from '../pages/staff/InstitutionPage';
import UsersPage from '../pages/staff/UsersPage';
import AuditPage from '../pages/staff/AuditPage';
import ApprovalsPage from '../pages/staff/ApprovalsPage';
import SecurityPage from '../pages/staff/SecurityPages';
import LetterTemplatesPage from '../pages/staff/LetterTemplatesPage';
import ImportsPage from '../pages/staff/ImportsPage';
import StaffIntegrationsPage from '../pages/staff/IntegrationsPage';
import AlumniPage from '../pages/staff/AlumniPage';
import ResultTrendsPage from '../pages/staff/ResultTrendsPage';
import ChangePasswordPage from '../pages/staff/ChangePasswordPage';
import { MarksPage, OfferingPage } from '../pages/staff/MarksPages';
import { RegulationsPage, ResultSheetPage, ResultSheetsPage } from '../pages/staff/ResultsPages';
import { AmendmentsPage } from '../pages/staff/ExamRecordsPages';
import { AttendancePage, TeachingPage } from '../pages/staff/TeachingPages';
import { CheckInScreenPage, MyTeachingPage } from '../pages/staff/MyTeachingPages';
import { CompetencePage, CompetenceUnitPage } from '../pages/staff/CompetencePages';
import { FinanceOverviewPage, SponsorDetailPage, SponsorsPage, WaiversPage } from '../pages/staff/FinanceOverviewPages';
import { EligibilityPage, OfferingEligibilityPage } from '../pages/staff/EligibilityPages';
import { ClearancePage } from '../pages/staff/ClearancePages';
import { GraduationPage, GraduationsPage } from '../pages/staff/GraduationPages';
import { HostelPage } from '../pages/staff/HostelPages';
import { IptPage } from '../pages/staff/IptPages';
import { QualificationChecksPage } from '../pages/staff/QualificationChecks';
import EntryTestPage from '../pages/staff/EntryTestPage';
import { ExamTimetablePage, TimetablePage } from '../pages/staff/TimetablePages';
import NoticesPage from '../pages/staff/NoticesPage';
import MessagingPage from '../pages/staff/MessagingPage';
import DashboardPage from '../pages/staff/DashboardPage';
import ReportsPage from '../pages/staff/ReportsPage';
import { CalendarPage, CurriculaPage, CurriculumEditorPage } from '../pages/staff/AcademicPages';
import SemesterRegistrationsPage from '../pages/staff/SemesterRegistrationsPage';
import HealthInsurancePage from '../pages/staff/HealthInsurancePage';
import SelectionPage from '../pages/staff/SelectionPage';
import VerificationPage from '../pages/staff/VerificationPage';
import { HandoverPage, StudentDetailPage, StudentsPage } from '../pages/staff/RegistryPages';
import { StatusChangesPage } from '../pages/staff/StatusAndCards';
import { TransfersPage } from '../pages/staff/TransferPages';
import { AppealsPage, ExamsOfficePage } from '../pages/staff/ExamsOfficePages';
import FinancePage from '../pages/staff/FinancePages';
import { ApplicationsPage, ProgrammesPage, StaffLoginPage, WindowDetailPage, WindowsPage } from '../pages/staff/StaffPages';

export default function StaffApp() {
  return (
    <Routes>
      <Route path="login" element={<StaffLoginPage />} />
      <Route element={<RequireAuth realm="staff"><StaffLayout /></RequireAuth>}>
        <Route index element={<DashboardPage />} />
        <Route path="applications" element={<ApplicationsPage />} />
        <Route path="windows" element={<WindowsPage />} />
        <Route path="windows/:id" element={<WindowDetailPage />} />
        <Route path="windows/:id/selection" element={<SelectionPage />} />
        <Route path="windows/:id/qualification-checks" element={<QualificationChecksPage />} />
        <Route path="windows/:id/verification" element={<VerificationPage />} />
        <Route path="windows/:id/handover" element={<HandoverPage />} />
        <Route path="windows/:id/entry-test" element={<EntryTestPage />} />
        <Route path="students" element={<StudentsPage />} />
        <Route path="students/:id" element={<StudentDetailPage />} />
        <Route path="status-changes" element={<StatusChangesPage />} />
        <Route path="transfers" element={<TransfersPage />} />
        <Route path="semester-registrations" element={<SemesterRegistrationsPage />} />
        <Route path="health-insurance" element={<HealthInsurancePage />} />
        <Route path="helpdesk" element={<HelpdeskPage />} />
        <Route path="helpdesk/:id" element={<HelpdeskTicketPage />} />
        <Route path="help" element={<StaffHelpPage />} />
        <Route path="calendar" element={<CalendarPage />} />
        <Route path="marks" element={<MarksPage />} />
        <Route path="marks/:id" element={<OfferingPage />} />
        <Route path="teaching" element={<TeachingPage />} />
        <Route path="teaching/:id" element={<AttendancePage />} />
        <Route path="teaching/check-in/:sessionId" element={<CheckInScreenPage />} />
        <Route path="my-teaching" element={<MyTeachingPage />} />
        <Route path="timetable" element={<TimetablePage />} />
        <Route path="exam-timetable" element={<ExamTimetablePage />} />
        <Route path="competence" element={<CompetencePage />} />
        <Route path="competence/:id" element={<CompetenceUnitPage />} />
        <Route path="eligibility" element={<EligibilityPage />} />
        <Route path="eligibility/:id" element={<OfferingEligibilityPage />} />
        <Route path="clearance" element={<ClearancePage />} />
        <Route path="hostels" element={<HostelPage />} />
        <Route path="ipt" element={<IptPage />} />
        <Route path="graduations" element={<GraduationsPage />} />
        <Route path="graduations/:id" element={<GraduationPage />} />
        <Route path="results" element={<ResultSheetsPage />} />
        <Route path="results/:id" element={<ResultSheetPage />} />
        <Route path="results-trends" element={<ResultTrendsPage />} />
        <Route path="exams-office" element={<ExamsOfficePage />} />
        <Route path="appeals" element={<AppealsPage />} />
        <Route path="regulations" element={<RegulationsPage />} />
        <Route path="amendments" element={<AmendmentsPage />} />
        <Route path="curricula" element={<CurriculaPage />} />
        <Route path="curricula/:id" element={<CurriculumEditorPage />} />
        <Route path="finance" element={<FinancePage />} />
        <Route path="finance-overview" element={<FinanceOverviewPage />} />
        <Route path="sponsors" element={<SponsorsPage />} />
        <Route path="sponsors/:id" element={<SponsorDetailPage />} />
        <Route path="waivers" element={<WaiversPage />} />
        <Route path="notices" element={<NoticesPage />} />
        <Route path="messages" element={<MessagingPage />} />
        <Route path="reports" element={<ReportsPage />} />
        <Route path="programmes" element={<ProgrammesPage />} />
        <Route path="institution" element={<InstitutionPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="approvals" element={<ApprovalsPage />} />
        <Route path="password" element={<ChangePasswordPage />} />
        <Route path="security" element={<SecurityPage />} />
        <Route path="letters" element={<LetterTemplatesPage />} />
        <Route path="imports" element={<ImportsPage />} />
        <Route path="integrations" element={<StaffIntegrationsPage />} />
        <Route path="alumni" element={<AlumniPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/staff" replace />} />
    </Routes>
  );
}
