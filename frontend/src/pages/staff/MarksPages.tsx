import AddIcon from '@mui/icons-material/Add';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import DownloadIcon from '@mui/icons-material/Download';
import FlagIcon from '@mui/icons-material/FlagOutlined';
import GroupIcon from '@mui/icons-material/GroupOutlined';
import UploadIcon from '@mui/icons-material/Upload';
import {
  Alert, Autocomplete, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, IconButton,
  MenuItem, Stack, Step, StepLabel, Stepper, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime, upload } from '../../api/client';
import type { AssessmentComponent, MarksRow, Offering, OfferingStatus, OfferingSummary, ResitMarks, Semester, StaffOption, UploadResult } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { ApprovalStagesDialog, ItemWindowDialog } from './ExamRouteDialogs';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };
const ALLOCATORS = ['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER', 'HEAD_OF_DEPARTMENT', 'DEPUTY_PRINCIPAL_ACADEMIC'];
const WIDE = ['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER', 'HEAD_OF_DEPARTMENT', 'DEPUTY_PRINCIPAL_ACADEMIC', 'MANAGEMENT_VIEWER'];
export const OFFERING_STAGE: Record<OfferingStatus, string> = {
  DRAFT: 'With lecturer', SUBMITTED: 'With Head of Department', HOD_APPROVED: 'At an approval stage', BOARD_APPROVED: 'Department approved',
};
const STAGE_COLOUR = { DRAFT: 'default', SUBMITTED: 'info', HOD_APPROVED: 'warning', BOARD_APPROVED: 'success' } as const;
const REMARK_COLOUR = { PASS: 'success', FAIL: 'error', ABSENT: 'warning', INCOMPLETE: 'default' } as const;
const num = (n?: number | null) => (n == null ? '—' : Number(n).toLocaleString('en-GB', { maximumFractionDigits: 2 }));

export function useRoles() {
  const { user } = useSession();
  const roles = user('staff')?.roles ?? [];
  return (allowed: string[]) => roles.some((r) => allowed.includes(r));
}

/** The semester most likely wanted: one whose marks entry is open, otherwise the latest that has started. */
export function useSemesterChoice() {
  const semesters = useQuery({ queryKey: ['staff', 'semesters'], queryFn: () => api<Semester[]>('/api/academic/semesters', staff) });
  const [semesterId, setSemesterId] = useState<number>();
  useEffect(() => {
    if (semesterId || !semesters.data?.length) return;
    const today = new Date().toISOString().slice(0, 10);
    const open = semesters.data.find((s) => s.marksEntryOpens && s.marksEntryOpens <= today && (!s.marksEntryCloses || s.marksEntryCloses >= today));
    setSemesterId((open ?? semesters.data.find((s) => s.registrationOpens <= today) ?? semesters.data[0]).id);
  }, [semesters.data, semesterId]);
  const picker = (
    <TextField select size="small" label="Semester" value={semesterId ?? ''} onChange={(e) => setSemesterId(Number(e.target.value))} sx={{ minWidth: 240 }}>
      {(semesters.data ?? []).map((s) => <MenuItem key={s.id} value={s.id}>{s.name} · {s.academicYear}</MenuItem>)}
    </TextField>
  );
  return { semesterId, semesters, picker };
}

// ============================================================ list of module offerings

