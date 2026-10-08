import AddIcon from '@mui/icons-material/Add';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import EditIcon from '@mui/icons-material/EditOutlined';
import {
  Alert, Autocomplete, Box, Button, Card, CardActionArea, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle,
  Divider, FormControlLabel, IconButton, MenuItem, Stack, Switch, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  Tabs, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { api, formatDate, formatDateTime } from '../../api/client';
import {
  CALENDAR_EVENT_KINDS, type AcademicCalendar, type AcademicYear, type CalendarEvent, type CourseModule, type Curriculum,
  type CurriculumModuleSlot, type CurriculumSummary, type Department, type ElectiveRule, type Programme, type Semester,
} from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle, humanise, statusColour } from '../../components/common';

const staff = { realm: 'staff' as const };
const CALENDAR_EDITORS = ['INSTITUTION_ADMIN', 'DEPUTY_PRINCIPAL_ACADEMIC'];
const PREPARERS = ['INSTITUTION_ADMIN', 'DEPUTY_PRINCIPAL_ACADEMIC', 'HEAD_OF_DEPARTMENT', 'PROGRAMME_COORDINATOR'];
const WINDOW_LABEL = { OPEN: 'Registration open', LATE: 'Late registration', NOT_OPEN: 'Not open yet', CLOSED: 'Registration closed' } as const;
const WINDOW_COLOUR = { OPEN: 'success', LATE: 'warning', NOT_OPEN: 'default', CLOSED: 'default' } as const;
const num = (n: number) => Number(n).toLocaleString('en-GB', { maximumFractionDigits: 1 });

function useRoles() {
  const { user } = useSession();
  const roles = user('staff')?.roles ?? [];
  return (allowed: string[]) => roles.some((r) => allowed.includes(r));
}

export function useYears() {
  return useQuery({ queryKey: ['staff', 'years'], queryFn: () => api<AcademicYear[]>('/api/academic/years', staff) });
}

/** The year to show first: the current one, otherwise the latest. */
function defaultYear(years?: AcademicYear[]) {
  if (!years?.length) return undefined;
  return (years.find((y) => y.current) ?? [...years].sort((a, b) => b.startDate.localeCompare(a.startDate))[0]).id;
}

// ============================================================ academic calendar (FR-ACA-04)

type SemesterForm = Omit<Semester, 'id' | 'academicYear' | 'window' | 'lateRegistrationCloses'> & { id?: number; lateRegistrationCloses: string };
type EventForm = { id?: number; academicYearId: number; semesterId: string; kind: string; title: string; startDate: string; endDate: string };

