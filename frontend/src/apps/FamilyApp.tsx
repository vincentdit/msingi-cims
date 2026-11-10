/**
 * The family and sponsor portal (FR-STU-05): a parent, guardian or sponsor signs in with a code sent to their phone and follows the
 * students linked to that phone: fees and payments always, results and attendance when the student shares them. Read-only.
 */
import LogoutIcon from '@mui/icons-material/Logout';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import LockIcon from '@mui/icons-material/LockOutlined';
import {
  Alert, AppBar, Avatar, Box, Button, Card, CardActionArea, CardContent, Chip, Container, Divider, IconButton, LinearProgress, Stack, Tab, Tabs, TextField,
  ToggleButton, ToggleButtonGroup, Toolbar, Tooltip, Typography,
} from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, Outlet, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import { api, formatDate, formatDateTime, formatTzs } from '../api/client';
import type { MyAttendance, Statement, StudentSemesterResult, TokenResponse } from '../api/types';
import { RequireAuth, useSession } from '../auth/session';
import { ErrorAlert, Loading } from '../components/common';
import { HowToPay, usePayWords } from '../components/HowToPay';
import { useInstitution } from '../components/InstitutionContext';
import { setLanguage } from '../i18n';

const family = { realm: 'family' as const };

interface LinkedStudent {
  studentId: number; registrationNo: string; name: string; programmeCode: string; programmeName: string; yearOfStudy: number; status: string;
  as: 'GUARDIAN' | 'SPONSOR'; relation?: string; seesResults: boolean; seesAttendance: boolean;
}

export default function FamilyApp() {
  return (
    <Routes>
      <Route path="login" element={<FamilyLoginPage />} />
      <Route element={<RequireAuth realm="family"><FamilyLayout /></RequireAuth>}>
        <Route index element={<FamilyHome />} />
        <Route path="students/:id" element={<FamilyStudentPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/family" replace />} />
    </Routes>
  );
}

function Brand() {
  const inst = useInstitution();
  return (
    <Box sx={{ width: 56, height: 56, borderRadius: '50%', bgcolor: 'primary.main', color: '#fff', display: 'grid', placeItems: 'center',
      fontFamily: 'Sora', fontWeight: 700, fontSize: 14 }}>{(inst.shortName ?? inst.name).slice(0, 4)}</Box>
  );
}

function Language() {
  const { i18n } = useTranslation();
  return (
    <ToggleButtonGroup size="small" exclusive value={i18n.language} onChange={(_, v) => v && setLanguage(v)} aria-label="language">
      <ToggleButton value="en" sx={{ px: 1, py: 0.25 }}>EN</ToggleButton>
      <ToggleButton value="sw" sx={{ px: 1, py: 0.25 }}>SW</ToggleButton>
    </ToggleButtonGroup>
  );
}

// ============================================================ sign-in with a code sent to the phone

function FamilyLoginPage() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const navigate = useNavigate();
  const { signIn } = useSession();
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [sent, setSent] = useState<{ message: string; developmentCode?: string } | null>(null);
  const ask = useMutation({
    mutationFn: () => api<{ message: string; developmentCode?: string }>('/api/family-auth/code', { method: 'POST', body: { phone } }),
    onSuccess: (r) => { setSent(r); if (r.developmentCode) setCode(r.developmentCode); },
  });
  const verify = useMutation({
    mutationFn: () => api<TokenResponse>('/api/family-auth/verify', { method: 'POST', body: { phone, code } }),
    onSuccess: (tok) => { signIn('family', tok); navigate('/family'); },
  });
  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'background.default', display: 'flex', alignItems: { sm: 'center' } }}>
      <Container maxWidth="xs" sx={{ py: 4 }}>
        <Stack spacing={1} alignItems="center" sx={{ mb: 3, textAlign: 'center' }}>
          <Brand />
          <Typography variant="h5" component="h1">{t('family.title')}</Typography>
          <Typography variant="body2" color="text.secondary">{inst.name}</Typography>
          <Language />
        </Stack>
        <Card sx={{ borderRadius: 3 }}>
          <CardContent sx={{ p: 3 }}>
            {!sent ? (
              <form onSubmit={(e) => { e.preventDefault(); ask.mutate(); }}>
                <Stack spacing={2}>
                  <Typography variant="body2" color="text.secondary">{t('family.phoneHint')}</Typography>
                  <TextField label={t('family.phone')} value={phone} onChange={(e) => setPhone(e.target.value)} required inputMode="tel" autoComplete="tel"
                    placeholder="07XX XXX XXX" />
                  <ErrorAlert error={ask.error} />
                  <Button type="submit" variant="contained" size="large" disabled={ask.isPending || phone.trim().length < 9}>{t('family.sendCode')}</Button>
                </Stack>
              </form>
            ) : (
              <form onSubmit={(e) => { e.preventDefault(); verify.mutate(); }}>
                <Stack spacing={2}>
                  <Alert severity="info">{t('family.codeSent')}</Alert>
                  <TextField label={t('family.code')} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} required inputMode="numeric"
                    autoComplete="one-time-code" slotProps={{ htmlInput: { maxLength: 6 } }} />
                  <ErrorAlert error={verify.error} />
                  <Button type="submit" variant="contained" size="large" disabled={verify.isPending || code.length < 6}>{t('family.signIn')}</Button>
                  <Button onClick={() => { setSent(null); setCode(''); verify.reset(); }}>{t('family.otherNumber')}</Button>
                </Stack>
              </form>
            )}
          </CardContent>
        </Card>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 2, textAlign: 'center' }}>{t('family.notListed')}</Typography>
      </Container>
    </Box>
  );
}