export function MarksPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const can = useRoles();
  const wide = can(WIDE);
  const { semesterId, semesters, picker } = useSemesterChoice();
  const [mine, setMine] = useState(!wide);
  const [assigning, setAssigning] = useState<OfferingSummary | null>(null);
  const [setupResult, setSetupResult] = useState<{ created: number; skipped: string[] } | null>(null);
  const [windowOpen, setWindowOpen] = useState(false);
  const [itemWindow, setItemWindow] = useState(false);
  const [stagesOpen, setStagesOpen] = useState(false);
  const list = useQuery({
    queryKey: ['staff', 'offerings', semesterId, mine], enabled: !!semesterId,
    queryFn: () => api<OfferingSummary[]>(`/api/exams/offerings?semesterId=${semesterId}${mine ? '&mine=true' : ''}`, staff),
  });
  const setup = useMutation({
    mutationFn: () => api<{ created: number; skipped: string[] }>('/api/exams/offerings/setup', { ...staff, method: 'POST', body: { semesterId } }),
    onSuccess: (r) => { setSetupResult(r); qc.invalidateQueries({ queryKey: ['staff', 'offerings'] }); },
  });
  const counts = useMemo(() => {
    const m: Record<string, number> = {};
    (list.data ?? []).forEach((o) => { m[o.status] = (m[o.status] ?? 0) + 1; });
    return m;
  }, [list.data]);

  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Marks" subtitle="Assessment plans, marks entry and moderation for each module taught in the semester." action={picker} />
      <ErrorAlert error={semesters.error ?? list.error ?? setup.error} />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} justifyContent="space-between" sx={{ mb: 2 }}>
        <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">
          {wide && (
            <>
              <Chip clickable label="All modules" color={!mine ? 'primary' : 'default'} variant={!mine ? 'filled' : 'outlined'} onClick={() => setMine(false)} />
              <Chip clickable label="My modules" color={mine ? 'primary' : 'default'} variant={mine ? 'filled' : 'outlined'} onClick={() => setMine(true)} />
            </>
          )}
          {(Object.keys(OFFERING_STAGE) as OfferingStatus[]).filter((s) => counts[s]).map((s) => (
            <Chip key={s} size="small" variant="outlined" color={STAGE_COLOUR[s]} label={`${OFFERING_STAGE[s]}: ${counts[s]}`} sx={{ alignSelf: 'center' }} />
          ))}
        </Stack>
        <Stack direction="row" spacing={1}>
        {can(['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER']) && semesterId && (
          <>
            <Button onClick={() => setWindowOpen(true)} sx={{ whiteSpace: 'nowrap' }}>Marks entry dates</Button>
            <Button onClick={() => setItemWindow(true)} sx={{ whiteSpace: 'nowrap' }}>Dates per item</Button>
            <Button onClick={() => setStagesOpen(true)} sx={{ whiteSpace: 'nowrap' }}>Approval stages</Button>
          </>
        )}
        {can(ALLOCATORS) && semesterId && (
          <Tooltip title="Creates a module offering, with the default CA/SE plan, for every module students are registered for">
            <span><Button variant="outlined" onClick={() => setup.mutate()} disabled={setup.isPending} sx={{ whiteSpace: 'nowrap' }}>Set up modules from registrations</Button></span>
          </Tooltip>
        )}
        </Stack>
      </Stack>
      {setupResult && (
        <Alert severity={setupResult.skipped.length ? 'warning' : 'success'} sx={{ mb: 2 }} onClose={() => setSetupResult(null)}>
          {setupResult.created ? `Set up ${setupResult.created} module${setupResult.created === 1 ? '' : 's'}.` : 'Every registered module is already set up.'}
          {setupResult.skipped.length > 0 && <Box component="ul" sx={{ m: 0, pl: 2 }}>{setupResult.skipped.map((x) => <li key={x}>{x}</li>)}</Box>}
        </Alert>
      )}
      {list.isLoading && <Loading />}
      {list.data && (
        <TableContainer component={Card}>
          <Table size="small">
            <TableHead><TableRow>
              <TableCell>Module</TableCell><TableCell>Lecturers</TableCell><TableCell align="right">Students</TableCell>
              <TableCell align="right">Marks complete</TableCell><TableCell>Stage</TableCell>{can(ALLOCATORS) && <TableCell />}
            </TableRow></TableHead>
            <TableBody>
              {list.data.map((o) => (
                <TableRow key={o.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/marks/${o.id}`)}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{o.code}</Typography>
                    <Typography variant="body2" color="text.secondary">{o.title}</Typography>
                  </TableCell>
                  <TableCell>{o.lecturers.length ? o.lecturers.map((l) => l.fullName).join(', ') : <Typography variant="body2" color="warning.main">Not allocated</Typography>}</TableCell>
                  <TableCell align="right">{o.students}</TableCell>
                  <TableCell align="right">{o.complete} / {o.students}</TableCell>
                  <TableCell>
                    <Chip size="small" color={STAGE_COLOUR[o.status]} label={OFFERING_STAGE[o.status]} />
                    {o.returnedNote && o.status === 'DRAFT' && <Chip size="small" color="warning" variant="outlined" label="Returned" sx={{ ml: 0.5 }} />}
                  </TableCell>
                  {can(ALLOCATORS) && (
                    <TableCell align="right" onClick={(e) => e.stopPropagation()}>
                      <Tooltip title="Allocate lecturers"><IconButton size="small" onClick={() => setAssigning(o)}><GroupIcon fontSize="small" /></IconButton></Tooltip>
                    </TableCell>
                  )}
                </TableRow>
              ))}
              {!list.data.length && (
                <TableRow><TableCell colSpan={6}>
                  <Typography color="text.secondary" sx={{ py: 1 }}>
                    {mine ? 'No modules are allocated to you this semester.' : 'No modules set up yet. Once Registry approves semester registrations, set them up from registrations.'}
                  </Typography>
                </TableCell></TableRow>
              )}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {windowOpen && semesters.data && semesterId && (
        <MarksWindowDialog semester={semesters.data.find((x) => x.id === semesterId)!} onClose={() => setWindowOpen(false)}
          onDone={() => { setWindowOpen(false); semesters.refetch(); }} />
      )}
      {itemWindow && semesterId && <ItemWindowDialog semesterId={semesterId} onClose={() => { setItemWindow(false); qc.invalidateQueries({ queryKey: ['staff', 'offering'] }); }} />}
      {stagesOpen && <ApprovalStagesDialog onClose={() => setStagesOpen(false)} />}
      {assigning && <AssignDialog offering={assigning} onClose={() => setAssigning(null)}
        onDone={() => { setAssigning(null); qc.invalidateQueries({ queryKey: ['staff', 'offerings'] }); }} />}
    </Container>
  );
}

function MarksWindowDialog({ semester, onClose, onDone }: { semester: Semester; onClose: () => void; onDone: () => void }) {
  const [opens, setOpens] = useState(semester.marksEntryOpens ?? '');
  const [closes, setCloses] = useState(semester.marksEntryCloses ?? '');
  const m = useMutation({
    mutationFn: () => api(`/api/exams/semesters/${semester.id}/marks-window`, { ...staff, method: 'PUT', body: { marksEntryOpens: opens || null, marksEntryCloses: closes || null } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Marks entry · {semester.name} {semester.academicYear}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField type="date" label="Examination marks from" value={opens} onChange={(e) => setOpens(e.target.value)} slotProps={{ inputLabel: { shrink: true } }}
            helperText="Open it once the examination eligibility lists are settled" />
          <TextField type="date" label="All marks close" value={closes} onChange={(e) => setCloses(e.target.value)} slotProps={{ inputLabel: { shrink: true } }}
            helperText="Coursework marks can be entered until then" />
          <ErrorAlert error={m.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" onClick={() => m.mutate()} disabled={m.isPending}>Save</Button></DialogActions>
    </Dialog>
  );
}

function AssignDialog({ offering, onClose, onDone }: { offering: { id: number; code: string; lecturers: { id: number; fullName: string }[] }; onClose: () => void; onDone: () => void }) {
  const people = useQuery({ queryKey: ['staff', 'exam-staff'], queryFn: () => api<StaffOption[]>('/api/exams/staff', staff) });
  const [chosen, setChosen] = useState<number[]>(offering.lecturers.map((l) => l.id));
  const save = useMutation({ mutationFn: () => api(`/api/exams/offerings/${offering.id}/staff`, { ...staff, method: 'PUT', body: { userIds: chosen } }), onSuccess: onDone });
  const options = people.data ?? [];
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Lecturers for {offering.code}</DialogTitle>
      <DialogContent>
        <Autocomplete multiple sx={{ mt: 1 }} options={options} value={options.filter((o) => chosen.includes(o.id))}
          getOptionLabel={(o) => o.fullName} onChange={(_, v) => setChosen(v.map((x) => x.id))}
          renderOption={(p, o) => <li {...p} key={o.id}><Box><Typography>{o.fullName}</Typography>
            <Typography variant="caption" color="text.secondary">{o.roles.map((r) => r.replace(/_/g, ' ').toLowerCase()).join(', ')}</Typography></Box></li>}
          renderInput={(p) => <TextField {...p} label="Lecturers and assessors" helperText="They enter the module's marks and submit them." />} />
        <ErrorAlert error={people.error ?? save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending}>Save</Button></DialogActions>
    </Dialog>
  );
}

// ============================================================ one module: plan, marks, moderation

type Edits = Record<string, string>;
const key = (regId: number, compId: number) => `${regId}:${compId}`;

function cellText(row: MarksRow, compId: number) {
  const c = row.scores[String(compId)];
  if (!c) return '';
  return c.absent ? 'ABS' : c.score == null ? '' : String(Number(c.score));
}

function parseCell(v: string, max: number): { ok: boolean; score?: number | null; absent?: boolean } {
  const t = v.trim().toUpperCase();
  if (t === '') return { ok: true, score: null, absent: false };
  if (t === 'ABS' || t === 'AB') return { ok: true, score: null, absent: true };
  const n = Number(t.replace(',', '.'));
  if (Number.isNaN(n) || n < 0 || n > max) return { ok: false };
  return { ok: true, score: n, absent: false };
}

export function OfferingPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'offering', id], queryFn: () => api<Offering>(`/api/exams/offerings/${id}`, staff) });
  const o = q.data;
  const [edits, setEdits] = useState<Edits>({});
  const [planOpen, setPlanOpen] = useState(false);
  const [assigning, setAssigning] = useState(false);
  const [returning, setReturning] = useState(false);
  const [uploadResult, setUploadResult] = useState<UploadResult | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const put = (x: Offering) => { qc.setQueryData(['staff', 'offering', id], x); qc.invalidateQueries({ queryKey: ['staff', 'offerings'] }); };
  const save = useMutation({
    mutationFn: () => {
      const entries = Object.entries(edits).map(([k, v]) => {
        const [reg, comp] = k.split(':').map(Number);
        const c = o!.components.find((x) => x.id === comp)!;
        const p = parseCell(v, c.maxScore);
        return { moduleRegistrationId: reg, componentId: comp, score: p.score ?? null, absent: !!p.absent };
      });
      return api<Offering>(`/api/exams/offerings/${id}/scores`, { ...staff, method: 'PUT', body: { entries } });
    },
    onSuccess: (x) => { put(x); setEdits({}); },
  });
  const act = useMutation({
    mutationFn: (what: 'submit' | 'hod-approve' | 'approve-stage') => api<Offering>(`/api/exams/offerings/${id}/${what}`, { ...staff, method: 'POST' }),
    onSuccess: put,
  });
  const send = useMutation({
    mutationFn: (file: File) => upload<UploadResult>(`/api/exams/offerings/${id}/marks-upload`, 'staff', file),
    onSuccess: (r) => { setUploadResult(r); if (r.updated) q.refetch(); },
  });
  const dirty = Object.keys(edits).length > 0;
  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => { if (dirty) e.preventDefault(); };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  if (q.isLoading) return <Loading />;
  if (!o) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  const ownWindow = (c: AssessmentComponent) => !!(c.entryOpens || c.entryCloses);
  const editable = (c: AssessmentComponent, r?: MarksRow) => (ownWindow(c)
    ? o.can.submit && (c.entryNote ?? '').startsWith('Open') && (c.category === 'CA' || r?.examEligible !== false)
    : c.category === 'CA' ? o.can.enterCa : o.can.enterSe && r?.examEligible !== false);
  const anyEditable = o.components.some((c) => editable(c));
  const invalid = Object.entries(edits).some(([k, v]) => {
    const c = o.components.find((x) => x.id === Number(k.split(':')[1]));
    return !c || !parseCell(v, c.maxScore).ok;
  });
  const stageNames = (o.stages ?? []).map((x) => x.name);
  const steps = ['Marks entry', 'Head of Department', ...stageNames, 'Department approved'];
  const passed = o.stageApprovals ?? [];
  const active = { DRAFT: 0, SUBMITTED: 1, HOD_APPROVED: 2 + passed.length, BOARD_APPROVED: steps.length }[o.status];
  const missing = o.rows.filter((r) => r.remark === 'INCOMPLETE').length;
  const focusNext = (row: number, col: number) => {
    const el = document.querySelector<HTMLInputElement>(`input[data-cell="${row + 1}-${col}"]`);
    el?.focus();
    el?.select();
  };

  return (
    <Container maxWidth="xl" disableGutters>
      <Button component={StaffLink} to="/staff/marks" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>Marks</Button>
      <PageTitle title={`${o.code} · ${o.title}`}
        subtitle={<>{o.semesterName} {o.academicYear} · {num(o.credits)} credits{o.departmentName && <> · {o.departmentName}</>} · {o.lecturers.map((l) => l.fullName).join(', ') || 'No lecturer allocated'}</>}
        action={<Stack direction="row" spacing={1}>
          <Button component={StaffLink} to={`/staff/teaching/${o.id}`}>Attendance</Button>
          {o.can.assign && <Button startIcon={<GroupIcon />} onClick={() => setAssigning(true)}>Lecturers</Button>}
        </Stack>} />
      <Card sx={{ mb: 2 }}>
        <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
          <Stepper activeStep={active} alternativeLabel>
            {steps.map((s, i) => (
              <Step key={s}><StepLabel optional={
                <Typography variant="caption" color="text.secondary">
                  {i === 0 && o.submittedAt ? `${o.submittedByName}, ${formatDate(o.submittedAt)}` : i === 1 && o.hodAt ? `${o.hodByName}, ${formatDate(o.hodAt)}`
                    : i >= 2 && passed[i - 2] ? `${passed[i - 2].byName}, ${formatDate(passed[i - 2].at)}` : ''}
                </Typography>}>{s}</StepLabel></Step>
            ))}
          </Stepper>
        </CardContent>
      </Card>
      {o.status === 'DRAFT' && o.returnedNote && (
        <Alert severity="warning" sx={{ mb: 2 }}><strong>Returned by {o.returnedByName}{o.returnedAt && `, ${formatDateTime(o.returnedAt)}`}:</strong> {o.returnedNote}</Alert>
      )}
      {o.status === 'DRAFT' && <Alert severity={anyEditable ? 'info' : 'warning'} sx={{ mb: 2 }}>{o.marksWindowNote}</Alert>}
      <ErrorAlert error={save.error ?? act.error ?? send.error} />

      <Grid container spacing={2}>
        <Grid size={{ xs: 12, xl: 9 }}>
          <Card>
            <CardContent sx={{ pb: 1 }}>
              <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} justifyContent="space-between" alignItems={{ md: 'center' }}>
                <Box>
                  <Typography variant="h6">Assessment plan</Typography>
                  <Typography variant="body2" color="text.secondary">
                    {o.components.map((c) => `${c.name} ${num(c.weight)}% (${c.category}, out of ${num(c.maxScore)})`).join(' · ')}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {o.regulation}: pass marks CA {num(o.caPassMark)} of {num(o.caWeight)}, SE {num(o.sePassMark)} of {num(o.seWeight)}; pass grade {o.gradeBands.filter((b) => b.passing).map((b) => b.grade).slice(-1)[0]} or better
                  </Typography>
                </Box>
                <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
                  {o.can.editPlan && <Button size="small" variant="outlined" onClick={() => setPlanOpen(true)}>Edit plan</Button>}
                  <Button size="small" startIcon={<DownloadIcon />} onClick={() => download(`/api/exams/offerings/${o.id}/marks-template`, 'staff', `${o.code} ${o.semesterName} marks.xlsx`)}>Excel</Button>
                  {anyEditable && (
                    <>
                      <Button size="small" startIcon={<UploadIcon />} onClick={() => fileRef.current?.click()} disabled={send.isPending || dirty}>Upload</Button>
                      <input ref={fileRef} type="file" hidden accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        onChange={(e) => { const f = e.target.files?.[0]; if (f) send.mutate(f); e.target.value = ''; }} />
                    </>
                  )}
                </Stack>
              </Stack>
            </CardContent>
            <TableContainer sx={{ maxHeight: '62vh' }}>
              <Table size="small" stickyHeader>
                <TableHead><TableRow>
                  <TableCell>Student</TableCell>
                  {o.components.map((c) => <TableCell key={c.id} align="center" sx={{ minWidth: 92 }}>{c.name}<br /><Typography variant="caption" color="text.secondary">{c.category} · /{num(c.maxScore)}</Typography>
                    {ownWindow(c) && <Typography variant="caption" component="div" color={(c.entryNote ?? '').startsWith('Open') ? 'success.main' : 'warning.main'}>{c.entryNote}</Typography>}</TableCell>)}
                  <TableCell align="right">CA /{num(o.caWeight)}</TableCell><TableCell align="right">SE /{num(o.seWeight)}</TableCell>
                  <TableCell align="right">Total</TableCell><TableCell>Grade</TableCell><TableCell>Result</TableCell>
                </TableRow></TableHead>
                <TableBody>
                  {o.rows.map((r, ri) => (
                    <TableRow key={r.moduleRegistrationId} hover>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>{r.registrationNo}</Typography>
                        <Typography variant="caption" color="text.secondary">{r.name} · {r.programmeCode}</Typography>
                        {r.examEligible === false && <Chip size="small" color="error" variant="outlined" label="Not eligible for exam" sx={{ ml: 0.5, height: 18, fontSize: 11 }} />}
                      </TableCell>
                      {o.components.map((c, ci) => {
                        const k = key(r.moduleRegistrationId, c.id!);
                        const value = edits[k] ?? cellText(r, c.id!);
                        const bad = edits[k] != null && !parseCell(edits[k], c.maxScore).ok;
                        return (
                          <TableCell key={c.id} align="center" sx={{ py: 0.5 }}>
                            {editable(c, r) ? (
                              <TextField size="small" value={value} error={bad} sx={{ width: 76, '& input': { textAlign: 'center', py: 0.75 },
                                '& .MuiOutlinedInput-root': { bgcolor: edits[k] != null ? 'action.hover' : undefined } }}
                                slotProps={{ htmlInput: { 'data-cell': `${ri}-${ci}`, 'aria-label': `${r.registrationNo} ${c.name}`, inputMode: 'decimal' } }}
                                onChange={(e) => {
                                  const v = e.target.value;
                                  setEdits((x) => {
                                    const n = { ...x };
                                    if (v === cellText(r, c.id!)) delete n[k]; else n[k] = v;
                                    return n;
                                  });
                                }}
                                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); focusNext(ri, ci); } }} />
                            ) : <Typography variant="body2">{value || '—'}</Typography>}
                          </TableCell>
                        );
                      })}
                      <TableCell align="right">{num(r.caTotal)}</TableCell>
                      <TableCell align="right">{num(r.seTotal)}</TableCell>
                      <TableCell align="right" sx={{ fontWeight: 700 }}>{num(r.total)}</TableCell>
                      <TableCell sx={{ fontWeight: 700 }}>{r.grade ?? '—'}</TableCell>
                      <TableCell>
                        <Tooltip title={r.failReason ?? ''}>
                          <Chip size="small" color={REMARK_COLOUR[r.remark]} label={r.remark === 'INCOMPLETE' ? 'Incomplete' : r.remark.charAt(0) + r.remark.slice(1).toLowerCase()} />
                        </Tooltip>
                      </TableCell>
                    </TableRow>
                  ))}
                  {!o.rows.length && <TableRow><TableCell colSpan={o.components.length + 6}><Typography color="text.secondary" sx={{ py: 1 }}>No students registered for this module.</Typography></TableCell></TableRow>}
                </TableBody>
              </Table>
            </TableContainer>
            {(anyEditable || o.can.submit) && (
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between" alignItems={{ sm: 'center' }} sx={{ p: 2, borderTop: 1, borderColor: 'divider' }}>
                <Typography variant="body2" color="text.secondary">
                  {dirty ? `${Object.keys(edits).length} unsaved change${Object.keys(edits).length === 1 ? '' : 's'}` : 'Type a mark, or ABS for absent. Enter moves down.'}
                  {invalid && <Typography component="span" color="error" variant="body2"> · fix the cells in red</Typography>}
                </Typography>
                <Stack direction="row" spacing={1}>
                  {dirty && <Button onClick={() => setEdits({})}>Discard</Button>}
                  {anyEditable && <Button variant="contained" disabled={!dirty || invalid || save.isPending} onClick={() => save.mutate()}>Save marks</Button>}
                  {o.can.submit && (
                    <Tooltip title={missing ? `${missing} student${missing === 1 ? ' has' : 's have'} missing marks` : dirty ? 'Save your changes first' : ''}>
                      <span><Button variant="outlined" color="success" disabled={!!missing || dirty || act.isPending}
                        onClick={() => { if (window.confirm('Submit these results to the Head of Department? You cannot change them unless they are returned to you.')) act.mutate('submit'); }}>
                        Submit to Head of Department</Button></span>
                    </Tooltip>
                  )}
                </Stack>
              </Stack>
            )}
          </Card>
        </Grid>
        <Grid size={{ xs: 12, xl: 3 }}>
          <Stack spacing={2} direction={{ xs: 'column', md: 'row', xl: 'column' }} sx={{ '& > *': { flex: 1 } }}>
            <Card>
              <CardContent>
                <Typography variant="h6" sx={{ mb: 1 }}>Results so far</Typography>
                <Stack direction="row" spacing={2} sx={{ mb: 1.5 }}>
                  <Box><Typography variant="caption" color="text.secondary">Average</Typography><Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 22 }}>{num(o.stats.mean)}</Typography></Box>
                  <Box><Typography variant="caption" color="text.secondary">Pass rate</Typography><Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 22 }}>{o.stats.passRate == null ? '—' : `${num(o.stats.passRate)}%`}</Typography></Box>
                  <Box><Typography variant="caption" color="text.secondary">Graded</Typography><Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 22 }}>{o.stats.graded}/{o.stats.students}</Typography></Box>
                </Stack>
                <GradeBars distribution={o.stats.distribution} passing={o.gradeBands.filter((b) => b.passing).map((b) => b.grade)} />
              </CardContent>
            </Card>
            {o.stats.flags.length > 0 && (
              <Card>
                <CardContent>
                  <Typography variant="h6" sx={{ mb: 1 }}>For the moderator</Typography>
                  <Stack spacing={1}>
                    {o.stats.flags.map((f) => <Stack key={f} direction="row" spacing={1}><FlagIcon fontSize="small" color="warning" /><Typography variant="body2">{f}</Typography></Stack>)}
                  </Stack>
                </CardContent>
              </Card>
            )}
            {(o.can.hodApprove || o.can.boardApprove || o.can.returnToLecturer) && (
              <Card>
                <CardContent>
                  <Typography variant="h6" sx={{ mb: 1 }}>{o.can.boardApprove ? o.waitingAtStage ?? 'Approval' : 'Moderation'}</Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
                    {o.can.hodApprove ? 'Check the marks and the flags, then approve or return them to the lecturer with a comment.'
                      : o.can.boardApprove ? `Record the approval at this stage (${o.waitingAtStage}), or return the results to the lecturer with a comment.` : 'Return the results to the lecturer for correction.'}
                  </Typography>
                  <Stack spacing={1}>
                    {o.can.hodApprove && <Button variant="contained" color="success" onClick={() => act.mutate('hod-approve')} disabled={act.isPending}>Approve results</Button>}
                    {o.can.boardApprove && <Button variant="contained" color="success" onClick={() => act.mutate('approve-stage')} disabled={act.isPending}>Approve: {o.waitingAtStage}</Button>}
                    {o.can.returnToLecturer && <Button color="warning" onClick={() => setReturning(true)}>Return to lecturer</Button>}
                  </Stack>
                </CardContent>
              </Card>
            )}
          </Stack>
        </Grid>
      </Grid>
      <ResitMarksCard offeringId={o.id} />
      {planOpen && <PlanDialog o={o} onClose={() => setPlanOpen(false)} onSaved={(x) => { put(x); setPlanOpen(false); }} />}
      {assigning && <AssignDialog offering={o} onClose={() => setAssigning(false)} onDone={() => { setAssigning(false); q.refetch(); }} />}
      {returning && <ReturnDialog id={o.id} onClose={() => setReturning(false)} onDone={(x) => { put(x); setReturning(false); }} />}
      {uploadResult && (
        <Dialog open onClose={() => setUploadResult(null)} fullWidth maxWidth="sm">
          <DialogTitle>{uploadResult.errors.length ? 'The file was not applied' : 'Marks uploaded'}</DialogTitle>
          <DialogContent>
            {uploadResult.errors.length ? (
              <>
                <Typography sx={{ mb: 1 }}>Nothing was saved. Correct these rows and upload the file again:</Typography>
                <Box component="ul" sx={{ m: 0, pl: 2 }}>{uploadResult.errors.map((e, i) => <li key={i}>Row {e.row}: {e.message}</li>)}</Box>
              </>
            ) : <Typography>{uploadResult.updated} mark{uploadResult.updated === 1 ? '' : 's'} saved and graded.</Typography>}
          </DialogContent>
          <DialogActions><Button onClick={() => setUploadResult(null)}>OK</Button></DialogActions>
        </Dialog>
      )}
    </Container>
  );
}

export function GradeBars({ distribution, passing }: { distribution: Record<string, number>; passing: string[] }) {
  const max = Math.max(1, ...Object.values(distribution));
  return (
    <Stack spacing={0.75}>
      {Object.entries(distribution).map(([g, n]) => (
        <Stack key={g} direction="row" spacing={1} alignItems="center">
          <Typography variant="body2" sx={{ width: 26, fontWeight: 700 }}>{g}</Typography>
          <Box sx={{ flexGrow: 1, height: 14, bgcolor: 'action.hover', borderRadius: 1, overflow: 'hidden' }}>
            <Box sx={{ width: `${(n / max) * 100}%`, height: '100%', bgcolor: passing.includes(g) ? 'primary.main' : 'error.light' }} />
          </Box>
          <Typography variant="body2" sx={{ width: 20, textAlign: 'right' }}>{n}</Typography>
        </Stack>
      ))}
    </Stack>
  );
}

function PlanDialog({ o, onClose, onSaved }: { o: Offering; onClose: () => void; onSaved: (x: Offering) => void }) {
  const [items, setItems] = useState<AssessmentComponent[]>(o.components.map((c) => ({ ...c })));
  const sum = (cat: 'CA' | 'SE') => items.filter((c) => c.category === cat).reduce((s, c) => s + Number(c.weight || 0), 0);
  const ok = sum('CA') === Number(o.caWeight) && sum('SE') === Number(o.seWeight);
  const save = useMutation({
    mutationFn: () => api<Offering>(`/api/exams/offerings/${o.id}/plan`, { ...staff, method: 'PUT', body: { components: items.map((c) => ({ ...c, weight: Number(c.weight), maxScore: Number(c.maxScore) })) } }),
    onSuccess: onSaved,
  });
  const set = (i: number, patch: Partial<AssessmentComponent>) => setItems(items.map((c, j) => (j === i ? { ...c, ...patch } : c)));
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Assessment plan · {o.code}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Split coursework into tests, assignments and practicals. Under {o.regulation}, CA items must add up to {num(o.caWeight)} and the semester examination to {num(o.seWeight)}.
          Each item is marked out of the number you choose and scaled to its weight.
        </Typography>
        <Stack spacing={1.5}>
          {items.map((c, i) => (
            <Stack key={c.id ?? `new-${i}`} direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }}>
              <TextField size="small" label="Item" value={c.name} onChange={(e) => set(i, { name: e.target.value })} sx={{ flexGrow: 1 }} />
              <TextField select size="small" label="Category" value={c.category} onChange={(e) => set(i, { category: e.target.value as 'CA' | 'SE' })} sx={{ width: 170 }}>
                <MenuItem value="CA">Coursework (CA)</MenuItem><MenuItem value="SE">Examination (SE)</MenuItem>
              </TextField>
              <TextField size="small" type="number" label="Weight %" value={c.weight} onChange={(e) => set(i, { weight: e.target.value as unknown as number })} sx={{ width: 110 }} />
              <TextField size="small" type="number" label="Marked out of" value={c.maxScore} onChange={(e) => set(i, { maxScore: e.target.value as unknown as number })} sx={{ width: 130 }} />
              <IconButton aria-label={`Remove ${c.name}`} onClick={() => setItems(items.filter((_, j) => j !== i))} disabled={items.length === 1}><DeleteIcon /></IconButton>
            </Stack>
          ))}
        </Stack>
        <Button startIcon={<AddIcon />} sx={{ mt: 1 }} onClick={() => setItems([...items, { name: `Test ${items.filter((x) => x.category === 'CA').length + 1}`, category: 'CA', weight: 10, maxScore: 100 }])}>Add item</Button>
        <Alert severity={ok ? 'success' : 'warning'} sx={{ mt: 2 }}>CA {num(sum('CA'))} of {num(o.caWeight)} · SE {num(sum('SE'))} of {num(o.seWeight)}</Alert>
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!ok || save.isPending || items.some((c) => !c.name.trim())} onClick={() => save.mutate()}>Save plan</Button></DialogActions>
    </Dialog>
  );
}

function ReturnDialog({ id, onClose, onDone }: { id: number; onClose: () => void; onDone: (x: Offering) => void }) {
  const [note, setNote] = useState('');
  const m = useMutation({ mutationFn: () => api<Offering>(`/api/exams/offerings/${id}/return`, { ...staff, method: 'POST', body: { note } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Return to the lecturer</DialogTitle>
      <DialogContent>
        <TextField autoFocus fullWidth multiline minRows={3} sx={{ mt: 1 }} label="What should be checked or corrected?" value={note} onChange={(e) => setNote(e.target.value)} />
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button color="warning" variant="contained" disabled={!note.trim() || m.isPending} onClick={() => m.mutate()}>Return</Button></DialogActions>
    </Dialog>
  );
}

// ============================================================ supplementary and special examination marks (FR-EXM-08)

function ResitMarksCard({ offeringId }: { offeringId: number }) {
  const qc = useQueryClient();
  const key = ['staff', 'resit-marks', offeringId];
  const q = useQuery({ queryKey: key, queryFn: () => api<ResitMarks>(`/api/exams/offerings/${offeringId}/resits`, staff) });
  const [edits, setEdits] = useState<Record<number, string>>({});
  const [returning, setReturning] = useState(false);
  const [note, setNote] = useState('');
  const put = (x: ResitMarks) => { qc.setQueryData(key, x); setEdits({}); };
  const save = useMutation({
    mutationFn: () => api<ResitMarks>(`/api/exams/offerings/${offeringId}/resits`, { ...staff, method: 'PUT', body: {
      entries: Object.entries(edits).map(([id, v]) => {
        const abs = v.trim().toUpperCase() === 'ABS';
        return { resitId: Number(id), score: abs || v.trim() === '' ? null : Number(v), absent: abs };
      }) } }),
    onSuccess: put,
  });
  const act = useMutation({
    mutationFn: (what: 'submit' | 'approve' | 'return') => api<ResitMarks>(`/api/exams/offerings/${offeringId}/resits/${what}`, { ...staff, method: 'POST',
      body: what === 'return' ? { note } : undefined }),
    onSuccess: (x) => { put(x); setReturning(false); qc.invalidateQueries({ queryKey: ['staff', 'resit-session'] }); },
  });
  const r = q.data;
  if (!r || !r.rows.length) return null;
  const bad = Object.values(edits).some((v) => { const t = v.trim().toUpperCase(); if (t === 'ABS' || t === '') return false; const n = Number(t); return Number.isNaN(n) || n < 0 || n > 100; });
  const dirty = Object.keys(edits).length > 0;
  const missing = r.rows.filter((x) => x.paid && (!x.remark || x.remark === 'INCOMPLETE')).length;
  return (
    <Card sx={{ mt: 2, borderTop: 3, borderColor: 'warning.main' }}>
      <CardContent sx={{ pb: 1 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" spacing={1}>
          <Box>
            <Typography variant="h6">Supplementary and special examinations</Typography>
            <Typography variant="body2" color="text.secondary">
              Coursework is carried from the first attempt; the resit (out of 100) replaces the examination at {num(r.seWeight)}%.
              {r.suppMaxGrade && ` A supplementary grade is capped at ${r.suppMaxGrade}.`} Marks can be entered once the student has paid.
            </Typography>
          </Box>
          <Chip sx={{ alignSelf: { md: 'flex-start' } }} color={r.status === 'APPROVED' ? 'success' : r.status === 'SUBMITTED' ? 'info' : 'default'}
            label={{ DRAFT: 'With lecturer', SUBMITTED: 'With Head of Department', APPROVED: `Approved${r.approvedByName ? ` by ${r.approvedByName}` : ''}` }[r.status]} />
        </Stack>
        {r.returnedNote && r.status === 'DRAFT' && <Alert severity="warning" sx={{ mt: 1 }}><strong>Returned:</strong> {r.returnedNote}</Alert>}
      </CardContent>
      <TableContainer>
        <Table size="small">
          <TableHead><TableRow>
            <TableCell>Student</TableCell><TableCell>Resit</TableCell><TableCell>First attempt</TableCell><TableCell align="right">CA</TableCell>
            <TableCell align="center">Resit exam /100</TableCell><TableCell align="right">SE</TableCell><TableCell align="right">Total</TableCell><TableCell>Grade</TableCell><TableCell>Result</TableCell>
          </TableRow></TableHead>
          <TableBody>
            {r.rows.map((x) => {
              const value = edits[x.resitId] ?? (x.absent ? 'ABS' : x.score == null ? '' : String(Number(x.score)));
              return (
                <TableRow key={x.resitId}>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{x.registrationNo}</Typography><Typography variant="caption" color="text.secondary">{x.name}</Typography></TableCell>
                  <TableCell>{x.kind === 'SPECIAL' ? 'Special' : 'Supplementary'}</TableCell>
                  <TableCell>{x.firstGrade ?? '—'}</TableCell>
                  <TableCell align="right">{num(x.caTotal)}</TableCell>
                  <TableCell align="center">
                    {!x.paid ? <Chip size="small" variant="outlined" color="warning" label="Not paid" />
                      : r.canEnter ? <TextField size="small" value={value} sx={{ width: 76, '& input': { textAlign: 'center', py: 0.75 } }}
                        slotProps={{ htmlInput: { 'aria-label': `${x.registrationNo} resit` } }}
                        onChange={(e) => setEdits({ ...edits, [x.resitId]: e.target.value })} />
                      : (value || '—')}
                  </TableCell>
                  <TableCell align="right">{num(x.seTotal)}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700 }}>{num(x.total)}</TableCell>
                  <TableCell sx={{ fontWeight: 700 }}>{x.grade ?? '—'}{x.capped && <Tooltip title="Capped: a supplementary grade cannot be higher"><Chip size="small" label="capped" sx={{ ml: 0.5, height: 18 }} /></Tooltip>}</TableCell>
                  <TableCell>{x.remark && <Tooltip title={x.failReason ?? ''}><Chip size="small" color={REMARK_COLOUR[x.remark as keyof typeof REMARK_COLOUR] ?? 'default'}
                    label={x.remark.charAt(0) + x.remark.slice(1).toLowerCase()} /></Tooltip>}</TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      </TableContainer>
      <ErrorAlert error={save.error ?? act.error} />
      {(r.canEnter || r.canApprove || r.canReturn) && (
        <Stack direction="row" spacing={1} justifyContent="flex-end" sx={{ p: 2, borderTop: 1, borderColor: 'divider' }}>
          {r.canEnter && <Button variant="contained" disabled={!dirty || bad || save.isPending} onClick={() => save.mutate()}>Save resit marks</Button>}
          {r.canSubmit && <Button variant="outlined" color="success" disabled={dirty || missing > 0 || act.isPending} onClick={() => act.mutate('submit')}>Submit to Head of Department</Button>}
          {r.canReturn && <Button color="warning" onClick={() => setReturning(true)}>Return to lecturer</Button>}
          {r.canApprove && <Button variant="contained" color="success" disabled={act.isPending} onClick={() => act.mutate('approve')}>Approve resit marks</Button>}
        </Stack>
      )}
      {returning && (
        <Dialog open onClose={() => setReturning(false)} fullWidth maxWidth="sm">
          <DialogTitle>Return the resit marks</DialogTitle>
          <DialogContent><TextField autoFocus fullWidth multiline minRows={2} sx={{ mt: 1 }} label="What should be corrected?" value={note} onChange={(e) => setNote(e.target.value)} /></DialogContent>
          <DialogActions><Button onClick={() => setReturning(false)}>Cancel</Button><Button color="warning" variant="contained" disabled={!note.trim()} onClick={() => act.mutate('return')}>Return</Button></DialogActions>
        </Dialog>
      )}
    </Card>
  );
}
