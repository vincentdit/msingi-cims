import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/session';
import PortalLayout from './components/PortalLayout';
import StaffLayout from './components/StaffLayout';
import ApplicationPage from './pages/applicant/ApplicationPage';
import SelectionPage from './pages/staff/SelectionPage';
import VerificationPage from './pages/staff/VerificationPage';
import { ForgotPasswordPage, LoginPage, RegisterPage, VerifyPage } from './pages/portal/AuthPages';
import HomePage from './pages/portal/HomePage';
import DocumentVerifyPage from './pages/portal/VerifyPage';
import {
  ApplicationsPage, ProgrammesPage, StaffDashboard, StaffLoginPage, WindowDetailPage, WindowsPage,
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
      <Route path="staff/login" element={<StaffLoginPage />} />
      <Route path="staff" element={<RequireAuth realm="staff"><StaffLayout /></RequireAuth>}>
        <Route index element={<StaffDashboard />} />
        <Route path="applications" element={<ApplicationsPage />} />
        <Route path="windows" element={<WindowsPage />} />
        <Route path="windows/:id" element={<WindowDetailPage />} />
        <Route path="windows/:id/selection" element={<SelectionPage />} />
        <Route path="windows/:id/verification" element={<VerificationPage />} />
        <Route path="programmes" element={<ProgrammesPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
