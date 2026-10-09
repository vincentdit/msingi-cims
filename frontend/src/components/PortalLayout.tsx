import { AppBar, Avatar, Box, Button, Container, Stack, ToggleButton, ToggleButtonGroup, Toolbar, Typography } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink, Outlet, useNavigate } from 'react-router-dom';
import { useSession } from '../auth/session';
import { setLanguage } from '../i18n';
import { useInstitution } from './InstitutionContext';

function initials(name: string) {
  return name.split(/\s+/).filter((w) => /^[A-Z]/.test(w)).slice(0, 2).map((w) => w[0]).join('');
}

/** Public, institution-branded layout for applicants. Mobile first. */
export default function PortalLayout() {
  const inst = useInstitution();
  const { t, i18n } = useTranslation();
  const { user, signOut } = useSession();
  const navigate = useNavigate();
  const applicant = user('applicant');

  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column' }}>
      <AppBar position="sticky" color="inherit" elevation={0} sx={{ borderBottom: '1px solid', borderColor: 'divider' }}>
        <Toolbar sx={{ gap: 1.5 }}>
          <Stack direction="row" spacing={1.5} alignItems="center" component={RouterLink} to="/" sx={{ textDecoration: 'none', color: 'inherit', flexGrow: 1, minWidth: 0 }}>
            {inst.branding.logoUrl ? (
              <Box component="img" src={inst.branding.logoUrl} alt="" sx={{ height: 40 }} />
            ) : (
              <Avatar sx={{ bgcolor: 'primary.main', fontFamily: 'Sora', fontWeight: 700, width: 40, height: 40, fontSize: 15 }}>{initials(inst.name)}</Avatar>
            )}
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="subtitle1" noWrap sx={{ fontFamily: 'Sora', fontWeight: 700, lineHeight: 1.2 }}>
                {inst.shortName ?? inst.name}
              </Typography>
              <Typography variant="caption" color="text.secondary" noWrap sx={{ display: { xs: 'none', sm: 'block' } }}>
                {inst.name}
              </Typography>
            </Box>
          </Stack>
          <ToggleButtonGroup size="small" exclusive value={i18n.language} onChange={(_, v) => v && setLanguage(v)} aria-label="language">
            <ToggleButton value="en" sx={{ px: 1, py: 0.25 }}>EN</ToggleButton>
            <ToggleButton value="sw" sx={{ px: 1, py: 0.25 }}>SW</ToggleButton>
          </ToggleButtonGroup>
          {applicant ? (
            <>
              <Button component={RouterLink} to="/application" sx={{ display: { xs: 'none', sm: 'inline-flex' } }}>{t('nav.myApplication')}</Button>
              <Button variant="outlined" onClick={() => { signOut('applicant'); navigate('/'); }}>{t('nav.signOut')}</Button>
            </>
          ) : (
            <Button variant="contained" component={RouterLink} to="/login">{t('nav.signIn')}</Button>
          )}
        </Toolbar>
      </AppBar>
      <Box component="main" sx={{ flexGrow: 1 }}>
        <Outlet />
      </Box>
      <Box component="footer" sx={{ borderTop: '1px solid', borderColor: 'divider', py: 3, bgcolor: 'background.paper' }}>
        <Container maxWidth="lg">
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
            <Typography variant="body2" color="text.secondary">
              {inst.name}{inst.nactvetRegNo ? ` · NACTVET ${inst.nactvetRegNo}` : ''}
              {inst.contactPhone ? ` · ${inst.contactPhone}` : ''}{inst.contactEmail ? ` · ${inst.contactEmail}` : ''}
            </Typography>
            <Stack direction="row" spacing={2} useFlexGap flexWrap="wrap" rowGap={0.5} sx={{ '& > *': { whiteSpace: 'nowrap' } }}>
              <Typography variant="body2" color="text.secondary" component={RouterLink} to="/verify" sx={{ textDecoration: 'none' }}>
                {t('verify.title')}
              </Typography>
              <Typography variant="body2" color="text.secondary" component={RouterLink} to="/staff" sx={{ textDecoration: 'none' }}>
                {t('nav.staff')}
              </Typography>
              <Typography variant="body2" color="text.secondary">{t('common.poweredBy')}</Typography>
            </Stack>
          </Stack>
        </Container>
      </Box>
    </Box>
  );
}
