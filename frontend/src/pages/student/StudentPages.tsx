import CampaignIcon from '@mui/icons-material/CampaignOutlined';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import DownloadIcon from '@mui/icons-material/Download';
import EditIcon from '@mui/icons-material/EditOutlined';
import PushPinIcon from '@mui/icons-material/PushPinOutlined';
import ScheduleIcon from '@mui/icons-material/Schedule';
import {
  Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Card, CardActionArea, CardContent, Chip, Container, Dialog,
  DialogActions, DialogContent, DialogTitle, Divider, LinearProgress, Link, Stack, TextField, Typography, useMediaQuery, useTheme,
} from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import { api, download, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type { ChangeRequest, Clearance, Statement, StudentDashboard, StudentNotice, StudentProfile, TokenResponse } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, humanise, statusColour } from '../../components/common';
import StudentAttendanceCard from './StudentAttendanceCard';
import StudentClearanceCard from './StudentClearanceCard';
import StudentHostelCard from './StudentHostelCard';
import StudentIptCard from './StudentIptCard';
import { useInstitution } from '../../components/InstitutionContext';
import { HowToPay, usePayWords } from '../../components/HowToPay';
import StudentTransferCard from './StudentTransferCard';
import EmailCard from '../../components/EmailCard';
import PrivacyCard from '../../components/PrivacyCard';
import AlumniCard from '../../components/AlumniCard';
import NinCard from '../../components/NinCard';

const student = { realm: 'student' as const };
const SHOW_DEV_TOOLS = import.meta.env.DEV || import.meta.env.VITE_SHOW_DEV_TOOLS === 'true';

// ============================================================ sign-in

export function StudentLoginPage() {
  const { t } = useTranslation();
  const inst = useInstitution();
  const navigate = useNavigate();
  const { signIn } = useSession();
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/student';
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const m = useMutation({
    mutationFn: () => api<TokenResponse>('/api/student-auth/login', { method: 'POST', body: { login, password } }),
    onSuccess: (tok) => { signIn('student', tok); navigate(from.startsWith('/student') ? from : '/student'); },
  });
  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'background.default', display: 'flex', alignItems: { sm: 'center' } }}>
      <Container maxWidth="xs" sx={{ py: 4 }}>
        <Stack spacing={1} alignItems="center" sx={{ mb: 3, textAlign: 'center' }}>
          <Box sx={{ width: 56, height: 56, borderRadius: '50%', bgcolor: 'primary.main', color: '#fff', display: 'grid', placeItems: 'center',
            fontFamily: 'Sora', fontWeight: 700, fontSize: 14 }}>{(inst.shortName ?? inst.name).slice(0, 4)}</Box>
          <Typography variant="h5" component="h1">{t('student.signInTitle')}</Typography>
          <Typography variant="body2" color="text.secondary">{inst.name}</Typography>
        </Stack>
        <Card sx={{ borderRadius: 3 }}>
          <CardContent sx={{ p: 3 }}>
            {new URLSearchParams(window.location.search).get('idle') === '1' && <Alert severity="info" sx={{ mb: 2 }}>{t('idle.signedOut')}</Alert>}
            <form onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
              <Stack spacing={2}>
                <Typography variant="body2" color="text.secondary">{t('student.signInHint')}</Typography>
                <TextField label={t('student.login')} value={login} onChange={(e) => setLogin(e.target.value)} required autoComplete="username" />
                <TextField label={t('auth.password')} value={password} onChange={(e) => setPassword(e.target.value)} type="password" required autoComplete="current-password" />
                <ErrorAlert error={m.error} />
                <Button type="submit" variant="contained" size="large" disabled={m.isPending}>{t('auth.signIn')}</Button>
                <Link component={RouterLink} to="/forgot-password" sx={{ alignSelf: 'center' }}>{t('auth.forgot')}</Link>
              </Stack>
            </form>
          </CardContent>
        </Card>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 2, textAlign: 'center' }}>
          {t('student.notStudentYet')} <Link component={RouterLink} to="/login">{t('student.toAdmission')}</Link>
        </Typography>
      </Container>
    </Box>
  );
}

