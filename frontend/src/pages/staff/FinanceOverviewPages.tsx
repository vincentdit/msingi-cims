import AddIcon from '@mui/icons-material/Add';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import DownloadIcon from '@mui/icons-material/Download';
import ErrorIcon from '@mui/icons-material/Error';
import InfoIcon from '@mui/icons-material/Info';
import UploadIcon from '@mui/icons-material/UploadFile';
import WarningIcon from '@mui/icons-material/Warning';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Divider, IconButton, LinearProgress, MenuItem,
  Stack, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Tooltip, Typography,
  FormControlLabel, Switch,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { Link as RouterLink, Navigate, useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime, formatTzs, upload } from '../../api/client';
import { BarList, ColumnChart } from '../../components/charts';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';

const staff = { realm: 'staff' as const };
const BURSAR = ['INSTITUTION_ADMIN', 'BURSAR'];
const BURSARY = ['INSTITUTION_ADMIN', 'BURSAR', 'ACCOUNTANT'];
const SEV_ICON = { success: <CheckCircleIcon color="success" />, info: <InfoIcon color="info" />, warning: <WarningIcon color="warning" />, error: <ErrorIcon color="error" /> };
const short = (n: number) => (n >= 1_000_000 ? `${(n / 1_000_000).toFixed(n >= 10_000_000 ? 0 : 1)}M` : n >= 1000 ? `${Math.round(n / 1000)}k` : String(n));
const pct = (n?: number | null) => (n == null ? '—' : `${Number(n).toLocaleString('en-GB', { maximumFractionDigits: 1 })}%`);
const today = () => new Date(Date.now() + 3 * 3600_000).toISOString().slice(0, 10);

// ============================================================ the Bursar's dashboard and the Accountant's desk

interface Amount { key: string; label: string; amount: number; count: number }
interface Milestone { dueDate: string; percent: number; expected: number; passed: boolean }
interface FinanceDashboard {
  years: { id: number; code: string }[]; academicYearId?: number; academicYear?: string; billed: number; paid: number; sponsorPending: number; waived: number;
  outstanding: number; percent: number; targets: Milestone[]; currentTarget?: Milestone; onTrack?: boolean;
  programmes: { programmeId?: number; code: string; name?: string; yearOfStudy: number; invoices: number; billed: number; paid: number; sponsorPending: number; balance: number; percent: number }[];
  ageing: Amount[]; topDebtors: { invoiceNo: string; payerName: string; registrationNo?: string; phone?: string; balance: number; daysOverdue: number }[];
  monthly: Amount[]; byPurpose: Amount[]; cashThisMonth: number; cashToday: number;
  work: { reconciliationExceptions: number; lastSignedOffMonth?: string; lastMonthSignedOff: boolean; adjustmentsOpen: number; adjustmentsOpenAmount: number;
    adjustmentsForMe: number; refundsToPay: number; sponsorInvoicesOpen: number; sponsorOutstanding: number; sponsorNotInvoiced: number; controlNumberFailures: number };
  next: { severity: keyof typeof SEV_ICON; text: string; link?: string }[];
}

function Kpi({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: ReactNode; tone?: string }) {
  return (
    <Grid size={{ xs: 6, md: 'grow' }}>
      <Card sx={{ height: '100%' }}>
        <CardContent>
          <Typography variant="caption" color="text.secondary">{label}</Typography>
          <Typography variant="h5" sx={{ fontWeight: 700 }} color={tone}>{value}</Typography>
          {hint && <Typography variant="caption" color="text.secondary">{hint}</Typography>}
        </CardContent>
      </Card>
    </Grid>
  );
}