// ============================================================ the portal

function FamilyLayout() {
  const inst = useInstitution();
  const { t } = useTranslation();
  const { signOut, user } = useSession();
  const navigate = useNavigate();
  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'background.default', pb: 4 }}>
      <AppBar position="sticky" color="inherit" elevation={0} sx={{ borderBottom: '1px solid', borderColor: 'divider' }}>
        <Toolbar sx={{ gap: 1.5 }}>
          <Avatar sx={{ bgcolor: 'primary.main', fontFamily: 'Sora', fontWeight: 700, width: 36, height: 36, fontSize: 11 }}>{(inst.shortName ?? inst.name).slice(0, 4)}</Avatar>
          <Box sx={{ minWidth: 0, flexGrow: 1, cursor: 'pointer' }} onClick={() => navigate('/family')}>
            <Typography variant="subtitle1" noWrap sx={{ fontFamily: 'Sora', fontWeight: 700, lineHeight: 1.2 }}>{inst.shortName ?? inst.name}</Typography>
            <Typography variant="caption" color="text.secondary" noWrap display="block">{t('family.title')} · {user('family')?.fullName}</Typography>
          </Box>
          <Language />
          <Tooltip title={t('nav.signOut')}>
            <IconButton aria-label={t('nav.signOut')} onClick={() => { signOut('family'); navigate('/family/login'); }}><LogoutIcon /></IconButton>
          </Tooltip>
        </Toolbar>
      </AppBar>
      <Container maxWidth="md" sx={{ pt: { xs: 2, md: 3 } }}>
        <Outlet />
      </Container>
    </Box>
  );
}

function useStudents() {
  return useQuery({ queryKey: ['family', 'students'], queryFn: () => api<LinkedStudent[]>('/api/family/students', family) });
}

function FamilyHome() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const q = useStudents();
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  if (q.data.length === 1) return <Navigate to={`/family/students/${q.data[0].studentId}`} replace />;
  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">{t('family.students')}</Typography>
      {q.data.length === 0 && <Alert severity="info">{t('family.noStudents')}</Alert>}
      {q.data.map((s) => (
        <Card key={s.studentId} sx={{ borderRadius: 3 }}>
          <CardActionArea onClick={() => navigate(`/family/students/${s.studentId}`)}>
            <CardContent sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Typography sx={{ fontWeight: 700 }}>{s.name}</Typography>
                <Typography variant="body2" color="text.secondary">{s.registrationNo} · {s.programmeCode} · {t('student.year', { n: s.yearOfStudy })}</Typography>
                <Chip size="small" sx={{ mt: 0.75 }} label={s.as === 'SPONSOR' ? t('family.asSponsor') : (s.relation ?? t('family.asGuardian'))} />
              </Box>
              <ChevronRightIcon color="action" />
            </CardContent>
          </CardActionArea>
        </Card>
      ))}
    </Stack>
  );
}