// ============================================================ home (dashboard)

function useDashboard() {
  return useQuery({ queryKey: ['student', 'dashboard'], queryFn: () => api<StudentDashboard>('/api/student/dashboard', student) });
}

export function StudentHome() {
  const { t } = useTranslation();
  const words = usePayWords();
  const navigate = useNavigate();
  const q = useDashboard();
  const d = q.data;
  if (q.isLoading) return <Loading />;
  if (!d) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  const paidPct = d.billed > 0 ? Math.min(100, Math.round((d.paid / d.billed) * 100)) : 0;
  return (
    <Stack spacing={2}>
      <Card sx={{ borderRadius: 3, bgcolor: 'primary.main', color: 'primary.contrastText', border: 0 }}>
        <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
          <Typography sx={{ opacity: 0.85 }}>{t('student.hello', { name: d.firstName })}</Typography>
          <Typography variant="h5" component="h1" sx={{ mt: 0.5 }}>{d.programmeName}</Typography>
          <Typography variant="body2" sx={{ opacity: 0.85 }}>{t('student.year', { n: d.yearOfStudy })} · {d.academicYear}</Typography>
          <Box sx={{ mt: 2, p: 1.5, borderRadius: 2, bgcolor: 'rgba(255,255,255,0.12)' }}>
            <Typography variant="caption" sx={{ opacity: 0.85 }}>{t('student.regNo')}</Typography>
            <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 20, letterSpacing: 0.5, wordBreak: 'break-all' }}>{d.registrationNo}</Typography>
          </Box>
        </CardContent>
      </Card>

      <NextStepsCard steps={d.next ?? []} />

      {d.status === 'ADMITTED' && d.firstRegistrationStatus !== 'APPROVED' && (
        <Alert severity="info"><strong>{t('student.statusADMITTED')}.</strong> {t('student.reportHint')}</Alert>
      )}
      {d.pendingRequest && <Alert severity="info" icon={<ScheduleIcon />}>{t('student.pendingRequest')}</Alert>}

      <Card sx={{ borderRadius: 3 }}>
        <CardActionArea onClick={() => navigate('/student/fees')}>
          <CardContent>
            <Stack direction="row" alignItems="center" spacing={1}>
              <Box sx={{ flexGrow: 1 }}>
                <Typography variant="body2" color="text.secondary">{t('student.balance')}</Typography>
                <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 26, color: d.balance > 0 ? 'text.primary' : 'success.main' }}>
                  {formatTzs(d.balance)}
                </Typography>
              </Box>
              <ChevronRightIcon color="action" />
            </Stack>
            {d.billed > 0 && (
              <>
                <LinearProgress variant="determinate" value={paidPct} sx={{ mt: 1, height: 8, borderRadius: 4 }} color={paidPct >= 100 ? 'success' : 'primary'} />
                <Typography variant="caption" color="text.secondary">{t('student.paidSoFar')}: {formatTzs(d.paid)} / {formatTzs(d.billed)} ({paidPct}%)</Typography>
              </>
            )}
            {d.nextPayment ? (
              <Box sx={{ mt: 1.5, p: 1.5, borderRadius: 2, bgcolor: 'action.hover' }}>
                <Typography variant="body2"><strong>{t('student.nextPayment')}:</strong> {formatTzs(d.nextPayment.balance)}
                  {d.nextPayment.dueDate && <> · {t('student.dueBy', { date: formatDate(d.nextPayment.dueDate) })}</>}</Typography>
                {d.nextPayment.controlNo && <Typography variant="body2" color="text.secondary">{words.number}: <strong style={{ fontFamily: 'monospace', fontSize: 15 }}>{d.nextPayment.controlNo}</strong></Typography>}
              </Box>
            ) : d.billed > 0 && <Typography variant="body2" color="success.main" sx={{ mt: 1 }}>{t('student.allPaid')}</Typography>}
          </CardContent>
        </CardActionArea>
      </Card>

      <StudentClearanceCard />
      <StudentHostelCard />
      <StudentIptCard />
      <StudentAttendanceCard />

      <Card sx={{ borderRadius: 3 }}>
        <CardContent>
          <Typography variant="h6" sx={{ mb: 1 }}>{t('student.clearance')}</Typography>
          <Stack divider={<Divider flexItem />} spacing={1}>
            {d.clearance.map((c) => <ClearanceRow key={c.gate} c={c} />)}
          </Stack>
        </CardContent>
      </Card>

      <Card sx={{ borderRadius: 3 }}>
        <CardContent>
          <Stack direction="row" alignItems="center" sx={{ mb: 1 }}>
            <Typography variant="h6" sx={{ flexGrow: 1 }}>{t('student.latestNotices')}</Typography>
            {d.unreadNotices > 0 && <Chip size="small" color="error" label={t('student.unread', { count: d.unreadNotices })} />}
          </Stack>
          {!d.latestNotices.length ? <Typography color="text.secondary">{t('student.noNotices')}</Typography> : (
            <Stack divider={<Divider flexItem />}>
              {d.latestNotices.map((n) => (
                <Box key={n.id} onClick={() => navigate('/student/notices', { state: { open: n.id } })} sx={{ py: 1, cursor: 'pointer' }}>
                  <Stack direction="row" spacing={1} alignItems="center">
                    {!n.read && <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: 'error.main', flexShrink: 0 }} />}
                    <Typography sx={{ fontWeight: n.read ? 400 : 700, flexGrow: 1 }} noWrap>{n.title}</Typography>
                    <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>{formatDate(n.publishAt)}</Typography>
                  </Stack>
                </Box>
              ))}
            </Stack>
          )}
          <Button component={RouterLink} to="/student/notices" size="small" sx={{ mt: 1, px: 0 }}>{t('student.allNotices')}</Button>
        </CardContent>
      </Card>
    </Stack>
  );
}

