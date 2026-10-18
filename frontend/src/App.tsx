import { lazy, Suspense, type ReactNode } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { Loading } from './components/common';
import { RequireAuth } from './auth/session';
import PortalLayout from './components/PortalLayout';
import { TracerSurveyPage, WorkplaceAssessmentPage } from './pages/portal/PublicForms';
import { ForgotPasswordPage, LoginPage, RegisterPage, VerifyPage } from './pages/portal/AuthPages';
import HomePage from './pages/portal/HomePage';
import DocumentVerifyPage from './pages/portal/VerifyPage';

// Each part of CIMS is its own bundle, fetched when first opened: an applicant on a phone does not download the back-office.
const StaffApp = lazy(() => import('./apps/StaffApp'));
const StudentApp = lazy(() => import('./apps/StudentApp'));
const PlatformApp = lazy(() => import('./apps/PlatformApp'));
const ApplicationPage = lazy(() => import('./pages/applicant/ApplicationPage'));
const HelpPage = lazy(() => import('./pages/portal/HelpPage'));

function Lazy({ children }: { children: ReactNode }) {
  return <Suspense fallback={<Loading />}>{children}</Suspense>;
}

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
        <Route path="ipt-assessment/:token" element={<WorkplaceAssessmentPage />} />
        <Route path="survey/:token" element={<TracerSurveyPage />} />
        <Route path="help" element={<Lazy><HelpPage /></Lazy>} />
        <Route path="application" element={<RequireAuth realm="applicant"><Lazy><ApplicationPage /></Lazy></RequireAuth>} />
      </Route>
      <Route path="student/*" element={<Lazy><StudentApp /></Lazy>} />
      <Route path="platform/*" element={<Lazy><PlatformApp /></Lazy>} />
      <Route path="staff/*" element={<Lazy><StaffApp /></Lazy>} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