function FamilyStudentPage() {
  const { t } = useTranslation();
  const id = Number(useParams().id);
  const students = useStudents();
  const [tab, setTab] = useState<'fees' | 'results' | 'attendance'>('fees');
  if (students.isLoading) return <Loading />;
  const s = students.data?.find((x) => x.studentId === id);
  if (!s) return <ErrorAlert error={students.error ?? new Error(t('family.notLinked'))} />;
  return (
    <Stack spacing={2}>
      <Card sx={{ borderRadius: 3, bgcolor: 'primary.main', color: 'primary.contrastText', border: 0 }}>
        <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
          <Typography variant="h5" component="h1">{s.name}</Typography>
          <Typography variant="body2" sx={{ opacity: 0.85 }}>{s.programmeName}</Typography>
          <Typography variant="body2" sx={{ opacity: 0.85 }}>{s.registrationNo} · {t('student.year', { n: s.yearOfStudy })}</Typography>
        </CardContent>
      </Card>
      <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="fullWidth">
        <Tab value="fees" label={t('student.navFees')} />
        <Tab value="results" label={t('student.navResults')} />
        <Tab value="attendance" label={t('family.attendance')} />
      </Tabs>
      {tab === 'fees' && <Fees id={id} />}
      {tab === 'results' && (s.seesResults ? <Results id={id} /> : <NotShared />)}
      {tab === 'attendance' && (s.seesAttendance ? <Attendance id={id} /> : <NotShared />)}
    </Stack>
  );
}

function NotShared() {
  const { t } = useTranslation();
  return <Alert severity="info" icon={<LockIcon />}>{t('family.notShared')}</Alert>;
}

function Figure({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <Box sx={{ flex: 1, minWidth: 100 }}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 20, color: tone }}>{value}</Typography>
    </Box>
  );
}

function Fees({ id }: { id: number }) {
  const { t } = useTranslation();
  const words = usePayWords();
  const q = useQuery({ queryKey: ['family', 'fees', id], queryFn: () => api<Statement>(`/api/family/students/${id}/fees`, family) });
  const st = q.data;
  if (q.isLoading) return <Loading />;
  if (!st) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  const open = st.invoices.filter((i) => i.balance > 0 && !['CANCELLED', 'CREDITED'].includes(i.status));
  return (
    <Stack spacing={2}>
      <Card sx={{ borderRadius: 3 }}><CardContent>
        <Stack direction="row" spacing={2} useFlexGap flexWrap="wrap">
          <Figure label={t('student.billed')} value={formatTzs(st.billed)} />
          <Figure label={t('student.paidSoFar')} value={formatTzs(st.paid)} />
          <Figure label={t('student.balance')} value={formatTzs(st.balance)} tone={st.balance > 0 ? 'warning.main' : 'success.main'} />
        </Stack>
        {st.billed > 0 && <LinearProgress variant="determinate" value={Math.min(100, (st.paid / st.billed) * 100)} sx={{ mt: 1.5, height: 8, borderRadius: 4 }} />}
      </CardContent></Card>
      {(st.sponsors ?? []).map((sp) => (
        <Alert key={sp.sponsor + sp.academicYear} severity="success">
          {t('fees.sponsored', { sponsor: sp.sponsor, year: sp.academicYear, percent: Number(sp.coveragePercent).toLocaleString('en-GB'), interpolation: { escapeValue: false } })}
        </Alert>
      ))}
      {open.length === 0 && st.billed > 0 && <Alert severity="success">{t('student.allPaid')}</Alert>}
      {open.map((inv) => (
        <Card key={inv.id} sx={{ borderRadius: 3 }}>
          <CardContent>
            <Typography sx={{ fontWeight: 700 }}>{inv.description}</Typography>
            <Typography variant="caption" color="text.secondary">{inv.invoiceNo}{inv.dueDate && <> · {t('student.dueBy', { date: formatDate(inv.dueDate) })}</>}</Typography>
            <Typography sx={{ my: 1 }}>{t('fees.balance')}: <strong>{formatTzs(inv.balance)}</strong></Typography>
            {inv.controlNo ? (
              <Box sx={{ p: 1.5, borderRadius: 2, bgcolor: 'action.hover' }}>
                <Typography variant="caption" color="text.secondary">{words.forInvoice(inv.purpose).number}</Typography>
                <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 24, letterSpacing: 1 }}>{inv.controlNo}</Typography>
                <HowToPay reference={inv.controlNo} amount={inv.balance} gepg={words.forInvoice(inv.purpose).gepg} />
              </Box>
            ) : <Typography variant="body2" color="text.secondary">{t('family.noControlNumber')}</Typography>}
          </CardContent>
        </Card>
      ))}
      <Typography variant="h6">{t('student.paymentHistory')}</Typography>
      <Card sx={{ borderRadius: 3 }}><CardContent>
        {!st.payments.length ? <Typography color="text.secondary">{t('student.noPayments')}</Typography> : (
          <Stack divider={<Divider flexItem />}>
            {[...st.payments].reverse().map((p) => (
              <Box key={p.receiptNo} sx={{ py: 1 }}>
                <Typography sx={{ fontWeight: 700 }}>{formatTzs(p.amount)}</Typography>
                <Typography variant="caption" color="text.secondary">{formatDateTime(p.paidAt)} · {t('fees.receipt')} {p.receiptNo}</Typography>
              </Box>
            ))}
          </Stack>
        )}
      </CardContent></Card>
    </Stack>
  );
}

