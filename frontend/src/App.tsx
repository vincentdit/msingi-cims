import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from './auth/session';
import PortalLayout from './components/PortalLayout';
import StaffLayout from './components/StaffLayout';
import ApplicationPage from './pages/applicant/ApplicationPage';
import { ForgotPasswordPage, LoginPage, RegisterPage, VerifyPage } from './pages/portal/AuthPages';
import HomePage from './pages/portal/HomePage';
import {
  ApplicationsPage, ProgrammesPage, StaffDashboard, StaffLoginPage, WindowDetailPage, WindowsPage,
} from './pages/staff/StaffPages';

export default function App() {
  return (
    <Routes>
      <Route element={<PortalLayout />}>
        <Route index element={<HomePage />} />
        <Route path="register" element={<RegisterPage />} />
        <Route path="verify" element={<VerifyPage />} />
        <Route path="login" element={<LoginPage />} />
        <Route path="forgot-password" element={<ForgotPasswordPage />} />
        <Route path="application" element={<RequireAuth realm="applicant"><ApplicationPage /></RequireAuth>} />
      </Route>
      <Route path="staff/login" element={<StaffLoginPage />} />
      <Route path="staff" element={<RequireAuth realm="staff"><StaffLayout /></RequireAuth>}>
        <Route index element={<StaffDashboard />} />
        <Route path="applications" element={<ApplicationsPage />} />
        <Route path="windows" element={<WindowsPage />} />
        <Route path="windows/:id" element={<WindowDetailPage />} />
        <Route path="programmes" element={<ProgrammesPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
