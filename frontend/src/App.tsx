import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/session';
import PortalLayout from './components/PortalLayout';
import StaffLayout from './components/StaffLayout';
import StudentLayout from './components/StudentLayout';
import InstitutionPage from './pages/staff/InstitutionPage';
import UsersPage from './pages/staff/UsersPage';
import AuditPage from './pages/staff/AuditPage';
import ApprovalsPage from './pages/staff/ApprovalsPage';
import ChangePasswordPage from './pages/staff/ChangePasswordPage';
import { StudentFees, StudentHome, StudentLoginPage, StudentNotices, StudentProfilePage } from './pages/student/StudentPages';
import SemesterRegistrationPage from './pages/student/SemesterRegistrationPage';
import StudentResultsPage from './pages/student/StudentResultsPage';
import { MarksPage, OfferingPage } from './pages/staff/MarksPages';
import { RegulationsPage, ResultSheetPage, ResultSheetsPage } from './pages/staff/ResultsPages';
import { AmendmentsPage } from './pages/staff/ExamRecordsPages';
import { AttendancePage, TeachingPage } from './pages/staff/TeachingPages';
import { CheckInScreenPage, MyTeachingPage } from './pages/staff/MyTeachingPages';
import CheckInPage from './pages/student/CheckInPage';
import { EligibilityPage, OfferingEligibilityPage } from './pages/staff/EligibilityPages';
import { ClearancePage } from './pages/staff/ClearancePages';
import { GraduationPage, GraduationsPage } from './pages/staff/GraduationPages';
import { HostelPage } from './pages/staff/HostelPages';
import { IptPage } from './pages/staff/IptPages';
import { QualificationChecksPage } from './pages/staff/QualificationChecks';
import NoticesPage from './pages/staff/NoticesPage';
import MessagingPage from './pages/staff/MessagingPage';
import DashboardPage from './pages/staff/DashboardPage';
import ReportsPage from './pages/staff/ReportsPage';
import { CalendarPage, CurriculaPage, CurriculumEditorPage } from './pages/staff/AcademicPages';
import SemesterRegistrationsPage from './pages/staff/SemesterRegistrationsPage';
import ApplicationPage from './pages/applicant/ApplicationPage';
import SelectionPage from './pages/staff/SelectionPage';
import VerificationPage from './pages/staff/VerificationPage';
import { HandoverPage, StudentDetailPage, StudentsPage } from './pages/staff/RegistryPages';
import { StatusChangesPage } from './pages/staff/StatusAndCards';
import { AppealsPage, ExamsOfficePage } from './pages/staff/ExamsOfficePages';
import PlatformLayout from './pages/platform/PlatformLayout';
import { PlatformLoginPage, PlatformPasswordPage } from './pages/platform/PlatformAuth';
import { InstitutionDetailPage, InstitutionsPage, OnboardPage } from './pages/platform/InstitutionPages';
import { IntegrationsPage, OverviewPage, PlatformAuditPage, PlatformUsersPage } from './pages/platform/PlatformPages';
import { CataloguePage } from './pages/platform/CataloguePages';
import FinancePage from './pages/staff/FinancePages';
import { ForgotPasswordPage, LoginPage, RegisterPage, VerifyPage } from './pages/portal/AuthPages';
import HomePage from './pages/portal/HomePage';
import DocumentVerifyPage from './pages/portal/VerifyPage';
import {
  ApplicationsPage, ProgrammesPage, StaffLoginPage, WindowDetailPage, WindowsPage,
} from './pages/staff/StaffPages';