function Results({ id }: { id: number }) {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['family', 'results', id], queryFn: () => api<{ semesters: StudentSemesterResult[] }>(`/api/family/students/${id}/results`, family) });
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  const list = q.data.semesters;
  const fixed = (n?: number | null) => (n == null ? '—' : Number(n).toFixed(1));
  return (
    <Stack spacing={2}>
      {!list.length && <Alert severity="info">{t('results.none')}</Alert>}
      {list.map((r) => (
        <Card key={r.id} sx={{ borderRadius: 3 }}>
          <CardContent>
            <Stack direction="row" justifyContent="space-between" spacing={1}>
              <Box>
                <Typography variant="h6">{r.semesterName} · {r.academicYear}</Typography>
                <Typography variant="caption" color="text.secondary">{t('results.yearPublished', { year: r.yearOfStudy, date: formatDate(r.publishedAt) })}</Typography>
              </Box>
              {r.decision && <Chip size="small" label={t(`results.decision${r.decision}`)} />}
            </Stack>
            {r.withheld ? <Alert severity="warning" icon={<LockIcon />} sx={{ mt: 1.5 }}><strong>{t('results.withheld')}</strong> {r.withheldReason}</Alert> : (
              <>
                <Stack direction="row" spacing={3} sx={{ my: 1.5 }}>
                  <Figure label={t('results.gpa')} value={fixed(r.gpa)} />
                  <Figure label={t('results.cgpa')} value={fixed(r.cgpa)} />
                  <Figure label={t('results.credits')} value={`${r.creditsPassed}/${r.creditsRegistered}`} />
                </Stack>
                <Stack divider={<Divider flexItem />}>
                  {r.modules.map((m) => (
                    <Stack key={m.code} direction="row" alignItems="center" spacing={1} sx={{ py: 0.75 }}>
                      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>{m.code}</Typography>
                        <Typography variant="body2" color="text.secondary">{m.title}</Typography>
                      </Box>
                      {m.remark !== 'PASS' && <Chip size="small" variant="outlined" color={m.remark === 'FAIL' ? 'error' : 'warning'} label={t(`results.remark${m.remark}`)} />}
                      <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 20, width: 36, textAlign: 'right' }}>{m.grade ?? '—'}</Typography>
                    </Stack>
                  ))}
                </Stack>
              </>
            )}
          </CardContent>
        </Card>
      ))}
      <Typography variant="caption" color="text.secondary">{t('results.provisional')}</Typography>
    </Stack>
  );
}

function Attendance({ id }: { id: number }) {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['family', 'attendance', id], queryFn: () => api<MyAttendance>(`/api/family/students/${id}/attendance`, family) });
  const a = q.data;
  if (q.isLoading) return <Loading />;
  if (!a) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  if (!a.semesterId) return <Alert severity="info">{t('attendance.none')}</Alert>;
  const pct = (n?: number | null) => (n == null ? '—' : `${Number(n).toLocaleString('en-GB', { maximumFractionDigits: 1 })}%`);
  return (
    <Card sx={{ borderRadius: 3 }}><CardContent>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>{t('attendance.semester', { semester: a.semesterName, threshold: pct(a.threshold) })}</Typography>
      <Stack spacing={1.5}>
        {a.modules.map((m) => (
          <Box key={m.code}>
            <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={1}>
              <Box sx={{ minWidth: 0 }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>{m.code}</Typography>
                <Typography variant="caption" color="text.secondary" noWrap component="div">{m.title}</Typography>
              </Box>
              <Typography sx={{ fontWeight: 700 }} color={m.below ? 'warning.main' : undefined}>{pct(m.percent)}</Typography>
            </Stack>
            <LinearProgress variant="determinate" value={Math.min(100, Number(m.percent ?? 0))} color={m.below ? 'warning' : 'success'}
              sx={{ my: 0.5, height: 6, borderRadius: 3, opacity: m.percent == null ? 0.3 : 1 }} />
            {m.eligible === false && <Chip size="small" color="error" label={t('attendance.notEligible')} />}
          </Box>
        ))}
      </Stack>
    </CardContent></Card>
  );
}