/** Finance overview: collections against targets, by programme, debtors, cash month by month, and the work waiting in the bursary. */
export function FinanceOverviewPage() {
  const can = useRoles();
  const [yearId, setYearId] = useState<number | ''>('');
  const [targets, setTargets] = useState(false);
  const [postings, setPostings] = useState(false);
  const q = useQuery({ queryKey: ['staff', 'finance-dashboard', yearId], refetchInterval: 60_000,
    queryFn: () => api<FinanceDashboard>(`/api/finance/dashboard${yearId ? `?academicYearId=${yearId}` : ''}`, staff) });
  const d = q.data;
  return (
    <>
      <PageTitle title="Finance overview" subtitle="Collections against targets, what each class owes, debtors, money received and the work waiting in the bursary."
        action={(
          <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" justifyContent="flex-end">
            {d && d.years.length > 0 && (
              <TextField select size="small" label="Academic year" value={yearId || d.academicYearId || ''} onChange={(e) => setYearId(Number(e.target.value))} sx={{ minWidth: 170 }}>
                {d.years.map((y) => <MenuItem key={y.id} value={y.id}>{y.code}</MenuItem>)}
              </TextField>
            )}
            {can(BURSAR) && d?.academicYearId && <Button onClick={() => setTargets(true)}>Targets</Button>}
            {can([...BURSARY, 'AUDITOR']) && <Button startIcon={<DownloadIcon />} onClick={() => setPostings(true)}>Postings</Button>}
          </Stack>
        )} />
      <ErrorAlert error={q.error} />
      {q.isLoading && <Loading />}
      {d && (
        <>
          <Grid container spacing={2} sx={{ mb: 2.5 }}>
            <Kpi label={`Annual fees ${d.academicYear ?? ''}`} value={formatTzs(d.billed)} hint={`${d.programmes.reduce((n, p) => n + p.invoices, 0)} invoices`} />
            <Kpi label="Paid" value={pct(d.percent)} hint={formatTzs(d.paid)} tone={d.onTrack === false ? 'warning.main' : d.onTrack ? 'success.main' : undefined} />
            <Kpi label="Still owed" value={formatTzs(d.outstanding)} hint={d.sponsorPending > 0 ? `${formatTzs(d.sponsorPending)} due from sponsors` : 'by students'} />
            <Kpi label="Waived" value={formatTzs(d.waived)} hint="Credit notes on annual fees" />
            <Kpi label="Received this month" value={formatTzs(d.cashThisMonth)} hint={`Today ${formatTzs(d.cashToday)}`} />
          </Grid>
          <Grid container spacing={2.5}>
            <Grid size={{ xs: 12, lg: 4 }}>
              <Card sx={{ mb: 2.5 }}>
                <CardContent>
                  <Typography variant="h6" sx={{ mb: 1 }}>Next</Typography>
                  <Stack divider={<Divider flexItem />}>
                    {d.next.map((s, i) => (
                      <Stack key={i} direction="row" spacing={1.5} alignItems="center" sx={{ py: 1 }}>
                        {SEV_ICON[s.severity]}
                        <Typography variant="body2" sx={{ flex: 1 }}>{s.text}</Typography>
                        {s.link && <Button size="small" component={RouterLink} to={s.link} endIcon={<ArrowForwardIcon />}>Open</Button>}
                      </Stack>
                    ))}
                  </Stack>
                </CardContent>
              </Card>
              <Card sx={{ mb: 2.5 }}>
                <CardContent>
                  <Typography variant="h6">Collections against targets</Typography>
                  {!d.targets.length ? (
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>No targets set for {d.academicYear}.{can(BURSAR) ? ' Use Targets to set milestones, e.g. 40% by 30 November.' : ''}</Typography>
                  ) : (
                    <>
                      <Box sx={{ position: 'relative', mt: 2.5, mb: 3.5 }}>
                        <LinearProgress variant="determinate" value={Math.min(100, d.percent)} sx={{ height: 12, borderRadius: 6 }}
                          color={d.onTrack === false ? 'warning' : 'success'} />
                        {d.targets.map((m) => (
                          <Tooltip key={m.dueDate} title={`${pct(m.percent)} by ${formatDate(m.dueDate)} (${formatTzs(m.expected)})`}>
                            <Box sx={{ position: 'absolute', left: `${Math.min(100, m.percent)}%`, top: -6, height: 24, borderLeft: '2px solid', borderColor: m.passed ? 'text.primary' : 'text.disabled' }}>
                              <Typography variant="caption" sx={{ position: 'absolute', top: 24, left: m.percent > 92 ? -34 : -14, whiteSpace: 'nowrap', color: m.passed ? 'text.primary' : 'text.secondary' }}>{pct(m.percent)}</Typography>
                            </Box>
                          </Tooltip>
                        ))}
                      </Box>
                      <Typography variant="body2">
                        {pct(d.percent)} paid.{' '}
                        {d.currentTarget ? <>Target {pct(d.currentTarget.percent)} by {formatDate(d.currentTarget.dueDate)}: <strong>{d.onTrack ? 'on track' : 'behind'}</strong>.</> : 'The first milestone has not come yet.'}
                      </Typography>
                      {d.targets.filter((m) => !m.passed).slice(0, 1).map((m) => (
                        <Typography key={m.dueDate} variant="caption" color="text.secondary">Next: {pct(m.percent)} by {formatDate(m.dueDate)} ({formatTzs(m.expected)})</Typography>
                      ))}
                    </>
                  )}
                </CardContent>
              </Card>
              <Card sx={{ mb: 2.5 }}>
                <CardContent>
                  <Typography variant="h6" sx={{ mb: 1.5 }}>Debtors by age</Typography>
                  <BarList rows={d.ageing.map((b) => ({ key: b.key, label: b.label, value: Number(b.amount), display: formatTzs(b.amount), note: `${b.count} invoice(s)` }))} />
                </CardContent>
              </Card>
              <Card>
                <CardContent>
                  <Typography variant="h6" sx={{ mb: 1 }}>Work in the bursary</Typography>
                  <WorkRow label="GePG reconciliation exceptions" value={d.work.reconciliationExceptions} link="/staff/finance" warn />
                  <WorkRow label="Waivers and refunds waiting" value={d.work.adjustmentsOpen} hint={d.work.adjustmentsOpen ? formatTzs(d.work.adjustmentsOpenAmount) : undefined} link="/staff/waivers" />
                  <WorkRow label="Refunds to pay out" value={d.work.refundsToPay} link="/staff/waivers" />
                  <WorkRow label="Sponsor invoices unpaid" value={d.work.sponsorInvoicesOpen} hint={d.work.sponsorInvoicesOpen ? formatTzs(d.work.sponsorOutstanding) : undefined} link="/staff/sponsors" />
                  <WorkRow label="Sponsors' shares not invoiced" value={d.work.sponsorNotInvoiced > 0 ? formatTzs(d.work.sponsorNotInvoiced) : 0} link="/staff/sponsors" />
                  <WorkRow label="Control numbers failed (7 days)" value={d.work.controlNumberFailures} link="/staff/finance" warn />
                  <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>
                    Reconciliation last signed off: {d.work.lastSignedOffMonth ? new Date(d.work.lastSignedOffMonth).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' }) : 'never'}
                  </Typography>
                </CardContent>
              </Card>
            </Grid>
            <Grid size={{ xs: 12, lg: 8 }}>
              <Card sx={{ mb: 2.5 }}>
                <CardContent>
                  <Typography variant="h6" sx={{ mb: 1 }}>Money received, last 12 months</Typography>
                  <ColumnChart ariaLabel="Money received per month over the last 12 months" format={(v) => `TZS ${short(v)}`}
                    points={d.monthly.map((m) => ({ key: m.key, label: m.label, value: Number(m.amount), tooltip: `${formatTzs(m.amount)} · ${m.count} payment(s)` }))} />
                  <Typography variant="caption" color="text.secondary">GePG payments and bank remittances; sponsors' shares moved onto students and waivers are not money received.</Typography>
                </CardContent>
              </Card>
              <Card sx={{ mb: 2.5 }}>
                <CardContent sx={{ pb: 0 }}><Typography variant="h6">Annual fees by class</Typography></CardContent>
                <TableContainer>
                  <Table size="small">
                    <TableHead><TableRow>
                      <TableCell>Class</TableCell><TableCell align="right" sx={{ display: { xs: 'none', xl: 'table-cell' } }}>Invoices</TableCell><TableCell align="right">Billed</TableCell>
                      <TableCell align="right">Paid</TableCell><TableCell align="right" sx={{ display: { xs: 'none', md: 'table-cell' } }}>From sponsors</TableCell>
                      <TableCell align="right">Owed</TableCell><TableCell sx={{ minWidth: 110 }}>Paid</TableCell>
                    </TableRow></TableHead>
                    <TableBody>
                      {d.programmes.map((p) => (
                        <TableRow key={`${p.programmeId}-${p.yearOfStudy}`}>
                          <TableCell sx={{ whiteSpace: 'nowrap' }}><Tooltip title={p.name ?? ''}><Typography variant="body2" sx={{ fontWeight: 600 }}>{p.code}{p.yearOfStudy ? ` · year ${p.yearOfStudy}` : ''}</Typography></Tooltip>
                            <Typography variant="caption" color="text.secondary">{p.invoices} invoice{p.invoices === 1 ? '' : 's'}</Typography></TableCell>
                          <TableCell align="right" sx={{ display: { xs: 'none', xl: 'table-cell' } }}>{p.invoices}</TableCell>
                          <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(p.billed)}</TableCell>
                          <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(p.paid)}</TableCell>
                          <TableCell align="right" sx={{ whiteSpace: 'nowrap', display: { xs: 'none', md: 'table-cell' } }}>{p.sponsorPending > 0 ? formatTzs(p.sponsorPending) : '—'}</TableCell>
                          <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(p.balance)}</TableCell>
                          <TableCell>
                            <Stack direction="row" spacing={1} alignItems="center">
                              <LinearProgress variant="determinate" value={Math.min(100, Number(p.percent))} sx={{ flexGrow: 1, height: 6, borderRadius: 3 }} />
                              <Typography variant="caption" sx={{ minWidth: 40, textAlign: 'right' }}>{pct(p.percent)}</Typography>
                            </Stack>
                          </TableCell>
                        </TableRow>
                      ))}
                      {!d.programmes.length && <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 1 }}>No annual fees billed for {d.academicYear} yet.</Typography></TableCell></TableRow>}
                    </TableBody>
                  </Table>
                </TableContainer>
              </Card>
              <Grid container spacing={2.5}>
                <Grid size={{ xs: 12, md: 6 }}>
                  <Card sx={{ height: '100%' }}>
                    <CardContent>
                      <Typography variant="h6" sx={{ mb: 1.5 }}>Received by purpose (12 months)</Typography>
                      <BarList rows={d.byPurpose.map((b) => ({ key: b.key, label: b.label, value: Number(b.amount), display: formatTzs(b.amount), note: `${b.count} payment(s)` }))} />
                    </CardContent>
                  </Card>
                </Grid>
                <Grid size={{ xs: 12, md: 6 }}>
                  <Card sx={{ height: '100%' }}>
                    <CardContent sx={{ pb: 0 }}><Typography variant="h6">Largest debtors</Typography></CardContent>
                    <Table size="small">
                      <TableBody>
                        {d.topDebtors.map((t) => (
                          <TableRow key={t.invoiceNo}>
                            <TableCell>
                              <Typography variant="body2" sx={{ fontWeight: 600 }}>{t.payerName}</Typography>
                              <Typography variant="caption" color="text.secondary">{t.registrationNo ?? t.invoiceNo}{t.daysOverdue > 0 ? ` · ${t.daysOverdue} days overdue` : ''}</Typography>
                            </TableCell>
                            <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(t.balance)}</TableCell>
                          </TableRow>
                        ))}
                        {!d.topDebtors.length && <TableRow><TableCell><Typography color="text.secondary">Nobody owes annual fees.</Typography></TableCell></TableRow>}
                      </TableBody>
                    </Table>
                  </Card>
                </Grid>
              </Grid>
            </Grid>
          </Grid>
          {targets && d.academicYearId && <TargetsDialog yearId={d.academicYearId} year={d.academicYear ?? ''} onClose={() => setTargets(false)} />}
          {postings && <PostingsDialog onClose={() => setPostings(false)} />}
        </>
      )}
    </>
  );
}

