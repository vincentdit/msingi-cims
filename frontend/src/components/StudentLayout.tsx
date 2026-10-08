import CampaignIcon from '@mui/icons-material/CampaignOutlined';
import HomeIcon from '@mui/icons-material/HomeOutlined';
import RegisterIcon from '@mui/icons-material/HowToRegOutlined';
import LogoutIcon from '@mui/icons-material/Logout';
import PaymentsIcon from '@mui/icons-material/PaymentsOutlined';
import PersonIcon from '@mui/icons-material/PersonOutline';
import {
  AppBar, Avatar, Badge, BottomNavigation, BottomNavigationAction, Box, Button, Container, IconButton, Paper, Stack, Tab, Tabs,
  ToggleButton, ToggleButtonGroup, Toolbar, Tooltip, Typography, useMediaQuery, useTheme,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import type { StudentDashboard } from '../api/types';
import { useSession } from '../auth/session';
import { setLanguage } from '../i18n';
import { useInstitution } from './InstitutionContext';

const TABS = [
  { to: '/student', key: 'navHome', icon: <HomeIcon /> },
  { to: '/student/register', key: 'navRegister', icon: <RegisterIcon /> },
  { to: '/student/fees', key: 'navFees', icon: <PaymentsIcon /> },
  { to: '/student/notices', key: 'navNotices', icon: <CampaignIcon /> },
  { to: '/student/profile', key: 'navProfile', icon: <PersonIcon /> },
];

/** Student portal shell (FR-STU-01): mobile-first with a bottom navigation bar, top tabs on wide screens. */
export default function StudentLayout() {
  const inst = useInstitution();
  const { t, i18n } = useTranslation();
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up('md'));
  const { signOut } = useSession();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const dash = useQuery({ queryKey: ['student', 'dashboard'], queryFn: () => api<StudentDashboard>('/api/student/dashboard', { realm: 'student' }) });
  const current = [...TABS].reverse().find((x) => pathname === x.to || pathname.startsWith(`${x.to}/`))?.to ?? '/student';
  const unread = dash.data?.unreadNotices ?? 0;
  const label = (key: string) => (key === 'navNotices' && unread
    ? <Badge color="error" badgeContent={unread} sx={{ '& .MuiBadge-badge': { right: -10 } }}>{t(`student.${key}`)}</Badge>
    : t(`student.${key}`));

  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'background.default', pb: wide ? 4 : 9 }}>
      <AppBar position="sticky" color="inherit" elevation={0} sx={{ borderBottom: '1px solid', borderColor: 'divider' }}>
        <Toolbar sx={{ gap: 1.5 }}>
          <Avatar sx={{ bgcolor: 'primary.main', fontFamily: 'Sora', fontWeight: 700, width: 36, height: 36, fontSize: 11 }}>
            {(inst.shortName ?? inst.name).slice(0, 4)}
          </Avatar>
          <Box sx={{ minWidth: 0, flexGrow: 1 }}>
            <Typography variant="subtitle1" noWrap sx={{ fontFamily: 'Sora', fontWeight: 700, lineHeight: 1.2 }}>{inst.shortName ?? inst.name}</Typography>
            <Typography variant="caption" color="text.secondary" noWrap display="block">{t('student.portal')}</Typography>
          </Box>
          <ToggleButtonGroup size="small" exclusive value={i18n.language} onChange={(_, v) => v && setLanguage(v)} aria-label="language">
            <ToggleButton value="en" sx={{ px: 1, py: 0.25 }}>EN</ToggleButton>
            <ToggleButton value="sw" sx={{ px: 1, py: 0.25 }}>SW</ToggleButton>
          </ToggleButtonGroup>
          {wide ? (
            <Button variant="outlined" onClick={() => { signOut('student'); navigate('/student/login'); }}>{t('nav.signOut')}</Button>
          ) : (
            <Tooltip title={t('nav.signOut')}>
              <IconButton aria-label={t('nav.signOut')} onClick={() => { signOut('student'); navigate('/student/login'); }}><LogoutIcon /></IconButton>
            </Tooltip>
          )}
        </Toolbar>
        {wide && (
          <Container maxWidth="md">
            <Tabs value={current} onChange={(_, v) => navigate(v)}>
              {TABS.map((x) => <Tab key={x.to} value={x.to} label={label(x.key)} icon={x.icon} iconPosition="start" sx={{ minHeight: 48 }} />)}
            </Tabs>
          </Container>
        )}
      </AppBar>
      <Container maxWidth="md" sx={{ pt: { xs: 2, md: 3 } }}>
        <Outlet />
      </Container>
      {!wide && (
        <Paper elevation={0} sx={{ position: 'fixed', bottom: 0, left: 0, right: 0, borderTop: '1px solid', borderColor: 'divider', zIndex: 10,
          pb: 'env(safe-area-inset-bottom)' }}>
          <BottomNavigation showLabels value={current} onChange={(_, v) => navigate(v)}>
            {TABS.map((x) => (
              <BottomNavigationAction key={x.to} value={x.to} label={t(`student.${x.key}`)}
                icon={x.key === 'navNotices' ? <Badge color="error" badgeContent={unread}>{x.icon}</Badge> : x.icon} />
            ))}
          </BottomNavigation>
        </Paper>
      )}
      <Stack sx={{ display: 'none' }} />
    </Box>
  );
}
