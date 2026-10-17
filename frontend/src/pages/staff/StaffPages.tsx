import AddIcon from '@mui/icons-material/Add';
import SearchIcon from '@mui/icons-material/Search';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, Divider, Drawer,
  IconButton, InputAdornment, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow,
  TextField, Typography, Checkbox, FormControlLabel, TableSortLabel, Tab, Tabs,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import CloseIcon from '@mui/icons-material/Close';
import DownloadIcon from '@mui/icons-material/Download';
import PrintIcon from '@mui/icons-material/Print';
import { homeFor } from '../../components/StaffLayout';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import ToggleButton from '@mui/material/ToggleButton';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import { setLanguage } from '../../i18n';
import { useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type {
  AdmissionWindow, ApplicantRow, ApplicationDetail, ApplicationSummary, Department, EntryRules, Page, Programme, TokenResponse, WindowStats,
} from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle, humanise, statusColour } from '../../components/common';
import { useInstitution } from '../../components/InstitutionContext';
import { EligibilityDetail } from '../applicant/steps';
import JoiningInstructionsCard from './JoiningInstructionsCard';
import { ApplicationDocumentsSection, WindowDocumentsCard } from './ApplicationDocuments';
import { CHECK_COLOUR, CHECK_LABEL, CheckButtons, describe } from './QualificationChecks';
import { CampusPicker, CampusesTab, DepartmentsTab, NamedListTab, useCampuses, useProgrammeCampuses } from './StructurePages';
import { WindowFormDialog, WindowHistoryCard, WindowLifecycle, WindowStateChip, useWindowRoles } from './WindowAdmin';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };

// ---------------------------------------------------------------- Login
export function StaffLoginPage() {
  const inst = useInstitution();
  const { signIn } = useSession();
  const navigate = useNavigate();
  const { i18n } = useTranslation();   // re-renders the page in the chosen language
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [mfa, setMfa] = useState<TokenResponse['mfa'] | null>(null);
  const [code, setCode] = useState('');
  const [smsSent, setSmsSent] = useState(false);
  const idleOut = new URLSearchParams(window.location.search).get('idle') === '1';
  const done = (t: TokenResponse) => {
    if (t.mfa) { setMfa(t.mfa); setCode(''); setSmsSent(false); return; }
    signIn('staff', t);
    navigate(t.user.mustChangePassword || t.user.mfaSetupRequired ? '/staff/password' : homeFor(t.user.roles));
  };
  const m = useMutation({
    mutationFn: () => api<TokenResponse>('/api/auth/login', { method: 'POST', body: { username, password } }),
    onSuccess: done,
  });
  const verify = useMutation({
    mutationFn: () => api<TokenResponse>('/api/auth/mfa/verify', { method: 'POST', body: { challenge: mfa!.challenge, code: code.trim() } }),
    onSuccess: done,
  });
  const sms = useMutation({
    mutationFn: () => api('/api/auth/mfa/sms', { method: 'POST', body: { challenge: mfa!.challenge } }),
    onSuccess: () => setSmsSent(true),
  });
  return (
    <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', bgcolor: '#0F1B2D', p: 2 }}>
      <Card sx={{ width: '100%', maxWidth: 420, borderRadius: 3 }}>
        <CardContent sx={{ p: 4 }}>
          <Stack direction="row" justifyContent="space-between" alignItems="center">
            <Typography variant="overline" color="text.secondary">CIMS back-office</Typography>
            <ToggleButtonGroup size="small" exclusive value={i18n.language === 'sw' ? 'sw' : 'en'} onChange={(_, v) => v && setLanguage(v)} aria-label="Language">
              <ToggleButton value="en" sx={{ px: 1, py: 0.25 }}>EN</ToggleButton>
              <ToggleButton value="sw" sx={{ px: 1, py: 0.25 }}>SW</ToggleButton>
            </ToggleButtonGroup>
          </Stack>
          <Typography variant="h5">{inst.shortName ?? inst.name}</Typography>
          {idleOut && !mfa && <Alert severity="info" sx={{ mt: 2 }}>You were signed out after a time with no activity.</Alert>}
          {!mfa ? (
            <form onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
              <Stack spacing={2} sx={{ mt: 3 }}>
                <TextField label="Username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
                <TextField label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
                <ErrorAlert error={m.error} />
                <Button type="submit" variant="contained" size="large" disabled={m.isPending}>Sign in</Button>
                <Button component={StaffLink} to="/">Back to admission portal</Button>
              </Stack>
            </form>
          ) : (
            <form onSubmit={(e) => { e.preventDefault(); verify.mutate(); }}>
              <Stack spacing={2} sx={{ mt: 3 }}>
                <Typography variant="body2">
                  {smsSent ? 'We sent a code by SMS to the phone on your account. Type it here.' : 'Open your authenticator app and type the 6-digit code for CIMS.'}
                </Typography>
                <TextField label="Code" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" autoFocus required
                  inputProps={{ maxLength: 9 }} helperText="Lost your phone? Type one of your recovery codes." />
                <ErrorAlert error={verify.error ?? sms.error} />
                <Button type="submit" variant="contained" size="large" disabled={verify.isPending || code.trim().length < 6}>Continue</Button>
                {mfa.smsAvailable && <Button onClick={() => sms.mutate()} disabled={sms.isPending}>{smsSent ? 'Send the SMS again' : 'Send me a code by SMS instead'}</Button>}
                <Button onClick={() => { setMfa(null); setPassword(''); }}>Back</Button>
              </Stack>
            </form>
          )}
        </CardContent>
      </Card>
    </Box>
  );
}