export function CalendarPage() {
  const qc = useQueryClient();
  const can = useRoles();
  const canEdit = can(CALENDAR_EDITORS);
  const years = useYears();
  const [yearId, setYearId] = useState<number>();
  useEffect(() => { if (!yearId) setYearId(defaultYear(years.data)); }, [years.data, yearId]);
  const cal = useQuery({
    queryKey: ['staff', 'calendar', yearId], enabled: !!yearId,
    queryFn: () => api<AcademicCalendar>(`/api/academic/calendar?academicYearId=${yearId}`, staff),
  });
  const [sem, setSem] = useState<SemesterForm | null>(null);
  const [ev, setEv] = useState<EventForm | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff', 'calendar'] });
  const del = useMutation({ mutationFn: (id: number) => api(`/api/academic/calendar-events/${id}`, { ...staff, method: 'DELETE' }), onSuccess: refresh });
  const year = years.data?.find((y) => y.id === yearId);
  const nextNo = (cal.data?.semesters.length ?? 0) + 1;

  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Academic calendar" subtitle="Semesters with their registration windows, and the key dates of the year."
        action={
          <TextField select size="small" label="Academic year" value={yearId ?? ''} onChange={(e) => setYearId(Number(e.target.value))} sx={{ minWidth: 180 }}>
            {(years.data ?? []).map((y) => <MenuItem key={y.id} value={y.id}>{y.code}{y.current ? ' (current)' : ''}</MenuItem>)}
          </TextField>
        } />
      <ErrorAlert error={years.error ?? cal.error ?? del.error} />
      {cal.isLoading && <Loading />}
      {cal.data && year && (
        <Stack spacing={3}>
          <Box>
            <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
              <Typography variant="h6">Semesters</Typography>
              {canEdit && nextNo <= 3 && (
                <Button startIcon={<AddIcon />} onClick={() => setSem({
                  academicYearId: year.id, semesterNo: nextNo, name: `Semester ${nextNo}`, startDate: '', endDate: '',
                  registrationOpens: '', registrationCloses: '', lateRegistrationCloses: '',
                })}>Add semester</Button>
              )}
            </Stack>
            {!cal.data.semesters.length && <Alert severity="info">No semesters for {year.code} yet. Students can register for a semester once it is set up here.</Alert>}
            <Grid container spacing={2}>
              {cal.data.semesters.map((s) => (
                <Grid key={s.id} size={{ xs: 12, md: 6 }}>
                  <Card sx={{ height: '100%' }}>
                    <CardContent>
                      <Stack direction="row" alignItems="center" spacing={1}>
                        <Typography variant="h6" sx={{ flexGrow: 1 }}>{s.name}</Typography>
                        <Chip size="small" color={WINDOW_COLOUR[s.window]} label={WINDOW_LABEL[s.window]} />
                        {canEdit && <Tooltip title="Edit"><IconButton size="small" onClick={() => setSem({ ...s, lateRegistrationCloses: s.lateRegistrationCloses ?? '' })}><EditIcon fontSize="small" /></IconButton></Tooltip>}
                      </Stack>
                      <Typography color="text.secondary">{formatDate(s.startDate)} – {formatDate(s.endDate)}</Typography>
                      <Divider sx={{ my: 1.5 }} />
                      <Typography variant="body2"><strong>Registration:</strong> {formatDate(s.registrationOpens)} – {formatDate(s.registrationCloses)}</Typography>
                      <Typography variant="body2" color="text.secondary">
                        {s.lateRegistrationCloses ? `Late registration until ${formatDate(s.lateRegistrationCloses)}` : 'No late registration period'}
                      </Typography>
                    </CardContent>
                  </Card>
                </Grid>
              ))}
            </Grid>
          </Box>

          <Box>
            <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
              <Typography variant="h6">Key dates</Typography>
              {canEdit && <Button startIcon={<AddIcon />} onClick={() => setEv({ academicYearId: year.id, semesterId: '', kind: 'TEACHING', title: '', startDate: '', endDate: '' })}>Add date</Button>}
            </Stack>
            <TableContainer component={Card}>
              <Table size="small">
                <TableHead><TableRow><TableCell>Dates</TableCell><TableCell>What</TableCell><TableCell>Kind</TableCell><TableCell>Semester</TableCell>{canEdit && <TableCell />}</TableRow></TableHead>
                <TableBody>
                  {cal.data.events.map((e) => (
                    <TableRow key={e.id} hover>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDate(e.startDate)}{e.endDate !== e.startDate && <> – {formatDate(e.endDate)}</>}</TableCell>
                      <TableCell>{e.title}</TableCell>
                      <TableCell><Chip size="small" variant="outlined" label={humanise(e.kind)} /></TableCell>
                      <TableCell>{e.semesterName ?? 'Whole year'}</TableCell>
                      {canEdit && (
                        <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                          <IconButton size="small" aria-label="Edit" onClick={() => setEv({ ...e, semesterId: e.semesterId ? String(e.semesterId) : '' })}><EditIcon fontSize="small" /></IconButton>
                          <IconButton size="small" aria-label="Delete" onClick={() => { if (window.confirm(`Delete "${e.title}"?`)) del.mutate(e.id); }}><DeleteIcon fontSize="small" /></IconButton>
                        </TableCell>
                      )}
                    </TableRow>
                  ))}
                  {!cal.data.events.length && <TableRow><TableCell colSpan={5}><Typography color="text.secondary" sx={{ py: 1 }}>No key dates yet: add teaching weeks, examinations, results and holidays.</Typography></TableCell></TableRow>}
                </TableBody>
              </Table>
            </TableContainer>
          </Box>
        </Stack>
      )}
      {sem && year && <SemesterDialog form={sem} year={year} onClose={() => setSem(null)} onSaved={() => { setSem(null); refresh(); }} />}
      {ev && cal.data && <EventDialog form={ev} semesters={cal.data.semesters} onClose={() => setEv(null)} onSaved={() => { setEv(null); refresh(); }} />}
    </Container>
  );
}

function DateField({ label, value, onChange, required = true, helper }: { label: string; value: string; onChange: (v: string) => void; required?: boolean; helper?: string }) {
  return <TextField type="date" label={label} value={value} onChange={(e) => onChange(e.target.value)} required={required} fullWidth helperText={helper}
    slotProps={{ inputLabel: { shrink: true } }} />;
}

