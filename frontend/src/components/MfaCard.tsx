import ShieldIcon from '@mui/icons-material/VerifiedUser';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../api/client';
import type { TokenResponse } from '../api/types';
import { useSession } from '../auth/session';
import { ErrorAlert } from './common';

interface MfaStatus { enabled: boolean; enabledAt?: string; required: boolean; policy: string; recoveryCodesLeft: number }
interface Setup { secret: string; otpauthUri: string; qrPng: string }

/** Two-step sign-in with an authenticator app (Google Authenticator, Microsoft Authenticator, Authy…) for staff (BR-36). */
export default function MfaCard() {
  const qc = useQueryClient();
  const { signIn } = useSession();
  const status = useQuery({ queryKey: ['mfa'], queryFn: () => api<MfaStatus>('/api/auth/mfa', { realm: 'staff' }) });
  const [setup, setSetup] = useState<Setup | null>(null);
  const [code, setCode] = useState('');
  const [codes, setCodes] = useState<string[] | null>(null);
  const [ask, setAsk] = useState<'disable' | 'codes' | null>(null);
  const [password, setPassword] = useState('');
  const start = useMutation({ mutationFn: () => api<Setup>('/api/auth/mfa/setup', { realm: 'staff', method: 'POST' }), onSuccess: (s) => { setSetup(s); setCode(''); } });
  const enable = useMutation({
    mutationFn: () => api<{ recoveryCodes: string[]; token: TokenResponse }>('/api/auth/mfa/enable', { realm: 'staff', method: 'POST', body: { code: code.trim() } }),
    onSuccess: (r) => { setCodes(r.recoveryCodes); setSetup(null); signIn('staff', r.token); qc.invalidateQueries({ queryKey: ['mfa'] }); },
  });
  const withPassword = useMutation({
    mutationFn: (): Promise<string[] | MfaStatus> => ask === 'codes'
      ? api<string[]>('/api/auth/mfa/recovery-codes', { realm: 'staff', method: 'POST', body: { password } })
      : api<MfaStatus>('/api/auth/mfa/disable', { realm: 'staff', method: 'POST', body: { password } }),
    onSuccess: (r) => { if (Array.isArray(r)) setCodes(r); setAsk(null); setPassword(''); qc.invalidateQueries({ queryKey: ['mfa'] }); },
  });
  const s = status.data;
  if (!s) return null;
  return (
    <Card>
      <CardContent>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
          <ShieldIcon color={s.enabled ? 'success' : 'disabled'} />
          <Typography variant="h6">Two-step sign-in</Typography>
          {s.enabled ? <Chip size="small" color="success" label="On" /> : <Chip size="small" label="Off" />}
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          After your password, CIMS asks for a 6-digit code from an authenticator app on your phone, so a stolen password alone cannot open your account.
        </Typography>
        {s.required && !s.enabled && <Alert severity="warning" sx={{ mb: 1.5 }}>Your role must use two-step sign-in. Set it up to continue using CIMS.</Alert>}
        {!s.enabled && !setup && <Button variant="contained" onClick={() => start.mutate()} disabled={start.isPending}>Set up</Button>}
        {setup && (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2.5} alignItems={{ sm: 'flex-start' }}>
            <Box component="img" src={setup.qrPng} alt="QR code for the authenticator app" sx={{ width: 180, height: 180, border: 1, borderColor: 'divider', borderRadius: 1 }} />
            <Stack spacing={1.25} sx={{ flex: 1 }}>
              <Typography variant="body2">1. Open your authenticator app and scan this code.</Typography>
              <Typography variant="body2" color="text.secondary">Cannot scan? Enter this key: <Box component="span" sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{setup.secret}</Box></Typography>
              <Typography variant="body2">2. Type the 6-digit code the app shows.</Typography>
              <Stack direction="row" spacing={1}>
                <TextField size="small" label="Code" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} inputProps={{ inputMode: 'numeric', maxLength: 6 }} />
                <Button variant="contained" disabled={code.length !== 6 || enable.isPending} onClick={() => enable.mutate()}>Turn on</Button>
              </Stack>
            </Stack>
          </Stack>
        )}
        {s.enabled && (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }}>
            <Typography variant="body2" sx={{ flex: 1 }}>{s.recoveryCodesLeft} recovery codes left.</Typography>
            <Button onClick={() => setAsk('codes')}>New recovery codes</Button>
            {!s.required && <Button color="error" onClick={() => setAsk('disable')}>Turn off</Button>}
          </Stack>
        )}
        <ErrorAlert error={start.error ?? enable.error} />
      </CardContent>
      <Dialog open={!!codes} onClose={() => setCodes(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Your recovery codes</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 1.5 }}>If you lose your phone, sign in with one of these instead of a code. Each works once. Keep them somewhere safe; they are not shown again.</Typography>
          <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 1, fontFamily: 'monospace', fontSize: 16, p: 1.5, bgcolor: 'action.hover', borderRadius: 1 }}>
            {codes?.map((c) => <span key={c}>{c}</span>)}
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => navigator.clipboard?.writeText(codes?.join('\n') ?? '')}>Copy</Button>
          <Button variant="contained" onClick={() => setCodes(null)}>I have saved them</Button>
        </DialogActions>
      </Dialog>
      <Dialog open={!!ask} onClose={() => setAsk(null)} maxWidth="xs" fullWidth>
        <DialogTitle>{ask === 'codes' ? 'New recovery codes' : 'Turn off two-step sign-in'}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 1.5 }}>{ask === 'codes' ? 'Your old recovery codes stop working.' : 'Your account will be protected by your password only.'} Confirm with your password.</Typography>
          <TextField fullWidth type="password" label="Password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
          <ErrorAlert error={withPassword.error} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAsk(null)}>Cancel</Button>
          <Button variant="contained" color={ask === 'disable' ? 'error' : 'primary'} disabled={!password || withPassword.isPending} onClick={() => withPassword.mutate()}>Confirm</Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}