// ---------------------------------------------------------------- Dashboard
export function StaffDashboard() {
  const windows = useQuery({ queryKey: ['staff', 'windows'], queryFn: () => api<AdmissionWindow[]>('/api/admission/windows', staff) });
  const current = windows.data?.find((w) => w.state === 'OPEN') ?? windows.data?.find((w) => w.state === 'SCHEDULED') ?? windows.data?.[0];
  const stats = useQuery({
    queryKey: ['staff', 'stats', current?.id], enabled: !!current,
    queryFn: () => api<WindowStats>(`/api/admission/windows/${current!.id}/stats`, staff),
  });
  const recent = useQuery({ queryKey: ['staff', 'recent'], queryFn: () => api<Page<ApplicationSummary>>('/api/admission/applications?size=5', staff) });
  if (windows.isLoading) return <Loading />;
  const tiles = [
    { label: 'Applications started', value: stats.data?.total },
    { label: 'Awaiting fee payment', value: (stats.data?.draft ?? 0) + (stats.data?.paymentPending ?? 0) },
    { label: 'Submitted', value: stats.data?.submitted },
  ];
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Admissions dashboard" subtitle={current ? `${current.name} · ${current.state === 'OPEN' ? `closes ${formatDateTime(current.closesAt)}` : current.state === 'SCHEDULED' ? `opens ${formatDateTime(current.opensAt)}` : current.state === 'CLOSED' ? `closed ${formatDate(current.closesAt)}` : 'draft'}` : 'No admission window yet'} />
      <ErrorAlert error={windows.error ?? stats.error} />
      <Grid container spacing={2} sx={{ mb: 3 }}>
        {tiles.map((t) => (
          <Grid key={t.label} size={{ xs: 12, sm: 4 }}>
            <Card><CardContent>
              <Typography variant="body2" color="text.secondary">{t.label}</Typography>
              <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 34 }}>{t.value ?? '—'}</Typography>
            </CardContent></Card>
          </Grid>
        ))}
      </Grid>
      <Card>
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
            <Typography variant="h6">Latest applications</Typography>
            <Button component={StaffLink} to="/staff/applications">View all</Button>
          </Stack>
          <ApplicationsTable rows={recent.data?.content ?? []} />
        </CardContent>
      </Card>
    </Container>
  );
}

function ApplicationsTable({ rows, onOpen }: { rows: ApplicationSummary[]; onOpen?: (id: number) => void }) {
  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          <TableRow><TableCell>Reference</TableCell><TableCell>Applicant</TableCell><TableCell>Phone</TableCell><TableCell>Fee</TableCell><TableCell>Status</TableCell><TableCell>Submitted</TableCell></TableRow>
        </TableHead>
        <TableBody>
          {rows.map((a) => (
            <TableRow key={a.id} hover onClick={() => onOpen?.(a.id)} sx={{ cursor: onOpen ? 'pointer' : 'default' }}>
              <TableCell>{a.referenceNo ?? '—'}</TableCell>
              <TableCell>{a.applicantName}</TableCell>
              <TableCell>{a.phone}</TableCell>
              <TableCell>{a.feePaid ? <Chip size="small" color="success" label="Paid" /> : <Chip size="small" label="Unpaid" />}</TableCell>
              <TableCell><Chip size="small" color={statusColour(a.status)} label={humanise(a.status)} /></TableCell>
              <TableCell>{formatDateTime(a.submittedAt)}</TableCell>
            </TableRow>
          ))}
          {rows.length === 0 && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 2 }}>No applications yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

// ---------------------------------------------------------------- Applications
const STATUSES = ['DRAFT', 'PAYMENT_PENDING', 'SUBMITTED', 'UNDER_REVIEW', 'SELECTED', 'NOT_SELECTED', 'PENDING_VERIFICATION', 'QUERIED',
  'NACTVET_REJECTED', 'OFFERED', 'ACCEPTED', 'DECLINED', 'OFFER_LAPSED', 'WITHDRAWN'];