function WorkRow({ label, value, hint, link, warn }: { label: string; value: number | string; hint?: string; link: string; warn?: boolean }) {
  const zero = value === 0;
  return (
    <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ py: 0.5 }}>
      <Typography variant="body2" color={zero ? 'text.secondary' : undefined}>{label}{hint && <Typography component="span" variant="caption" color="text.secondary"> · {hint}</Typography>}</Typography>
      <Button size="small" component={RouterLink} to={link} color={!zero && warn ? 'warning' : 'primary'} sx={{ minWidth: 0, fontWeight: 700 }}>{value}</Button>
    </Stack>
  );
}

function TargetsDialog({ yearId, year, onClose }: { yearId: number; year: string; onClose: () => void }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'finance-targets', yearId], queryFn: () => api<{ dueDate: string; percent: number }[]>(`/api/finance/targets?academicYearId=${yearId}`, staff) });
  const [rows, setRows] = useState<{ dueDate: string; percent: string }[] | null>(null);
  useEffect(() => { if (q.data && rows == null) setRows(q.data.map((t) => ({ dueDate: t.dueDate, percent: String(t.percent) }))); }, [q.data, rows]);
  const save = useMutation({
    mutationFn: () => api(`/api/finance/targets?academicYearId=${yearId}`, { ...staff, method: 'PUT', body: { targets: (rows ?? []).map((r) => ({ dueDate: r.dueDate, percent: Number(r.percent) })) } }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['staff', 'finance-dashboard'] }); onClose(); },
  });
  const set = (i: number, patch: Partial<{ dueDate: string; percent: string }>) => setRows((rs) => (rs ?? []).map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Collection targets {year}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>The share of the year's annual fees to be collected by each date. Milestones must not go down over time.</Typography>
        <ErrorAlert error={q.error ?? save.error} />
        <Stack spacing={1.5}>
          {(rows ?? []).map((r, i) => (
            <Stack key={i} direction="row" spacing={1} alignItems="center">
              <TextField size="small" type="date" label="By" value={r.dueDate} onChange={(e) => set(i, { dueDate: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
              <TextField size="small" type="number" label="% of annual fees" value={r.percent} onChange={(e) => set(i, { percent: e.target.value })} sx={{ width: 160 }}
                slotProps={{ htmlInput: { min: 1, max: 100 } }} />
              <IconButton aria-label="Remove milestone" onClick={() => setRows((rs) => (rs ?? []).filter((_, j) => j !== i))}><DeleteIcon /></IconButton>
            </Stack>
          ))}
        </Stack>
        <Button startIcon={<AddIcon />} sx={{ mt: 1 }} onClick={() => setRows((rs) => [...(rs ?? []), { dueDate: today(), percent: '' }])}>Add milestone</Button>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending || (rows ?? []).some((r) => !r.dueDate || !r.percent)}>Save targets</Button></DialogActions>
    </Dialog>
  );
}

function PostingsDialog({ onClose }: { onClose: () => void }) {
  const first = today().slice(0, 8) + '01';
  const [from, setFrom] = useState(first);
  const [to, setTo] = useState(today());
  const [err, setErr] = useState<unknown>(null);
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Postings for the accounting system</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Totals by month, source and fee item (CSV), for MUSE or your accounting system.</Typography>
        <ErrorAlert error={err} />
        <Stack spacing={2}>
          <TextField size="small" type="date" label="From" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField size="small" type="date" label="To" value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button>
        <Button variant="contained" startIcon={<DownloadIcon />} onClick={() => download(`/api/finance/postings.csv?from=${from}&to=${to}`, 'staff', `postings-${from}-to-${to}.csv`).then(onClose).catch(setErr)}>Download</Button></DialogActions>
    </Dialog>
  );
}

// ============================================================ sponsors (BP-17, FR-FIN-09)

interface SponsorRow { id: number; code: string; name: string; kind: string; contactName?: string; contactPhone?: string; contactEmail?: string; address?: string; active: boolean;
  students: number; invoiced: number; paid: number; balance: number }
interface SponsorshipView { id: number; studentId: number; registrationNo: string; studentName: string; programmeCode: string; academicYear: string; coveragePercent: number;
  capAmount?: number; feeItemCodes: string[]; reference?: string; status: 'ACTIVE' | 'ENDED'; share: number; invoiced: number; paid: number; outstanding: number; note?: string }
interface SponsorInvoice { id: number; invoiceNo: string; description: string; total: number; paid: number; balance: number; status: string; controlNo?: string;
  controlNoExpiresAt?: string; createdAt: string; students: number }
interface SponsorDetail { sponsor: SponsorRow; years: { id: number; code: string }[]; academicYearId?: number; sponsorships: SponsorshipView[]; invoices: SponsorInvoice[];
  shares: number; invoiced: number; paid: number; balance: number; notYetInvoiced: number; canManage: boolean }
const KIND_LABEL: Record<string, string> = { GOVERNMENT: 'Government', LOAN_BOARD: 'Loans board', COMPANY: 'Company', NGO: 'NGO', RELIGIOUS: 'Religious body', OTHER: 'Other' };