export function ClearanceRow({ c }: { c: Clearance }) {
  const { t } = useTranslation();
  return (
    <Stack direction="row" spacing={1.5} alignItems="center">
      <Box sx={{ flexGrow: 1 }}>
        <Typography>{t(`student.gate${c.gate}`, { defaultValue: humanise(c.gate) })}</Typography>
        <Typography variant="caption" color="text.secondary">
          {!c.enabled ? t('student.gateOff') : c.cleared ? (c.reason ?? '') : c.amountNeeded ? t('student.stillToPay', { amount: formatTzs(c.amountNeeded) }) : c.reason}
        </Typography>
      </Box>
      <Chip size="small" icon={c.cleared ? <CheckCircleIcon /> : undefined} color={c.cleared ? 'success' : 'warning'}
        label={c.cleared ? t('student.cleared') : t('student.notCleared')} />
    </Stack>
  );
}

// ============================================================ fees (FR-STU-02)

export function StudentFees() {
  const { t } = useTranslation();
  const words = usePayWords();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['student', 'account'], queryFn: () => api<Statement>('/api/student/account', student) });
  const refresh = (st: Statement) => { qc.setQueryData(['student', 'account'], st); qc.invalidateQueries({ queryKey: ['student', 'dashboard'] }); };
  const getCn = useMutation({ mutationFn: (id: number) => api<Statement>(`/api/student/invoices/${id}/control-number`, { ...student, method: 'POST' }), onSuccess: refresh });
  const simulate = useMutation({ mutationFn: (id: number) => api<Statement>(`/api/student/invoices/${id}/simulate-payment`, { ...student, method: 'POST', body: {} }), onSuccess: refresh });
  const [dlError, setDlError] = useState<unknown>(null);
  const st = q.data;
  if (q.isLoading) return <Loading />;
  if (!st) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  const receipt = (no: string) => download(`/api/student/receipt.pdf?no=${encodeURIComponent(no)}`, 'student', `receipt-${no.replace(/\W+/g, '-')}.pdf`).catch(setDlError);
  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">{t('student.navFees')}</Typography>
      <Grid container spacing={1.5}>
        <Figure label={t('student.billed')} value={formatTzs(st.billed)} />
        <Figure label={t('student.paidSoFar')} value={formatTzs(st.paid)} />
        <Figure label={t('student.balance')} value={formatTzs(st.balance)} tone={st.balance > 0 ? 'warning.main' : 'success.main'} />
      </Grid>
      {st.credit > 0 && <Alert severity="info">{t('fees.credit', { amount: formatTzs(st.credit) })}</Alert>}
      {(st.sponsors ?? []).map((sp) => (
        <Alert key={sp.sponsor + sp.academicYear} severity="success">
          {t('fees.sponsored', { sponsor: sp.sponsor, year: sp.academicYear, percent: Number(sp.coveragePercent).toLocaleString('en-GB'), interpolation: { escapeValue: false } })}
          {sp.capAmount ? ` ${t('fees.sponsorCap', { amount: formatTzs(sp.capAmount) })}` : ''}
          {!!st.sponsorPending && st.sponsorPending > 0 && <> {t('fees.sponsorPending', { amount: formatTzs(st.sponsorPending) })}</>}
        </Alert>
      ))}

      <Typography variant="h6">{t('student.invoices')}</Typography>
      {st.invoices.map((inv) => (
        <Card key={inv.id} sx={{ borderRadius: 3 }}>
          <CardContent>
            <Stack direction="row" spacing={1} alignItems="flex-start">
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Typography sx={{ fontWeight: 700 }}>{inv.description}</Typography>
                <Typography variant="caption" color="text.secondary">{inv.invoiceNo}{inv.dueDate && <> · {t('student.dueBy', { date: formatDate(inv.dueDate) })}</>}</Typography>
              </Box>
              <Chip size="small" color={statusColour(inv.status)} label={humanise(inv.status)} />
            </Stack>
            <Stack direction="row" spacing={3} sx={{ my: 1.5 }} useFlexGap flexWrap="wrap">
              <Amount label={t('student.total')} value={inv.total} />
              <Amount label={t('fees.paid')} value={inv.paid} />
              <Amount label={t('fees.balance')} value={inv.balance} strong />
            </Stack>
            {inv.balance > 0 && !['CANCELLED', 'CREDITED'].includes(inv.status) && (
              inv.controlNo ? (
                <Box sx={{ p: 1.5, borderRadius: 2, bgcolor: 'action.hover' }}>
                  <Typography variant="caption" color="text.secondary">{words.number}</Typography>
                  <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 24, letterSpacing: 1 }}>{inv.controlNo}</Typography>
                  <HowToPay reference={inv.controlNo} amount={inv.balance} />
                </Box>
              ) : <Button variant="contained" disabled={getCn.isPending} onClick={() => getCn.mutate(inv.id)}>{words.getNumber}</Button>
            )}
            {SHOW_DEV_TOOLS && inv.balance > 0 && (
              <Button size="small" color="warning" variant="outlined" sx={{ mt: 1.5 }} disabled={simulate.isPending} onClick={() => simulate.mutate(inv.id)}>{t('student.simulatePay')}</Button>
            )}
            {inv.lines.length > 1 && (
              <Accordion disableGutters elevation={0} sx={{ mt: 1.5, '&:before': { display: 'none' }, bgcolor: 'transparent' }}>
                <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: 0, minHeight: 0 }}><Typography variant="body2">{t('student.lines')}</Typography></AccordionSummary>
                <AccordionDetails sx={{ px: 0, pt: 0 }}>
                  {inv.lines.map((l, i) => (
                    <Stack key={i} direction="row" spacing={1} sx={{ py: 0.5 }}>
                      <Typography variant="body2" sx={{ flexGrow: 1 }}>{l.description}{l.instalment > 1 ? ` (${t('student.instalment', { n: l.instalment })})` : ''}</Typography>
                      <Typography variant="body2" color={l.balance > 0 ? 'text.primary' : 'success.main'} sx={{ whiteSpace: 'nowrap' }}>
                        {formatTzs(l.paid)} / {formatTzs(l.amount)}
                      </Typography>
                    </Stack>
                  ))}
                </AccordionDetails>
              </Accordion>
            )}
          </CardContent>
        </Card>
      ))}
      <ErrorAlert error={getCn.error ?? simulate.error} />

      <Typography variant="h6">{t('student.paymentHistory')}</Typography>
      <Card sx={{ borderRadius: 3 }}>
        <CardContent>
          {!st.payments.length ? <Typography color="text.secondary">{t('student.noPayments')}</Typography> : (
            <Stack divider={<Divider flexItem />}>
              {[...st.payments].reverse().map((p) => (
                <Stack key={p.receiptNo} direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 0.5, sm: 1.5 }} alignItems={{ sm: 'center' }} sx={{ py: 1.25 }}>
                  <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                    <Typography sx={{ fontWeight: 700 }}>{formatTzs(p.amount)}</Typography>
                    <Typography variant="caption" color="text.secondary" display="block">
                      {formatDateTime(p.paidAt)} · {[p.pspName, p.channel].filter(Boolean).join(' · ')}
                    </Typography>
                    <Typography variant="caption" color="text.secondary" display="block" sx={{ wordBreak: 'break-all' }}>{t('fees.receipt')} {p.receiptNo}</Typography>
                  </Box>
                  <Button size="small" startIcon={<DownloadIcon />} onClick={() => receipt(p.receiptNo)} sx={{ alignSelf: { xs: 'flex-start', sm: 'center' }, whiteSpace: 'nowrap' }}>
                    {t('student.downloadReceipt')}
                  </Button>
                </Stack>
              ))}
            </Stack>
          )}
          <ErrorAlert error={dlError} />
        </CardContent>
      </Card>
    </Stack>
  );
}

