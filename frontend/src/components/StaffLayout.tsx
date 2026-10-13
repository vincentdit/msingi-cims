import AssignmentIcon from '@mui/icons-material/AssignmentInd';
import BadgeIcon from '@mui/icons-material/Badge';
import CampaignIcon from '@mui/icons-material/Campaign';
import SwapHorizIcon from '@mui/icons-material/SwapHoriz';
import SmsIcon from '@mui/icons-material/Sms';
import AssessmentIcon from '@mui/icons-material/Assessment';
import PaymentsIcon from '@mui/icons-material/Payments';
import DashboardIcon from '@mui/icons-material/SpaceDashboard';
import EventIcon from '@mui/icons-material/EventAvailable';
import LogoutIcon from '@mui/icons-material/Logout';
import MenuIcon from '@mui/icons-material/Menu';
import SchoolIcon from '@mui/icons-material/School';
import MarksIcon from '@mui/icons-material/EditNote';
import ResultsIcon from '@mui/icons-material/FactCheck';
import RulesIcon from '@mui/icons-material/Rule';
import AmendIcon from '@mui/icons-material/PublishedWithChanges';
import CalendarIcon from '@mui/icons-material/CalendarMonth';
import CurriculumIcon from '@mui/icons-material/MenuBook';
import HowToRegIcon from '@mui/icons-material/HowToReg';
import TeachingIcon from '@mui/icons-material/CoPresent';
import MyTeachingIcon from '@mui/icons-material/LocalLibrary';
import CompetenceIcon from '@mui/icons-material/Construction';
import FinanceIcon from '@mui/icons-material/QueryStats';
import SponsorIcon from '@mui/icons-material/VolunteerActivism';
import WaiverIcon from '@mui/icons-material/ReceiptLong';
import EligibleIcon from '@mui/icons-material/VerifiedUser';
import ClearanceIcon from '@mui/icons-material/TaskAlt';
import GraduationIcon from '@mui/icons-material/WorkspacePremium';
import HostelIcon from '@mui/icons-material/Bed';
import IptIcon from '@mui/icons-material/Engineering';
import InstitutionIcon from '@mui/icons-material/AccountBalance';
import UsersIcon from '@mui/icons-material/ManageAccounts';
import AuditIcon from '@mui/icons-material/Policy';
import ApprovalsIcon from '@mui/icons-material/Inbox';
import OfficeIcon from '@mui/icons-material/FactCheck';
import AppealIcon from '@mui/icons-material/Gavel';
import { Badge } from '@mui/material';
import KeyIcon from '@mui/icons-material/Key';
import { Alert, Button } from '@mui/material';
import {
  AppBar, Box, Divider, Drawer, IconButton, List, ListItemButton, ListItemIcon, ListItemText, Toolbar, Typography, useMediaQuery, useTheme,
} from '@mui/material';
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { can, pageOf, ROLE_LABEL, type PageKey } from '../auth/access';
import { useSession } from '../auth/session';
import { useInstitution } from './InstitutionContext';

const WIDTH = 248;

