import AssignmentIcon from '@mui/icons-material/AssignmentInd';
import BadgeIcon from '@mui/icons-material/Badge';
import CampaignIcon from '@mui/icons-material/Campaign';
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
import EligibleIcon from '@mui/icons-material/VerifiedUser';
import ClearanceIcon from '@mui/icons-material/TaskAlt';
import GraduationIcon from '@mui/icons-material/WorkspacePremium';
import HostelIcon from '@mui/icons-material/Bed';
import IptIcon from '@mui/icons-material/Engineering';
import {
  AppBar, Box, Divider, Drawer, IconButton, List, ListItemButton, ListItemIcon, ListItemText, Toolbar, Typography, useMediaQuery, useTheme,
} from '@mui/material';
import { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useSession } from '../auth/session';
import { useInstitution } from './InstitutionContext';

const WIDTH = 248;
const TEACHING = ['LECTURER', 'ASSESSOR'];
const EXAMS = ['INSTITUTION_ADMIN', 'DEPUTY_PRINCIPAL_ACADEMIC', 'HEAD_OF_DEPARTMENT', 'EXAMINATIONS_OFFICER', 'MANAGEMENT_VIEWER'];
const CLEARANCE = ['INSTITUTION_ADMIN', 'REGISTRAR', 'DEPUTY_PRINCIPAL_ACADEMIC', 'MANAGEMENT_VIEWER', 'HEAD_OF_DEPARTMENT', 'CLEARANCE_OFFICER', 'HOSTEL_WARDEN'];
const NAV: { to: string; label: string; icon: JSX.Element; end?: boolean; roles?: string[]; teaching?: boolean; officer?: boolean; officerRoles?: string[] }[] = [
  { to: '/staff', label: 'Dashboard', icon: <DashboardIcon />, end: true },
  { to: '/staff/applications', label: 'Applications', icon: <AssignmentIcon /> },
  { to: '/staff/windows', label: 'Admission windows', icon: <EventIcon /> },
  { to: '/staff/students', label: 'Students', icon: <BadgeIcon /> },
  { to: '/staff/semester-registrations', label: 'Semester registration', icon: <HowToRegIcon /> },
  { to: '/staff/curricula', label: 'Curricula', icon: <CurriculumIcon /> },
  { to: '/staff/calendar', label: 'Academic calendar', icon: <CalendarIcon /> },
  { to: '/staff/teaching', label: 'Teaching & attendance', icon: <TeachingIcon />, roles: [...EXAMS, ...TEACHING, 'PROGRAMME_COORDINATOR'], teaching: true },
  { to: '/staff/marks', label: 'Marks', icon: <MarksIcon />, roles: [...EXAMS, ...TEACHING, 'PROGRAMME_COORDINATOR'], teaching: true },
  { to: '/staff/eligibility', label: 'Exam eligibility', icon: <EligibleIcon />, roles: EXAMS },
  { to: '/staff/results', label: 'Results', icon: <ResultsIcon />, roles: EXAMS },
  { to: '/staff/amendments', label: 'Result amendments', icon: <AmendIcon />, roles: ['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER', 'DEPUTY_PRINCIPAL_ACADEMIC'] },
  { to: '/staff/regulations', label: 'Exam regulations', icon: <RulesIcon />, roles: EXAMS },
  { to: '/staff/clearance', label: 'Clearance', icon: <ClearanceIcon />, roles: [...CLEARANCE], officer: true },
  { to: '/staff/graduations', label: 'Graduation', icon: <GraduationIcon />, roles: ['INSTITUTION_ADMIN', 'REGISTRAR', 'DEPUTY_PRINCIPAL_ACADEMIC', 'MANAGEMENT_VIEWER'] },
  { to: '/staff/hostels', label: 'Hostels', icon: <HostelIcon />, roles: ['INSTITUTION_ADMIN', 'HOSTEL_WARDEN', 'REGISTRAR', 'BURSAR', 'ACCOUNTANT', 'MANAGEMENT_VIEWER'], officer: true },
  { to: '/staff/ipt', label: 'Industrial training', icon: <IptIcon />, roles: ['INSTITUTION_ADMIN', 'IPT_COORDINATOR', 'REGISTRAR', 'DEPUTY_PRINCIPAL_ACADEMIC', 'HEAD_OF_DEPARTMENT', 'EXAMINATIONS_OFFICER', 'MANAGEMENT_VIEWER', 'PROGRAMME_COORDINATOR', ...TEACHING], teaching: true, officer: true },
  { to: '/staff/finance', label: 'Fees', icon: <PaymentsIcon /> },
  { to: '/staff/notices', label: 'Notices', icon: <CampaignIcon /> },
  { to: '/staff/messages', label: 'Messages', icon: <SmsIcon /> },
  { to: '/staff/reports', label: 'Reports', icon: <AssessmentIcon />, officerRoles: ['HOSTEL_WARDEN', 'IPT_COORDINATOR'] },
  { to: '/staff/programmes', label: 'Programmes', icon: <SchoolIcon /> },
];