/** What the student should do next, across registration, fees, attendance, resits, hostel, IPT and clearance. */
function NextStepsCard({ steps }: { steps: StudentDashboard['next'] }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  if (!steps.length) return null;
  const colour = { success: 'success.main', info: 'info.main', warning: 'warning.main', error: 'error.main' } as const;
  return (
    <Card sx={{ borderRadius: 3 }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 1 }}>{t('next.title')}</Typography>
        <Stack divider={<Divider flexItem />}>
          {steps.map((s, i) => (
            <Stack key={i} direction="row" spacing={1.25} alignItems="center" sx={{ py: 1 }}>
              <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: colour[s.severity], flexShrink: 0 }} />
              <Typography variant="body2" sx={{ flex: 1 }}>{t(`next.${s.kind}`, { ...s.values, interpolation: { escapeValue: false } })}</Typography>
              {s.link && <Button size="small" onClick={() => navigate(s.link!)}>{t('next.open')}</Button>}
            </Stack>
          ))}
        </Stack>
      </CardContent>
    </Card>
  );
}

function Figure({ label, value, tone }: { label: string; value: ReactNode; tone?: string }) {
  return (
    <Grid size={{ xs: 12, sm: 4 }}>
      <Card sx={{ borderRadius: 3, height: '100%' }}><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
        <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 20, color: tone }}>{value}</Typography>
      </CardContent></Card>
    </Grid>
  );
}

