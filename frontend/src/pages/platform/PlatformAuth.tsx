import { Alert, Box, Button, Card, CardContent, Container, Stack, TextField, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import { ErrorAlert, PageTitle } from '../../components/common';
import { MSINGI_ACCENT, MSINGI_PRIMARY } from '../../components/InstitutionContext';
import { NewPasswordFields, PasswordField, passwordOk } from '../../components/PasswordFields';
import { platform, usePlatformSession, type PlatformToken } from './shared';

/** Sign-in for Msingi Technologies staff. Institution staff sign in at their institution's address instead. */
export function PlatformLoginPage() {
  const { me, signIn } = usePlatformSession();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const m = useMutation({
    mutationFn: () => api<PlatformToken>('/api/platform/auth/login', { method: 'POST', body: { username, password } }),
    onSuccess: (t) => { signIn(t); navigate(t.user.mustChangePassword ? '/platform/password' : '/platform', { replace: true }); },
  });
  if (me) return <Navigate to="/platform" replace />;
  const submit = (e: FormEvent) => { e.preventDefault(); if (username && password) m.mutate(); };
  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', p: 2,
      background: `radial-gradient(1200px 600px at 10% -10%, ${MSINGI_PRIMARY}22, transparent), radial-gradient(900px 500px at 110% 110%, ${MSINGI_ACCENT}1f, transparent)` }}>
      <Card sx={{ width: '100%', maxWidth: 420, borderRadius: 3 }}>
        <CardContent sx={{ p: { xs: 3, sm: 4 } }}>
          <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 22 }}>
            Msingi <Box component="span" sx={{ color: MSINGI_ACCENT }}>CIMS</Box>
          </Typography>
          <Typography color="text.secondary" sx={{ mb: 3 }}>Platform console for Msingi Technologies staff</Typography>
          <Box component="form" onSubmit={submit}>
            <Stack spacing={2}>
              <TextField label="Username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus required fullWidth />
              <PasswordField label="Password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required fullWidth />
              <ErrorAlert error={m.error} />
              <Button type="submit" variant="contained" size="large" disabled={!username || !password || m.isPending}>Sign in</Button>
            </Stack>
          </Box>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 3 }}>
            College staff: sign in at your college's own CIMS address.
          </Typography>
        </CardContent>
      </Card>
    </Box>
  );
}

/** A platform user chooses their own password: required after a temporary one. */
export function PlatformPasswordPage() {
  const { me, signIn } = usePlatformSession();
  const navigate = useNavigate();
  const forced = !!me?.mustChangePassword;
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [done, setDone] = useState(false);
  const m = useMutation({
    mutationFn: () => api<PlatformToken>('/api/platform/auth/password', { ...platform, method: 'POST', body: { currentPassword: current, newPassword: next } }),
    onSuccess: (t) => {
      signIn(t);
      if (forced) navigate('/platform', { replace: true });
      else { setDone(true); setCurrent(''); setNext(''); setConfirm(''); }
    },
  });
  return (
    <Container maxWidth="sm" disableGutters>
      <PageTitle title={forced ? 'Choose your password' : 'Change password'}
        subtitle={forced ? 'You signed in with a temporary password. Choose your own to continue.' : 'Your other sessions are signed out when you change it.'} />
      <Card>
        <CardContent>
          <Stack spacing={2}>
            {done && <Alert severity="success">Your password has been changed.</Alert>}
            <PasswordField label={forced ? 'Temporary password' : 'Current password'} value={current} onChange={(e) => setCurrent(e.target.value)}
              autoComplete="current-password" required fullWidth />
            <NewPasswordFields password={next} confirm={confirm} onPassword={setNext} onConfirm={setConfirm} label="New password" />
            <ErrorAlert error={m.error} />
            <Box>
              <Button variant="contained" disabled={!current || !passwordOk(next) || next !== confirm || m.isPending} onClick={() => m.mutate()}>
                {forced ? 'Save and continue' : 'Change password'}
              </Button>
            </Box>
          </Stack>
        </CardContent>
      </Card>
    </Container>
  );
}