type Item = { page: PageKey; to: string; label: string; icon: JSX.Element; end?: boolean };
const GROUPS: { title?: string; items: Item[] }[] = [
  { items: [
    { page: 'dashboard', to: '/staff', label: 'Dashboard', icon: <DashboardIcon />, end: true },
    { page: 'approvals', to: '/staff/approvals', label: 'Approvals', icon: <ApprovalsIcon /> },
    { page: 'reports', to: '/staff/reports', label: 'Reports', icon: <AssessmentIcon /> },
  ] },
  { title: 'Admissions', items: [
    { page: 'windows', to: '/staff/windows', label: 'Admission windows', icon: <EventIcon /> },
    { page: 'applications', to: '/staff/applications', label: 'Applicants', icon: <AssignmentIcon /> },
    { page: 'programmes', to: '/staff/programmes', label: 'Programmes', icon: <SchoolIcon /> },
  ] },
  { title: 'Registry', items: [
    { page: 'students', to: '/staff/students', label: 'Students', icon: <BadgeIcon /> },
    { page: 'statusChanges', to: '/staff/status-changes', label: 'Status changes', icon: <SwapHorizIcon /> },
    { page: 'semesterRegistrations', to: '/staff/semester-registrations', label: 'Semester registration', icon: <HowToRegIcon /> },
    { page: 'graduations', to: '/staff/graduations', label: 'Graduation', icon: <GraduationIcon /> },
  ] },
  { title: 'Teaching', items: [
    { page: 'myTeaching', to: '/staff/my-teaching', label: 'My teaching', icon: <MyTeachingIcon /> },
    { page: 'calendar', to: '/staff/calendar', label: 'Academic calendar', icon: <CalendarIcon /> },
    { page: 'curricula', to: '/staff/curricula', label: 'Curricula', icon: <CurriculumIcon /> },
    { page: 'teaching', to: '/staff/teaching', label: 'Teaching & attendance', icon: <TeachingIcon /> },
    { page: 'marks', to: '/staff/marks', label: 'Marks', icon: <MarksIcon /> },
    { page: 'competence', to: '/staff/competence', label: 'Competence (NVA)', icon: <CompetenceIcon /> },
  ] },
  { title: 'Examinations', items: [
    { page: 'examsOffice', to: '/staff/exams-office', label: 'Examinations office', icon: <OfficeIcon /> },
    { page: 'eligibility', to: '/staff/eligibility', label: 'Exam eligibility', icon: <EligibleIcon /> },
    { page: 'results', to: '/staff/results', label: 'Results', icon: <ResultsIcon /> },
    { page: 'appeals', to: '/staff/appeals', label: 'Remarks & appeals', icon: <AppealIcon /> },
    { page: 'amendments', to: '/staff/amendments', label: 'Result amendments', icon: <AmendIcon /> },
    { page: 'regulations', to: '/staff/regulations', label: 'Exam regulations', icon: <RulesIcon /> },
  ] },
  { title: 'Student services', items: [
    { page: 'clearance', to: '/staff/clearance', label: 'Clearance', icon: <ClearanceIcon /> },
    { page: 'hostels', to: '/staff/hostels', label: 'Hostels', icon: <HostelIcon /> },
    { page: 'ipt', to: '/staff/ipt', label: 'Industrial training', icon: <IptIcon /> },
  ] },
  { title: 'Finance', items: [
    { page: 'financeOverview', to: '/staff/finance-overview', label: 'Finance overview', icon: <FinanceIcon /> },
    { page: 'finance', to: '/staff/finance', label: 'Fees', icon: <PaymentsIcon /> },
    { page: 'sponsors', to: '/staff/sponsors', label: 'Sponsors', icon: <SponsorIcon /> },
    { page: 'waivers', to: '/staff/waivers', label: 'Waivers & refunds', icon: <WaiverIcon /> },
  ] },
  { title: 'Communication', items: [
    { page: 'notices', to: '/staff/notices', label: 'Notices', icon: <CampaignIcon /> },
    { page: 'messages', to: '/staff/messages', label: 'Messages', icon: <SmsIcon /> },
  ] },
  { title: 'Administration', items: [
    { page: 'users', to: '/staff/users', label: 'Users & roles', icon: <UsersIcon /> },
    { page: 'audit', to: '/staff/audit', label: 'Audit trail', icon: <AuditIcon /> },
    { page: 'institution', to: '/staff/institution', label: 'Institution', icon: <InstitutionIcon /> },
  ] },
];

