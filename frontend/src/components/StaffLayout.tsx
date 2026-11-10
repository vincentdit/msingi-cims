import SubscriptionIcon from '@mui/icons-material/ReceiptLongOutlined';
import SupportAgentIcon from '@mui/icons-material/SupportAgent';
import HelpIcon from '@mui/icons-material/HelpOutline';
import AssignmentIcon from '@mui/icons-material/AssignmentInd';
import BadgeIcon from '@mui/icons-material/Badge';
import CampaignIcon from '@mui/icons-material/Campaign';
import SwapHorizIcon from '@mui/icons-material/SwapHoriz';
import MoveUpIcon from '@mui/icons-material/MoveUp';
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
import HealthIcon from '@mui/icons-material/HealthAndSafety';
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
import LibraryIcon from '@mui/icons-material/LocalLibraryOutlined';
import LeaveIcon from '@mui/icons-material/BeachAccessOutlined';
import HrIcon from '@mui/icons-material/BadgeOutlined';
import AssetsIcon from '@mui/icons-material/Inventory2Outlined';
import { Badge } from '@mui/material';
import KeyIcon from '@mui/icons-material/Key';
import SecurityIcon from '@mui/icons-material/Security';
import LetterIcon from '@mui/icons-material/HistoryEdu';
import IntegrationIcon from '@mui/icons-material/Hub';
import AlumniIcon from '@mui/icons-material/Diversity3';
import ReturnsIcon from '@mui/icons-material/Outbox';
import VerifiedIcon from '@mui/icons-material/VerifiedUser';
import ImportIcon from '@mui/icons-material/DriveFolderUpload';
import TimetableIcon from '@mui/icons-material/ViewWeek';
import ExamTimetableIcon from '@mui/icons-material/EventNote';
import AlmanacIcon from '@mui/icons-material/CalendarMonth';
import OrganisationIcon from '@mui/icons-material/AccountTree';
import PayrollIcon from '@mui/icons-material/AccountBalanceWalletOutlined';
import PayslipIcon from '@mui/icons-material/ReceiptLongOutlined';
import QualityIcon from '@mui/icons-material/ThumbsUpDownOutlined';
import WelfareIcon from '@mui/icons-material/VolunteerActivismOutlined';
import ProcurementIcon from '@mui/icons-material/ShoppingCartOutlined';
import IdleSignOut from './IdleSignOut';
import { disabledLinkStyles } from './StaffLink';
import { Alert, Button, ToggleButton, ToggleButtonGroup } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { setLanguage } from '../i18n';
import {
  AppBar, Box, Divider, Drawer, IconButton, List, ListItemButton, ListItemIcon, ListItemText, Toolbar, Typography, useMediaQuery, useTheme,
} from '@mui/material';
import { useCallback, useEffect, useRef, useState } from 'react';
import NextIcon from '@mui/icons-material/NavigateNext';
import { Breadcrumbs, Link as MuiLink } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import { Navigate, NavLink, Outlet, Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import { can, canOpen, ROLE_LABEL, type PageKey } from '../auth/access';
import { useSession } from '../auth/session';
import { useInstitution } from './InstitutionContext';

const WIDTH = 248;
/** The bright mark of the page that is open: readable on the dark menu whatever the institution's colours. */
const MARK = '#7CC4FF';

/** The menu entry of a path: the longest menu link the path is on or below ("/staff/hr/12" is under Human resources). */
function entryFor(groups: { title?: string; items: Item[] }[], path: string) {
  let best: { group?: string; item: Item } | undefined;
  for (const g of groups) for (const i of g.items) {
    const on = path === i.to || (!i.end && path.startsWith(`${i.to}/`));
    if (on && (!best || i.to.length > best.item.to.length)) best = { group: g.title, item: i };
  }
  return best;
}

/** One look for every menu link: the open page gets a lighter band, a coloured bar on its left, bold text and a coloured icon. */
const linkSx = {
  borderRadius: 2, my: 0.25, py: 0.75, color: 'inherit', position: 'relative',
  '&:hover': { bgcolor: 'rgba(255,255,255,0.06)' },
  '&.active': {
    bgcolor: 'rgba(124,196,255,0.18)', color: '#fff',
    '&::before': { content: '""', position: 'absolute', left: -8, top: 6, bottom: 6, width: 4, borderRadius: 2, bgcolor: MARK },
    '& .MuiListItemIcon-root': { color: MARK, opacity: 1 },
    '& .MuiListItemText-primary': { fontWeight: 700 },
  },
  '&.active:hover': { bgcolor: 'rgba(124,196,255,0.24)' },
  '&:focus-visible': { outline: `2px solid ${MARK}`, outlineOffset: -2 },
} as const;

type Item = { page: PageKey; to: string; label: string; icon: JSX.Element; end?: boolean };
const GROUPS: { title?: string; items: Item[] }[] = [
  { items: [
    { page: 'dashboard', to: '/staff', label: 'Dashboard', icon: <DashboardIcon />, end: true },
    { page: 'approvals', to: '/staff/approvals', label: 'Approvals', icon: <ApprovalsIcon /> },
    { page: 'reports', to: '/staff/reports', label: 'Reports', icon: <AssessmentIcon /> },
  ] },
  { title: 'Institution', items: [
    { page: 'almanac', to: '/staff/almanac', label: 'Almanac', icon: <AlmanacIcon /> },
    { page: 'organisation', to: '/staff/organisation', label: 'Organisation structure', icon: <OrganisationIcon /> },
  ] },
  { title: 'Admissions', items: [
    { page: 'windows', to: '/staff/windows', label: 'Admission windows', icon: <EventIcon /> },
    { page: 'applications', to: '/staff/applications', label: 'Applicants', icon: <AssignmentIcon /> },
    { page: 'programmes', to: '/staff/programmes', label: 'Programmes', icon: <SchoolIcon /> },
  ] },
  { title: 'Registry', items: [
    { page: 'students', to: '/staff/students', label: 'Students', icon: <BadgeIcon /> },
    { page: 'statusChanges', to: '/staff/status-changes', label: 'Status changes', icon: <SwapHorizIcon /> },
    { page: 'transfers', to: '/staff/transfers', label: 'Transfers', icon: <MoveUpIcon /> },
    { page: 'semesterRegistrations', to: '/staff/semester-registrations', label: 'Semester registration', icon: <HowToRegIcon /> },
    { page: 'healthInsurance', to: '/staff/health-insurance', label: 'Health insurance', icon: <HealthIcon /> },
    { page: 'graduations', to: '/staff/graduations', label: 'Graduation', icon: <GraduationIcon /> },
    { page: 'alumni', to: '/staff/alumni', label: 'Alumni', icon: <AlumniIcon /> },
    { page: 'nactvetReturns', to: '/staff/nactvet-returns', label: 'NACTVET returns', icon: <ReturnsIcon /> },
    { page: 'verifications', to: '/staff/verifications', label: 'Document checks', icon: <VerifiedIcon /> },
  ] },
  { title: 'Teaching', items: [
    { page: 'myTeaching', to: '/staff/my-teaching', label: 'My teaching', icon: <MyTeachingIcon /> },
    { page: 'timetable', to: '/staff/timetable', label: 'Timetable', icon: <TimetableIcon /> },
    { page: 'calendar', to: '/staff/calendar', label: 'Academic calendar', icon: <CalendarIcon /> },
    { page: 'curricula', to: '/staff/curricula', label: 'Curricula', icon: <CurriculumIcon /> },
    { page: 'teaching', to: '/staff/teaching', label: 'Teaching & attendance', icon: <TeachingIcon /> },
    { page: 'marks', to: '/staff/marks', label: 'Marks', icon: <MarksIcon /> },
    { page: 'competence', to: '/staff/competence', label: 'Competence (NVA)', icon: <CompetenceIcon /> },
    { page: 'quality', to: '/staff/quality', label: 'Quality assurance', icon: <QualityIcon /> },
  ] },
  { title: 'Examinations', items: [
    { page: 'examsOffice', to: '/staff/exams-office', label: 'Examinations office', icon: <OfficeIcon /> },
    { page: 'examTimetable', to: '/staff/exam-timetable', label: 'Exam timetable', icon: <ExamTimetableIcon /> },
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
    { page: 'library', to: '/staff/library', label: 'Library', icon: <LibraryIcon /> },
    { page: 'welfare', to: '/staff/welfare', label: 'Discipline and welfare', icon: <WelfareIcon /> },
  ] },
  { title: 'Staff and property', items: [
    { page: 'leave', to: '/staff/leave', label: 'Leave', icon: <LeaveIcon /> },
    { page: 'payslips', to: '/staff/payslips', label: 'My payslips', icon: <PayslipIcon /> },
    { page: 'hr', to: '/staff/hr', label: 'Human resources', icon: <HrIcon /> },
    { page: 'payroll', to: '/staff/payroll', label: 'Payroll', icon: <PayrollIcon /> },
    { page: 'procurement', to: '/staff/procurement', label: 'Procurement', icon: <ProcurementIcon /> },
    { page: 'assets', to: '/staff/assets', label: 'Assets and stores', icon: <AssetsIcon /> },
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
  { title: 'Help', items: [
    { page: 'helpdesk', to: '/staff/helpdesk', label: 'Helpdesk', icon: <SupportAgentIcon /> },
    { page: 'help', to: '/staff/help', label: 'Ask for help', icon: <HelpIcon /> },
  ] },
  { title: 'Administration', items: [
    { page: 'users', to: '/staff/users', label: 'Users & roles', icon: <UsersIcon /> },
    { page: 'audit', to: '/staff/audit', label: 'Audit trail', icon: <AuditIcon /> },
    { page: 'imports', to: '/staff/imports', label: 'Data import', icon: <ImportIcon /> },
    { page: 'letters', to: '/staff/letters', label: 'Letter wording', icon: <LetterIcon /> },
    { page: 'integrations', to: '/staff/integrations', label: 'Integrations', icon: <IntegrationIcon /> },
    { page: 'security', to: '/staff/security', label: 'Security & privacy', icon: <SecurityIcon /> },
    { page: 'institution', to: '/staff/institution', label: 'Institution', icon: <InstitutionIcon /> },
    { page: 'subscription', to: '/staff/msingi-subscription', label: 'Msingi subscription', icon: <SubscriptionIcon /> },
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
  const only = (r: string) => !!roles?.length && roles.every((x) => x === r);
  if (only('DEAN_OF_STUDENTS')) return '/staff/welfare';
  if (only('QUALITY_ASSURANCE_OFFICER')) return '/staff/quality';
  if (only('PROCUREMENT_OFFICER')) return '/staff/procurement';
  if (only('LIBRARIAN')) return '/staff/library';
  if (only('HR_OFFICER')) return '/staff/hr';
  if (only('STORES_OFFICER')) return '/staff/assets';
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
  // English or Kiswahili (BR-40): kept with the account, so it follows the person to another device
  const { i18n } = useTranslation();
  const lang = i18n.language === 'sw' ? 'sw' : 'en';
  const chooseLanguage = (v: 'en' | 'sw' | null) => {
    if (!v || v === lang) return;
    setLanguage(v);
    api('/api/account/language', { realm: 'staff', method: 'PUT', body: { language: v } }).catch(() => { /* kept on this device anyway */ });
  };
  // a Head of Department or Programme Coordinator limited to part of the institution sees it named under their roles
  const scope = useQuery({ queryKey: ['staff', 'scope', me?.id], enabled: !!me && !me.mustChangePassword, staleTime: 300_000,
    queryFn: () => api<{ all: boolean; departments: string[]; programmes: string[] }>('/api/auth/scope', { realm: 'staff' }) }).data;
  const roles = me?.roles ?? [];
  const location = useLocation();
  // how many decisions wait for this person, shown on the Approvals menu item
  const waiting = useQuery({ queryKey: ['staff', 'approvals', 'count'], enabled: can(roles, 'approvals') && !me?.mustChangePassword,
    refetchInterval: 60_000, queryFn: () => api<{ actionable: number }>('/api/approvals/count', { realm: 'staff' }) }).data?.actionable ?? 0;
  // requests waiting in the person's offices (not yet assigned, or assigned to them), on the Helpdesk menu item
  const helpdesk = useQuery({ queryKey: ['helpdesk', 'counts'], enabled: can(roles, 'helpdesk') && !me?.mustChangePassword,
    refetchInterval: 60_000, queryFn: () => api<{ mine: number; unassigned: number }>('/api/helpdesk/counts', { realm: 'staff' }) }).data;
  const helpdeskWaiting = (helpdesk?.mine ?? 0) + (helpdesk?.unassigned ?? 0);
  const groups = GROUPS.map((g) => ({ ...g, items: g.items.filter((i) => can(roles, i.page)) })).filter((g) => g.items.length);
  const idleOut = useCallback(() => { signOut('staff'); navigate('/staff/login?idle=1'); }, [signOut, navigate]);
  const onPassword = location.pathname.startsWith('/staff/password');
  const here = onPassword ? { group: undefined, item: { page: 'dashboard' as PageKey, to: '/staff/password', label: 'My account', icon: <KeyIcon /> } }
    : entryFor(groups, location.pathname);
  const deeper = !!here && location.pathname !== here.item.to;
  // the open page stays in sight in a long menu, and the browser tab is named after it
  const navRef = useRef<HTMLElement>(null);
  useEffect(() => {
    // the phone menu is drawn only as it slides open, so look for the link a moment later
    const t = window.setTimeout(() => {
      const el = navRef.current?.querySelector<HTMLElement>('a.active');
      el?.scrollIntoView?.({ block: 'center' });
    }, desktop ? 0 : 300);
    return () => window.clearTimeout(t);
  }, [location.pathname, open, desktop]);
  useEffect(() => {
    document.title = here ? `${here.item.label} · ${inst.shortName ?? inst.name}` : (inst.shortName ?? inst.name);
  }, [here?.item.label, inst.shortName, inst.name]);
  if ((me?.mustChangePassword || me?.mfaSetupRequired) && !onPassword) return <Navigate to="/staff/password" replace />;
  if (location.pathname === '/staff' && !can(roles, 'dashboard')) return <Navigate to={homeFor(roles)} replace />;
  const allowed = onPassword || canOpen(roles, location.pathname);

  const drawer = (
    <Box sx={{ minHeight: '100%', display: 'flex', flexDirection: 'column', bgcolor: '#0F1B2D', color: '#E6EBF2' }}>
      <Box sx={{ p: 2.5 }}>
        <Typography sx={{ fontFamily: 'Sora', fontWeight: 700 }}>{inst.shortName ?? inst.name}</Typography>
        <Typography variant="caption" sx={{ opacity: 0.7 }}>CIMS · Back-office</Typography>
      </Box>
      <Divider sx={{ borderColor: 'rgba(255,255,255,0.08)' }} />
      <List component="nav" ref={navRef} aria-label="Main menu" sx={{ px: 1, flexGrow: 1 }} dense>
        {groups.map((g, gi) => {
          const openGroup = !!g.title && here?.group === g.title;
          return (
          <Box key={g.title ?? gi} role="group" aria-label={g.title ?? undefined} sx={{ mt: gi ? 1.5 : 0.5 }}>
            {g.title && (
              <Typography component="div" sx={{ px: 1.5, pb: 0.5, fontSize: 12, fontWeight: openGroup ? 700 : 600, display: 'flex', alignItems: 'center', gap: 0.75,
                color: openGroup ? MARK : 'rgba(230,235,242,0.55)', textTransform: openGroup ? 'uppercase' : 'none', letterSpacing: openGroup ? 0.6 : 0 }}>
                {openGroup && <Box component="span" aria-hidden sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: MARK }} />}
                <span>{g.title}</span>
              </Typography>
            )}
            <Box sx={{ p: 0, m: 0 }}>
              {g.items.map((n) => (
                <ListItemButton
                  key={n.to}
                  component={NavLink}
                  to={n.to}
                  end={n.end}
                  onClick={() => setOpen(false)}
                  sx={{ ...linkSx, '& .MuiListItemIcon-root': { color: 'inherit', minWidth: 38, opacity: 0.85 } }}
                >
                  <ListItemIcon>{n.icon}</ListItemIcon>
                  <ListItemText primary={n.label} primaryTypographyProps={{ fontSize: 15 }} />
                  {n.page === 'approvals' && waiting > 0 && <Badge badgeContent={waiting} color="warning" sx={{ mr: 1.5 }} aria-label={`${waiting} waiting`} />}
                  {n.page === 'helpdesk' && helpdeskWaiting > 0 && <Badge badgeContent={helpdeskWaiting} color="warning" sx={{ mr: 1.5 }} aria-label={`${helpdeskWaiting} waiting`} />}
                </ListItemButton>
              ))}
            </Box>
          </Box>
          );
        })}
      </List>
      <Box sx={{ p: 2 }}>
        <ToggleButtonGroup size="small" exclusive value={lang} onChange={(_, v) => chooseLanguage(v)} aria-label="Language"
          sx={{ mb: 1.5, '& .MuiToggleButton-root': { color: 'rgba(230,235,242,0.75)', borderColor: 'rgba(255,255,255,0.2)', px: 1.5, py: 0.25, textTransform: 'none' },
            '& .Mui-selected': { color: '#fff !important', bgcolor: 'rgba(255,255,255,0.16) !important' } }}>
          <ToggleButton value="en" lang="en">English</ToggleButton>
          <ToggleButton value="sw" lang="sw">Kiswahili</ToggleButton>
        </ToggleButtonGroup>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{me?.fullName}</Typography>
        <Typography variant="caption" sx={{ opacity: 0.7 }}>{me?.roles.map((r) => ROLE_LABEL[r] ?? r).join(', ')}</Typography>
        {scope && !scope.all && (
          <Typography variant="caption" component="div" sx={{ mt: 0.5, px: 1, py: 0.5, borderRadius: 1, bgcolor: 'rgba(255,255,255,0.08)' }}>
            {scope.departments.length ? `Department: ${scope.departments.join(', ')}` : `Programme: ${scope.programmes.join(', ')}`}
          </Typography>
        )}
        <ListItemButton component={NavLink} to="/staff/password" sx={{ ...linkSx, mt: 1, px: 1 }} onClick={() => setOpen(false)}>
          <ListItemIcon sx={{ color: 'inherit', minWidth: 34 }}><KeyIcon fontSize="small" /></ListItemIcon>
          <ListItemText primary="My account" />
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
      {disabledLinkStyles}
      <IdleSignOut kind="staff" onSignOut={idleOut} />
      {!desktop && (
        <AppBar position="fixed" color="inherit" elevation={0} sx={{ borderBottom: '1px solid', borderColor: 'divider' }}>
          <Toolbar>
            <IconButton edge="start" onClick={() => setOpen(true)} aria-label="menu"><MenuIcon /></IconButton>
            <Box sx={{ ml: 1, minWidth: 0 }}>
              <Typography noWrap sx={{ fontFamily: 'Sora', fontWeight: 700, lineHeight: 1.2 }}>{here?.item.label ?? inst.shortName ?? inst.name}</Typography>
              {here && <Typography noWrap variant="caption" color="text.secondary" display="block">{here.group ?? (inst.shortName ?? inst.name)}</Typography>}
            </Box>
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
        {/* a new language redraws the page in it (keyed), without reloading data */}
        {/* where you are: the menu section and page, with a way back to the list page from a record opened from it */}
        {allowed && here && desktop && (
          <Breadcrumbs separator={<NextIcon fontSize="small" />} aria-label="You are here" sx={{ mb: 2, fontSize: 14, color: 'text.secondary' }}>
            {here.group && <span>{here.group}</span>}
            {deeper ? (
              <MuiLink component={RouterLink} to={here.item.to} underline="hover" color="inherit" sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                <span>{here.item.label}</span>
              </MuiLink>
            ) : (
              <Typography component="span" aria-current="page" sx={{ fontSize: 14, fontWeight: 600, color: 'text.primary' }}>{here.item.label}</Typography>
            )}
          </Breadcrumbs>
        )}
        {allowed ? <Box key={lang}><Outlet /></Box> : (
          <Alert severity="info" sx={{ maxWidth: 640 }} action={<Button color="inherit" onClick={() => navigate(homeFor(roles))}>Go to my pages</Button>}>
            This page is not part of your role. Ask the institution administrator if you need it.
          </Alert>
        )}
      </Box>
    </Box>
  );
}
