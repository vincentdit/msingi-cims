/** The student part of CIMS, loaded only when someone opens it (keeps the admission portal small on phones). */
import { PublishedAlmanac } from '../pages/portal/PublishedPages';
import HelpCentre from '../components/helpdesk/HelpCentre';
import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireAuth } from '../auth/session';
import StudentLayout from '../components/StudentLayout';
import { StudentFees, StudentHome, StudentLoginPage, StudentNotices, StudentProfilePage } from '../pages/student/StudentPages';
import SemesterRegistrationPage from '../pages/student/SemesterRegistrationPage';
import StudentResultsPage from '../pages/student/StudentResultsPage';
import CheckInPage from '../pages/student/CheckInPage';
import StudentTimetablePage from '../pages/student/StudentTimetablePage';

import { StudentLibraryPage, StudentModulePage, StudentModulesPage } from '../pages/student/StudentStudyPages';
export default function StudentApp() {
  return (
    <Routes>
      <Route path="login" element={<StudentLoginPage />} />
      <Route element={<RequireAuth realm="student"><StudentLayout /></RequireAuth>}>
        <Route index element={<StudentHome />} />
        <Route path="register" element={<SemesterRegistrationPage />} />
        <Route path="timetable" element={<StudentTimetablePage />} />
        <Route path="results" element={<StudentResultsPage />} />
        <Route path="check-in" element={<CheckInPage />} />
        <Route path="fees" element={<StudentFees />} />
        <Route path="modules" element={<StudentModulesPage />} />
        <Route path="modules/:id" element={<StudentModulePage />} />
        <Route path="library" element={<StudentLibraryPage />} />
        <Route path="notices" element={<StudentNotices />} />
        <Route path="almanac" element={<PublishedAlmanac realm="student" />} />
        <Route path="profile" element={<StudentProfilePage />} />
        <Route path="help" element={<HelpCentre mode="student" intro="Find an answer, or ask the office that can help and follow the reply here." />} />
      </Route>
      <Route path="*" element={<Navigate to="/student" replace />} />
    </Routes>
  );
}
