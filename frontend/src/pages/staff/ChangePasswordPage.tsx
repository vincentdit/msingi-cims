import { Alert, Box, Button, Card, CardContent, Container, Stack, TextField, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { TokenResponse } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, PageTitle } from '../../components/common';
import { NewPasswordFields, passwordOk } from '../../components/PasswordFields';
import { homeFor } from '../../components/StaffLayout';
import EmailCard from '../../components/EmailCard';
import MfaCard from '../../components/MfaCard';
import PrivacyCard from '../../components/PrivacyCard';

/** The staff member's own account: password, two-step sign-in, email and personal data. A temporary password or a required MFA set-up comes first. */
export default function ChangePasswordPage() {
  const { user, signIn } = useSession();
  const navigate = useNavigate();
  const me = user('staff');
  const forced = !!me?.mustChangePassword;
  const mfaFirst = !forced && !!me?.mfaSetupRequired;
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [done, setDone] = useState(false);
  const m = useMutation({
    mutationFn: () => api<TokenResponse>('/api/auth/password', { realm: 'staff', method: 'POST', body: { currentPassword: current, newPassword: next } }),
    onSuccess: (t) => {
      signIn('staff', t);
      if (forced) navigate(homeFor(t.user.roles), { replace: true });
      else { setDone(true); setCurrent(''); setNext(''); setConfirm(''); }
    },
  });
  return (
    <Container maxWidth="sm" disableGutters>
      <PageTitle title={forced ? 'Choose your password' : mfaFirst ? 'Set up two-step sign-in' : 'My account'}
        subtitle={forced ? 'You signed in with a temporary password from the administrator. Choose your own to continue.'
          : mfaFirst ? 'Your role must sign in with an authenticator app. It takes a minute.' : 'Your password, two-step sign-in, email and personal data.'} />
      {!forced && <Box sx={{ mb: 2.5 }}><MfaCard /></Box>}
      {!mfaFirst && <Card>
        <CardContent>
          <Stack spacing={2}>
            {!forced && <Typography variant="h6">Password</Typography>}
            {done && <Alert severity="success">Your password has been changed.</Alert>}
            <TextField label={forced ? 'Temporary password' : 'Current password'} type="password" value={current} onChange={(e) => setCurrent(e.target.value)}
              autoComplete="current-password" required fullWidth />
            <NewPasswordFields password={next} confirm={confirm} onPassword={setNext} onConfirm={setConfirm} label="New password" />
            <ErrorAlert error={m.error} />
            <Box>
              <Button variant="contained" disabled={!current || !passwordOk(next) || next !== confirm || m.isPending} onClick={() => m.mutate()}>
                {forced ? 'Save and continue' : 'Change password'}
              </Button>
            </Box>
            <Typography variant="caption" color="text.secondary">Signed in as {me?.fullName}.</Typography>
          </Stack>
        </CardContent>
      </Card>}
      {!forced && !mfaFirst && <Box sx={{ mt: 2.5 }}><EmailCard realm="staff" /></Box>}
      {!forced && !mfaFirst && <Box sx={{ mt: 2.5 }}><PrivacyCard realm="staff" /></Box>}
    </Container>
  );
}