/** Where each person lands: their first page (the dashboard when they have one). */
export function homeFor(roles: readonly string[] | undefined) {
  // the Examinations Officer starts from the Examinations Office board
  if (roles?.includes('EXAMINATIONS_OFFICER') && !roles.includes('INSTITUTION_ADMIN')) return '/staff/exams-office';
  // lecturers and assessors start from their own modules; the bursary from the finance overview; a sponsor's officer from their sponsor
  if (roles?.length && roles.every((r) => r === 'LECTURER' || r === 'ASSESSOR')) return '/staff/my-teaching';
  if (roles?.length && roles.every((r) => r === 'BURSAR' || r === 'ACCOUNTANT')) return '/staff/finance-overview';
  if (roles?.length && roles.every((r) => r === 'SPONSOR_OFFICER')) return '/staff/sponsors';
  // officers of one service start from that service
  if (roles?.length && roles.every((r) => r === 'CLEARANCE_OFFICER' || r === 'HOSTEL_WARDEN' || r === 'IPT_COORDINATOR')) {
    return roles.includes('IPT_COORDINATOR') ? '/staff/ipt' : roles.includes('HOSTEL_WARDEN') ? '/staff/hostels' : '/staff/clearance';
  }
  for (const g of GROUPS) for (const i of g.items) if (can(roles, i.page)) return i.to;
  return '/staff/password';
}

