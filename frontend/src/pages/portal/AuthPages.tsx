import { Alert, Box, Button, Card, CardContent, Checkbox, Container, FormControlLabel, Link, Stack, TextField, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useState, type FormEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { TokenResponse } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert } from '../../components/common';

interface OtpSent {
  maskedPhone: string;
  message: string;
  devOtp?: string;
}

function AuthCard({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children: ReactNode }) {
  return (
    <Container maxWidth="sm" sx={{ py: { xs: 4, md: 8 } }}>
      <Card sx={{ borderRadius: 3 }}>
        <CardContent sx={{ p: { xs: 3, sm: 4 } }}>
          <Typography variant="h5" component="h1">{title}</Typography>
          {subtitle && <Typography color="text.secondary" sx={{ mt: 1 }}>{subtitle}</Typography>}
          <Box sx={{ mt: 3 }}>{children}</Box>
        </CardContent>
      </Card>
    </Container>
  );
}

function DevCode({ code }: { code?: string }) {
  const { t } = useTranslation();
  return code ? <Alert severity="warning" sx={{ mb: 2 }}>{t('auth.devCode', { code })}</Alert> : null;
}

export function RegisterPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [f, setF] = useState({ firstName: '', middleName: '', surname: '', phone: '', email: '', password: '', acceptPrivacyNotice: false });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });
  const m = useMutation({
    mutationFn: () => api<OtpSent & { userId: number }>('/api/applicant-auth/register', { method: 'POST', body: { ...f, email: f.email || null } }),
    onSuccess: (r) => navigate('/verify-phone', { state: { phone: f.phone, masked: r.maskedPhone, devOtp: r.devOtp } }),
  });
  const submit = (e: FormEvent) => { e.preventDefault(); m.mutate(); };

  return (
    <AuthCard title={t('auth.registerTitle')}>
      <form onSubmit={submit}>
        <Stack spacing={2}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField label={t('auth.firstName')} value={f.firstName} onChange={set('firstName')} required autoComplete="given-name" />
            <TextField label={t('auth.surname')} value={f.surname} onChange={set('surname')} required autoComplete="family-name" />
          </Stack>
          <TextField label={t('auth.middleName')} value={f.middleName} onChange={set('middleName')} />
          <TextField label={t('auth.phone')} value={f.phone} onChange={set('phone')} required placeholder="0712 345 678" inputMode="tel" autoComplete="tel" />
          <TextField label={t('auth.email')} value={f.email} onChange={set('email')} type="email" autoComplete="email" />
          <TextField label={t('auth.password')} value={f.password} onChange={set('password')} type="password" required helperText={t('auth.passwordHelp')} autoComplete="new-password" />
          <FormControlLabel control={<Checkbox checked={f.acceptPrivacyNotice} onChange={set('acceptPrivacyNotice')} />} label={<Typography variant="body2">{t('auth.privacy')}</Typography>} />
          <ErrorAlert error={m.error} />
          <Button type="submit" variant="contained" size="large" disabled={m.isPending || !f.acceptPrivacyNotice}>{t('auth.create')}</Button>
          <Link component={RouterLink} to="/login" textAlign="center">{t('auth.haveAccount')}</Link>
        </Stack>
      </form>
    </AuthCard>
  );
}