export function SponsorsPage() {
  const can = useRoles();
  const navigate = useNavigate();
  const [editing, setEditing] = useState<SponsorRow | 'new' | null>(null);
  const q = useQuery({ queryKey: ['staff', 'sponsors'], queryFn: () => api<SponsorRow[]>('/api/finance/sponsors', staff) });
  const officerOnly = can(['SPONSOR_OFFICER']) && !can([...BURSARY, 'MANAGEMENT_VIEWER', 'AUDITOR']);
  if (officerOnly && q.data?.length === 1) return <Navigate to={`/staff/sponsors/${q.data[0].id}`} replace />;
  return (
    <>
      <PageTitle title="Sponsors" subtitle="Sponsors such as HESLB, the students they sponsor, sponsor invoices and statements."
        action={can(BURSARY) ? <Button variant="contained" startIcon={<AddIcon />} onClick={() => setEditing('new')}>Add sponsor</Button> : undefined} />
      <ErrorAlert error={q.error} />
      {q.isLoading && <Loading />}
      {q.data && (
        <TableContainer component={Card}>
          <Table size="small">
            <TableHead><TableRow>
              <TableCell>Sponsor</TableCell><TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Contact</TableCell>
              <TableCell align="right">Students</TableCell><TableCell align="right">Invoiced</TableCell><TableCell align="right">Paid</TableCell><TableCell align="right">Owed</TableCell>
            </TableRow></TableHead>
            <TableBody>
              {q.data.map((s) => (
                <TableRow key={s.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/sponsors/${s.id}`)}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{s.name}{!s.active && <Chip size="small" label="Inactive" sx={{ ml: 1, height: 20 }} />}</Typography>
                    <Typography variant="caption" color="text.secondary">{s.code} · {KIND_LABEL[s.kind]}</Typography>
                  </TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>
                    <Typography variant="body2">{s.contactName ?? '—'}</Typography>
                    <Typography variant="caption" color="text.secondary">{[s.contactPhone, s.contactEmail].filter(Boolean).join(' · ')}</Typography>
                  </TableCell>
                  <TableCell align="right">{s.students}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(s.invoiced)}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(s.paid)}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(s.balance)}</TableCell>
                </TableRow>
              ))}
              {!q.data.length && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 1 }}>No sponsors yet.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {editing && <SponsorDialog sponsor={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />}
    </>
  );
}

function SponsorDialog({ sponsor, onClose }: { sponsor: SponsorRow | null; onClose: () => void }) {
  const qc = useQueryClient();
  const [f, setF] = useState({ code: sponsor?.code ?? '', name: sponsor?.name ?? '', kind: sponsor?.kind ?? 'LOAN_BOARD', contactName: sponsor?.contactName ?? '',
    contactPhone: sponsor?.contactPhone ?? '', contactEmail: sponsor?.contactEmail ?? '', address: sponsor?.address ?? '', active: sponsor?.active ?? true });
  const set = (k: keyof typeof f, v: string | boolean) => setF((x) => ({ ...x, [k]: v }));
  const save = useMutation({
    mutationFn: () => api(sponsor ? `/api/finance/sponsors/${sponsor.id}` : '/api/finance/sponsors', { ...staff, method: sponsor ? 'PUT' : 'POST',
      body: { ...f, contactName: f.contactName || undefined, contactPhone: f.contactPhone || undefined, contactEmail: f.contactEmail || undefined, address: f.address || undefined } }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['staff'] }); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{sponsor ? `Edit ${sponsor.name}` : 'Add a sponsor'}</DialogTitle>
      <DialogContent>
        <ErrorAlert error={save.error} />
        <Grid container spacing={1.5} sx={{ mt: 0.5 }}>
          <Grid size={{ xs: 4 }}><TextField fullWidth size="small" label="Code" value={f.code} onChange={(e) => set('code', e.target.value.toUpperCase())} /></Grid>
          <Grid size={{ xs: 8 }}><TextField fullWidth size="small" label="Name" value={f.name} onChange={(e) => set('name', e.target.value)} /></Grid>
          <Grid size={{ xs: 6 }}><TextField fullWidth size="small" select label="Kind" value={f.kind} onChange={(e) => set('kind', e.target.value)}>
            {Object.entries(KIND_LABEL).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField></Grid>
          <Grid size={{ xs: 6 }}><TextField fullWidth size="small" label="Contact person" value={f.contactName} onChange={(e) => set('contactName', e.target.value)} /></Grid>
          <Grid size={{ xs: 6 }}><TextField fullWidth size="small" label="Phone" value={f.contactPhone} onChange={(e) => set('contactPhone', e.target.value)} /></Grid>
          <Grid size={{ xs: 6 }}><TextField fullWidth size="small" label="Email" value={f.contactEmail} onChange={(e) => set('contactEmail', e.target.value)} /></Grid>
          <Grid size={12}><TextField fullWidth size="small" label="Address" value={f.address} onChange={(e) => set('address', e.target.value)} /></Grid>
          {sponsor && <Grid size={12}><TextField fullWidth size="small" select label="Status" value={f.active ? 'yes' : 'no'} onChange={(e) => set('active', e.target.value === 'yes')}>
            <MenuItem value="yes">Active</MenuItem><MenuItem value="no">Inactive</MenuItem></TextField></Grid>}
        </Grid>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" onClick={() => save.mutate()} disabled={!f.code || !f.name || save.isPending}>Save</Button></DialogActions>
    </Dialog>
  );
}

export function SponsorDetailPage() {
  const { id } = useParams();
  const can = useRoles();
  const qc = useQueryClient();
  const [yearId, setYearId] = useState<number | ''>('');
  const [adding, setAdding] = useState(false);
  const [importing, setImporting] = useState(false);
  const [editing, setEditing] = useState(false);
  const [remitting, setRemitting] = useState<SponsorInvoice | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const q = useQuery({ queryKey: ['staff', 'sponsor', id, yearId], queryFn: () => api<SponsorDetail>(`/api/finance/sponsors/${id}${yearId ? `?academicYearId=${yearId}` : ''}`, staff) });
  const d = q.data;
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff'] });
  const invoice = useMutation({
    mutationFn: () => api<{ invoiceNo: string; students: number; amount: number; skipped: string[] }>(`/api/finance/sponsors/${id}/invoices`, { ...staff, method: 'POST', body: { academicYearId: d!.academicYearId } }),
    onSuccess: (r) => { setNotice(`Invoice ${r.invoiceNo} for ${r.students} student(s), ${formatTzs(r.amount)}.${r.skipped.length ? ` Not included: ${r.skipped.join('; ')}.` : ''}`); refresh(); },
  });
  const end = useMutation({ mutationFn: (sid: number) => api(`/api/finance/sponsorships/${sid}/end`, { ...staff, method: 'POST' }), onSuccess: refresh });
  const control = useMutation({ mutationFn: (iid: number) => api(`/api/finance/sponsor-invoices/${iid}/control-number`, { ...staff, method: 'POST' }), onSuccess: refresh });
  const officer = can(['SPONSOR_OFFICER']) && !can([...BURSARY, 'MANAGEMENT_VIEWER', 'AUDITOR']);
  return (
    <>
      {!officer && <Button component={RouterLink} to="/staff/sponsors" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>Sponsors</Button>}
      <ErrorAlert error={q.error ?? invoice.error ?? end.error ?? control.error ?? err} />
      {q.isLoading && <Loading />}
      {d && (
        <>
          <PageTitle title={d.sponsor.name} subtitle={`${d.sponsor.code} · ${KIND_LABEL[d.sponsor.kind]}${d.sponsor.contactName ? ` · ${d.sponsor.contactName}` : ''}${d.sponsor.contactPhone ? ` · ${d.sponsor.contactPhone}` : ''}`}
            action={(
              <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" justifyContent="flex-end">
                <TextField select size="small" label="Academic year" value={yearId || d.academicYearId || ''} onChange={(e) => setYearId(Number(e.target.value))} sx={{ minWidth: 150 }}>
                  {d.years.map((y) => <MenuItem key={y.id} value={y.id}>{y.code}</MenuItem>)}
                </TextField>
                <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/finance/sponsors/${id}/statement.xlsx?academicYearId=${d.academicYearId}`, 'staff', `sponsor-statement-${d.sponsor.code}.xlsx`).catch(setErr)}>Statement</Button>
                {d.canManage && <Button onClick={() => setEditing(true)}>Edit</Button>}
                {d.canManage && <Button variant="contained" onClick={() => invoice.mutate()} disabled={invoice.isPending || d.notYetInvoiced <= 0}>Invoice the sponsor</Button>}
              </Stack>
            )} />
          {notice && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>{notice}</Alert>}
          <Grid container spacing={2} sx={{ mb: 2.5 }}>
            <Kpi label="Students" value={d.sponsorships.filter((x) => x.status === 'ACTIVE').length} />
            <Kpi label="Sponsor's shares" value={formatTzs(d.shares)} hint={d.notYetInvoiced > 0 ? `${formatTzs(d.notYetInvoiced)} not invoiced yet` : 'Nothing left to invoice'} />
            <Kpi label="Invoiced" value={formatTzs(d.invoiced)} />
            <Kpi label="Paid" value={formatTzs(d.paid)} />
            <Kpi label="Owed" value={formatTzs(d.balance)} tone={d.balance > 0 ? 'warning.main' : undefined} />
          </Grid>
          <Card sx={{ mb: 2.5 }}>
            <CardContent sx={{ pb: 0 }}>
              <Stack direction="row" justifyContent="space-between" alignItems="center">
                <Typography variant="h6">Sponsored students</Typography>
                {d.canManage && <Stack direction="row" spacing={1}>
                  <Button size="small" startIcon={<UploadIcon />} onClick={() => setImporting(true)}>Import list</Button>
                  <Button size="small" startIcon={<AddIcon />} onClick={() => setAdding(true)}>Add student</Button>
                </Stack>}
              </Stack>
            </CardContent>
            <TableContainer>
              <Table size="small">
                <TableHead><TableRow>
                  <TableCell>Student</TableCell><TableCell>Covers</TableCell><TableCell align="right">Share</TableCell>
                  <TableCell align="right">Invoiced</TableCell><TableCell align="right">Paid</TableCell><TableCell align="right">Outstanding</TableCell>{d.canManage && <TableCell />}
                </TableRow></TableHead>
                <TableBody>
                  {d.sponsorships.map((x) => (
                    <TableRow key={x.id} sx={{ opacity: x.status === 'ENDED' ? 0.55 : 1 }}>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>{x.studentName}</Typography>
                        <Typography variant="caption" color="text.secondary">{x.registrationNo} · {x.programmeCode}{x.reference ? ` · ref ${x.reference}` : ''}</Typography>
                        {x.note && <Typography variant="caption" color="warning.main" display="block">{x.note}</Typography>}
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">{pct(x.coveragePercent)} of {x.feeItemCodes.length ? x.feeItemCodes.join(', ') : 'all fees'}</Typography>
                        {x.capAmount && <Typography variant="caption" color="text.secondary">up to {formatTzs(x.capAmount)}</Typography>}
                        {x.status === 'ENDED' && <Chip size="small" label="Ended" sx={{ ml: 1, height: 20 }} />}
                      </TableCell>
                      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(x.share)}</TableCell>
                      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(x.invoiced)}</TableCell>
                      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(x.paid)}</TableCell>
                      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(x.outstanding)}</TableCell>
                      {d.canManage && <TableCell align="right">{x.status === 'ACTIVE' && <Button size="small" color="inherit" onClick={() => end.mutate(x.id)}>End</Button>}</TableCell>}
                    </TableRow>
                  ))}
                  {!d.sponsorships.length && <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 1 }}>No students sponsored this year.</Typography></TableCell></TableRow>}
                </TableBody>
              </Table>
            </TableContainer>
          </Card>
          <Card>
            <CardContent sx={{ pb: 0 }}><Typography variant="h6">Sponsor invoices</Typography></CardContent>
            <TableContainer>
              <Table size="small">
                <TableHead><TableRow>
                  <TableCell>Invoice</TableCell><TableCell align="right">Total</TableCell><TableCell align="right">Paid</TableCell><TableCell align="right">Balance</TableCell>
                  <TableCell>Control number</TableCell><TableCell />
                </TableRow></TableHead>
                <TableBody>
                  {d.invoices.map((i) => (
                    <TableRow key={i.id}>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>{i.invoiceNo}</Typography>
                        <Typography variant="caption" color="text.secondary">{i.description} · {formatDate(i.createdAt)}</Typography>
                      </TableCell>
                      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(i.total)}</TableCell>
                      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(i.paid)}</TableCell>
                      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{i.status === 'PAID' ? <Chip size="small" color="success" label="Paid" /> : formatTzs(i.balance)}</TableCell>
                      <TableCell sx={{ fontFamily: 'ui-monospace, Menlo, Consolas, monospace' }}>
                        {i.controlNo ?? (i.status !== 'PAID' && <Button size="small" onClick={() => control.mutate(i.id)} disabled={control.isPending}>Get control number</Button>)}
                      </TableCell>
                      <TableCell align="right">{d.canManage && i.status !== 'PAID' && <Button size="small" onClick={() => setRemitting(i)}>Record bank payment</Button>}</TableCell>
                    </TableRow>
                  ))}
                  {!d.invoices.length && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 1 }}>No sponsor invoices this year.</Typography></TableCell></TableRow>}
                </TableBody>
              </Table>
            </TableContainer>
            <CardContent><Typography variant="caption" color="text.secondary">As the sponsor pays (by GePG control number or bank transfer), each student's share moves onto that student's invoice. Until then, the share invoiced to the sponsor counts towards the student's clearance.</Typography></CardContent>
          </Card>
          {adding && d.academicYearId && <SponsorshipDialog sponsorId={d.sponsor.id} yearId={d.academicYearId} onClose={() => setAdding(false)} onDone={refresh} />}
          {importing && d.academicYearId && <ImportDialog sponsorId={d.sponsor.id} yearId={d.academicYearId} onClose={() => setImporting(false)} onDone={refresh} />}
          {editing && <SponsorDialog sponsor={d.sponsor} onClose={() => setEditing(false)} />}
          {remitting && <RemittanceDialog invoice={remitting} onClose={() => setRemitting(null)} onDone={() => { setRemitting(null); setNotice('Bank payment recorded and moved onto the students\' invoices.'); refresh(); }} />}
        </>
      )}
    </>
  );
}