export default function App() {
  return (
    <Routes>
      <Route element={<PortalLayout />}>
        <Route index element={<HomePage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="verify-phone" element={<VerifyPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="forgot-password" element={<ForgotPasswordPage />} />
        <Route path="verify" element={<DocumentVerifyPage />} />
        <Route path="verify/:code" element={<DocumentVerifyPage />} />
        <Route path="application" element={<RequireAuth realm="applicant"><ApplicationPage /></RequireAuth>} />
      </Route>
      <Route path="student/login" element={<StudentLoginPage />} />
      <Route path="student" element={<RequireAuth realm="student"><StudentLayout /></RequireAuth>}>
        <Route index element={<StudentHome />} />
        <Route path="register" element={<SemesterRegistrationPage />} />
        <Route path="results" element={<StudentResultsPage />} />
        <Route path="check-in" element={<CheckInPage />} />
        <Route path="fees" element={<StudentFees />} />
        <Route path="notices" element={<StudentNotices />} />
        <Route path="profile" element={<StudentProfilePage />} />
      </Route>
      <Route path="platform/login" element={<PlatformLoginPage />} />
      <Route path="platform" element={<RequireAuth realm="platform"><PlatformLayout /></RequireAuth>}>
        <Route index element={<OverviewPage />} />
        <Route path="institutions" element={<InstitutionsPage />} />
        <Route path="institutions/new" element={<OnboardPage />} />
        <Route path="institutions/:id" element={<InstitutionDetailPage />} />
        <Route path="nactvet" element={<CataloguePage />} />
        <Route path="integrations" element={<IntegrationsPage />} />
        <Route path="users" element={<PlatformUsersPage />} />
        <Route path="audit" element={<PlatformAuditPage />} />
        <Route path="password" element={<PlatformPasswordPage />} />
      </Route>
      <Route path="staff/login" element={<StaffLoginPage />} />
      <Route path="staff" element={<RequireAuth realm="staff"><StaffLayout /></RequireAuth>}>
        <Route index element={<DashboardPage />} />
        <Route path="applications" element={<ApplicationsPage />} />
        <Route path="windows" element={<WindowsPage />} />
        <Route path="windows/:id" element={<WindowDetailPage />} />
        <Route path="windows/:id/selection" element={<SelectionPage />} />
        <Route path="windows/:id/qualification-checks" element={<QualificationChecksPage />} />
        <Route path="windows/:id/verification" element={<VerificationPage />} />
        <Route path="windows/:id/handover" element={<HandoverPage />} />
        <Route path="students" element={<StudentsPage />} />
        <Route path="students/:id" element={<StudentDetailPage />} />
        <Route path="status-changes" element={<StatusChangesPage />} />
        <Route path="semester-registrations" element={<SemesterRegistrationsPage />} />
        <Route path="calendar" element={<CalendarPage />} />
        <Route path="marks" element={<MarksPage />} />
        <Route path="marks/:id" element={<OfferingPage />} />
        <Route path="teaching" element={<TeachingPage />} />
        <Route path="teaching/:id" element={<AttendancePage />} />
        <Route path="teaching/check-in/:sessionId" element={<CheckInScreenPage />} />
        <Route path="my-teaching" element={<MyTeachingPage />} />
        <Route path="eligibility" element={<EligibilityPage />} />
        <Route path="eligibility/:id" element={<OfferingEligibilityPage />} />
        <Route path="clearance" element={<ClearancePage />} />
        <Route path="hostels" element={<HostelPage />} />
        <Route path="ipt" element={<IptPage />} />
        <Route path="graduations" element={<GraduationsPage />} />
        <Route path="graduations/:id" element={<GraduationPage />} />
        <Route path="results" element={<ResultSheetsPage />} />
        <Route path="results/:id" element={<ResultSheetPage />} />
        <Route path="exams-office" element={<ExamsOfficePage />} />
        <Route path="appeals" element={<AppealsPage />} />
        <Route path="regulations" element={<RegulationsPage />} />
        <Route path="amendments" element={<AmendmentsPage />} />
        <Route path="curricula" element={<CurriculaPage />} />
        <Route path="curricula/:id" element={<CurriculumEditorPage />} />
        <Route path="finance" element={<FinancePage />} />
        <Route path="notices" element={<NoticesPage />} />
        <Route path="messages" element={<MessagingPage />} />
        <Route path="reports" element={<ReportsPage />} />
        <Route path="programmes" element={<ProgrammesPage />} />
        <Route path="institution" element={<InstitutionPage />} />
        <Route path="users" element={<UsersPage />} />
        <Route path="audit" element={<AuditPage />} />
        <Route path="approvals" element={<ApprovalsPage />} />
        <Route path="password" element={<ChangePasswordPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
