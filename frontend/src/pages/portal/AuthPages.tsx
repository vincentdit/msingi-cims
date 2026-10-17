import { Alert, Box, Button, Card, CardContent, Checkbox, Container, FormControlLabel, Link, Stack, TextField, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import { api } from '../../api/client';
import type { TokenResponse } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert } from '../../components/common';
import { NewPasswordFields, passwordOk } from '../../components/PasswordFields';

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

interface NectaCandidate {
  indexNumber: string; examYear: number; firstName: string; middleName?: string; surname: string; fullName: string; sex?: 'M' | 'F';
  centreName?: string; division?: string; points?: number; subjects: { code: string; name: string; grade: string }[]; alreadyRegistered: boolean;
  premNumber?: string;
}

const YEARS = Array.from({ length: new Date().getFullYear() - 1987 }, (_, i) => new Date().getFullYear() - i);

/** Registration: the Form Four index number and year first; NECTA supplies the name and results, which the applicant confirms. */
export function RegisterPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [index, setIndex] = useState('');
  const [year, setYear] = useState(String(new Date().getFullYear() - 1));
  const [candidate, setCandidate] = useState<NectaCandidate | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [f, setF] = useState({ phone: '', email: '', password: '', acceptPrivacyNotice: false });
  const [confirm, setConfirm] = useState('');
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setF({ ...f, [k]: e.target.type === 'checkbox' ? e.target.checked : e.target.value });
  // "S0101/0001/2023" typed in one go fills in the year
  const onIndex = (v: string) => {
    const m = v.trim().toUpperCase().match(/^([SP]\d{4}[/-]\d{4})[/-](\d{4})$/);
    if (m) { setIndex(m[1].replace('-', '/')); setYear(m[2]); } else setIndex(v.toUpperCase());
  };
  const lookup = useMutation({
    mutationFn: () => api<NectaCandidate>('/api/applicant-auth/necta-lookup', { method: 'POST', body: { indexNumber: index, examYear: Number(year) } }),
    onSuccess: (c) => { setCandidate(c); setConfirmed(false); },
  });
  const m = useMutation({
    mutationFn: () => api<OtpSent & { userId: number }>('/api/applicant-auth/register', { method: 'POST',
      body: { indexNumber: candidate!.indexNumber, examYear: candidate!.examYear, phone: f.phone, email: f.email || null, password: f.password, acceptPrivacyNotice: f.acceptPrivacyNotice } }),
    onSuccess: (r) => navigate('/verify-phone', { state: { phone: f.phone, masked: r.maskedPhone, devOtp: r.devOtp } }),
  });
  const reset = () => { setCandidate(null); setConfirmed(false); lookup.reset(); m.reset(); };

  return (
    <AuthCard title={t('auth.registerTitle')} subtitle={!candidate ? t('auth.nectaIntro') : undefined}>
      {!candidate && (
        <form onSubmit={(e) => { e.preventDefault(); lookup.mutate(); }}>
          <Stack spacing={2}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField label={t('auth.indexNumber')} value={index} onChange={(e) => onIndex(e.target.value)} required autoFocus
                placeholder="S0101/0001" helperText={t('auth.indexHelp')} sx={{ flex: 2 }} slotProps={{ htmlInput: { autoCapitalize: 'characters', spellCheck: false } }} />
              <TextField select label={t('auth.examYear')} value={year} onChange={(e) => setYear(e.target.value)} required sx={{ flex: 1 }}
                slotProps={{ select: { native: true } }}>
                {YEARS.map((y) => <option key={y} value={y}>{y}</option>)}
              </TextField>
            </Stack>
            <ErrorAlert error={lookup.error} />
            <Button type="submit" variant="contained" size="large" disabled={lookup.isPending || !index.trim()}>{lookup.isPending ? '…' : t('auth.find')}</Button>
            <Link component={RouterLink} to="/login" textAlign="center">{t('auth.haveAccount')}</Link>
          </Stack>
        </form>
      )}

      {candidate && !confirmed && (
        <Stack spacing={2}>
          <Typography variant="subtitle2" color="text.secondary">{t('auth.confirmTitle')}</Typography>
          <Card variant="outlined" sx={{ borderRadius: 2 }}>
            <CardContent>
              <Typography variant="h6">{candidate.fullName}</Typography>
              <Typography variant="body2" color="text.secondary">
                {[candidate.indexNumber + ' · ' + candidate.examYear, candidate.sex && t(`auth.sex${candidate.sex}`), candidate.centreName].filter(Boolean).join(' · ')}
              </Typography>
              {candidate.premNumber && <Typography variant="body2" color="text.secondary">{t('app.premNumber')}: <strong>{candidate.premNumber}</strong></Typography>}
              {(candidate.division || candidate.points != null) && (
                <Typography variant="body2" sx={{ mt: 1, fontWeight: 600 }}>
                  {[candidate.division && t('auth.division', { division: candidate.division }), candidate.points != null && t('auth.points', { points: candidate.points })].filter(Boolean).join(' · ')}
                </Typography>
              )}
              <Box sx={{ mt: 1.5, display: 'flex', flexWrap: 'wrap', gap: 0.75 }}>
                {candidate.subjects.map((s) => (
                  <Box key={s.code + s.name} sx={{ px: 1, py: 0.25, borderRadius: 1, bgcolor: 'action.hover', fontSize: 13 }}>{s.name} <b>{s.grade}</b></Box>
                ))}
              </Box>
            </CardContent>
          </Card>
          {candidate.alreadyRegistered ? (
            <>
              <Alert severity="info">{t('auth.alreadyRegistered')}</Alert>
              <Button variant="contained" component={RouterLink} to="/login">{t('auth.signIn')}</Button>
              <Button onClick={reset}>{t('auth.notMe')}</Button>
            </>
          ) : (
            <>
              <Button variant="contained" size="large" onClick={() => setConfirmed(true)}>{t('auth.isMe')}</Button>
              <Button onClick={reset}>{t('auth.notMe')}</Button>
            </>
          )}
        </Stack>
      )}

      {candidate && confirmed && (
        <form onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
          <Stack spacing={2}>
            <Alert severity="success" icon={false} action={<Button size="small" onClick={reset}>{t('auth.notMe').split(' — ')[0]}</Button>}>
              {t('auth.accountFor', { name: candidate.fullName, index: `${candidate.indexNumber}/${candidate.examYear}` })}
            </Alert>
            <TextField label={t('auth.phone')} value={f.phone} onChange={set('phone')} required placeholder="0712 345 678" inputMode="tel" autoComplete="tel" autoFocus />
            <TextField label={t('auth.email')} value={f.email} onChange={set('email')} type="email" autoComplete="email" />
            <NewPasswordFields password={f.password} confirm={confirm} onPassword={(v) => setF({ ...f, password: v })} onConfirm={setConfirm} />
            <FormControlLabel control={<Checkbox checked={f.acceptPrivacyNotice} onChange={set('acceptPrivacyNotice')} />} label={<Typography variant="body2">{t('auth.privacy')}</Typography>} />
            <ErrorAlert error={m.error} />
            <Button type="submit" variant="contained" size="large"
              disabled={m.isPending || !f.acceptPrivacyNotice || !passwordOk(f.password) || confirm !== f.password}>{t('auth.create')}</Button>
          </Stack>
        </form>
      )}
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
      if (code === 'ACCOUNT_NOT_VERIFIED') navigate('/verify-phone', { state: /^[SP]\d{4}/i.test(login.trim()) ? {} : { phone: login } });
    },
  });

  return (
    <AuthCard title={t('auth.loginTitle')}>
      {new URLSearchParams(window.location.search).get('idle') === '1' && <Alert severity="info" sx={{ mb: 2 }}>{t('idle.signedOut')}</Alert>}
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
  const [confirm, setConfirm] = useState('');
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
            <NewPasswordFields label={t('auth.newPassword')} password={password} confirm={confirm} onPassword={setPassword} onConfirm={setConfirm} />
            <Button variant="contained" onClick={() => reset.mutate()} disabled={reset.isPending || !code || !passwordOk(password) || confirm !== password}>{t('auth.reset')}</Button>
          </>
        )}
        <ErrorAlert error={send.error ?? reset.error} />
      </Stack>
    </AuthCard>
  );
}
