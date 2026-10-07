import AssignmentIcon from '@mui/icons-material/AssignmentInd';
import BadgeIcon from '@mui/icons-material/Badge';
import PaymentsIcon from '@mui/icons-material/Payments';
import DashboardIcon from '@mui/icons-material/SpaceDashboard';
import EventIcon from '@mui/icons-material/EventAvailable';
import LogoutIcon from '@mui/icons-material/Logout';
import MenuIcon from '@mui/icons-material/Menu';
import SchoolIcon from '@mui/icons-material/School';
import {
  AppBar, Box, Divider, Drawer, IconButton, List, ListItemButton, ListItemIcon, ListItemText, Toolbar, Typography, useMediaQuery, useTheme,
} from '@mui/material';
import { useState } from 'react';
import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { useSession } from '../auth/session';
import { useInstitution } from './InstitutionContext';

const WIDTH = 248;
const NAV = [
  { to: '/staff', label: 'Dashboard', icon: <DashboardIcon />, end: true },
  { to: '/staff/applications', label: 'Applications', icon: <AssignmentIcon /> },
  { to: '/staff/windows', label: 'Admission windows', icon: <EventIcon /> },
  { to: '/staff/students', label: 'Students', icon: <BadgeIcon /> },
  { to: '/staff/finance', label: 'Fees', icon: <PaymentsIcon /> },
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

  const drawer = (
    <Box sx={{ height: '100%', display: 'flex', flexDirection: 'column', bgcolor: '#0F1B2D', color: '#E6EBF2' }}>
      <Box sx={{ p: 2.5 }}>
        <Typography sx={{ fontFamily: 'Sora', fontWeight: 700 }}>{inst.shortName ?? inst.name}</Typography>
        <Typography variant="caption" sx={{ opacity: 0.7 }}>CIMS · Back-office</Typography>
      </Box>
      <Divider sx={{ borderColor: 'rgba(255,255,255,0.08)' }} />
      <List sx={{ px: 1, flexGrow: 1 }}>
        {NAV.map((n) => (
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