function SemesterDialog({ form: initial, year, onClose, onSaved }: { form: SemesterForm; year: AcademicYear; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState(initial);
  const set = (k: keyof SemesterForm) => (v: string) => setF({ ...f, [k]: v });
  const save = useMutation({
    mutationFn: () => api<Semester>(f.id ? `/api/academic/semesters/${f.id}` : '/api/academic/semesters', {
      ...staff, method: f.id ? 'PUT' : 'POST', body: { ...f, lateRegistrationCloses: f.lateRegistrationCloses || null },
    }),
    onSuccess: onSaved,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        <DialogTitle>{f.id ? `Edit ${initial.name}` : 'New semester'} · {year.code}</DialogTitle>
        <DialogContent>
          <Grid container spacing={2} sx={{ mt: 0.5 }}>
            <Grid size={4}><TextField select fullWidth label="Number" value={f.semesterNo} onChange={(e) => setF({ ...f, semesterNo: Number(e.target.value), name: `Semester ${e.target.value}` })}>
              {[1, 2, 3].map((n) => <MenuItem key={n} value={n}>{n}</MenuItem>)}</TextField></Grid>
            <Grid size={8}><TextField fullWidth required label="Name" value={f.name} onChange={(e) => set('name')(e.target.value)} /></Grid>
            <Grid size={6}><DateField label="Teaching starts" value={f.startDate} onChange={set('startDate')} /></Grid>
            <Grid size={6}><DateField label="Semester ends" value={f.endDate} onChange={set('endDate')} /></Grid>
            <Grid size={12}><Typography variant="subtitle2">Registration window</Typography></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><DateField label="Opens" value={f.registrationOpens} onChange={set('registrationOpens')} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><DateField label="Closes" value={f.registrationCloses} onChange={set('registrationCloses')} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><DateField label="Late until" value={f.lateRegistrationCloses} onChange={set('lateRegistrationCloses')} required={false} helper="Optional" /></Grid>
          </Grid>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
            Dates must fall within {year.code} ({formatDate(year.startDate)} – {formatDate(year.endDate)}). Students who register after the window closes are marked late.
          </Typography>
          <ErrorAlert error={save.error} />
        </DialogContent>
        <DialogActions><Button onClick={onClose}>Cancel</Button><Button type="submit" variant="contained" disabled={save.isPending}>Save</Button></DialogActions>
      </form>
    </Dialog>
  );
}

function EventDialog({ form: initial, semesters, onClose, onSaved }: { form: EventForm; semesters: Semester[]; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState(initial);
  const save = useMutation({
    mutationFn: () => api<CalendarEvent>(f.id ? `/api/academic/calendar-events/${f.id}` : '/api/academic/calendar-events', {
      ...staff, method: f.id ? 'PUT' : 'POST', body: { ...f, semesterId: f.semesterId ? Number(f.semesterId) : null, endDate: f.endDate || f.startDate },
    }),
    onSuccess: onSaved,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        <DialogTitle>{f.id ? 'Edit date' : 'New key date'}</DialogTitle>
        <DialogContent>
          <Grid container spacing={2} sx={{ mt: 0.5 }}>
            <Grid size={12}><TextField fullWidth required label="What" placeholder="e.g. Semester 1 examinations" value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} /></Grid>
            <Grid size={6}><TextField select fullWidth label="Kind" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}>
              {CALENDAR_EVENT_KINDS.map((k) => <MenuItem key={k} value={k}>{humanise(k)}</MenuItem>)}</TextField></Grid>
            <Grid size={6}><TextField select fullWidth label="Semester" value={f.semesterId} onChange={(e) => setF({ ...f, semesterId: e.target.value })}>
              <MenuItem value="">Whole year</MenuItem>
              {semesters.map((s) => <MenuItem key={s.id} value={String(s.id)}>{s.name}</MenuItem>)}</TextField></Grid>
            <Grid size={6}><DateField label="From" value={f.startDate} onChange={(v) => setF({ ...f, startDate: v })} /></Grid>
            <Grid size={6}><DateField label="To" value={f.endDate} onChange={(v) => setF({ ...f, endDate: v })} required={false} helper="Leave empty for one day" /></Grid>
          </Grid>
          <ErrorAlert error={save.error} />
        </DialogContent>
        <DialogActions><Button onClick={onClose}>Cancel</Button><Button type="submit" variant="contained" disabled={save.isPending}>Save</Button></DialogActions>
      </form>
    </Dialog>
  );
}

// ============================================================ curricula and the module catalogue (FR-ACA-02/03)

export function CurriculaPage() {
  const [tab, setTab] = useState<'curricula' | 'modules'>('curricula');
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Curricula" subtitle="Versioned curricula per programme: the modules of every semester, core or elective. Students follow the version in force when they joined." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2, borderBottom: 1, borderColor: 'divider' }}>
        <Tab value="curricula" label="Curricula" />
        <Tab value="modules" label="Module catalogue" />
      </Tabs>
      {tab === 'curricula' ? <CurriculumList /> : <ModuleCatalogue />}
    </Container>
  );
}

function CurriculumList() {
  const navigate = useNavigate();
  const can = useRoles();
  const [programmeId, setProgrammeId] = useState('');
  const [creating, setCreating] = useState(false);
  const programmes = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const list = useQuery({
    queryKey: ['staff', 'curricula', programmeId],
    queryFn: () => api<CurriculumSummary[]>(`/api/academic/curricula${programmeId ? `?programmeId=${programmeId}` : ''}`, staff),
  });
  const byProgramme = useMemo(() => {
    const m = new Map<number, CurriculumSummary[]>();
    (list.data ?? []).forEach((c) => m.set(c.programmeId, [...(m.get(c.programmeId) ?? []), c]));
    return m;
  }, [list.data]);
  const without = (programmes.data ?? []).filter((p) => !byProgramme.has(p.id) && (!programmeId || String(p.id) === programmeId));
  return (
    <>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }} justifyContent="space-between">
        <TextField select size="small" label="Programme" value={programmeId} onChange={(e) => setProgrammeId(e.target.value)} sx={{ width: { xs: '100%', sm: 360 } }}>
          <MenuItem value="">All programmes</MenuItem>
          {programmes.data?.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.code} · {p.name}</MenuItem>)}
        </TextField>
        {can(PREPARERS) && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreating(true)} sx={{ flexShrink: 0, whiteSpace: 'nowrap' }}>New curriculum</Button>}
      </Stack>
      <ErrorAlert error={list.error ?? programmes.error} />
      {list.isLoading && <Loading />}
      <Stack spacing={2}>
        {[...byProgramme.entries()].map(([pid, versions]) => (
          <Card key={pid}>
            <CardContent sx={{ pb: 1 }}>
              <Typography variant="h6">{versions[0].programmeCode} · {versions[0].programmeName}</Typography>
            </CardContent>
            {versions.map((c) => (
              <CardActionArea key={c.id} component={RouterLink} to={`/staff/curricula/${c.id}`} sx={{ px: 2, py: 1.25, borderTop: 1, borderColor: 'divider' }}>
                <Stack direction="row" spacing={2} alignItems="center">
                  <Typography sx={{ fontWeight: 600, minWidth: 40 }}>v{c.curriculumVersion}</Typography>
                  <Chip size="small" color={c.status === 'DRAFT' ? 'warning' : statusColour(c.status)} label={humanise(c.status)} />
                  <Typography variant="body2" sx={{ flexGrow: 1 }} color="text.secondary">
                    From {c.effectiveYear} intake · {c.modules} modules · {num(c.totalCredits)} credits
                  </Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ display: { xs: 'none', sm: 'block' } }}>
                    {c.approvedAt ? `Approved ${formatDate(c.approvedAt)}` : 'Not approved'}
                  </Typography>
                </Stack>
              </CardActionArea>
            ))}
          </Card>
        ))}
        {!!without.length && list.data && (
          <Alert severity="warning">
            No curriculum yet for {without.map((p) => p.code).join(', ')}. Students on {without.length === 1 ? 'this programme' : 'these programmes'} cannot register for a semester.
          </Alert>
        )}
      </Stack>
      {creating && <NewCurriculumDialog programmes={programmes.data ?? []} onClose={() => setCreating(false)} onCreated={(id) => navigate(`/staff/curricula/${id}`)} />}
    </>
  );
}

