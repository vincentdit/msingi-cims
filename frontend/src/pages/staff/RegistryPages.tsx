import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import DownloadIcon from '@mui/icons-material/Download';
import HowToRegIcon from '@mui/icons-material/HowToReg';
import NumbersIcon from '@mui/icons-material/Numbers';
import SearchIcon from '@mui/icons-material/Search';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControlLabel, InputAdornment, LinearProgress, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead,
  TablePagination, TableRow, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type {
  Clearance, HandoverOverview, Page, Programme, RegistrySettings, Statement, StudentDetail, StudentSummary,
} from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle, humanise, statusColour } from '../../components/common';
import { StudentExamCard } from './ExamRecordsPages';
import { useRoles } from './MarksPages';
import { ChangeRequestsButton } from './ChangeRequests';

const staff = { realm: 'staff' as const };
const REGISTRY_ROLES = ['INSTITUTION_ADMIN', 'REGISTRAR'];

function useCanRegister() {
  const { user } = useSession();
  return !!user('staff')?.roles.some((r) => REGISTRY_ROLES.includes(r));
}

// ============================================================ hand-over (BP-05 step 1)

/** Accepted applicants of a window become students with registration numbers. */
export function HandoverPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const canRegister = useCanRegister();
  const ov = useQuery({ queryKey: ['staff', 'handover', id], queryFn: () => api<HandoverOverview>(`/api/registry/windows/${id}/handover`, staff) });
  const [selected, setSelected] = useState<number[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [numbering, setNumbering] = useState(false);
  const handover = useMutation({
    mutationFn: (ids: number[]) => api<{ created: number; students: { registrationNo?: string }[] }>(`/api/registry/windows/${id}/handover`,
      { method: 'POST', ...staff, body: { applicationIds: ids } }),
    onSuccess: (r) => {
      const nos = r.students.map((x) => x.registrationNo).filter(Boolean);
      setNotice(`${r.created} student record(s) created${nos.length ? `: ${nos[0]}${nos.length > 1 ? ` … ${nos[nos.length - 1]}` : ''}` : ''}. `
        + 'Each student was sent their registration number and reporting details by SMS.');
      setSelected([]);
      qc.invalidateQueries({ queryKey: ['staff'] });
    },
  });

  if (ov.isLoading) return <Loading />;
  const d = ov.data;
  if (!d) return <ErrorAlert error={ov.error} />;
  const pending = d.rows.filter((r) => !r.studentId);
  const toggle = (appId: number) => setSelected((s) => (s.includes(appId) ? s.filter((x) => x !== appId) : [...s, appId]));

  return (
    <Container maxWidth="lg" disableGutters>
      <Button component={RouterLink} to={`/staff/windows/${id}`} startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>{d.windowName}</Button>
      <PageTitle title="Registration hand-over"
        subtitle="Applicants who accepted their offer become students: their record is created from the application, with a registration number. They complete registration when they report with their original documents."
        action={canRegister && pending.length > 0 && (
          <Button variant="contained" startIcon={<HowToRegIcon />} sx={{ whiteSpace: 'nowrap' }} disabled={handover.isPending}
            onClick={() => handover.mutate(selected)}>
            {selected.length ? `Hand over ${selected.length} selected` : `Hand over all (${pending.length})`}
          </Button>
        )} />
      {handover.isPending && <LinearProgress sx={{ mb: 2 }} />}
      <ErrorAlert error={handover.error} />
      {notice && <Alert severity="success" onClose={() => setNotice(null)} sx={{ mb: 2 }}>{notice}</Alert>}

      <Grid container spacing={1.5} sx={{ mb: 2 }}>
        <Tile label="Accepted offers" value={d.accepted} />
        <Tile label="Handed over" value={d.handedOver} tone="success" />
        <Tile label="Waiting" value={d.pending} tone={d.pending ? 'warning' : undefined} />
        <Grid size={{ xs: 12, sm: 3 }}>
          <Card sx={{ height: '100%' }}><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
            <Typography variant="body2" color="text.secondary">Registration numbers</Typography>
            <Typography sx={{ fontFamily: 'monospace', fontWeight: 700, fontSize: 18, mt: 0.5 }}>{d.registrationNoExample}</Typography>
            <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace', display: 'block', overflowWrap: 'anywhere' }}>{d.registrationNoPattern}</Typography>
            <Button size="small" startIcon={<NumbersIcon />} sx={{ ml: -0.5 }} onClick={() => setNumbering(true)}>Numbering</Button>
          </CardContent></Card>
        </Grid>
      </Grid>

      <Card>
        {!d.rows.length
          ? <CardContent><Typography color="text.secondary">No applicant has accepted an offer in this window yet.</Typography></CardContent>
          : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    {canRegister && <TableCell padding="checkbox">
                      <Checkbox size="small" disabled={!pending.length} checked={!!pending.length && selected.length === pending.length}
                        indeterminate={selected.length > 0 && selected.length < pending.length}
                        onChange={(e) => setSelected(e.target.checked ? pending.map((r) => r.applicationId) : [])} inputProps={{ 'aria-label': 'Select all waiting' }} />
                    </TableCell>}
                    <TableCell>Reference</TableCell><TableCell>Applicant</TableCell><TableCell>Programme</TableCell>
                    <TableCell>Accepted</TableCell><TableCell>Admission letter</TableCell><TableCell>Registration no.</TableCell><TableCell>Status</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {d.rows.map((r) => (
                    <TableRow key={r.applicationId} hover selected={selected.includes(r.applicationId)}>
                      {canRegister && <TableCell padding="checkbox">
                        {!r.studentId && <Checkbox size="small" checked={selected.includes(r.applicationId)} onChange={() => toggle(r.applicationId)}
                          inputProps={{ 'aria-label': `Select ${r.applicantName}` }} />}
                      </TableCell>}
                      <TableCell>{r.referenceNo}</TableCell>
                      <TableCell>{r.applicantName}</TableCell>
                      <TableCell>{r.programmeName}</TableCell>
                      <TableCell>{formatDate(r.acceptedAt)}</TableCell>
                      <TableCell>{r.letterNo ?? <Typography variant="body2" color="text.secondary">Not issued</Typography>}</TableCell>
                      <TableCell>{r.studentId
                        ? <Typography component={RouterLink} to={`/staff/students/${r.studentId}`} sx={{ fontFamily: 'monospace', fontWeight: 600, color: 'primary.main', textDecoration: 'none' }}>{r.registrationNo}</Typography>
                        : '—'}</TableCell>
                      <TableCell>{r.studentStatus
                        ? <Chip size="small" color={statusColour(r.studentStatus)} label={humanise(r.studentStatus)} />
                        : <Chip size="small" variant="outlined" color="warning" label="Waiting" />}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
      </Card>
      <NumberingDialog open={numbering} onClose={() => setNumbering(false)} canEdit={canRegister} />
    </Container>
  );
}

// ============================================================ registration number settings (FR-REG-02)

const TOKENS: [string, string][] = [
  ['{INST}', 'Institution code'], ['{PROG}', 'Programme code'], ['{FAMILY}', 'NTA or NVA'], ['{LEVEL}', 'Award level'],
  ['{YEAR}', 'Entry year, e.g. 2027'], ['{YY}', 'Entry year, e.g. 27'], ['{INTAKE}', 'S (September) or M (March)'], ['{SEQ:4}', 'Running number, 3–6 digits'],
];

function NumberingDialog({ open, onClose, canEdit }: { open: boolean; onClose: () => void; canEdit: boolean }) {
  const qc = useQueryClient();
  const settings = useQuery({ queryKey: ['staff', 'registry-settings'], queryFn: () => api<RegistrySettings>('/api/registry/settings', staff), enabled: open });
  const [pattern, setPattern] = useState('');
  useEffect(() => { if (open && settings.data) setPattern(settings.data.registrationNoPattern); }, [open, settings.data]);
  const preview = useQuery({
    queryKey: ['staff', 'registry-preview', pattern], enabled: open && canEdit && !!pattern.trim(), retry: false,
    queryFn: () => api<RegistrySettings>('/api/registry/settings/preview', { method: 'POST', ...staff, body: { pattern } }),
  });
  const save = useMutation({
    mutationFn: () => api<RegistrySettings>('/api/registry/settings', { method: 'PUT', ...staff, body: { pattern } }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['staff'] }); onClose(); },
  });
  const changed = settings.data && pattern.trim() !== settings.data.registrationNoPattern;
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Registration number format</DialogTitle>
      <DialogContent>
        {settings.isLoading && <Loading />}
        {settings.data && (
          <>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              Applies to students handed over from now on. Numbers already given never change and are never reused. The running number restarts for each distinct prefix — include {'{PROG}'} to number each programme separately.
            </Typography>
            <TextField label="Pattern" fullWidth value={pattern} onChange={(e) => setPattern(e.target.value)} disabled={!canEdit}
              inputProps={{ style: { fontFamily: 'monospace' }, maxLength: 80 }} />
            <Box sx={{ mt: 1.5, minHeight: 48 }}>
              {preview.data && <Alert severity="success" icon={false}>Example: <strong style={{ fontFamily: 'monospace' }}>{preview.data.example}</strong></Alert>}
              {preview.error && <ErrorAlert error={preview.error} />}
              {!canEdit && <Alert severity="info" icon={false}>Example: <strong style={{ fontFamily: 'monospace' }}>{settings.data.example}</strong></Alert>}
            </Box>
            <Table size="small" sx={{ mt: 1 }}>
              <TableBody>
                {TOKENS.map(([t, d]) => (
                  <TableRow key={t}>
                    <TableCell sx={{ fontFamily: 'monospace', width: 110 }}>
                      {canEdit ? <Button size="small" sx={{ fontFamily: 'monospace', minWidth: 0, p: 0 }} onClick={() => setPattern((p) => p + t)}>{t}</Button> : t}
                    </TableCell>
                    <TableCell>{d}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <ErrorAlert error={save.error} />
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{canEdit ? 'Cancel' : 'Close'}</Button>
        {canEdit && <Button variant="contained" disabled={!changed || !preview.data || save.isPending} onClick={() => save.mutate()}>Save</Button>}
      </DialogActions>
    </Dialog>
  );
}

// ============================================================ student register

const STATUSES = ['ADMITTED', 'ACTIVE', 'POSTPONED', 'SUSPENDED', 'DISCONTINUED', 'WITHDRAWN', 'COMPLETED', 'GRADUATED'];

export function StudentsPage() {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [programmeId, setProgrammeId] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [numbering, setNumbering] = useState(false);
  const [dlError, setDlError] = useState<unknown>(null);
  const canRegister = useCanRegister();
  useEffect(() => { const t = setTimeout(() => { setSearch(q); setPage(0); }, 300); return () => clearTimeout(t); }, [q]);
  const programmes = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const seesRequests = useRoles()(['INSTITUTION_ADMIN', 'REGISTRAR', 'DEPUTY_PRINCIPAL_ACADEMIC', 'MANAGEMENT_VIEWER']);
  const stats = useQuery({ queryKey: ['staff', 'student-stats'], queryFn: () => api<Record<string, number>>('/api/registry/students/stats', staff) });
  const params = new URLSearchParams({ page: String(page), size: '25' });
  if (search) params.set('q', search);
  if (programmeId) params.set('programmeId', programmeId);
  if (status) params.set('status', status);
  const list = useQuery({ queryKey: ['staff', 'students', params.toString()], queryFn: () => api<Page<StudentSummary>>(`/api/registry/students?${params}`, staff) });
  const programmeName = programmes.data?.find((p) => String(p.id) === programmeId)?.code;

  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Students" subtitle="One record per student from admission to graduation."
        action={<Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" justifyContent="flex-end" sx={{ '& .MuiButton-root': { whiteSpace: 'nowrap' } }}>
          {seesRequests && <ChangeRequestsButton canDecide={canRegister} />}
          <Button variant="outlined" startIcon={<NumbersIcon />} onClick={() => setNumbering(true)}>Numbering</Button>
          <Button variant="outlined" startIcon={<DownloadIcon />} disabled={!programmeId}
            onClick={() => download(`/api/registry/programmes/${programmeId}/class-list`, 'staff', `class-list-${programmeName}.csv`).catch(setDlError)}>
            Class list
          </Button>
        </Stack>} />
      <ErrorAlert error={list.error ?? dlError} />
      {stats.data && (
        <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" sx={{ mb: 2 }}>
          {STATUSES.filter((s) => stats.data[s]).map((s) => (
            <Chip key={s} clickable color={status === s ? 'primary' : 'default'} variant={status === s ? 'filled' : 'outlined'}
              label={`${humanise(s)}: ${stats.data[s]}`} onClick={() => { setStatus(status === s ? '' : s); setPage(0); }} />
          ))}
        </Stack>
      )}
      <Card>
        <CardContent sx={{ pb: 1 }}>
          <Grid container spacing={1.5}>
            <Grid size={{ xs: 12, md: 5 }}>
              <TextField fullWidth size="small" placeholder="Name, registration number or phone" value={q} onChange={(e) => setQ(e.target.value)}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment> } }} />
            </Grid>
            <Grid size={{ xs: 12, sm: 7, md: 4 }}>
              <TextField select fullWidth size="small" label="Programme" value={programmeId} onChange={(e) => { setProgrammeId(e.target.value); setPage(0); }}
                helperText={!programmeId ? 'Choose a programme for its class list' : ' '}>
                <MenuItem value="">All programmes</MenuItem>
                {programmes.data?.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.name}</MenuItem>)}
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 5, md: 3 }}>
              <TextField select fullWidth size="small" label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
                <MenuItem value="">Any status</MenuItem>
                {STATUSES.map((s) => <MenuItem key={s} value={s}>{humanise(s)}</MenuItem>)}
              </TextField>
            </Grid>
          </Grid>
        </CardContent>
        {list.isLoading ? <Loading /> : (
          <>
            <TableContainer>
              <Table size="small">
                <TableHead><TableRow>
                  <TableCell>Registration no.</TableCell><TableCell>Name</TableCell><TableCell>Sex</TableCell><TableCell>Programme</TableCell>
                  <TableCell>Intake</TableCell><TableCell align="center">Year</TableCell><TableCell>Status</TableCell>
                </TableRow></TableHead>
                <TableBody>
                  {list.data?.content.map((s) => (
                    <TableRow key={s.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/students/${s.id}`)}>
                      <TableCell sx={{ fontFamily: 'monospace', fontWeight: 600 }}>{s.registrationNo}</TableCell>
                      <TableCell>{s.fullName}</TableCell>
                      <TableCell>{s.sex}</TableCell>
                      <TableCell>{s.programmeName}</TableCell>
                      <TableCell>{s.academicYear} · {s.intake}</TableCell>
                      <TableCell align="center">{s.yearOfStudy}</TableCell>
                      <TableCell><Chip size="small" color={statusColour(s.status)} label={humanise(s.status)} /></TableCell>
                    </TableRow>
                  ))}
                  {!list.data?.content.length && (
                    <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 2 }}>
                      No students found. Students appear here once Registry hands over accepted applicants from an admission window.
                    </Typography></TableCell></TableRow>
                  )}
                </TableBody>
              </Table>
            </TableContainer>
            <TablePagination component="div" count={list.data?.totalElements ?? 0} page={page} onPageChange={(_, p) => setPage(p)}
              rowsPerPage={25} rowsPerPageOptions={[25]} />
          </>
        )}
      </Card>
      <NumberingDialog open={numbering} onClose={() => setNumbering(false)} canEdit={canRegister} />
    </Container>
  );
}

// ============================================================ student record and first registration (BP-05 steps 4–5)

export function StudentDetailPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const canRegister = useCanRegister();
  const canSeeFees = useRoles()(['INSTITUTION_ADMIN', 'BURSAR', 'ACCOUNTANT', 'MANAGEMENT_VIEWER', 'AUDITOR', 'REGISTRAR']);
  const st = useQuery({ queryKey: ['staff', 'student', id], queryFn: () => api<StudentDetail>(`/api/registry/students/${id}`, staff) });
  const [docs, setDocs] = useState<{ item: string; verified: boolean }[]>([]);
  const [remarks, setRemarks] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [confirm, setConfirm] = useState(false);
  useEffect(() => {
    if (st.data?.firstRegistration) { setDocs(st.data.firstRegistration.documents); setRemarks(st.data.firstRegistration.remarks ?? ''); }
  }, [st.data]);
  const done = (d: StudentDetail) => { qc.setQueryData(['staff', 'student', id], d); qc.invalidateQueries({ queryKey: ['staff', 'student', id, 'clearance'] }); qc.invalidateQueries({ queryKey: ['staff', 'students'] }); qc.invalidateQueries({ queryKey: ['staff', 'student-stats'] }); };
  const save = useMutation({
    mutationFn: () => api<StudentDetail>(`/api/registry/students/${id}/first-registration`, { method: 'PUT', ...staff, body: { documents: docs, remarks } }),
    onSuccess: (d) => { done(d); setNotice('Checklist saved.'); },
  });
  const approve = useMutation({
    mutationFn: async () => {
      await api(`/api/registry/students/${id}/first-registration`, { method: 'PUT', ...staff, body: { documents: docs, remarks } });
      return api<StudentDetail>(`/api/registry/students/${id}/first-registration/approve`, { method: 'POST', ...staff });
    },
    onSuccess: (d) => { setConfirm(false); done(d); setNotice('First registration approved. The student is now registered and was told by SMS.'); },
  });

  if (st.isLoading) return <Loading />;
  const d = st.data;
  if (!d) return <ErrorAlert error={st.error} />;
  const s = d.summary;
  const fr = d.firstRegistration;
  const allVerified = docs.length > 0 && docs.every((x) => x.verified);
  const dirty = fr && (JSON.stringify(docs) !== JSON.stringify(fr.documents) || (remarks || '') !== (fr.remarks ?? ''));

  return (
    <Container maxWidth="lg" disableGutters>
      <Button component={RouterLink} to="/staff/students" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>Students</Button>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'flex-start' }} spacing={1} sx={{ mb: 3 }}>
        <Box>
          <Typography variant="h5" component="h1">{s.fullName}</Typography>
          <Typography sx={{ fontFamily: 'monospace', fontWeight: 600, fontSize: 18 }}>{s.registrationNo}</Typography>
          <Typography color="text.secondary">{s.programmeName} · {s.academicYear} {s.intake} · Year {s.yearOfStudy}</Typography>
        </Box>
        <Chip color={statusColour(s.status)} label={humanise(s.status)} />
      </Stack>
      {notice && <Alert severity="success" onClose={() => setNotice(null)} sx={{ mb: 2 }}>{notice}</Alert>}

      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, md: 7 }}>
          {fr && (
            <Card sx={{ mb: 2.5 }}>
              <CardContent>
                <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
                  <Typography variant="h6">First registration</Typography>
                  <Chip size="small" color={statusColour(fr.status)} label={fr.status === 'APPROVED' ? 'Approved' : 'Awaiting reporting'} />
                </Stack>
                {fr.status === 'APPROVED' ? (
                  <>
                    <Typography>Approved by <strong>{fr.approvedByName}</strong> on {formatDateTime(fr.approvedAt)}.</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Reported {formatDateTime(fr.reportedAt)}; originals checked by {fr.checkedByName}.</Typography>
                    <Stack spacing={0.5} sx={{ mt: 1.5 }}>
                      {fr.documents.map((x) => <Typography key={x.item} variant="body2">✓ {x.item}</Typography>)}
                    </Stack>
                    {fr.remarks && <Alert severity="info" icon={false} sx={{ mt: 1.5 }}>{fr.remarks}</Alert>}
                  </>
                ) : (
                  <>
                    <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                      When the student reports, tick each original document you have seen. Registration can be approved once all are verified.
                      {fr.reportedAt && <> Reported {formatDateTime(fr.reportedAt)}{fr.checkedByName ? ` (checked by ${fr.checkedByName})` : ''}.</>}
                    </Typography>
                    <Stack>
                      {docs.map((x, i) => (
                        <FormControlLabel key={x.item} disabled={!canRegister} label={x.item}
                          control={<Checkbox checked={x.verified} onChange={(e) => setDocs(docs.map((y, j) => (j === i ? { ...y, verified: e.target.checked } : y)))} />} />
                      ))}
                    </Stack>
                    <TextField label="Remarks" fullWidth multiline minRows={2} sx={{ mt: 1 }} value={remarks} disabled={!canRegister}
                      onChange={(e) => setRemarks(e.target.value)} inputProps={{ maxLength: 500 }} />
                    <ErrorAlert error={save.error ?? approve.error} />
                    {canRegister && (
                      <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
                        <Button variant="outlined" disabled={!dirty || save.isPending} onClick={() => save.mutate()}>Save checklist</Button>
                        <Button variant="contained" startIcon={<HowToRegIcon />} disabled={!allVerified || approve.isPending} onClick={() => setConfirm(true)}>
                          Approve registration
                        </Button>
                      </Stack>
                    )}
                  </>
                )}
              </CardContent>
            </Card>
          )}
          <Section title="Personal details">
            <Field label="Full name" value={[d.firstName, d.middleName, d.surname].filter(Boolean).join(' ')} />
            <Field label="Sex" value={s.sex === 'F' ? 'Female' : 'Male'} />
            <Field label="Date of birth" value={formatDate(d.dateOfBirth)} />
            <Field label="Nationality" value={d.nationality} />
            <Field label="Phone" value={s.phone} />
            <Field label="Email" value={d.email} />
            <Field label="Address" value={[d.postalAddress, d.district, d.region].filter(Boolean).join(', ')} />
            <Field label="Disability" value={d.disability} />
            <Field label="Next of kin" value={[d.nextOfKinName, d.nextOfKinRelation, d.nextOfKinPhone].filter(Boolean).join(' · ')} />
          </Section>
        </Grid>
        <Grid size={{ xs: 12, md: 5 }}>
          {canSeeFees && <StudentFeesCard studentId={s.id} />}
          <StudentExamCard studentId={s.id} regNo={s.registrationNo} />
          <Section title="Programme">
            <Field label="Programme" value={`${s.programmeName} (${s.programmeCode})`} />
            <Field label="Award" value={d.awardName} />
            <Field label="Entry" value={`${s.academicYear}, ${s.intake}`} />
            <Field label="Year of study" value={String(s.yearOfStudy)} />
          </Section>
          <Section title="Admission">
            <Field label="Application" value={d.applicationRef} />
            <Field label="NACTVET verification" value={d.nactvetVerificationNo} />
            <Field label="Admission letter" value={d.admissionLetterNo} />
            <Field label="Handed over" value={`${formatDateTime(d.admittedAt)} by ${d.admittedByName}`} />
          </Section>
        </Grid>
      </Grid>

      <Dialog open={confirm} onClose={() => setConfirm(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Approve first registration?</DialogTitle>
        <DialogContent>
          <Typography><strong>{s.fullName}</strong> ({s.registrationNo}) becomes a registered student of {s.programmeName}.</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>All {docs.length} original document(s) have been verified.</Typography>
          <ErrorAlert error={approve.error} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(false)}>Cancel</Button>
          <Button variant="contained" disabled={approve.isPending} onClick={() => approve.mutate()}>Approve</Button>
        </DialogActions>
      </Dialog>
    </Container>
  );
}

/** Fees on the student record: balance, registration clearance and invoices with control numbers (BP-06). */
function StudentFeesCard({ studentId }: { studentId: number }) {
  const statement = useQuery({ queryKey: ['staff', 'student', String(studentId), 'statement'], queryFn: () => api<Statement>(`/api/finance/students/${studentId}/statement`, staff) });
  const clearance = useQuery({ queryKey: ['staff', 'student', String(studentId), 'clearance'], queryFn: () => api<Clearance>(`/api/finance/students/${studentId}/clearance?gate=REGISTRATION`, staff) });
  const st = statement.data;
  const c = clearance.data;
  return (
    <Card sx={{ mb: 2.5 }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 1.5 }}>Fees</Typography>
        <ErrorAlert error={statement.error ?? clearance.error} />
        {c && c.enabled && (
          <Alert severity={c.cleared ? 'success' : 'warning'} sx={{ mb: 1.5 }}>
            <strong>{c.cleared ? 'Cleared for registration' : 'Not cleared for registration'}</strong>
            {!c.cleared && c.reason && <Box>{c.reason}</Box>}
          </Alert>
        )}
        {st && (
          <>
            <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: '50% 50%', rowGap: 0.5 }}>
              <Field label="Billed" value={formatTzs(st.billed)} />
              <Field label="Paid" value={formatTzs(st.paid)} />
              <Field label="Balance" value={formatTzs(st.balance)} />
              {st.credit > 0 && <Field label="Credit" value={formatTzs(st.credit)} />}
            </Box>
            {st.invoices.map((i) => (
              <Box key={i.id} sx={{ mt: 1.5, pt: 1.5, borderTop: 1, borderColor: 'divider' }}>
                <Stack direction="row" justifyContent="space-between" alignItems="center">
                  <Typography variant="body2"><strong>{i.description}</strong></Typography>
                  <Chip size="small" color={statusColour(i.status)} label={humanise(i.status)} />
                </Stack>
                <Typography variant="body2" color="text.secondary">{i.invoiceNo} · {formatTzs(i.paid)} of {formatTzs(i.total)}</Typography>
                {i.controlNo && i.balance > 0 && <Typography variant="body2">Control number <strong style={{ fontFamily: 'monospace' }}>{i.controlNo}</strong></Typography>}
              </Box>
            ))}
            {!st.invoices.length && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>No invoices yet.</Typography>}
          </>
        )}
      </CardContent>
    </Card>
  );
}

function Tile({ label, value, tone }: { label: string; value: number; tone?: 'success' | 'warning' }) {
  return (
    <Grid size={{ xs: 4, sm: 3 }}>
      <Card sx={{ height: '100%' }}><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
        <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 26, color: tone ? `${tone}.main` : undefined }}>{value}</Typography>
      </CardContent></Card>
    </Grid>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card sx={{ mb: 2.5 }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 1.5 }}>{title}</Typography>
        <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '38% 62%' }, rowGap: 1 }}>{children}</Box>
      </CardContent>
    </Card>
  );
}

function Field({ label, value }: { label: string; value?: string | null }) {
  return (
    <Box sx={{ display: 'contents' }}>
      <Typography component="dt" variant="body2" color="text.secondary">{label}</Typography>
      <Typography component="dd" sx={{ m: 0, mb: { xs: 1, sm: 0 } }}>{value || '—'}</Typography>
    </Box>
  );
}
