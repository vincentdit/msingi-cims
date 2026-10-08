import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/session';
import PortalLayout from './components/PortalLayout';
import StaffLayout from './components/StaffLayout';
import StudentLayout from './components/StudentLayout';
import { StudentFees, StudentHome, StudentLoginPage, StudentNotices, StudentProfilePage } from './pages/student/StudentPages';
import NoticesPage from './pages/staff/NoticesPage';
import MessagingPage from './pages/staff/MessagingPage';
import DashboardPage from './pages/staff/DashboardPage';
import ReportsPage from './pages/staff/ReportsPage';
import ApplicationPage from './pages/applicant/ApplicationPage';
import SelectionPage from './pages/staff/SelectionPage';
import VerificationPage from './pages/staff/VerificationPage';
import { HandoverPage, StudentDetailPage, StudentsPage } from './pages/staff/RegistryPages';
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
        <Route path="fees" element={<StudentFees />} />
        <Route path="notices" element={<StudentNotices />} />
        <Route path="profile" element={<StudentProfilePage />} />
      </Route>
      <Route path="staff/login" element={<StaffLoginPage />} />
      <Route path="staff" element={<RequireAuth realm="staff"><StaffLayout /></RequireAuth>}>
        <Route index element={<DashboardPage />} />
        <Route path="applications" element={<ApplicationsPage />} />
        <Route path="windows" element={<WindowsPage />} />
        <Route path="windows/:id" element={<WindowDetailPage />} />
        <Route path="windows/:id/selection" element={<SelectionPage />} />
        <Route path="windows/:id/verification" element={<VerificationPage />} />
        <Route path="windows/:id/handover" element={<HandoverPage />} />
        <Route path="students" element={<StudentsPage />} />
        <Route path="students/:id" element={<StudentDetailPage />} />
        <Route path="finance" element={<FinancePage />} />
        <Route path="notices" element={<NoticesPage />} />
        <Route path="messages" element={<MessagingPage />} />
        <Route path="reports" element={<ReportsPage />} />
        <Route path="programmes" element={<ProgrammesPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