/** The Admissions Office's applicant search (as NOAS): filters, the list, CSV export, and each application's file. */
export function ApplicationsPage() {
  const empty = { windowId: '', status: '', programmeId: '', firstChoice: false, sex: '', feePaid: '', checksPending: false, from: '', to: '', q: '' };
  const [f, setF] = useState(empty);
  const [text, setText] = useState('');
  const [sort, setSort] = useState('created');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [open, setOpen] = useState<number | null>(null);
  const windows = useQuery({ queryKey: ['staff', 'windows'], queryFn: () => api<AdmissionWindow[]>('/api/admission/windows', staff) });
  const programmes = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const filters = new URLSearchParams(Object.entries(f).filter(([, v]) => v !== '' && v !== false).map(([k, v]) => [k, String(v)]));
  const list = useQuery({ queryKey: ['staff', 'applicants', filters.toString(), sort, page, size],
    queryFn: () => api<Page<ApplicantRow>>(`/api/admission/applicants?${filters}&sort=${sort}&page=${page}&size=${size}`, staff) });
  const exportCsv = useMutation({ mutationFn: () => download(`/api/admission/applicants.csv?${filters}`, 'staff', 'applicants.csv') });
  const set = (patch: Partial<typeof empty>) => { setF({ ...f, ...patch }); setPage(0); };
  const any = filters.toString() !== '';
  return (
    <Container maxWidth="xl" disableGutters>
      <PageTitle title="Applicants" subtitle="Find applicants by name, index number, phone, email or reference, filter the list, export it, and print an application file."
        action={<Button variant="outlined" startIcon={<DownloadIcon />} sx={{ whiteSpace: 'nowrap' }} disabled={exportCsv.isPending || !list.data?.totalElements}
          onClick={() => exportCsv.mutate()}>{exportCsv.isPending ? 'Exporting…' : 'Export CSV'}</Button>} />
      <ErrorAlert error={exportCsv.error} />
      <Card sx={{ mb: 2 }}>
        <CardContent>
          <Grid container spacing={1.5}>
            <Grid size={{ xs: 12, md: 6 }}>
              <TextField size="small" fullWidth placeholder="Name, index number (S0101/0001), phone, email or reference" value={text}
                onChange={(e) => setText(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') set({ q: text.trim() }); }}
                onBlur={() => text.trim() !== f.q && set({ q: text.trim() })}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchIcon /></InputAdornment> } }} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField size="small" fullWidth select label="Admission window" value={f.windowId} onChange={(e) => set({ windowId: e.target.value })}>
                <MenuItem value="">All windows</MenuItem>
                {windows.data?.map((w) => <MenuItem key={w.id} value={String(w.id)}>{w.name}</MenuItem>)}
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField size="small" fullWidth select label="Status" value={f.status} onChange={(e) => set({ status: e.target.value })}>
                <MenuItem value="">Any status</MenuItem>
                {STATUSES.map((st) => <MenuItem key={st} value={st}>{humanise(st)}</MenuItem>)}
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField size="small" fullWidth select label="Programme chosen" value={f.programmeId} onChange={(e) => set({ programmeId: e.target.value })}>
                <MenuItem value="">Any programme</MenuItem>
                {programmes.data?.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.code} · {p.name}</MenuItem>)}
              </TextField>
            </Grid>
            <Grid size={{ xs: 6, sm: 3, md: 1.5 }}>
              <TextField size="small" fullWidth select label="Sex" value={f.sex} onChange={(e) => set({ sex: e.target.value })}>
                <MenuItem value="">Both</MenuItem><MenuItem value="F">Female</MenuItem><MenuItem value="M">Male</MenuItem>
              </TextField>
            </Grid>
            <Grid size={{ xs: 6, sm: 3, md: 1.5 }}>
              <TextField size="small" fullWidth select label="Fee" value={f.feePaid} onChange={(e) => set({ feePaid: e.target.value })}>
                <MenuItem value="">Paid or not</MenuItem><MenuItem value="true">Paid</MenuItem><MenuItem value="false">Not paid</MenuItem>
              </TextField>
            </Grid>
            <Grid size={{ xs: 6, sm: 3, md: 1.5 }}>
              <TextField size="small" fullWidth type="date" label="Submitted from" value={f.from} onChange={(e) => set({ from: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
            </Grid>
            <Grid size={{ xs: 6, sm: 3, md: 1.5 }}>
              <TextField size="small" fullWidth type="date" label="to" value={f.to} onChange={(e) => set({ to: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
            </Grid>
            <Grid size={{ xs: 12, md: 3 }}>
              <Stack direction="row" spacing={1} alignItems="center" sx={{ height: '100%' }} flexWrap="wrap" useFlexGap>
                {f.programmeId && <FormControlLabel control={<Checkbox size="small" checked={f.firstChoice} onChange={(e) => set({ firstChoice: e.target.checked })} />} label="1st choice only" />}
                <FormControlLabel control={<Checkbox size="small" checked={f.checksPending} onChange={(e) => set({ checksPending: e.target.checked })} />} label="Qualifications to check" />
                {any && <Button size="small" onClick={() => { setF(empty); setText(''); setPage(0); }}>Clear</Button>}
              </Stack>
            </Grid>
          </Grid>
        </CardContent>
      </Card>
      <Card>
        <ErrorAlert error={list.error} />
        {list.isLoading ? <Loading /> : (
          <TableContainer>
            <Table size="small" sx={{ minWidth: 900 }}>
              <TableHead>
                <TableRow>
                  <TableCell>Reference</TableCell>
                  <TableCell sortDirection={sort === 'name' ? 'asc' : false}>
                    <TableSortLabel active={sort === 'name'} onClick={() => setSort('name')}>Applicant</TableSortLabel>
                  </TableCell>
                  <TableCell>Index number</TableCell><TableCell>First choice</TableCell><TableCell>Fee</TableCell><TableCell>Status</TableCell>
                  <TableCell sortDirection={sort === 'submitted' ? 'desc' : false}>
                    <TableSortLabel active={sort === 'submitted'} direction="desc" onClick={() => setSort('submitted')}>Submitted</TableSortLabel>
                  </TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {list.data?.content.map((a) => (
                  <TableRow key={a.id} hover onClick={() => setOpen(a.id)} sx={{ cursor: 'pointer' }}>
                    <TableCell>{a.referenceNo ?? <Typography variant="body2" color="text.secondary">Not submitted</Typography>}</TableCell>
                    <TableCell>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{a.applicantName}</Typography>
                      <Typography variant="caption" color="text.secondary">{[a.sex === 'F' ? 'Female' : a.sex === 'M' ? 'Male' : null, a.phone, a.region].filter(Boolean).join(' · ')}</Typography>
                    </TableCell>
                    <TableCell>{a.indexNumber ?? '—'}</TableCell>
                    <TableCell>{a.firstChoice ?? '—'}{a.choices > 1 && <Typography component="span" variant="caption" color="text.secondary"> +{a.choices - 1}</Typography>}</TableCell>
                    <TableCell>{a.feePaid ? <Chip size="small" color="success" label="Paid" /> : <Chip size="small" label="Not paid" />}</TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                        <Chip size="small" color={statusColour(a.status)} label={humanise(a.status)} />
                        {a.checksPending > 0 && <Chip size="small" color="warning" variant="outlined" label={`${a.checksPending} to check`} />}
                      </Stack>
                    </TableCell>
                    <TableCell>{a.submittedAt ? formatDateTime(a.submittedAt) : '—'}</TableCell>
                  </TableRow>
                ))}
                {!list.data?.content.length && (
                  <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 2 }}>{any ? 'No applicants match these filters.' : 'No applications yet.'}</Typography></TableCell></TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
        )}
        <TablePagination component="div" count={list.data?.totalElements ?? 0} page={page} rowsPerPage={size}
          onPageChange={(_, p) => setPage(p)} onRowsPerPageChange={(e) => { setSize(Number(e.target.value)); setPage(0); }} rowsPerPageOptions={[25, 50, 100]} />
      </Card>
      <ApplicationDrawer id={open} onClose={() => setOpen(null)} />
    </Container>
  );
}

function ApplicationDrawer({ id, onClose }: { id: number | null; onClose: () => void }) {
  const d = useQuery({ queryKey: ['staff', 'application', id], enabled: id != null, queryFn: () => api<ApplicationDetail>(`/api/admission/applications/${id}`, staff) });
  const a = d.data;
  const [printing, setPrinting] = useState(false);
  return (
    <Drawer anchor="right" open={id != null} onClose={onClose} sx={{ '& .MuiDrawer-paper': { width: { xs: '100%', sm: 560 }, p: 3 } }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="h6">{a?.application.referenceNo ?? 'Application'}</Typography>
        <Stack direction="row" spacing={1} alignItems="center">
          {a && <Button size="small" variant="outlined" startIcon={<PrintIcon />} disabled={printing}
            onClick={() => { setPrinting(true); download(`/api/admission/applications/${id}/file`, 'staff', `application-${a.application.referenceNo ?? id}.pdf`)
              .finally(() => { setPrinting(false); d.refetch(); }); }}>{printing ? 'Preparing…' : 'Print file'}</Button>}
          <IconButton onClick={onClose} aria-label="Close"><CloseIcon /></IconButton>
        </Stack>
      </Stack>
      {d.isLoading && <Loading />}
      {a && (
        <Stack spacing={2} sx={{ mt: 2 }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <Chip color={statusColour(a.application.status)} label={humanise(a.application.status)} />
            {a.application.amendedAt && <Typography variant="caption" color="text.secondary">Changed by the applicant after submitting · {formatDateTime(a.application.amendedAt)}</Typography>}
          </Stack>
          <Box>
            <Typography variant="subtitle2" color="text.secondary">Applicant</Typography>
            <Typography sx={{ fontWeight: 700 }}>{a.applicant.fullName}</Typography>
            <Typography variant="body2">{a.applicant.phone}{a.applicant.email ? ` · ${a.applicant.email}` : ''}</Typography>
            <Typography variant="body2">{a.applicant.sex === 'F' ? 'Female' : a.applicant.sex === 'M' ? 'Male' : '—'} · Born {formatDate(a.applicant.dateOfBirth)} · {a.applicant.region ?? '—'}</Typography>
            <Typography variant="body2">Next of kin: {a.applicant.nextOfKinName ?? '—'} ({a.applicant.nextOfKinRelation ?? '—'}) {a.applicant.nextOfKinPhone ?? ''}</Typography>
            {a.applicant.premNumber && <Typography variant="body2">{`PREM number (NECTA): ${a.applicant.premNumber}`}</Typography>}
          </Box>
          <Divider />
          <Box>
            <Typography variant="subtitle2" color="text.secondary">Qualifications</Typography>
            {a.qualifications.map((q) => (
              <Box key={q.id} sx={{ mt: 1 }}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Typography variant="body2" sx={{ fontWeight: 700 }}>{describe(q).head}</Typography>
                  <Chip size="small" color={CHECK_COLOUR[q.checkStatus]} label={q.source === 'MANUAL' ? CHECK_LABEL[q.checkStatus] : `Verified by ${q.source}`} />
                </Stack>
                <Typography variant="body2" color="text.secondary">
                  {[describe(q).rest, q.gpa != null ? `GPA ${q.gpa}` : null, q.classAward, q.division ? `Division ${q.division}` : null].filter(Boolean).join(' · ')}
                </Typography>
                {q.subjects.length > 0 && <Typography variant="caption" color="text.secondary">{q.subjects.map((s) => `${s.name} ${s.grade}`).join(', ')}</Typography>}
                {q.checkNote && <Typography variant="caption" color="error.main" sx={{ display: 'block' }}>{q.checkNote}</Typography>}
                <Box sx={{ mt: 0.5 }}><CheckButtons q={q} onDone={() => d.refetch()} /></Box>
              </Box>
            ))}
          </Box>
          <ApplicationDocumentsSection applicationId={a.application.id} onChange={() => d.refetch()} />
          <Divider />
          <Box>
            <Typography variant="subtitle2" color="text.secondary">Fee</Typography>
            <Typography variant="body2">{a.fee.invoiceNo ?? 'No invoice'} · {formatTzs(a.fee.amount)} · {a.fee.feePaid ? 'Paid' : 'Not paid'}{a.fee.controlNo ? ` · Control no. ${a.fee.controlNo}` : ''}</Typography>
          </Box>
          <Divider />
          <Box>
            <Typography variant="subtitle2" color="text.secondary">Programme choices</Typography>
            {a.application.choices.map((c) => (
              <Card key={c.id} sx={{ mt: 1 }}><CardContent>
                <Typography sx={{ fontWeight: 700 }}>{c.order}. {c.programmeName}</Typography>
                <Box sx={{ mt: 1 }}><EligibilityDetail result={c.eligibility} /></Box>
              </CardContent></Card>
            ))}
          </Box>
          <Divider />
          <Box>
            <Typography variant="subtitle2" color="text.secondary">History</Typography>
            <Box component="ol" sx={{ listStyle: 'none', p: 0, m: 0, mt: 1, borderLeft: 2, borderColor: 'divider' }}>
              {a.history.map((h, i) => (
                <Box component="li" key={i} sx={{ pl: 1.5, pb: 1, position: 'relative' }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{h.label}</Typography>
                  <Typography variant="caption" color="text.secondary">{formatDateTime(h.at)}{h.by ? ` · ${h.by}` : ''}</Typography>
                </Box>
              ))}
              {!a.history.length && <Typography variant="body2" color="text.secondary" sx={{ pl: 1.5 }}>No history recorded.</Typography>}
            </Box>
          </Box>
        </Stack>
      )}
    </Drawer>
  );
}

// ---------------------------------------------------------------- Programmes
export function ProgrammesPage() {
  const { user } = useSession();
  const canEdit = !!user('staff')?.roles.some((r) => r === 'INSTITUTION_ADMIN' || r === 'DEPUTY_PRINCIPAL_ACADEMIC');
  const [tab, setTab] = useState<'programmes' | 'departments' | 'faculties' | 'campuses'>('programmes');
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Programmes and structure" subtitle="NVA and NTA programmes accredited by NACTVET, the departments and faculties that run them, and the campuses they are taught at." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" sx={{ mb: 2 }}>
        <Tab value="programmes" label="Programmes" />
        <Tab value="departments" label="Departments" />
        <Tab value="faculties" label="Faculties / schools" />
        <Tab value="campuses" label="Campuses" />
      </Tabs>
      {tab === 'programmes' && <ProgrammesTab canEdit={canEdit} />}
      {tab === 'departments' && <DepartmentsTab canEdit={canEdit} />}
      {tab === 'faculties' && <NamedListTab kind="faculties" canEdit={canEdit} />}
      {tab === 'campuses' && <CampusesTab canEdit={canEdit} />}
    </Container>
  );
}

function ProgrammesTab({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const campuses = useCampuses();
  const pc = useProgrammeCampuses();
  const [campusIds, setCampusIds] = useState<number[]>([]);
  const list = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const depts = useQuery({ queryKey: ['staff', 'departments'], queryFn: () => api<Department[]>('/api/academic/departments', staff) });
  const empty = { id: 0, departmentId: '', code: '', nactvetCode: '', name: '', awardFamily: 'NTA', awardLevel: '4', durationSemesters: '2', accreditedCapacity: '', entryLevel: '' };
  const [form, setForm] = useState<typeof empty | null>(null);
  const edit = (p: Programme) => { setCampusIds(pc.data?.[String(p.id)] ?? []); setForm({ id: p.id, departmentId: String(p.departmentId), code: p.code, nactvetCode: p.nactvetCode ?? '', name: p.name,
    awardFamily: p.awardFamily, awardLevel: String(p.awardLevel), durationSemesters: String(p.durationSemesters),
    accreditedCapacity: p.accreditedCapacity ? String(p.accreditedCapacity) : '', entryLevel: p.entryLevelSet ? String(p.entryLevel) : '' }); };
  const save = useMutation({
    mutationFn: async (f: typeof empty) => {
      const saved = await api<Programme>(f.id ? `/api/academic/programmes/${f.id}` : '/api/academic/programmes', { method: f.id ? 'PUT' : 'POST', realm: 'staff', body: {
        ...f, id: undefined, departmentId: Number(f.departmentId), awardLevel: Number(f.awardLevel), durationSemesters: Number(f.durationSemesters),
        accreditedCapacity: f.accreditedCapacity ? Number(f.accreditedCapacity) : null, entryLevel: f.entryLevel ? Number(f.entryLevel) : null, active: true } });
      if ((campuses.data?.length ?? 0) > 1) await api(`/api/academic/programmes/${saved.id}/campuses`, { method: 'PUT', realm: 'staff', body: { campusIds } });
      return saved;
    },
    onSuccess: () => { setForm(null); qc.invalidateQueries({ queryKey: ['staff', 'programmes'] }); qc.invalidateQueries({ queryKey: ['staff', 'programme-campuses'] }); qc.invalidateQueries({ queryKey: ['staff', 'campuses'] }); },
  });
  const campusNames = (id: number) => {
    const ids = pc.data?.[String(id)] ?? [];
    if (!ids.length) return campuses.data?.find((c) => c.main)?.name ?? '—';
    return ids.map((i) => campuses.data?.find((c) => c.id === i)?.name).filter(Boolean).join(', ');
  };
  const multiCampus = (campuses.data?.length ?? 0) > 1;
  const levels = (f: typeof empty) => (f.awardFamily === 'NVA' ? [1, 2, 3] : [4, 5, 6, 7, 8]).filter((l) => l <= Number(f.awardLevel));
  return (
    <>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }}>
        <Typography variant="body2" color="text.secondary">Applicants see only programmes that admit at the level their highest qualification leads to.</Typography>
        {canEdit && <Button variant="contained" startIcon={<AddIcon />} onClick={() => { setCampusIds([]); setForm(empty); }} sx={{ flexShrink: 0, ml: 2 }}>New programme</Button>}
      </Stack>
      <ErrorAlert error={list.error} />
      <Card><TableContainer>
        <Table size="small">
          <TableHead><TableRow><TableCell>Code</TableCell><TableCell>Programme</TableCell><TableCell>Award</TableCell><TableCell>Admits at</TableCell><TableCell>Department</TableCell>{multiCampus && <TableCell>Campus</TableCell>}<TableCell align="right">Capacity</TableCell></TableRow></TableHead>
          <TableBody>
            {list.data?.map((p) => (
              <TableRow key={p.id} hover={canEdit} sx={{ cursor: canEdit ? 'pointer' : 'default' }} onClick={() => canEdit && edit(p)}>
                <TableCell>{p.code}</TableCell><TableCell>{p.name}</TableCell><TableCell>{p.awardName}</TableCell>
                <TableCell>{p.awardFamily} Level {p.entryLevel}</TableCell>
                <TableCell>{p.departmentName}</TableCell>{multiCampus && <TableCell>{campusNames(p.id)}</TableCell>}<TableCell align="right">{p.accreditedCapacity ?? '—'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer></Card>
      <Dialog open={!!form} onClose={() => setForm(null)} fullWidth maxWidth="sm">
        <DialogTitle>{form?.id ? form.name : 'New programme'}</DialogTitle>
        {form && (
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              <TextField select label="Department" value={form.departmentId} onChange={(e) => setForm({ ...form, departmentId: e.target.value })}>
                {depts.data?.map((d) => <MenuItem key={d.id} value={String(d.id)}>{d.name}</MenuItem>)}
              </TextField>
              <Stack direction="row" spacing={2}>
                <TextField label="Code" value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />
                <TextField label="NACTVET code" value={form.nactvetCode} onChange={(e) => setForm({ ...form, nactvetCode: e.target.value })} />
              </Stack>
              <TextField label="Programme name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              <Stack direction="row" spacing={2}>
                <TextField select label="Award" value={form.awardFamily} onChange={(e) => setForm({ ...form, awardFamily: e.target.value, awardLevel: e.target.value === 'NVA' ? '1' : '4', entryLevel: '' })}>
                  <MenuItem value="NTA">NTA</MenuItem><MenuItem value="NVA">NVA</MenuItem>
                </TextField>
                <TextField select label="Level" value={form.awardLevel} onChange={(e) => setForm({ ...form, awardLevel: e.target.value, entryLevel: '' })}>
                  {(form.awardFamily === 'NVA' ? [1, 2, 3] : [4, 5, 6, 7, 8]).map((l) => <MenuItem key={l} value={String(l)}>{l}</MenuItem>)}
                </TextField>
                <TextField label="Semesters" value={form.durationSemesters} onChange={(e) => setForm({ ...form, durationSemesters: e.target.value })} />
                <TextField label="Capacity" value={form.accreditedCapacity} onChange={(e) => setForm({ ...form, accreditedCapacity: e.target.value })} />
              </Stack>
              <TextField select label="Admits at" value={form.entryLevel} onChange={(e) => setForm({ ...form, entryLevel: e.target.value })}
                slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
                helperText="The level applicants enter at, e.g. an Ordinary Diploma (NTA 4–6) admits Form Four leavers at NTA 4. Leave on automatic to work it out from the level and semesters.">
                <MenuItem value="">Automatic</MenuItem>
                {levels(form).map((l) => <MenuItem key={l} value={String(l)}>{form.awardFamily} Level {l}</MenuItem>)}
              </TextField>
              <CampusPicker value={campusIds} onChange={setCampusIds} />
              <ErrorAlert error={save.error} />
            </Stack>
          </DialogContent>
        )}
        <DialogActions><Button onClick={() => setForm(null)}>Cancel</Button><Button variant="contained" onClick={() => form && save.mutate(form)} disabled={save.isPending}>Save</Button></DialogActions>
      </Dialog>
    </>
  );
}

// ---------------------------------------------------------------- Windows
export function WindowsPage() {
  const list = useQuery({ queryKey: ['staff', 'windows'], queryFn: () => api<AdmissionWindow[]>('/api/admission/windows', staff) });
  const { canEdit } = useWindowRoles();
  const [creating, setCreating] = useState(false);
  const navigate = useNavigate();
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Admission windows" subtitle="Intakes, dates, fees and programmes on offer. Open, close and reopen windows from each window's page."
        action={canEdit && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreating(true)}>New window</Button>} />
      <ErrorAlert error={list.error} />
      {list.data && !list.data.length && <Alert severity="info">No admission windows yet.</Alert>}
      <Stack spacing={2}>
        {list.data?.map((w) => (
          <Card key={w.id} component={StaffLink} to={`/staff/windows/${w.id}`} sx={{ textDecoration: 'none' }}>
            <CardContent>
              <Stack direction="row" justifyContent="space-between" spacing={2}>
                <Box>
                  <Typography variant="h6">{w.name}</Typography>
                  <Typography color="text.secondary">{formatDateTime(w.opensAt)} → {formatDateTime(w.closesAt)} · Fee {formatTzs(w.applicationFee)} · Up to {w.maxChoices} choices</Typography>
                </Box>
                <Box><WindowStateChip state={w.state} /></Box>
              </Stack>
            </CardContent>
          </Card>
        ))}
      </Stack>
      {creating && <WindowFormDialog win={null} onClose={() => setCreating(false)} onSaved={(w) => navigate(`/staff/windows/${w.id}`)} />}
    </Container>
  );
}

function buildRules(f: { passes: string; subjects: string; minGrade: string; altFamily: string; altLevel: string; summary: string }): EntryRules {
  const subjects = f.subjects.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean);
  const pathways: EntryRules['pathways'] = [];
  const conditions = [];
  if (Number(f.passes) > 0) conditions.push({ type: 'CSEE_MIN_PASSES' as const, count: Number(f.passes), minGrade: f.minGrade });
  if (subjects.length) conditions.push({ type: 'CSEE_SUBJECT' as const, count: subjects.length, minGrade: f.minGrade, subjects });
  if (conditions.length) pathways.push({ label: 'Form Four (CSEE)', conditions });
  if (f.altFamily) pathways.push({ label: `${f.altFamily} Level ${f.altLevel} holders`, conditions: [{ type: 'PRIOR_AWARD', family: f.altFamily, minLevel: Number(f.altLevel) }] });
  return { summary: f.summary, pathways };
}

const CONFIGURE_ROLES = ['INSTITUTION_ADMIN', 'ADMISSIONS_OFFICER', 'DEPUTY_PRINCIPAL_ACADEMIC'];
const DOCUMENT_ROLES = ['INSTITUTION_ADMIN', 'ADMISSIONS_OFFICER', 'DEPUTY_PRINCIPAL_ACADEMIC', 'REGISTRAR'];

export function WindowDetailPage() {
  const { id } = useParams();
  const { user } = useSession();
  const canConfigure = !!user('staff')?.roles.some((r) => CONFIGURE_ROLES.includes(r));
  // the documents applicants upload are personal data: only the offices that handle applications see the list (API rule)
  const canSeeDocuments = !!user('staff')?.roles.some((r) => DOCUMENT_ROLES.includes(r));
  const qc = useQueryClient();
  const w = useQuery({ queryKey: ['staff', 'window', id], queryFn: () => api<AdmissionWindow>(`/api/admission/windows/${id}`, staff) });
  const programmes = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff'] });
  const [editing, setEditing] = useState(false);
  const empty = { programmeId: '', capacity: '50', passes: '4', subjects: 'BASIC MATHEMATICS', minGrade: 'D', altFamily: '', altLevel: '3', summary: 'Four passes in CSEE including Basic Mathematics' };
  const [form, setForm] = useState<typeof empty | null>(null);
  const add = useMutation({
    mutationFn: (f: typeof empty) => api(`/api/admission/windows/${id}/programmes`, { method: 'POST', realm: 'staff', body: { programmeId: Number(f.programmeId), capacity: Number(f.capacity), entryRules: buildRules(f) } }),
    onSuccess: () => { setForm(null); refresh(); },
  });
  if (w.isLoading) return <Loading />;
  const win = w.data;
  if (!win) return <ErrorAlert error={w.error} />;
  // the steps that work on applications are ready once the window has opened (applications can exist)
  const notOpenYet = win.state === 'DRAFT' || win.state === 'SCHEDULED' ? 'Ready once the window opens for applications.' : undefined;
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title={win.name} subtitle={`${win.academicYear} · ${win.intake} · ${formatDateTime(win.opensAt)} → ${formatDateTime(win.closesAt)} · Fee ${formatTzs(win.applicationFee)} · Offers open ${win.offerAcceptanceDays} days`}
        action={<Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" justifyContent="flex-end" sx={{ '& .MuiButton-root': { whiteSpace: 'nowrap' } }}>
          <Button variant="outlined" component={StaffLink} to={`/staff/windows/${id}/entry-test`}
            disabledReason={win.programmes?.length ? undefined : 'Add a programme to the window first.'}>Test entry rules</Button>
          <Button variant="outlined" component={StaffLink} to={`/staff/windows/${id}/qualification-checks`} disabledReason={notOpenYet}>Qualification checks</Button>
          <Button variant="outlined" component={StaffLink} to={`/staff/windows/${id}/selection`} disabledReason={notOpenYet}>Selection</Button>
          <Button variant="outlined" component={StaffLink} to={`/staff/windows/${id}/verification`} disabledReason={notOpenYet}>NACTVET &amp; offers</Button>
          <Button variant="outlined" component={StaffLink} to={`/staff/windows/${id}/handover`} disabledReason={notOpenYet}>Registration hand-over</Button>
          {canConfigure && win.status !== 'CLOSED' && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setForm(empty)}>Add programme</Button>}
        </Stack>} />
      <WindowLifecycle win={win} onEdit={() => setEditing(true)} />
      <Card><TableContainer>
        <Table size="small">
          <TableHead><TableRow><TableCell>Programme</TableCell><TableCell>Award</TableCell><TableCell align="right">Places</TableCell><TableCell>Minimum entry requirements</TableCell></TableRow></TableHead>
          <TableBody>
            {win.programmes?.map((p) => (
              <TableRow key={p.id}><TableCell>{p.programmeName}</TableCell><TableCell>{p.awardName}</TableCell><TableCell align="right">{p.capacity}</TableCell><TableCell>{p.requirementSummary}</TableCell></TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer></Card>
      {canSeeDocuments && <WindowDocumentsCard windowId={win.id} canEdit={canConfigure} />}
      <JoiningInstructionsCard win={win} canEdit={canConfigure} />
      <WindowHistoryCard windowId={win.id} />
      {editing && <WindowFormDialog win={win} onClose={() => setEditing(false)} onSaved={(x) => { setEditing(false); qc.setQueryData(['staff', 'window', id], x); refresh(); }} />}
      <Dialog open={!!form} onClose={() => setForm(null)} fullWidth maxWidth="sm">
        <DialogTitle>Offer a programme</DialogTitle>
        {form && (
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              <TextField select label="Programme" value={form.programmeId} onChange={(e) => setForm({ ...form, programmeId: e.target.value })}>
                {programmes.data?.filter((p) => !win.programmes?.some((wp) => wp.programmeId === p.id)).map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.name}</MenuItem>)}
              </TextField>
              <TextField label="Places (capacity)" value={form.capacity} onChange={(e) => setForm({ ...form, capacity: e.target.value })} />
              <Typography variant="subtitle2">NACTVET minimum entry requirements</Typography>
              <Stack direction="row" spacing={2}>
                <TextField label="CSEE passes (0 = none)" value={form.passes} onChange={(e) => setForm({ ...form, passes: e.target.value })} />
                <TextField select label="Minimum grade" value={form.minGrade} onChange={(e) => setForm({ ...form, minGrade: e.target.value })}>
                  {['A', 'B', 'C', 'D'].map((g) => <MenuItem key={g} value={g}>{g}</MenuItem>)}
                </TextField>
              </Stack>
              <TextField label="Required subjects (comma separated)" value={form.subjects} onChange={(e) => setForm({ ...form, subjects: e.target.value })} helperText="e.g. BASIC MATHEMATICS, PHYSICS" />
              <Stack direction="row" spacing={2}>
                <TextField select label="Alternative: prior award" value={form.altFamily} onChange={(e) => setForm({ ...form, altFamily: e.target.value })}>
                  <MenuItem value="">None</MenuItem><MenuItem value="NVA">NVA</MenuItem><MenuItem value="NTA">NTA</MenuItem>
                </TextField>
                <TextField label="Minimum level" value={form.altLevel} onChange={(e) => setForm({ ...form, altLevel: e.target.value })} disabled={!form.altFamily} />
              </Stack>
              <TextField label="Summary shown to applicants" value={form.summary} onChange={(e) => setForm({ ...form, summary: e.target.value })} multiline />
              <ErrorAlert error={add.error} />
            </Stack>
          </DialogContent>
        )}
        <DialogActions><Button onClick={() => setForm(null)}>Cancel</Button><Button variant="contained" onClick={() => form && add.mutate(form)} disabled={add.isPending}>Add</Button></DialogActions>
      </Dialog>
    </Container>
  );
}
