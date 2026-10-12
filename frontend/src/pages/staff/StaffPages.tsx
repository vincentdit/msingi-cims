import AddIcon from '@mui/icons-material/Add';
import SearchIcon from '@mui/icons-material/Search';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, Divider, Drawer,
  IconButton, InputAdornment, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow,
  TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import CloseIcon from '@mui/icons-material/Close';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { api, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type {
  AdmissionWindow, ApplicationDetail, ApplicationSummary, Department, EntryRules, Page, Programme, TokenResponse, WindowStats,
} from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle, humanise, statusColour } from '../../components/common';
import { useInstitution } from '../../components/InstitutionContext';
import { EligibilityDetail } from '../applicant/steps';
import JoiningInstructionsCard from './JoiningInstructionsCard';
import { CHECK_COLOUR, CHECK_LABEL, CheckButtons, describe } from './QualificationChecks';
import { WindowFormDialog, WindowHistoryCard, WindowLifecycle, WindowStateChip, useWindowRoles } from './WindowAdmin';

const staff = { realm: 'staff' as const };

// ---------------------------------------------------------------- Login
export function StaffLoginPage() {
  const inst = useInstitution();
  const { signIn } = useSession();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const m = useMutation({
    mutationFn: () => api<TokenResponse>('/api/auth/login', { method: 'POST', body: { username, password } }),
    onSuccess: (t) => { signIn('staff', t); navigate('/staff'); },
  });
  return (
    <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', bgcolor: '#0F1B2D', p: 2 }}>
      <Card sx={{ width: '100%', maxWidth: 420, borderRadius: 3 }}>
        <CardContent sx={{ p: 4 }}>
          <Typography variant="overline" color="text.secondary">CIMS back-office</Typography>
          <Typography variant="h5">{inst.shortName ?? inst.name}</Typography>
          <form onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
            <Stack spacing={2} sx={{ mt: 3 }}>
              <TextField label="Username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
              <TextField label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
              <ErrorAlert error={m.error} />
              <Button type="submit" variant="contained" size="large" disabled={m.isPending}>Sign in</Button>
              <Button component={RouterLink} to="/">Back to admission portal</Button>
            </Stack>
          </form>
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
            <Button component={RouterLink} to="/staff/applications">View all</Button>
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
export function ApplicationsPage() {
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [open, setOpen] = useState<number | null>(null);
  const params = new URLSearchParams({ page: String(page), size: String(size), ...(status && { status }), ...(search && { q: search }) });
  const list = useQuery({ queryKey: ['staff', 'applications', params.toString()], queryFn: () => api<Page<ApplicationSummary>>(`/api/admission/applications?${params}`, staff) });
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Applications" subtitle="Search and review applications received through the admission portal." />
      <Card>
        <CardContent>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 2 }}>
            <TextField size="small" placeholder="Name, phone or reference" value={q} onChange={(e) => setQ(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') { setSearch(q); setPage(0); } }}
              slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchIcon /></InputAdornment> } }} />
            <TextField size="small" select label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} sx={{ minWidth: 200 }}>
              <MenuItem value="">All</MenuItem>
              {['DRAFT', 'PAYMENT_PENDING', 'SUBMITTED', 'UNDER_REVIEW', 'SELECTED', 'NOT_SELECTED'].map((s) => <MenuItem key={s} value={s}>{humanise(s)}</MenuItem>)}
            </TextField>
          </Stack>
          <ErrorAlert error={list.error} />
          {list.isLoading ? <Loading /> : <ApplicationsTable rows={list.data?.content ?? []} onOpen={setOpen} />}
          <TablePagination component="div" count={list.data?.totalElements ?? 0} page={page} rowsPerPage={size}
            onPageChange={(_, p) => setPage(p)} onRowsPerPageChange={(e) => { setSize(Number(e.target.value)); setPage(0); }} rowsPerPageOptions={[20, 50, 100]} />
        </CardContent>
      </Card>
      <ApplicationDrawer id={open} onClose={() => setOpen(null)} />
    </Container>
  );
}

function ApplicationDrawer({ id, onClose }: { id: number | null; onClose: () => void }) {
  const d = useQuery({ queryKey: ['staff', 'application', id], enabled: id != null, queryFn: () => api<ApplicationDetail>(`/api/admission/applications/${id}`, staff) });
  const a = d.data;
  return (
    <Drawer anchor="right" open={id != null} onClose={onClose} sx={{ '& .MuiDrawer-paper': { width: { xs: '100%', sm: 560 }, p: 3 } }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="h6">{a?.application.referenceNo ?? 'Application'}</Typography>
        <IconButton onClick={onClose}><CloseIcon /></IconButton>
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
        </Stack>
      )}
    </Drawer>
  );
}