function NewCurriculumDialog({ programmes, onClose, onCreated }: { programmes: Programme[]; onClose: () => void; onCreated: (id: number) => void }) {
  const years = useYears();
  const [programmeId, setProgrammeId] = useState('');
  const [yearId, setYearId] = useState('');
  useEffect(() => { if (!yearId && years.data) setYearId(String(defaultYear(years.data) ?? '')); }, [years.data, yearId]);
  const create = useMutation({
    mutationFn: () => api<Curriculum>('/api/academic/curricula', {
      ...staff, method: 'POST', body: { programmeId: Number(programmeId), effectiveYearId: Number(yearId), modules: [], electiveRules: [] },
    }),
    onSuccess: (c) => onCreated(c.id),
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={(e) => { e.preventDefault(); create.mutate(); }}>
        <DialogTitle>New curriculum</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField select required label="Programme" value={programmeId} onChange={(e) => setProgrammeId(e.target.value)}>
              {programmes.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.code} · {p.name}</MenuItem>)}
            </TextField>
            <TextField select required label="Applies to students who join in" value={yearId} onChange={(e) => setYearId(e.target.value)}
              helperText="Students who joined earlier stay on the version they started with.">
              {(years.data ?? []).map((y) => <MenuItem key={y.id} value={String(y.id)}>{y.code}</MenuItem>)}
            </TextField>
            <Typography variant="body2" color="text.secondary">
              This starts an empty draft. To change an approved curriculum, open it and choose “Start a new version” instead: its modules are copied.
            </Typography>
            <ErrorAlert error={create.error} />
          </Stack>
        </DialogContent>
        <DialogActions><Button onClick={onClose}>Cancel</Button><Button type="submit" variant="contained" disabled={create.isPending || !programmeId}>Create draft</Button></DialogActions>
      </form>
    </Dialog>
  );
}