function SponsorshipDialog({ sponsorId, yearId, onClose, onDone }: { sponsorId: number; yearId: number; onClose: () => void; onDone: () => void }) {
  const [regNo, setRegNo] = useState('');
  const [percent, setPercent] = useState('100');
  const [cap, setCap] = useState('');
  const [items, setItems] = useState('');
  const [ref, setRef] = useState('');
  const save = useMutation({
    mutationFn: () => api(`/api/finance/sponsors/${sponsorId}/sponsorships`, { ...staff, method: 'POST', body: { registrationNo: regNo.trim(), academicYearId: yearId,
      coveragePercent: Number(percent), capAmount: cap ? Number(cap) : undefined, feeItemCodes: items ? items.split(',').map((x) => x.trim()).filter(Boolean) : [], reference: ref || undefined } }),
    onSuccess: () => { onDone(); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Add a sponsored student</DialogTitle>
      <DialogContent>
        <ErrorAlert error={save.error} />
        <Grid container spacing={1.5} sx={{ mt: 0.5 }}>
          <Grid size={12}><TextField fullWidth size="small" label="Registration number" value={regNo} onChange={(e) => setRegNo(e.target.value)} /></Grid>
          <Grid size={{ xs: 6 }}><TextField fullWidth size="small" type="number" label="Share (%)" value={percent} onChange={(e) => setPercent(e.target.value)} slotProps={{ htmlInput: { min: 1, max: 100 } }} /></Grid>
          <Grid size={{ xs: 6 }}><TextField fullWidth size="small" type="number" label="Up to (TZS, optional)" value={cap} onChange={(e) => setCap(e.target.value)} /></Grid>
          <Grid size={12}><TextField fullWidth size="small" label="Fee items covered (codes, optional)" placeholder="e.g. TUITION, EXAM" helperText="Leave empty for every fee item" value={items} onChange={(e) => setItems(e.target.value)} /></Grid>
          <Grid size={12}><TextField fullWidth size="small" label="Reference (optional)" placeholder="e.g. loan or form IV index number" value={ref} onChange={(e) => setRef(e.target.value)} /></Grid>
        </Grid>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" onClick={() => save.mutate()} disabled={!regNo.trim() || !percent || save.isPending}>Add</Button></DialogActions>
    </Dialog>
  );
}

function ImportDialog({ sponsorId, yearId, onClose, onDone }: { sponsorId: number; yearId: number; onClose: () => void; onDone: () => void }) {
  const [text, setText] = useState('registrationNo,coveragePercent,capAmount,reference\n');
  const [result, setResult] = useState<{ created: number; skipped: number; errors: { row: number; message: string }[] } | null>(null);
  const [err, setErr] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const send = async () => {
    setBusy(true);
    setErr(null);
    try {
      const file = new File([text], 'list.csv', { type: 'text/csv' });
      const r = await upload<{ created: number; skipped: number; errors: { row: number; message: string }[] }>(`/api/finance/sponsors/${sponsorId}/import?academicYearId=${yearId}`, 'staff', file);
      setResult(r);
      onDone();
    } catch (e) {
      setErr(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Import a beneficiary list</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>Paste the list (e.g. HESLB's beneficiaries) as CSV, or load a file. Columns: registration number, share %, cap amount (optional), reference (optional).</Typography>
        <Button component="label" size="small" startIcon={<UploadIcon />} sx={{ mb: 1 }}>Load a CSV file<input hidden type="file" accept=".csv,text/csv" onChange={async (e) => {
          const f = e.target.files?.[0];
          if (f) setText(await f.text());
        }} /></Button>
        <TextField fullWidth multiline minRows={8} value={text} onChange={(e) => setText(e.target.value)} slotProps={{ htmlInput: { style: { fontFamily: 'ui-monospace, Menlo, Consolas, monospace', fontSize: 13 } } }} />
        <ErrorAlert error={err} />
        {result && (
          <Alert severity={result.errors.length ? 'warning' : 'success'} sx={{ mt: 1.5 }}>
            {result.created} added, {result.skipped} already on the list.
            {result.errors.map((e) => <div key={e.row}>Row {e.row}: {e.message}</div>)}
          </Alert>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button><Button variant="contained" onClick={send} disabled={busy || text.trim().split('\n').length < 2}>Import</Button></DialogActions>
    </Dialog>
  );
}

function RemittanceDialog({ invoice, onClose, onDone }: { invoice: SponsorInvoice; onClose: () => void; onDone: () => void }) {
  const [amount, setAmount] = useState(String(invoice.balance));
  const [ref, setRef] = useState('');
  const [on, setOn] = useState(today());
  const save = useMutation({ mutationFn: () => api(`/api/finance/sponsor-invoices/${invoice.id}/remittances`, { ...staff, method: 'POST', body: { amount: Number(amount), reference: ref.trim(), paidOn: on } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Bank payment for {invoice.invoiceNo}</DialogTitle>
      <DialogContent>
        <ErrorAlert error={save.error} />
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField size="small" type="number" label="Amount (TZS)" value={amount} onChange={(e) => setAmount(e.target.value)} helperText={`Balance ${formatTzs(invoice.balance)}`} />
          <TextField size="small" label="Bank reference" value={ref} onChange={(e) => setRef(e.target.value)} />
          <TextField size="small" type="date" label="Paid on" value={on} onChange={(e) => setOn(e.target.value)} slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: today() } }} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" onClick={() => save.mutate()} disabled={!ref.trim() || !Number(amount) || save.isPending}>Record</Button></DialogActions>
    </Dialog>
  );
}

// ============================================================ waivers and refunds (FR-FIN-08/10)

interface Adjustment {
  id: number; adjustmentNo: string; kind: AdjKind; schemeName?: string; studentId: number; registrationNo: string; studentName: string; invoiceNo?: string; amount: number;
  reason: string; evidenceRef: string; status: 'PENDING' | 'ENDORSED' | 'APPROVED' | 'REJECTED' | 'PAID'; needsSecondLevel: boolean; requestedByName: string; requestedAt: string;
  firstByName?: string; firstAt?: string; secondByName?: string; secondAt?: string; decisionNote?: string; documentNo?: string; paidReference?: string; paidAt?: string;
  waitingFor?: string; canApprove: boolean; canReject: boolean; canMarkPaid: boolean;
  /** Raised by CIMS itself after a status change or a hostel bed change, check-out or cancellation. */
  origin?: 'STATUS_CHANGE' | 'HOSTEL_CHANGE' | 'HOSTEL_CHECKOUT' | 'HOSTEL_CANCEL';
}
const ORIGIN_LABEL: Record<NonNullable<Adjustment['origin']>, string> = {
  STATUS_CHANGE: 'Raised after a status change', HOSTEL_CHANGE: 'Raised after a hostel bed change', HOSTEL_CHECKOUT: 'Raised at hostel check-out',
  HOSTEL_CANCEL: 'Raised when a paid hostel bed was cancelled',
};
type AdjKind = 'WAIVER' | 'REFUND' | 'DISCOUNT' | 'SCHOLARSHIP';
const KIND_NAME: Record<AdjKind, string> = { WAIVER: 'Waiver', REFUND: 'Refund', DISCOUNT: 'Discount', SCHOLARSHIP: 'Scholarship' };
interface Scheme { id: number; code: string; name: string; kind: 'DISCOUNT' | 'SCHOLARSHIP'; basis: 'PERCENT' | 'FIXED'; value: number; feeCategory?: string; description?: string; active: boolean }
const schemeText = (x: Scheme) => `${x.basis === 'PERCENT' ? `${x.value}%` : formatTzs(x.value)} of ${x.feeCategory ? `${x.feeCategory.toLowerCase()} fees` : 'the invoice'}`;

const ADJ_STATUS: Record<Adjustment['status'], { label: string; colour: 'default' | 'info' | 'warning' | 'success' | 'error' }> = {
  PENDING: { label: 'Waiting for the Bursar', colour: 'warning' }, ENDORSED: { label: 'Waiting for the Principal', colour: 'info' },
  APPROVED: { label: 'Approved', colour: 'success' }, REJECTED: { label: 'Rejected', colour: 'error' }, PAID: { label: 'Paid out', colour: 'success' },
};

export function WaiversPage() {
  const can = useRoles();
  const qc = useQueryClient();
  const [tab, setTab] = useState<'open' | 'all' | 'schemes'>('open');
  const [creating, setCreating] = useState(false);
  const [deciding, setDeciding] = useState<{ a: Adjustment; approve: boolean } | null>(null);
  const [paying, setPaying] = useState<Adjustment | null>(null);
  const list = useQuery({ queryKey: ['staff', 'adjustments', tab], enabled: tab !== 'schemes', queryFn: () => api<Adjustment[]>(`/api/finance/adjustments${tab === 'open' ? '?open=true' : ''}`, staff) });
  const settings = useQuery({ queryKey: ['staff', 'finance-settings'], queryFn: () => api<{ approvalThreshold: number }>('/api/finance/settings', staff) });
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff'] });
  return (
    <>
      <PageTitle title="Waivers, discounts and refunds" subtitle={`Waivers, discounts, scholarships and refunds: requested by the bursary with evidence; approved by the Bursar, and by the Principal as well above ${settings.data ? formatTzs(settings.data.approvalThreshold) : 'the threshold'}.`}
        action={<Stack direction="row" spacing={1}>
          {can(BURSAR) && settings.data && <ThresholdButton value={settings.data.approvalThreshold} onDone={refresh} />}
          {can(BURSARY) && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreating(true)}>New request</Button>}
        </Stack>} />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2, borderBottom: 1, borderColor: 'divider' }}>
        <Tab value="open" label="Waiting" /><Tab value="all" label="All" /><Tab value="schemes" label="Discount and scholarship schemes" />
      </Tabs>
      <ErrorAlert error={list.error} />
      {list.isLoading && <Loading />}
      {tab === 'schemes' && <SchemesTab canEdit={can(BURSAR)} />}
      {tab !== 'schemes' && list.data && (
        <TableContainer component={Card}>
          <Table size="small">
            <TableHead><TableRow>
              <TableCell>Request</TableCell><TableCell>Student</TableCell><TableCell align="right">Amount</TableCell><TableCell>Stage</TableCell><TableCell />
            </TableRow></TableHead>
            <TableBody>
              {list.data.map((a) => (
                <TableRow key={a.id}>
                  <TableCell sx={{ maxWidth: 360 }}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{KIND_NAME[a.kind]} · {a.adjustmentNo}</Typography>
                    {a.schemeName && <Typography variant="caption" display="block">Scheme: {a.schemeName}</Typography>}
                    {a.origin && <Chip size="small" variant="outlined" color="info" label={ORIGIN_LABEL[a.origin]} sx={{ my: 0.25 }} />}
                    <Typography variant="caption" color="text.secondary" display="block">{a.reason}</Typography>
                    <Typography variant="caption" color="text.secondary">Evidence: {a.evidenceRef}{a.invoiceNo ? ` · invoice ${a.invoiceNo}` : ''} · by {a.requestedByName}, {formatDateTime(a.requestedAt)}</Typography>
                  </TableCell>
                  <TableCell><Typography variant="body2">{a.studentName}</Typography><Typography variant="caption" color="text.secondary">{a.registrationNo}</Typography></TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(a.amount)}{a.needsSecondLevel && <Typography variant="caption" color="text.secondary" display="block">two levels</Typography>}</TableCell>
                  <TableCell>
                    <Chip size="small" color={ADJ_STATUS[a.status].colour} label={ADJ_STATUS[a.status].label} variant={a.status === 'PENDING' || a.status === 'ENDORSED' ? 'outlined' : 'filled'} />
                    {a.firstByName && <Typography variant="caption" display="block" color="text.secondary">{a.status === 'REJECTED' && !a.secondByName ? 'Rejected' : 'Approved'} by {a.firstByName}</Typography>}
                    {a.secondByName && <Typography variant="caption" display="block" color="text.secondary">{a.status === 'REJECTED' ? 'Rejected' : 'Approved'} by {a.secondByName}</Typography>}
                    {a.documentNo && <Typography variant="caption" display="block">{a.kind === 'REFUND' ? 'Voucher' : 'Credit note'} {a.documentNo}</Typography>}
                    {a.decisionNote && <Typography variant="caption" display="block" color="text.secondary">“{a.decisionNote}”</Typography>}
                    {a.paidReference && <Typography variant="caption" display="block" color="text.secondary">Paid: {a.paidReference}</Typography>}
                  </TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    {a.canApprove && <Button size="small" variant="contained" onClick={() => setDeciding({ a, approve: true })}>Approve</Button>}
                    {a.canReject && <Button size="small" color="error" onClick={() => setDeciding({ a, approve: false })}>Reject</Button>}
                    {a.canMarkPaid && <Button size="small" onClick={() => setPaying(a)}>Record payment</Button>}
                  </TableCell>
                </TableRow>
              ))}
              {!list.data.length && <TableRow><TableCell colSpan={5}><Typography color="text.secondary" sx={{ py: 1 }}>{tab === 'open' ? 'Nothing is waiting.' : 'No waivers or refunds yet.'}</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {creating && <AdjustmentDialog onClose={() => setCreating(false)} onDone={() => { setCreating(false); refresh(); }} />}
      {deciding && <DecideDialog a={deciding.a} approve={deciding.approve} onClose={() => setDeciding(null)} onDone={() => { setDeciding(null); refresh(); }} />}
      {paying && <PaidDialog a={paying} onClose={() => setPaying(null)} onDone={() => { setPaying(null); refresh(); }} />}
    </>
  );
}

/** Discount and scholarship schemes the Bursar offers (FR-FIN-08). */
function SchemesTab({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'fee-schemes'], queryFn: () => api<Scheme[]>('/api/finance/fee-schemes', staff) });
  const empty = { id: 0, code: '', name: '', kind: 'DISCOUNT' as Scheme['kind'], basis: 'PERCENT' as Scheme['basis'], value: '', feeCategory: '', description: '', active: true };
  const [f, setF] = useState<typeof empty | null>(null);
  const save = useMutation({
    mutationFn: (x: typeof empty) => api(x.id ? `/api/finance/fee-schemes/${x.id}` : '/api/finance/fee-schemes', { ...staff, method: x.id ? 'PUT' : 'POST',
      body: { code: x.code, name: x.name, kind: x.kind, basis: x.basis, value: Number(x.value), feeCategory: x.feeCategory || null, description: x.description || null, active: x.active } }),
    onSuccess: () => { setF(null); qc.invalidateQueries({ queryKey: ['staff', 'fee-schemes'] }); },
  });
  return (
    <>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }}>
        <Typography variant="body2" color="text.secondary">A scheme works out the amount: a percentage of a fee category (e.g. 10% of tuition) or a fixed sum. Each grant is still approved like a waiver.</Typography>
        {canEdit && <Button variant="contained" startIcon={<AddIcon />} sx={{ flexShrink: 0, ml: 2 }} onClick={() => setF(empty)}>New scheme</Button>}
      </Stack>
      <ErrorAlert error={q.error} />
      <TableContainer component={Card}><Table size="small">
        <TableHead><TableRow><TableCell>Scheme</TableCell><TableCell>Kind</TableCell><TableCell>Gives</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {q.data?.map((x) => (
            <TableRow key={x.id} hover={canEdit} sx={{ cursor: canEdit ? 'pointer' : 'default' }}
              onClick={() => canEdit && setF({ ...x, value: String(x.value), feeCategory: x.feeCategory ?? '', description: x.description ?? '' })}>
              <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{x.name}</Typography><Typography variant="caption" color="text.secondary">{x.code}{x.description ? ` · ${x.description}` : ''}</Typography></TableCell>
              <TableCell>{KIND_NAME[x.kind]}</TableCell><TableCell>{schemeText(x)}</TableCell>
              <TableCell align="right">{!x.active && <Chip size="small" label="Inactive" />}</TableCell>
            </TableRow>
          ))}
          {q.data?.length === 0 && <TableRow><TableCell colSpan={4}><Typography color="text.secondary">No schemes yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table></TableContainer>
      <Dialog open={!!f} onClose={() => setF(null)} fullWidth maxWidth="sm">
        <DialogTitle>{f?.id ? f.name : 'New scheme'}</DialogTitle>
        {f && (
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              <Stack direction="row" spacing={1}>
                <TextField size="small" label="Code" value={f.code} onChange={(e) => setF({ ...f, code: e.target.value.toUpperCase() })} sx={{ width: 150 }} />
                <TextField size="small" fullWidth label="Name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} />
              </Stack>
              <Stack direction="row" spacing={1}>
                <TextField select size="small" fullWidth label="Kind" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value as Scheme['kind'] })}>
                  <MenuItem value="DISCOUNT">Discount</MenuItem><MenuItem value="SCHOLARSHIP">Scholarship</MenuItem>
                </TextField>
                <TextField select size="small" fullWidth label="Basis" value={f.basis} onChange={(e) => setF({ ...f, basis: e.target.value as Scheme['basis'] })}>
                  <MenuItem value="PERCENT">Percentage</MenuItem><MenuItem value="FIXED">Fixed amount</MenuItem>
                </TextField>
                <TextField size="small" fullWidth type="number" label={f.basis === 'PERCENT' ? 'Percent' : 'Amount (TZS)'} value={f.value} onChange={(e) => setF({ ...f, value: e.target.value })} />
              </Stack>
              <TextField select size="small" label="Applies to" value={f.feeCategory} onChange={(e) => setF({ ...f, feeCategory: e.target.value })}
                slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
                <MenuItem value="">The whole invoice</MenuItem>
                {['TUITION', 'REGISTRATION', 'EXAMINATION', 'ACCOMMODATION', 'OTHER'].map((c) => <MenuItem key={c} value={c}>{c.charAt(0) + c.slice(1).toLowerCase()} fees only</MenuItem>)}
              </TextField>
              <TextField size="small" label="Description (optional)" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} />
              <FormControlLabel control={<Switch checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} />} label="Offered" />
              <ErrorAlert error={save.error} />
            </Stack>
          </DialogContent>
        )}
        <DialogActions><Button onClick={() => setF(null)}>Cancel</Button>
          <Button variant="contained" disabled={!f?.code || !f?.name.trim() || !Number(f?.value) || save.isPending} onClick={() => f && save.mutate(f)}>Save</Button></DialogActions>
      </Dialog>
    </>
  );
}