// ---------------------------------------------------------------- Programmes
export function ProgrammesPage() {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const depts = useQuery({ queryKey: ['staff', 'departments'], queryFn: () => api<Department[]>('/api/academic/departments', staff) });
  const empty = { departmentId: '', code: '', nactvetCode: '', name: '', awardFamily: 'NTA', awardLevel: '4', durationSemesters: '2', accreditedCapacity: '' };
  const [form, setForm] = useState<typeof empty | null>(null);
  const save = useMutation({
    mutationFn: (f: typeof empty) => api('/api/academic/programmes', { method: 'POST', realm: 'staff', body: {
      ...f, departmentId: Number(f.departmentId), awardLevel: Number(f.awardLevel), durationSemesters: Number(f.durationSemesters),
      accreditedCapacity: f.accreditedCapacity ? Number(f.accreditedCapacity) : null, active: true } }),
    onSuccess: () => { setForm(null); qc.invalidateQueries({ queryKey: ['staff', 'programmes'] }); },
  });
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Programmes" subtitle="NVA and NTA programmes accredited by NACTVET." action={<Button variant="contained" startIcon={<AddIcon />} onClick={() => setForm(empty)}>New programme</Button>} />
      <ErrorAlert error={list.error} />
      <Card><TableContainer>
        <Table size="small">
          <TableHead><TableRow><TableCell>Code</TableCell><TableCell>Programme</TableCell><TableCell>Award</TableCell><TableCell>Department</TableCell><TableCell align="right">Capacity</TableCell></TableRow></TableHead>
          <TableBody>
            {list.data?.map((p) => (
              <TableRow key={p.id}>
                <TableCell>{p.code}</TableCell><TableCell>{p.name}</TableCell><TableCell>{p.awardName}</TableCell>
                <TableCell>{p.departmentName}</TableCell><TableCell align="right">{p.accreditedCapacity ?? '—'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer></Card>
      <Dialog open={!!form} onClose={() => setForm(null)} fullWidth maxWidth="sm">
        <DialogTitle>New programme</DialogTitle>
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
                <TextField select label="Award" value={form.awardFamily} onChange={(e) => setForm({ ...form, awardFamily: e.target.value, awardLevel: e.target.value === 'NVA' ? '1' : '4' })}>
                  <MenuItem value="NTA">NTA</MenuItem><MenuItem value="NVA">NVA</MenuItem>
                </TextField>
                <TextField select label="Level" value={form.awardLevel} onChange={(e) => setForm({ ...form, awardLevel: e.target.value })}>
                  {(form.awardFamily === 'NVA' ? [1, 2, 3] : [4, 5, 6, 7, 8]).map((l) => <MenuItem key={l} value={String(l)}>{l}</MenuItem>)}
                </TextField>
                <TextField label="Semesters" value={form.durationSemesters} onChange={(e) => setForm({ ...form, durationSemesters: e.target.value })} />
                <TextField label="Capacity" value={form.accreditedCapacity} onChange={(e) => setForm({ ...form, accreditedCapacity: e.target.value })} />
              </Stack>
              <ErrorAlert error={save.error} />
            </Stack>
          </DialogContent>
        )}
        <DialogActions><Button onClick={() => setForm(null)}>Cancel</Button><Button variant="contained" onClick={() => form && save.mutate(form)} disabled={save.isPending}>Save</Button></DialogActions>
      </Dialog>
    </Container>
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
          <Card key={w.id} component={RouterLink} to={`/staff/windows/${w.id}`} sx={{ textDecoration: 'none' }}>
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

export function WindowDetailPage() {
  const { id } = useParams();
  const { user } = useSession();
  const canConfigure = !!user('staff')?.roles.some((r) => CONFIGURE_ROLES.includes(r));
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
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title={win.name} subtitle={`${win.academicYear} · ${win.intake} · ${formatDateTime(win.opensAt)} → ${formatDateTime(win.closesAt)} · Fee ${formatTzs(win.applicationFee)} · Offers open ${win.offerAcceptanceDays} days`}
        action={<Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" justifyContent="flex-end" sx={{ '& .MuiButton-root': { whiteSpace: 'nowrap' } }}>
          <Button variant="outlined" component={RouterLink} to={`/staff/windows/${id}/qualification-checks`}>Qualification checks</Button>
          <Button variant="outlined" component={RouterLink} to={`/staff/windows/${id}/selection`}>Selection</Button>
          <Button variant="outlined" component={RouterLink} to={`/staff/windows/${id}/verification`}>NACTVET &amp; offers</Button>
          <Button variant="outlined" component={RouterLink} to={`/staff/windows/${id}/handover`}>Registration hand-over</Button>
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