type ModuleForm = { id?: number; code: string; title: string; credits: string; ntaLevel: string; assessmentType: 'GRADED' | 'COMPETENCE'; departmentId: string; description: string; active: boolean };

function ModuleCatalogue() {
  const qc = useQueryClient();
  const can = useRoles();
  const [q, setQ] = useState('');
  const [form, setForm] = useState<ModuleForm | null>(null);
  const list = useQuery({ queryKey: ['staff', 'modules'], queryFn: () => api<CourseModule[]>('/api/academic/modules', staff) });
  const shown = (list.data ?? []).filter((m) => !q || `${m.code} ${m.title}`.toLowerCase().includes(q.toLowerCase()));
  return (
    <>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }} justifyContent="space-between">
        <TextField size="small" label="Search code or title" value={q} onChange={(e) => setQ(e.target.value)} sx={{ minWidth: 280 }} />
        {can(PREPARERS) && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setForm({
          code: '', title: '', credits: '10', ntaLevel: '4', assessmentType: 'GRADED', departmentId: '', description: '', active: true,
        })}>New module</Button>}
      </Stack>
      <ErrorAlert error={list.error} />
      {list.isLoading && <Loading />}
      {list.data && (
        <TableContainer component={Card}>
          <Table size="small">
            <TableHead><TableRow>
              <TableCell>Code</TableCell><TableCell>Title</TableCell><TableCell align="right">Credits</TableCell><TableCell>NTA level</TableCell>
              <TableCell>Assessment</TableCell><TableCell>Department</TableCell><TableCell>Used in</TableCell>
            </TableRow></TableHead>
            <TableBody>
              {shown.map((m) => (
                <TableRow key={m.id} hover sx={{ cursor: can(PREPARERS) ? 'pointer' : undefined, opacity: m.active ? 1 : 0.55 }}
                  onClick={() => can(PREPARERS) && setForm({ ...m, credits: String(m.credits), ntaLevel: m.ntaLevel ? String(m.ntaLevel) : '',
                    departmentId: m.departmentId ? String(m.departmentId) : '', description: m.description ?? '' })}>
                  <TableCell sx={{ fontWeight: 600, whiteSpace: 'nowrap' }}>{m.code}</TableCell>
                  <TableCell>{m.title}{!m.active && <Chip size="small" label="Inactive" sx={{ ml: 1 }} />}</TableCell>
                  <TableCell align="right">{num(m.credits)}</TableCell>
                  <TableCell>{m.ntaLevel ?? '—'}</TableCell>
                  <TableCell>{m.assessmentType === 'GRADED' ? 'Graded' : 'Competent / not yet'}</TableCell>
                  <TableCell>{m.departmentName ?? '—'}</TableCell>
                  <TableCell>{m.usedInCurricula ? `${m.usedInCurricula} curricul${m.usedInCurricula === 1 ? 'um' : 'a'}` : '—'}</TableCell>
                </TableRow>
              ))}
              {!shown.length && <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 1 }}>No modules{q ? ' match' : ' yet'}.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {form && <ModuleDialog form={form} onClose={() => setForm(null)} onSaved={() => { setForm(null); qc.invalidateQueries({ queryKey: ['staff', 'modules'] }); }} />}
    </>
  );
}

