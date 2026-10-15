/** The platform part of CIMS, loaded only when someone opens it (keeps the admission portal small on phones). */
import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from '../auth/session';
import PlatformLayout from '../pages/platform/PlatformLayout';
import { PlatformLoginPage, PlatformPasswordPage } from '../pages/platform/PlatformAuth';
import { InstitutionDetailPage, InstitutionsPage, OnboardPage } from '../pages/platform/InstitutionPages';
import { IntegrationsPage, OverviewPage, PlatformAuditPage, PlatformUsersPage } from '../pages/platform/PlatformPages';
import { CataloguePage } from '../pages/platform/CataloguePages';

export default function PlatformApp() {
  return (
    <Routes>
      <Route path="login" element={<PlatformLoginPage />} />
      <Route element={<RequireAuth realm="platform"><PlatformLayout /></RequireAuth>}>
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
      <Route path="*" element={<Navigate to="/platform" replace />} />
    </Routes>
  );
}
