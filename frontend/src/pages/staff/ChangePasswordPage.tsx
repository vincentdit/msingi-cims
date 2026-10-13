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

/** A staff member chooses their own password: required after a temporary one, available any time from the menu. */
export default function ChangePasswordPage() {
  const { user, signIn } = useSession();
  const navigate = useNavigate();
  const me = user('staff');
  const forced = !!me?.mustChangePassword;
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
      <PageTitle title={forced ? 'Choose your password' : 'Change password'}
        subtitle={forced ? 'You signed in with a temporary password from the administrator. Choose your own to continue.' : 'Your other sessions are signed out when you change it.'} />
      <Card>
        <CardContent>
          <Stack spacing={2}>
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
      </Card>
    </Container>
  );
}