function ModuleDialog({ form: initial, onClose, onSaved }: { form: ModuleForm; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState(initial);
  const departments = useQuery({ queryKey: ['staff', 'departments'], queryFn: () => api<Department[]>('/api/academic/departments', staff) });
  const save = useMutation({
    mutationFn: () => api<CourseModule>(f.id ? `/api/academic/modules/${f.id}` : '/api/academic/modules', {
      ...staff, method: f.id ? 'PUT' : 'POST',
      body: { ...f, credits: Number(f.credits), ntaLevel: f.ntaLevel ? Number(f.ntaLevel) : null, departmentId: f.departmentId ? Number(f.departmentId) : null,
        description: f.description || null },
    }),
    onSuccess: onSaved,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        <DialogTitle>{f.id ? `Edit ${initial.code}` : 'New module'}</DialogTitle>
        <DialogContent>
          <Grid container spacing={2} sx={{ mt: 0.5 }}>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth required label="Code" value={f.code} onChange={(e) => setF({ ...f, code: e.target.value.toUpperCase() })} placeholder="ITU 04101" /></Grid>
            <Grid size={{ xs: 12, sm: 8 }}><TextField fullWidth required label="Title" value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} /></Grid>
            <Grid size={4}><TextField fullWidth required type="number" label="Credits" value={f.credits} onChange={(e) => setF({ ...f, credits: e.target.value })} slotProps={{ htmlInput: { min: 0.5, step: 0.5 } }} /></Grid>
            <Grid size={4}><TextField select fullWidth label="NTA level" value={f.ntaLevel} onChange={(e) => setF({ ...f, ntaLevel: e.target.value })}>
              <MenuItem value="">—</MenuItem>{[4, 5, 6, 7, 8].map((l) => <MenuItem key={l} value={String(l)}>{l}</MenuItem>)}</TextField></Grid>
            <Grid size={4}><TextField select fullWidth label="Assessment" value={f.assessmentType} onChange={(e) => setF({ ...f, assessmentType: e.target.value as ModuleForm['assessmentType'] })}>
              <MenuItem value="GRADED">Graded</MenuItem><MenuItem value="COMPETENCE">Competence-based</MenuItem></TextField></Grid>
            <Grid size={12}><TextField select fullWidth label="Department" value={f.departmentId} onChange={(e) => setF({ ...f, departmentId: e.target.value })}>
              <MenuItem value="">—</MenuItem>{departments.data?.map((d) => <MenuItem key={d.id} value={String(d.id)}>{d.name}</MenuItem>)}</TextField></Grid>
            <Grid size={12}><TextField fullWidth multiline minRows={2} label="Description" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} /></Grid>
            {f.id && <Grid size={12}><FormControlLabel control={<Switch checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} />} label="Active (can be added to curricula)" /></Grid>}
          </Grid>
          {!!initial.id && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Code, credits and assessment cannot change once the module is in an approved curriculum: create a new module instead.</Typography>}
          <ErrorAlert error={save.error} />
        </DialogContent>
        <DialogActions><Button onClick={onClose}>Cancel</Button><Button type="submit" variant="contained" disabled={save.isPending}>Save</Button></DialogActions>
      </form>
    </Dialog>
  );
}

// ============================================================ curriculum editor

type Draft = { modules: CurriculumModuleSlot[]; electiveRules: ElectiveRule[]; notes: string; effectiveYearId: number };