function Amount({ label, value, strong }: { label: string; value: number; strong?: boolean }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography sx={{ fontWeight: strong ? 700 : 600 }}>{formatTzs(value)}</Typography>
    </Box>
  );
}

// ============================================================ notices

export function StudentNotices() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const openId = (useLocation().state as { open?: number } | null)?.open;
  const [open, setOpen] = useState<StudentNotice | null>(null);
  const q = useQuery({ queryKey: ['student', 'notices'], queryFn: () => api<StudentNotice[]>('/api/student/notices', student) });
  const read = useMutation({
    mutationFn: (id: number) => api<StudentNotice>(`/api/student/notices/${id}/read`, { ...student, method: 'POST' }),
    onSuccess: (n) => {
      qc.setQueryData<StudentNotice[]>(['student', 'notices'], (old) => old?.map((x) => (x.id === n.id ? n : x)));
      qc.invalidateQueries({ queryKey: ['student', 'dashboard'] });
    },
  });
  const show = (n: StudentNotice) => { setOpen(n); if (!n.read) read.mutate(n.id); };
  const [autoOpened, setAutoOpened] = useState(false);
  if (q.data && openId && !autoOpened) {
    const n = q.data.find((x) => x.id === openId);
    setAutoOpened(true);
    if (n) show(n);
  }
  if (q.isLoading) return <Loading />;
  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">{t('student.notices')}</Typography>
      <ErrorAlert error={q.error} />
      {q.data && !q.data.length && (
        <Card sx={{ borderRadius: 3 }}><CardContent sx={{ textAlign: 'center', py: 5 }}>
          <CampaignIcon color="disabled" sx={{ fontSize: 48 }} />
          <Typography color="text.secondary">{t('student.noNotices')}</Typography>
        </CardContent></Card>
      )}
      {q.data?.map((n) => (
        <Card key={n.id} sx={{ borderRadius: 3, borderLeft: n.read ? undefined : '4px solid', borderLeftColor: 'primary.main' }}>
          <CardActionArea onClick={() => show(n)}>
            <CardContent>
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }} useFlexGap flexWrap="wrap">
                {n.pinned && <Chip size="small" icon={<PushPinIcon />} label={t('student.pinned')} />}
                {!n.read && <Chip size="small" color="primary" label={t('student.new')} />}
                <Chip size="small" variant="outlined" label={humanise(n.category)} />
                <Typography variant="caption" color="text.secondary" sx={{ ml: 'auto' }}>{formatDate(n.publishAt)}</Typography>
              </Stack>
              <Typography sx={{ fontWeight: 700 }}>{n.title}</Typography>
              <Typography variant="body2" color="text.secondary" sx={{ display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{n.body}</Typography>
            </CardContent>
          </CardActionArea>
        </Card>
      ))}
      <NoticeDialog notice={open} onClose={() => setOpen(null)} />
    </Stack>
  );
}

