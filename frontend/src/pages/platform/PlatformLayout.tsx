import ApartmentIcon from '@mui/icons-material/Apartment';
import AccountBalanceIcon from '@mui/icons-material/AccountBalance';
import DashboardIcon from '@mui/icons-material/SpaceDashboard';
import HistoryIcon from '@mui/icons-material/History';
import KeyIcon from '@mui/icons-material/Key';
import LogoutIcon from '@mui/icons-material/Logout';
import MenuIcon from '@mui/icons-material/Menu';
import MonitorHeartIcon from '@mui/icons-material/MonitorHeart';
import PeopleIcon from '@mui/icons-material/SupervisorAccount';
import SupportAgentIcon from '@mui/icons-material/SupportAgent';
import ReceiptIcon from '@mui/icons-material/ReceiptLongOutlined';
import {
  AppBar, Badge, Box, Divider, Drawer, IconButton, List, ListItemButton, ListItemIcon, ListItemText, Toolbar, Typography, useMediaQuery, useTheme,
} from '@mui/material';
import { useState } from 'react';
import { Navigate, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { MSINGI_ACCENT } from '../../components/InstitutionContext';
import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/client';
import { platform, usePlatformSession } from './shared';

const WIDTH = 248;
const BG = '#0B2B2A';

const NAV = [
  { to: '/platform', label: 'Overview', icon: <DashboardIcon />, end: true },
  { to: '/platform/institutions', label: 'Institutions', icon: <ApartmentIcon /> },
  { to: '/platform/nactvet', label: 'NACTVET catalogue', icon: <AccountBalanceIcon /> },
  { to: '/platform/billing', label: 'Billing', icon: <ReceiptIcon /> },
  { to: '/platform/support', label: 'Support', icon: <SupportAgentIcon /> },
  { to: '/platform/integrations', label: 'Integration health', icon: <MonitorHeartIcon /> },
  { to: '/platform/users', label: 'Platform users', icon: <PeopleIcon /> },
  { to: '/platform/audit', label: 'Audit trail', icon: <HistoryIcon /> },
];

/** The Msingi platform console: for Msingi Technologies staff, across all institutions. */
export default function PlatformLayout() {
  const theme = useTheme();
  const desktop = useMediaQuery(theme.breakpoints.up('md'));
  const [open, setOpen] = useState(false);
  const { me, signOut } = usePlatformSession();
  // support tickets waiting for Msingi (not yet assigned, or assigned to this person)
  const counts = useQuery({ queryKey: ['platform', 'support', 'counts'], enabled: !!me && !me.mustChangePassword, refetchInterval: 60_000,
    queryFn: () => api<{ mine: number; unassigned: number }>('/api/platform/support/counts', platform) }).data;
  const waiting = (counts?.mine ?? 0) + (counts?.unassigned ?? 0);
  const navigate = useNavigate();
  const location = useLocation();
  const onPassword = location.pathname.startsWith('/platform/password');
  if (me?.mustChangePassword && !onPassword) return <Navigate to="/platform/password" replace />;

  const brand = (
    <Box>
      <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, letterSpacing: 0.2 }}>
        Msingi <Box component="span" sx={{ color: MSINGI_ACCENT }}>CIMS</Box>
      </Typography>
      <Typography variant="caption" sx={{ opacity: 0.7 }}>Platform console</Typography>
    </Box>
  );

  const drawer = (
    <Box sx={{ minHeight: '100%', display: 'flex', flexDirection: 'column', bgcolor: BG, color: '#E3EFEE' }}>
      <Box sx={{ p: 2.5 }}>{brand}</Box>
      <Divider sx={{ borderColor: 'rgba(255,255,255,0.08)' }} />
      <List component="nav" aria-label="Main menu" sx={{ px: 1, pt: 1, flexGrow: 1 }} dense>
        {NAV.map((n) => (
          <ListItemButton key={n.to} component={NavLink} to={n.to} end={n.end} onClick={() => setOpen(false)}
            sx={{
              borderRadius: 2, my: 0.25, py: 0.75, color: 'inherit',
              '&.active': { bgcolor: 'rgba(255,255,255,0.10)', color: '#fff', boxShadow: `inset 3px 0 0 ${MSINGI_ACCENT}` },
              '& .MuiListItemIcon-root': { color: 'inherit', minWidth: 38, opacity: 0.85 },
            }}>
            <ListItemIcon>{n.icon}</ListItemIcon>
            <ListItemText primary={n.label} primaryTypographyProps={{ fontSize: 15 }} />
            {n.to === '/platform/support' && waiting > 0 && <Badge badgeContent={waiting} color="warning" sx={{ mr: 1.5 }} aria-label={`${waiting} waiting`} />}
          </ListItemButton>
        ))}
      </List>
      <Box sx={{ p: 2 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{me?.fullName}</Typography>
        <Typography variant="caption" sx={{ opacity: 0.7 }}>Msingi Technologies · {me?.username}</Typography>
        <ListItemButton component={NavLink} to="/platform/password" sx={{ mt: 1, borderRadius: 2, px: 1, color: 'inherit' }} onClick={() => setOpen(false)}>
          <ListItemIcon sx={{ color: 'inherit', minWidth: 34 }}><KeyIcon fontSize="small" /></ListItemIcon>
          <ListItemText primary="Change password" />
        </ListItemButton>
        <ListItemButton sx={{ borderRadius: 2, px: 1 }} onClick={() => { signOut(); navigate('/platform/login'); }}>
          <ListItemIcon sx={{ color: 'inherit', minWidth: 34 }}><LogoutIcon fontSize="small" /></ListItemIcon>
          <ListItemText primary="Sign out" />
        </ListItemButton>
      </Box>
    </Box>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      {!desktop && (
        <AppBar position="fixed" elevation={0} sx={{ bgcolor: BG }}>
          <Toolbar>
            <IconButton edge="start" color="inherit" onClick={() => setOpen(true)} aria-label="menu"><MenuIcon /></IconButton>
            <Box sx={{ ml: 1 }}>{brand}</Box>
          </Toolbar>
        </AppBar>
      )}
      <Drawer variant={desktop ? 'permanent' : 'temporary'} open={desktop || open} onClose={() => setOpen(false)}
        sx={{ width: WIDTH, flexShrink: 0, '& .MuiDrawer-paper': { width: WIDTH, border: 0, bgcolor: BG, color: '#E3EFEE' } }}>
        {drawer}
      </Drawer>
      <Box component="main" sx={{ flexGrow: 1, p: { xs: 2, md: 4 }, mt: { xs: 8, md: 0 }, minWidth: 0 }}>
        <Outlet />
      </Box>
    </Box>
  );
}