export function CurriculumEditorPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const can = useRoles();
  const q = useQuery({ queryKey: ['staff', 'curriculum', id], queryFn: () => api<Curriculum>(`/api/academic/curricula/${id}`, staff) });
  const modules = useQuery({ queryKey: ['staff', 'modules'], queryFn: () => api<CourseModule[]>('/api/academic/modules', staff) });
  const years = useYears();
  const c = q.data;
  const [draft, setDraft] = useState<Draft | null>(null);
  const [dirty, setDirty] = useState(false);
  useEffect(() => {
    if (c) { setDraft({ modules: c.modules, electiveRules: c.electiveRules, notes: c.notes ?? '', effectiveYearId: c.effectiveYearId }); setDirty(false); }
  }, [c]);
  const done = (x: Curriculum) => { qc.setQueryData(['staff', 'curriculum', String(x.id)], x); qc.invalidateQueries({ queryKey: ['staff', 'curricula'] }); qc.invalidateQueries({ queryKey: ['staff', 'modules'] }); };
  const save = useMutation({
    mutationFn: () => api<Curriculum>(`/api/academic/curricula/${id}`, {
      ...staff, method: 'PUT', body: {
        programmeId: c!.programmeId, effectiveYearId: draft!.effectiveYearId, notes: draft!.notes || null,
        modules: draft!.modules.map((m) => ({ moduleId: m.moduleId, yearOfStudy: m.yearOfStudy, semesterNo: m.semesterNo, category: m.category })),
        electiveRules: draft!.electiveRules.filter((r) => draft!.modules.some((m) => m.category === 'ELECTIVE' && m.yearOfStudy === r.yearOfStudy && m.semesterNo === r.semesterNo)),
      },
    }),
    onSuccess: done,
  });
  const act = useMutation({
    mutationFn: (what: 'approve' | 'revise' | 'retire') => api<Curriculum>(`/api/academic/curricula/${id}/${what}`, { ...staff, method: 'POST' }),
    onSuccess: (x) => { done(x); if (String(x.id) !== id) navigate(`/staff/curricula/${x.id}`); },
  });

  if (q.isLoading || !draft) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  if (!c) return null;
  const editable = c.status === 'DRAFT' && can(PREPARERS);
  const slots: { year: number; sem: number }[] = [];
  for (let k = 1; k <= Math.max(1, c.programmeSemesters); k++) slots.push({ year: Math.ceil(k / 2), sem: ((k - 1) % 2) + 1 });
  const change = (d: Partial<Draft>) => { setDraft({ ...draft, ...d }); setDirty(true); };
  const used = new Set(draft.modules.map((m) => m.moduleId));

  return (
    <Container maxWidth="lg" disableGutters>
      <Button component={RouterLink} to="/staff/curricula" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>Curricula</Button>
      <PageTitle title={`${c.programmeCode} curriculum v${c.curriculumVersion}`}
        subtitle={<>{c.programmeName} · from the {c.effectiveYear} intake · {c.programmeSemesters} semesters
          {c.studentsOnIt > 0 && <> · {c.studentsOnIt} student{c.studentsOnIt === 1 ? '' : 's'} on this version</>}</>}
        action={
          <Stack direction="row" spacing={1} alignItems="center">
            <Chip color={c.status === 'DRAFT' ? 'warning' : statusColour(c.status)} label={humanise(c.status)} />
            {editable && <Button variant="contained" disabled={!dirty || save.isPending} onClick={() => save.mutate()}>Save draft</Button>}
            {c.status === 'DRAFT' && can(CALENDAR_EDITORS) && (
              <Tooltip title={!c.canApprove ? 'You prepared this draft: someone else must approve it' : dirty ? 'Save your changes first' : c.problems.length ? 'Fix the problems listed first' : ''}>
                <span><Button variant="outlined" color="success" disabled={!c.canApprove || dirty || c.problems.length > 0 || act.isPending}
                  onClick={() => { if (window.confirm(`Approve ${c.programmeCode} v${c.curriculumVersion}? Students joining from ${c.effectiveYear} will follow it.`)) act.mutate('approve'); }}>Approve</Button></span>
              </Tooltip>
            )}
            {c.status === 'APPROVED' && can(PREPARERS) && <Button variant="outlined" onClick={() => act.mutate('revise')} disabled={act.isPending} sx={{ whiteSpace: 'nowrap', flexShrink: 0 }}>Start a new version</Button>}
            {c.status === 'APPROVED' && can(CALENDAR_EDITORS) && (
              <Button color="inherit" onClick={() => { if (window.confirm('Retire this curriculum? Students already on it keep it; new students will not get it.')) act.mutate('retire'); }}>Retire</Button>
            )}
          </Stack>
        } />
      <ErrorAlert error={save.error ?? act.error ?? modules.error} />
      {c.status === 'DRAFT' && (c.problems.length > 0 ? (
        <Alert severity="warning" sx={{ mb: 2 }}>
          <strong>Before it can be approved{dirty ? ' (as last saved)' : ''}:</strong>
          <Box component="ul" sx={{ m: 0, pl: 2 }}>{c.problems.map((p) => <li key={p}>{p}</li>)}</Box>
        </Alert>
      ) : <Alert severity="success" sx={{ mb: 2 }}>Complete{dirty ? ' as last saved' : ''}: ready for approval by the Deputy Principal (Academic){c.createdByName ? `, not ${c.createdByName}` : ''}.</Alert>)}
      {c.status !== 'DRAFT' && c.approvedAt && (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Prepared by {c.createdByName ?? '—'} · approved by {c.approvedByName} on {formatDateTime(c.approvedAt)}{c.retiredAt && ` · retired ${formatDate(c.retiredAt)}`}
        </Typography>
      )}

      {editable && (
        <Grid container spacing={2} sx={{ mb: 2 }}>
          <Grid size={{ xs: 12, sm: 4 }}>
            <TextField select fullWidth size="small" label="Applies from the intake of" value={draft.effectiveYearId} onChange={(e) => change({ effectiveYearId: Number(e.target.value) })}>
              {(years.data ?? []).map((y) => <MenuItem key={y.id} value={y.id}>{y.code}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid size={{ xs: 12, sm: 8 }}><TextField fullWidth size="small" label="Notes (e.g. NACTVET approval reference)" value={draft.notes} onChange={(e) => change({ notes: e.target.value })} /></Grid>
        </Grid>
      )}
      {!editable && c.notes && <Typography variant="body2" sx={{ mb: 2 }}><strong>Notes:</strong> {c.notes}</Typography>}

      <Grid container spacing={2}>
        {slots.map(({ year, sem }) => {
          const inSlot = draft.modules.filter((m) => m.yearOfStudy === year && m.semesterNo === sem)
            .sort((a, b) => a.category.localeCompare(b.category) || a.code.localeCompare(b.code));
          const electives = inSlot.filter((m) => m.category === 'ELECTIVE');
          const rule = draft.electiveRules.find((r) => r.yearOfStudy === year && r.semesterNo === sem);
          const core = inSlot.filter((m) => m.category === 'CORE').reduce((s, m) => s + Number(m.credits), 0);
          const elCredits = electives.map((m) => Number(m.credits)).sort((a, b) => a - b);
          const minTotal = core + elCredits.slice(0, rule?.minElectives ?? 0).reduce((s, x) => s + x, 0);
          const maxTotal = core + elCredits.slice(Math.max(0, elCredits.length - (rule?.maxElectives ?? 0))).reduce((s, x) => s + x, 0);
          const setRule = (k: 'minElectives' | 'maxElectives', v: number) => {
            const base = rule ?? { yearOfStudy: year, semesterNo: sem, minElectives: 1, maxElectives: 1 };
            change({ electiveRules: [...draft.electiveRules.filter((r) => r !== rule), { ...base, [k]: v }] });
          };
          const slotProblems = c.slots.find((s) => s.yearOfStudy === year && s.semesterNo === sem)?.problems ?? [];
          return (
            <Grid key={`${year}-${sem}`} size={{ xs: 12, md: 6 }}>
              <Card sx={{ height: '100%', borderColor: !dirty && slotProblems.length && c.status === 'DRAFT' ? 'warning.main' : undefined }}>
                <CardContent>
                  <Stack direction="row" justifyContent="space-between" alignItems="baseline">
                    <Typography variant="h6">Year {year} · Semester {sem}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {electives.length && rule ? (minTotal === maxTotal ? `${num(minTotal)} credits` : `${num(minTotal)}–${num(maxTotal)} credits`) : `${num(core)} credits`}
                    </Typography>
                  </Stack>
                  <Stack divider={<Divider flexItem />} sx={{ my: 1 }}>
                    {inSlot.map((m) => (
                      <Stack key={m.moduleId} direction="row" alignItems="center" spacing={1} sx={{ py: 0.5 }}>
                        <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                          <Typography variant="body2" sx={{ fontWeight: 600 }}>{m.code}</Typography>
                          <Typography variant="body2" color="text.secondary" noWrap>{m.title}</Typography>
                        </Box>
                        <Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>{num(m.credits)} cr</Typography>
                        {editable ? (
                          <>
                            <TextField select size="small" value={m.category} sx={{ width: 118 }} aria-label={`${m.code} category`}
                              onChange={(e) => change({ modules: draft.modules.map((x) => x === m ? { ...x, category: e.target.value as 'CORE' | 'ELECTIVE' } : x) })}>
                              <MenuItem value="CORE">Core</MenuItem><MenuItem value="ELECTIVE">Elective</MenuItem>
                            </TextField>
                            <IconButton size="small" aria-label={`Remove ${m.code}`} onClick={() => change({ modules: draft.modules.filter((x) => x !== m) })}><DeleteIcon fontSize="small" /></IconButton>
                          </>
                        ) : <Chip size="small" variant="outlined" label={m.category === 'CORE' ? 'Core' : 'Elective'} />}
                      </Stack>
                    ))}
                    {!inSlot.length && <Typography variant="body2" color="text.secondary" sx={{ py: 1 }}>No modules yet.</Typography>}
                  </Stack>
                  {electives.length > 0 && (
                    editable ? (
                      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1.5 }}>
                        <Typography variant="body2">Students choose</Typography>
                        <TextField size="small" type="number" label="at least" value={rule?.minElectives ?? ''} sx={{ width: 90 }}
                          onChange={(e) => setRule('minElectives', Number(e.target.value))} slotProps={{ htmlInput: { min: 0, max: electives.length } }} />
                        <TextField size="small" type="number" label="at most" value={rule?.maxElectives ?? ''} sx={{ width: 90 }}
                          onChange={(e) => setRule('maxElectives', Number(e.target.value))} slotProps={{ htmlInput: { min: 0, max: electives.length } }} />
                        <Typography variant="body2">of {electives.length}</Typography>
                      </Stack>
                    ) : rule && <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                      Students choose {rule.minElectives === rule.maxElectives ? rule.minElectives : `${rule.minElectives}–${rule.maxElectives}`} of {electives.length} electives.
                    </Typography>
                  )}
                  {editable && (
                    <Autocomplete size="small" options={(modules.data ?? []).filter((m) => m.active && !used.has(m.id))}
                      getOptionLabel={(m) => `${m.code} · ${m.title} (${num(m.credits)} cr)`} value={null} blurOnSelect
                      onChange={(_, m) => m && change({ modules: [...draft.modules, {
                        moduleId: m.id, code: m.code, title: m.title, credits: m.credits, ntaLevel: m.ntaLevel, assessmentType: m.assessmentType,
                        yearOfStudy: year, semesterNo: sem, category: 'CORE',
                      }] })}
                      renderInput={(p) => <TextField {...p} placeholder="Add a module from the catalogue" />} />
                  )}
                </CardContent>
              </Card>
            </Grid>
          );
        })}
      </Grid>
    </Container>
  );
}