/** Back-office layout for institution staff. */
export default function StaffLayout() {
  const inst = useInstitution();
  const theme = useTheme();
  const desktop = useMediaQuery(theme.breakpoints.up('md'));
  const [open, setOpen] = useState(false);
  const { user, signOut } = useSession();
  const navigate = useNavigate();
  const me = user('staff');
  // a Head of Department or Programme Coordinator limited to part of the institution sees it named under their roles
  const scope = useQuery({ queryKey: ['staff', 'scope', me?.id], enabled: !!me && !me.mustChangePassword, staleTime: 300_000,
    queryFn: () => api<{ all: boolean; departments: string[]; programmes: string[] }>('/api/auth/scope', { realm: 'staff' }) }).data;
  const roles = me?.roles ?? [];
  const location = useLocation();
  // how many decisions wait for this person, shown on the Approvals menu item
  const waiting = useQuery({ queryKey: ['staff', 'approvals', 'count'], enabled: can(roles, 'approvals') && !me?.mustChangePassword,
    refetchInterval: 60_000, queryFn: () => api<{ actionable: number }>('/api/approvals/count', { realm: 'staff' }) }).data?.actionable ?? 0;
  const groups = GROUPS.map((g) => ({ ...g, items: g.items.filter((i) => can(roles, i.page)) })).filter((g) => g.items.length);
  const page = pageOf(location.pathname);
  const onPassword = location.pathname.startsWith('/staff/password');
  if (me?.mustChangePassword && !onPassword) return <Navigate to="/staff/password" replace />;
  if (location.pathname === '/staff' && !can(roles, 'dashboard')) return <Navigate to={homeFor(roles)} replace />;
  const allowed = onPassword || !page || can(roles, page);

  const drawer = (
    <Box sx={{ minHeight: '100%', display: 'flex', flexDirection: 'column', bgcolor: '#0F1B2D', color: '#E6EBF2' }}>
      <Box sx={{ p: 2.5 }}>
        <Typography sx={{ fontFamily: 'Sora', fontWeight: 700 }}>{inst.shortName ?? inst.name}</Typography>
        <Typography variant="caption" sx={{ opacity: 0.7 }}>CIMS · Back-office</Typography>
      </Box>
      <Divider sx={{ borderColor: 'rgba(255,255,255,0.08)' }} />
      <List sx={{ px: 1, flexGrow: 1 }} dense>
        {groups.map((g, gi) => (
          <Box key={g.title ?? gi} component="li" sx={{ listStyle: 'none', mt: gi ? 1.5 : 0.5 }}>
            {g.title && <Typography component="div" sx={{ px: 1.5, pb: 0.5, fontSize: 12, fontWeight: 600, color: 'rgba(230,235,242,0.55)' }}>{g.title}</Typography>}
            <Box component="ul" sx={{ p: 0, m: 0 }}>
              {g.items.map((n) => (
                <ListItemButton
                  key={n.to}
                  component={NavLink}
                  to={n.to}
                  end={n.end}
                  onClick={() => setOpen(false)}
                  sx={{
                    borderRadius: 2, my: 0.25, py: 0.75, color: 'inherit',
                    '&.active': { bgcolor: 'rgba(255,255,255,0.10)', color: '#fff' },
                    '& .MuiListItemIcon-root': { color: 'inherit', minWidth: 38, opacity: 0.85 },
                  }}
                >
                  <ListItemIcon>{n.icon}</ListItemIcon>
                  <ListItemText primary={n.label} primaryTypographyProps={{ fontSize: 15 }} />
                  {n.page === 'approvals' && waiting > 0 && <Badge badgeContent={waiting} color="warning" sx={{ mr: 1.5 }} aria-label={`${waiting} waiting`} />}
                </ListItemButton>
              ))}
            </Box>
          </Box>
        ))}
      </List>
      <Box sx={{ p: 2 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{me?.fullName}</Typography>
        <Typography variant="caption" sx={{ opacity: 0.7 }}>{me?.roles.map((r) => ROLE_LABEL[r] ?? r).join(', ')}</Typography>
        {scope && !scope.all && (
          <Typography variant="caption" component="div" sx={{ mt: 0.5, px: 1, py: 0.5, borderRadius: 1, bgcolor: 'rgba(255,255,255,0.08)' }}>
            {scope.departments.length ? `Department: ${scope.departments.join(', ')}` : `Programme: ${scope.programmes.join(', ')}`}
          </Typography>
        )}
        <ListItemButton component={NavLink} to="/staff/password" sx={{ mt: 1, borderRadius: 2, px: 1, color: 'inherit' }} onClick={() => setOpen(false)}>
          <ListItemIcon sx={{ color: 'inherit', minWidth: 34 }}><KeyIcon fontSize="small" /></ListItemIcon>
          <ListItemText primary="Change password" />
        </ListItemButton>
        <ListItemButton sx={{ borderRadius: 2, px: 1 }} onClick={() => { signOut('staff'); navigate('/staff/login'); }}>
          <ListItemIcon sx={{ color: 'inherit', minWidth: 34 }}><LogoutIcon fontSize="small" /></ListItemIcon>
          <ListItemText primary="Sign out" />
        </ListItemButton>
      </Box>
    </Box>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      {!desktop && (
        <AppBar position="fixed" color="inherit" elevation={0} sx={{ borderBottom: '1px solid', borderColor: 'divider' }}>
          <Toolbar>
            <IconButton edge="start" onClick={() => setOpen(true)} aria-label="menu"><MenuIcon /></IconButton>
            <Typography sx={{ ml: 1, fontFamily: 'Sora', fontWeight: 700 }}>{inst.shortName ?? inst.name}</Typography>
          </Toolbar>
        </AppBar>
      )}
      <Drawer
        variant={desktop ? 'permanent' : 'temporary'}
        open={desktop || open}
        onClose={() => setOpen(false)}
        sx={{ width: WIDTH, flexShrink: 0, '& .MuiDrawer-paper': { width: WIDTH, border: 0, bgcolor: '#0F1B2D', color: '#E6EBF2', scrollbarWidth: 'thin', scrollbarColor: 'rgba(255,255,255,0.25) transparent' } }}
      >
        {drawer}
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, p: { xs: 2, md: 4 }, mt: { xs: 8, md: 0 }, minWidth: 0 }}>
        {allowed ? <Outlet /> : (
          <Alert severity="info" sx={{ maxWidth: 640 }} action={<Button color="inherit" onClick={() => navigate(homeFor(roles))}>Go to my pages</Button>}>
            This page is not part of your role. Ask the institution administrator if you need it.
          </Alert>
        )}
      </Box>
    </Box>
  );
}