function NoticeDialog({ notice, onClose }: { notice: StudentNotice | null; onClose: () => void }) {
  const { t } = useTranslation();
  const theme = useTheme();
  const full = useMediaQuery(theme.breakpoints.down('sm'));
  return (
    <Dialog open={!!notice} onClose={onClose} fullScreen={full} maxWidth="sm" fullWidth>
      {notice && (
        <>
          <DialogTitle>{notice.title}</DialogTitle>
          <DialogContent>
            <Typography variant="caption" color="text.secondary" display="block" sx={{ mb: 2 }}>
              {humanise(notice.category)} · {formatDateTime(notice.publishAt)}{notice.publishedByName && <> · {t('student.postedBy', { name: notice.publishedByName })}</>}
            </Typography>
            <Typography sx={{ whiteSpace: 'pre-line' }}>{notice.body}</Typography>
          </DialogContent>
          <DialogActions><Button onClick={onClose}>OK</Button></DialogActions>
        </>
      )}
    </Dialog>
  );
}

// ============================================================ profile and correction requests (FR-REG-08)

const GROUPS: { key: string; fields: string[] }[] = [
  { key: 'personal', fields: ['firstName', 'middleName', 'surname', 'dateOfBirth', 'disability'] },
  { key: 'contacts', fields: ['phone', 'email', 'region', 'district', 'postalAddress'] },
  { key: 'nextOfKin', fields: ['nextOfKinName', 'nextOfKinPhone', 'nextOfKinRelation'] },
];