export function VerifyPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { signIn } = useSession();
  const state = (useLocation().state ?? {}) as { phone?: string; masked?: string; devOtp?: string };
  const [phone, setPhone] = useState(state.phone ?? '');
  const [code, setCode] = useState('');
  const [devOtp, setDevOtp] = useState(state.devOtp);
  const verify = useMutation({
    mutationFn: () => api<TokenResponse>('/api/applicant-auth/verify', { method: 'POST', body: { phone, code } }),
    onSuccess: (tok) => { signIn('applicant', tok); navigate('/application'); },
  });
  const resend = useMutation({
    mutationFn: () => api<OtpSent>('/api/applicant-auth/resend-code', { method: 'POST', body: { phone } }),
    onSuccess: (r) => setDevOtp(r.devOtp),
  });

  return (
    <AuthCard title={t('auth.verifyTitle')} subtitle={t('auth.verifyText', { phone: state.masked ?? phone })}>
      <form onSubmit={(e) => { e.preventDefault(); verify.mutate(); }}>
        <Stack spacing={2}>
          <DevCode code={devOtp} />
          {!state.phone && <TextField label={t('auth.phone')} value={phone} onChange={(e) => setPhone(e.target.value)} required />}
          <TextField label={t('auth.code')} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} required inputMode="numeric" autoComplete="one-time-code" slotProps={{ htmlInput: { maxLength: 8, style: { letterSpacing: 6, fontSize: 22 } } }} />
          <ErrorAlert error={verify.error ?? resend.error} />
          <Button type="submit" variant="contained" size="large" disabled={verify.isPending}>{t('auth.verify')}</Button>
          <Button onClick={() => resend.mutate()} disabled={resend.isPending || !phone}>{t('auth.resend')}</Button>
        </Stack>
      </form>
    </AuthCard>
  );
}

export function LoginPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { signIn } = useSession();
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/application';
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const m = useMutation({
    mutationFn: () => api<TokenResponse>('/api/applicant-auth/login', { method: 'POST', body: { login, password } }),
    onSuccess: (tok) => { signIn('applicant', tok); navigate(from); },
    onError: (e: unknown) => {
      const code = (e as { problem?: { code?: string } }).problem?.code;
      if (code === 'ACCOUNT_NOT_VERIFIED') navigate('/verify-phone', { state: { phone: login } });
    },
  });

  return (
    <AuthCard title={t('auth.loginTitle')}>
      <form onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
        <Stack spacing={2}>
          <TextField label={t('auth.login')} value={login} onChange={(e) => setLogin(e.target.value)} required autoComplete="username" />
          <TextField label={t('auth.password')} value={password} onChange={(e) => setPassword(e.target.value)} type="password" required autoComplete="current-password" />
          <ErrorAlert error={m.error} />
          <Button type="submit" variant="contained" size="large" disabled={m.isPending}>{t('auth.signIn')}</Button>
          <Stack direction="row" justifyContent="space-between">
            <Link component={RouterLink} to="/register">{t('auth.noAccount')}</Link>
            <Link component={RouterLink} to="/forgot-password">{t('auth.forgot')}</Link>
          </Stack>
        </Stack>
      </form>
    </AuthCard>
  );
}

export function ForgotPasswordPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [sent, setSent] = useState<OtpSent | null>(null);
  const send = useMutation({
    mutationFn: () => api<OtpSent>('/api/applicant-auth/forgot-password', { method: 'POST', body: { phone } }),
    onSuccess: setSent,
  });
  const reset = useMutation({
    mutationFn: () => api('/api/applicant-auth/reset-password', { method: 'POST', body: { phone, code, newPassword: password } }),
    onSuccess: () => navigate('/login'),
  });

  return (
    <AuthCard title={t('auth.forgotTitle')}>
      <Stack spacing={2}>
        <TextField label={t('auth.phone')} value={phone} onChange={(e) => setPhone(e.target.value)} disabled={!!sent} />
        {!sent ? (
          <Button variant="contained" onClick={() => send.mutate()} disabled={send.isPending || !phone}>{t('auth.sendCode')}</Button>
        ) : (
          <>
            <Alert severity="info">{sent.message}</Alert>
            <DevCode code={sent.devOtp} />
            <TextField label={t('auth.code')} value={code} onChange={(e) => setCode(e.target.value)} inputMode="numeric" />
            <TextField label={t('auth.newPassword')} type="password" value={password} onChange={(e) => setPassword(e.target.value)} helperText={t('auth.passwordHelp')} />
            <Button variant="contained" onClick={() => reset.mutate()} disabled={reset.isPending}>{t('auth.reset')}</Button>
          </>
        )}
        <ErrorAlert error={send.error ?? reset.error} />
      </Stack>
    </AuthCard>
  );
}