function ThresholdButton({ value, onDone }: { value: number; onDone: () => void }) {
  const [open, setOpen] = useState(false);
  const [v, setV] = useState(String(value));
  const save = useMutation({ mutationFn: () => api('/api/finance/settings', { ...staff, method: 'PUT', body: { approvalThreshold: Number(v) } }), onSuccess: () => { setOpen(false); onDone(); } });
  return (
    <>
      <Button onClick={() => setOpen(true)}>Threshold</Button>
      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle>Approval threshold</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Above this amount, a waiver or refund approved by the Bursar also needs the Principal's approval.</Typography>
          <ErrorAlert error={save.error} />
          <TextField fullWidth size="small" type="number" label="Amount (TZS)" value={v} onChange={(e) => setV(e.target.value)} />
        </DialogContent>
        <DialogActions><Button onClick={() => setOpen(false)}>Cancel</Button><Button variant="contained" onClick={() => save.mutate()} disabled={v === '' || save.isPending}>Save</Button></DialogActions>
      </Dialog>
    </>
  );
}

interface Options {
  studentId: number; registrationNo: string; name: string; credit: number; refundable: number;
  invoices: { id: number; invoiceNo: string; description: string; balance: number; waivable: number; paid: number; refundable: number }[];
}

function AdjustmentDialog({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [kind, setKind] = useState<AdjKind>('WAIVER');
  const [schemeId, setSchemeId] = useState<number | ''>('');
  const schemes = useQuery({ queryKey: ['staff', 'fee-schemes'], queryFn: () => api<Scheme[]>('/api/finance/fee-schemes', staff) });
  const offered = schemes.data?.filter((x) => x.active && x.kind === kind) ?? [];
  const credit = kind !== 'REFUND';
  const byScheme = credit && kind !== 'WAIVER' && schemeId !== '';
  const [regNo, setRegNo] = useState('');
  const [lookup, setLookup] = useState('');
  const [invoiceId, setInvoiceId] = useState<number | ''>('');
  /** A refund of fees paid on an invoice (e.g. hostel fees for a stay that ended) rather than of the student's credit. */
  const [fromInvoice, setFromInvoice] = useState(false);
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [evidence, setEvidence] = useState('');
  const opts = useQuery({ queryKey: ['staff', 'adjustment-options', lookup], enabled: !!lookup, retry: false,
    queryFn: () => api<Options>(`/api/finance/adjustments/options?registrationNo=${encodeURIComponent(lookup)}`, staff) });
  const o = opts.data;
  const chosen = o?.invoices.find((i) => i.id === invoiceId);
  const max = credit ? chosen?.waivable : fromInvoice ? chosen?.refundable : o?.refundable;
  const save = useMutation({
    mutationFn: () => api('/api/finance/adjustments', { ...staff, method: 'POST', body: { kind, studentId: o!.studentId, invoiceId: credit || fromInvoice ? invoiceId : undefined,
      amount: byScheme ? undefined : Number(amount), schemeId: byScheme ? schemeId : undefined, reason: reason.trim(), evidenceRef: evidence.trim() } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>New waiver, discount, scholarship or refund</DialogTitle>
      <DialogContent>
        <ErrorAlert error={opts.error ?? save.error} />
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select size="small" label="Kind" value={kind} onChange={(e) => { setKind(e.target.value as AdjKind); setSchemeId(''); }}>
            <MenuItem value="WAIVER">Waiver (reduces what is owed on an invoice)</MenuItem>
            <MenuItem value="DISCOUNT">Discount (from a discount scheme, or an amount)</MenuItem>
            <MenuItem value="SCHOLARSHIP">Scholarship (from a scholarship scheme)</MenuItem>
            <MenuItem value="REFUND">Refund (pays money back: the student's credit, or fees paid on an invoice)</MenuItem>
          </TextField>
          {(kind === 'DISCOUNT' || kind === 'SCHOLARSHIP') && (
            <TextField select size="small" label="Scheme" value={schemeId} onChange={(e) => setSchemeId(e.target.value === '' ? '' : Number(e.target.value))}
              slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }} helperText={offered.length ? 'The amount is worked out from the scheme' : 'The Bursar sets schemes on the Schemes tab'}>
              {kind === 'DISCOUNT' && <MenuItem value="">No scheme: type the amount</MenuItem>}
              {offered.map((x) => <MenuItem key={x.id} value={x.id}>{x.name} · {schemeText(x)}</MenuItem>)}
            </TextField>
          )}
          <Stack direction="row" spacing={1}>
            <TextField size="small" fullWidth label="Registration number" value={regNo} onChange={(e) => setRegNo(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') setLookup(regNo.trim()); }} />
            <Button onClick={() => setLookup(regNo.trim())} disabled={!regNo.trim()}>Find</Button>
          </Stack>
          {o && (
            <>
              <Typography variant="body2"><strong>{o.name}</strong> · credit {formatTzs(o.credit)}{o.refundable !== o.credit ? ` (${formatTzs(o.refundable)} refundable)` : ''}</Typography>
              {credit && (
                <TextField select size="small" label="Invoice" value={invoiceId} onChange={(e) => setInvoiceId(Number(e.target.value))}>
                  {o.invoices.filter((i) => i.balance > 0).map((i) => <MenuItem key={i.id} value={i.id}>{i.invoiceNo} · {i.description} · owes {formatTzs(i.balance)}</MenuItem>)}
                  {!o.invoices.some((i) => i.balance > 0) && <MenuItem disabled value="">No invoice with a balance</MenuItem>}
                </TextField>
              )}
              {!credit && (
                <FormControlLabel control={<Checkbox checked={fromInvoice} onChange={(e) => { setFromInvoice(e.target.checked); setInvoiceId(''); }} />}
                  label="Refund fees paid on an invoice (the invoice is reduced by a credit note), not the student's credit" />
              )}
              {!credit && fromInvoice && (
                <TextField select size="small" label="Invoice paid" value={invoiceId} onChange={(e) => setInvoiceId(Number(e.target.value))}>
                  {o.invoices.filter((i) => i.refundable > 0).map((i) => <MenuItem key={i.id} value={i.id}>{i.invoiceNo} · {i.description} · paid {formatTzs(i.paid)}</MenuItem>)}
                  {!o.invoices.some((i) => i.refundable > 0) && <MenuItem disabled value="">No invoice with a payment to refund</MenuItem>}
                </TextField>
              )}
              {!byScheme && <TextField size="small" type="number" label="Amount (TZS)" value={amount} onChange={(e) => setAmount(e.target.value)}
                helperText={max != null ? `At most ${formatTzs(max)}` : undefined} error={max != null && Number(amount) > max} />}
              <TextField size="small" multiline minRows={2} label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} />
              <TextField size="small" label="Evidence" placeholder="e.g. board minute, letter reference" value={evidence} onChange={(e) => setEvidence(e.target.value)} />
            </>
          )}
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={() => save.mutate()} disabled={!o || (!byScheme && !Number(amount)) || (kind === 'SCHOLARSHIP' && !byScheme) || reason.trim().length < 10 || !evidence.trim() || ((credit || fromInvoice) && !invoiceId) || save.isPending}>Submit</Button></DialogActions>
    </Dialog>
  );
}

function DecideDialog({ a, approve, onClose, onDone }: { a: Adjustment; approve: boolean; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('');
  const save = useMutation({ mutationFn: () => api(`/api/finance/adjustments/${a.id}/${approve ? 'approve' : 'reject'}`, { ...staff, method: 'POST', body: { note: note || undefined } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{approve ? 'Approve' : 'Reject'} {a.kind === 'WAIVER' ? 'waiver' : 'refund'} {a.adjustmentNo}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 1.5 }}>{a.studentName} ({a.registrationNo}) · {formatTzs(a.amount)}. {a.reason}</Typography>
        {approve && a.needsSecondLevel && a.status === 'PENDING' && <Alert severity="info" sx={{ mb: 1.5 }}>Above the threshold: after you, the Principal approves as well.</Alert>}
        <ErrorAlert error={save.error} />
        <TextField fullWidth multiline minRows={2} label={approve ? 'Note (optional)' : 'Reason for rejecting'} value={note} onChange={(e) => setNote(e.target.value)} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color={approve ? 'primary' : 'error'} onClick={() => save.mutate()} disabled={(!approve && !note.trim()) || save.isPending}>{approve ? 'Approve' : 'Reject'}</Button></DialogActions>
    </Dialog>
  );
}

function PaidDialog({ a, onClose, onDone }: { a: Adjustment; onClose: () => void; onDone: () => void }) {
  const [ref, setRef] = useState('');
  const save = useMutation({ mutationFn: () => api(`/api/finance/adjustments/${a.id}/paid`, { ...staff, method: 'POST', body: { reference: ref.trim() } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Refund {a.documentNo} paid</DialogTitle>
      <DialogContent>
        <ErrorAlert error={save.error} />
        <TextField fullWidth size="small" sx={{ mt: 1 }} label="Payment reference" placeholder="e.g. cheque or transfer number" value={ref} onChange={(e) => setRef(e.target.value)} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" onClick={() => save.mutate()} disabled={!ref.trim() || save.isPending}>Save</Button></DialogActions>
    </Dialog>
  );
}