export function StudentProfilePage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [editing, setEditing] = useState(false);
  const q = useQuery({ queryKey: ['student', 'profile'], queryFn: () => api<StudentProfile>('/api/student/profile', student) });
  const cancel = useMutation({
    mutationFn: (id: number) => api<ChangeRequest>(`/api/student/profile/change-requests/${id}/cancel`, { ...student, method: 'POST' }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['student'] }); },
  });
  const p = q.data;
  if (q.isLoading) return <Loading />;
  if (!p) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  const pending = p.requests.find((r) => r.status === 'PENDING');
  const value = (f: string) => {
    const v = (p as unknown as Record<string, string | undefined>)[f];
    return f === 'dateOfBirth' && v ? formatDate(v) : v || '—';
  };
  return (
    <Stack spacing={2}>
      <Stack direction="row" alignItems="center" spacing={1}>
        <Typography variant="h5" component="h1" sx={{ flexGrow: 1, minWidth: 0 }} noWrap>{t('student.profile')}</Typography>
        <Button variant="outlined" startIcon={<EditIcon />} disabled={!!pending} onClick={() => setEditing(true)} sx={{ whiteSpace: 'nowrap', flexShrink: 0 }}>{t('student.requestChange')}</Button>
      </Stack>

      <Card sx={{ borderRadius: 3 }}>
        <CardContent>
          <Typography variant="h6" sx={{ mb: 1 }}>{t('student.programme')}</Typography>
          <Rows rows={[
            [t('student.regNo'), <strong key="r" style={{ fontFamily: 'monospace' }}>{p.registrationNo}</strong>],
            [t('student.programme'), `${p.programmeName} (${p.programmeCode})`],
            [t('student.award'), p.awardName],
            ['', `${t('student.year', { n: p.yearOfStudy })} · ${p.academicYear} · ${p.intake}`],
            [t('student.status'), <Chip key="s" size="small" color={statusColour(p.status)} label={humanise(p.status)} />],
            ...(p.nactvetVerificationNo ? [['NACTVET', p.nactvetVerificationNo] as [string, ReactNode]] : []),
          ]} />
          <Typography variant="caption" color="text.secondary">{t('student.readOnlyNote')}</Typography>
        </CardContent>
      </Card>

      <StudentTransferCard />
      <EmailCard realm="student" />
      <NinCard />
      <AlumniCard />
      <PrivacyCard realm="student" />

      {GROUPS.map((g) => (
        <Card key={g.key} sx={{ borderRadius: 3 }}>
          <CardContent>
            <Typography variant="h6" sx={{ mb: 1 }}>{t(`student.${g.key}`)}</Typography>
            <Rows rows={[
              ...(g.key === 'personal' ? [[t('student.sex'), p.sex === 'F' ? t('student.female') : t('student.male')] as [string, ReactNode], [t('student.nationality'), p.nationality] as [string, ReactNode]] : []),
              ...g.fields.map((f) => [t(`student.fields.${f}`, { defaultValue: p.editableFields[f] ?? f }), value(f)] as [string, ReactNode]),
            ]} />
          </CardContent>
        </Card>
      ))}

      {!!p.requests.length && (
        <Card sx={{ borderRadius: 3 }}>
          <CardContent>
            <Typography variant="h6" sx={{ mb: 1 }}>{t('student.requests')}</Typography>
            <Stack divider={<Divider flexItem />} spacing={1.5}>
              {p.requests.map((r) => (
                <Box key={r.id}>
                  <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
                    <Chip size="small" color={r.status === 'APPROVED' ? 'success' : r.status === 'REJECTED' ? 'error' : r.status === 'PENDING' ? 'warning' : 'default'}
                      label={t(`student.status${r.status}`)} />
                    <Typography variant="caption" color="text.secondary">{formatDateTime(r.submittedAt)}</Typography>
                  </Stack>
                  {r.changes.map((c) => (
                    <Typography key={c.field} variant="body2"><strong>{c.label}:</strong> {c.from || '—'} → {c.to || '—'}</Typography>
                  ))}
                  {r.status === 'PENDING' && r.needsDocuments && <Typography variant="body2" color="warning.main">{t('student.bringDocuments')}</Typography>}
                  {r.decisionNote && <Typography variant="body2" color="text.secondary">“{r.decisionNote}” — {r.decidedByName}</Typography>}
                  {r.status === 'PENDING' && (
                    <Button size="small" color="inherit" disabled={cancel.isPending} onClick={() => cancel.mutate(r.id)} sx={{ mt: 0.5, px: 0 }}>{t('student.cancelRequest')}</Button>
                  )}
                </Box>
              ))}
            </Stack>
            <ErrorAlert error={cancel.error} />
          </CardContent>
        </Card>
      )}
      {editing && <ChangeDialog profile={p} onClose={() => setEditing(false)} />}
    </Stack>
  );
}