/** Back-office layout for institution staff. */
export default function StaffLayout() {
  const inst = useInstitution();
  const theme = useTheme();
  const desktop = useMediaQuery(theme.breakpoints.up('md'));
  const [open, setOpen] = useState(false);
  const { user, signOut } = useSession();
  const navigate = useNavigate();
  const me = user('staff');
  const roles = me?.roles ?? [];
  // Lecturers and assessors only see their teaching and marks; other menu items follow the roles that use them.
  const teachingOnly = roles.length > 0 && roles.every((r) => TEACHING.includes(r));
  // Clearance officers, wardens and the IPT coordinator only see the office they run.
  const officerOnly = roles.length > 0 && roles.every((r) => ['CLEARANCE_OFFICER', 'HOSTEL_WARDEN', 'IPT_COORDINATOR'].includes(r));
  const nav = NAV.filter((n) => (teachingOnly ? n.teaching : officerOnly ? (n.officer && (!n.roles || n.roles.some((r) => roles.includes(r)))) || !!n.officerRoles?.some((r) => roles.includes(r)) : !n.roles || n.roles.some((r) => roles.includes(r))));

  const drawer = (
    <Box sx={{ height: '100%', display: 'flex', flexDirection: 'column', bgcolor: '#0F1B2D', color: '#E6EBF2' }}>
      <Box sx={{ p: 2.5 }}>
        <Typography sx={{ fontFamily: 'Sora', fontWeight: 700 }}>{inst.shortName ?? inst.name}</Typography>
        <Typography variant="caption" sx={{ opacity: 0.7 }}>CIMS · Back-office</Typography>
      </Box>
      <Divider sx={{ borderColor: 'rgba(255,255,255,0.08)' }} />
      <List sx={{ px: 1, flexGrow: 1 }}>
        {nav.map((n) => (
          <ListItemButton
            key={n.to}
            component={NavLink}
            to={n.to}
            end={n.end}
            onClick={() => setOpen(false)}
            sx={{
              borderRadius: 2, my: 0.25, color: 'inherit',
              '&.active': { bgcolor: 'rgba(255,255,255,0.10)', color: '#fff' },
              '& .MuiListItemIcon-root': { color: 'inherit', minWidth: 38, opacity: 0.85 },
            }}
          >
            <ListItemIcon>{n.icon}</ListItemIcon>
            <ListItemText primary={n.label} />
          </ListItemButton>
        ))}
      </List>
      <Box sx={{ p: 2 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{me?.fullName}</Typography>
        <Typography variant="caption" sx={{ opacity: 0.7 }}>{me?.roles.map((r) => r.replace(/_/g, ' ').toLowerCase()).join(', ')}</Typography>
        <ListItemButton sx={{ mt: 1, borderRadius: 2, px: 1 }} onClick={() => { signOut('staff'); navigate('/staff/login'); }}>
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
        sx={{ width: WIDTH, flexShrink: 0, '& .MuiDrawer-paper': { width: WIDTH, border: 0 } }}
      >
        {drawer}
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, p: { xs: 2, md: 4 }, mt: { xs: 8, md: 0 }, minWidth: 0 }}>
        <Outlet />
      </Box>
    </Box>
  );
}