function Rows({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <Stack spacing={0.75} sx={{ mb: 1 }}>
      {rows.map(([k, v], i) => (
        <Stack key={i} direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 0, sm: 2 }}>
          <Typography variant="body2" color="text.secondary" sx={{ width: { sm: 200 }, flexShrink: 0 }}>{k}</Typography>
          <Typography variant="body2" component="div" sx={{ wordBreak: 'break-word' }}>{v}</Typography>
        </Stack>
      ))}
    </Stack>
  );
}

function ChangeDialog({ profile, onClose }: { profile: StudentProfile; onClose: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const theme = useTheme();
  const full = useMediaQuery(theme.breakpoints.down('sm'));
  const original = profile as unknown as Record<string, string | undefined>;
  const [values, setValues] = useState<Record<string, string>>(
    () => Object.fromEntries(Object.keys(profile.editableFields).map((f) => [f, original[f] ?? ''])),
  );
  const [reason, setReason] = useState('');
  const changed = Object.fromEntries(Object.entries(values).filter(([f, v]) => v.trim() !== (original[f] ?? '')));
  const m = useMutation({
    mutationFn: () => api<ChangeRequest>('/api/student/profile/change-requests', { ...student, method: 'POST', body: { changes: changed, reason } }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['student'] }); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} fullScreen={full} maxWidth="sm" fullWidth>
      <DialogTitle>{t('student.requestTitle')}</DialogTitle>
      <DialogContent>
        <Alert severity="info" sx={{ mb: 2 }}>{t('student.requestHint')}</Alert>
        {GROUPS.map((g) => (
          <Box key={g.key} sx={{ mb: 2 }}>
            <Typography variant="subtitle2" sx={{ mb: 1 }}>{t(`student.${g.key}`)}</Typography>
            <Grid container spacing={1.5}>
              {g.fields.map((f) => (
                <Grid key={f} size={{ xs: 12, sm: f === 'postalAddress' || f === 'disability' ? 12 : 6 }}>
                  <TextField size="small" label={t(`student.fields.${f}`, { defaultValue: profile.editableFields[f] })} value={values[f]} type={f === 'dateOfBirth' ? 'date' : 'text'}
                    slotProps={{ inputLabel: { shrink: true } }} onChange={(e) => setValues({ ...values, [f]: e.target.value })}
                    sx={changed[f] !== undefined ? { '& .MuiOutlinedInput-root': { bgcolor: 'rgba(227,167,47,0.08)' } } : undefined} />
                </Grid>
              ))}
            </Grid>
          </Box>
        ))}
        <TextField multiline minRows={2} label={t('student.reason')} value={reason} onChange={(e) => setReason(e.target.value)} inputProps={{ maxLength: 500 }} />
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions>
        <Typography variant="caption" color="text.secondary" sx={{ flexGrow: 1, pl: 2 }}>{!Object.keys(changed).length ? t('student.noChange') : ''}</Typography>
        <Button onClick={onClose}>{t('student.cancel')}</Button>
        <Button variant="contained" disabled={!Object.keys(changed).length || !reason.trim() || m.isPending} onClick={() => m.mutate()}>{t('student.send')}</Button>
      </DialogActions>
    </Dialog>
  );
}
