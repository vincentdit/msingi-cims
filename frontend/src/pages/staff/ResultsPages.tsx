import AddIcon from '@mui/icons-material/Add';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import DownloadIcon from '@mui/icons-material/Download';
import EditIcon from '@mui/icons-material/EditOutlined';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel,
  IconButton, MenuItem, Stack, Step, StepLabel, Stepper, Switch, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField,
  Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime } from '../../api/client';
import {
  DECISIONS, type AcademicYear, type ClassificationBand, type Decision, type GradeBand, type Regulation, type ResultSheet, type SheetRow,
  type SheetSummary,
} from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles, useSemesterChoice } from './MarksPages';

const staff = { realm: 'staff' as const };
export const DECISION_LABEL: Record<Decision, string> = {
  PASS: 'Pass', SUPPLEMENTARY: 'Supplementary', SPECIAL_EXAM: 'Special exam', INCOMPLETE: 'Incomplete', REPEAT: 'Repeat', DISCONTINUE: 'Discontinue',
};
export const DECISION_COLOUR = { PASS: 'success', SUPPLEMENTARY: 'warning', SPECIAL_EXAM: 'info', INCOMPLETE: 'default', REPEAT: 'error', DISCONTINUE: 'error' } as const;
const SHEET_STAGE = {
  NOT_COMPUTED: 'Not computed', COMPUTED: 'With Academic Committee', RECOMMENDED: 'With Governing Board', CONFIRMED: 'Confirmed: ready to publish', PUBLISHED: 'Published',
} as const;
const SHEET_COLOUR = { NOT_COMPUTED: 'default', COMPUTED: 'info', RECOMMENDED: 'warning', CONFIRMED: 'primary', PUBLISHED: 'success' } as const;
const num = (n?: number | null, d = 1) => (n == null ? '—' : Number(n).toFixed(d));

// ============================================================ class result sheets

export function ResultSheetsPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const can = useRoles();
  const { semesterId, semesters, picker } = useSemesterChoice();
  const list = useQuery({
    queryKey: ['staff', 'sheets', semesterId], enabled: !!semesterId,
    queryFn: () => api<SheetSummary[]>(`/api/exams/sheets?semesterId=${semesterId}`, staff),
  });
  const compute = useMutation({
    mutationFn: (x: SheetSummary) => api<ResultSheet>('/api/exams/sheets/compute', { ...staff, method: 'POST', body: { semesterId, programmeId: x.programmeId, yearOfStudy: x.yearOfStudy } }),
    onSuccess: (s) => { qc.invalidateQueries({ queryKey: ['staff', 'sheets'] }); navigate(`/staff/results/${s.id}`); },
  });
  const officer = can(['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER']);
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Results" subtitle="Class result sheets: computed by the Examinations Officer, reviewed by the Academic Committee, confirmed by the Governing Board, then published." action={picker} />
      <ErrorAlert error={semesters.error ?? list.error ?? compute.error} />
      {list.isLoading && <Loading />}
      <Stack spacing={1.5}>
        {list.data?.map((x) => (
          <Card key={`${x.programmeId}-${x.yearOfStudy}`}>
            <CardContent>
              <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ md: 'center' }}>
                <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                  <Stack direction="row" spacing={1} alignItems="center" useFlexGap flexWrap="wrap">
                    <Typography variant="h6" sx={{ fontSize: 17 }}>{x.programmeCode} · Year {x.yearOfStudy}</Typography>
                    <Chip size="small" color={SHEET_COLOUR[x.status]} label={SHEET_STAGE[x.status]} />
                  </Stack>
                  <Typography variant="body2" color="text.secondary">{x.programmeName} · {x.students} student{x.students === 1 ? '' : 's'}
                    {x.publishedAt && <> · published {formatDate(x.publishedAt)}</>}</Typography>
                  {x.modulesPending.length > 0 && (
                    <Typography variant="body2" color="warning.main" sx={{ mt: 0.5 }}>Waiting for department approval: {x.modulesPending.join(', ')}</Typography>
                  )}
                  {Object.keys(x.decisions).length > 0 && (
                    <Stack direction="row" spacing={0.5} sx={{ mt: 1 }} useFlexGap flexWrap="wrap">
                      {Object.entries(x.decisions).map(([d, n]) => <Chip key={d} size="small" variant="outlined" color={DECISION_COLOUR[d as Decision]} label={`${DECISION_LABEL[d as Decision]} ${n}`} />)}
                    </Stack>
                  )}
                </Box>
                <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
                  {officer && (x.status === 'NOT_COMPUTED' || x.status === 'COMPUTED') && (
                    <Tooltip title={x.modulesPending.length ? 'Every module must be approved by its department first' : ''}>
                      <span><Button variant={x.status === 'NOT_COMPUTED' ? 'contained' : 'outlined'} disabled={x.modulesPending.length > 0 || compute.isPending}
                        onClick={() => compute.mutate(x)}>{x.status === 'NOT_COMPUTED' ? 'Compute results' : 'Recompute'}</Button></span>
                    </Tooltip>
                  )}
                  {x.id && <Button component={RouterLink} to={`/staff/results/${x.id}`}>Open</Button>}
                </Stack>
              </Stack>
            </CardContent>
          </Card>
        ))}
        {list.data && !list.data.length && <Alert severity="info">No classes with approved semester registrations in this semester.</Alert>}
      </Stack>
    </Container>
  );
}

export function ResultSheetPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'sheet', id], queryFn: () => api<ResultSheet>(`/api/exams/sheets/${id}`, staff) });
  const s = q.data;
  const [deciding, setDeciding] = useState<SheetRow | null>(null);
  const [noteFor, setNoteFor] = useState<'recommend' | 'return' | null>(null);
  const done = (x: ResultSheet) => { qc.setQueryData(['staff', 'sheet', id], x); qc.invalidateQueries({ queryKey: ['staff', 'sheets'] }); };
  const act = useMutation({
    mutationFn: (what: 'confirm' | 'publish') => api<ResultSheet>(`/api/exams/sheets/${id}/${what}`, { ...staff, method: 'POST' }),
    onSuccess: done,
  });
  const recompute = useMutation({
    mutationFn: () => api<ResultSheet>('/api/exams/sheets/compute', { ...staff, method: 'POST', body: { semesterId: s!.semesterId, programmeId: s!.programmeId, yearOfStudy: s!.yearOfStudy } }),
    onSuccess: done,
  });
  if (q.isLoading) return <Loading />;
  if (!s) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  const steps = ['Computed', 'Academic Committee', 'Governing Board', 'Published'];
  const active = { COMPUTED: 1, RECOMMENDED: 2, CONFIRMED: 3, PUBLISHED: 4 }[s.status];
  const exportUrl = `/api/reports/EXM-02/export?format=xlsx&semesterId=${s.semesterId}&programmeId=${s.programmeId}&yearOfStudy=${s.yearOfStudy}`;
  return (
    <Container maxWidth="xl" disableGutters>
      <Button component={RouterLink} to="/staff/results" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>Results</Button>
      <PageTitle title={`${s.programmeCode} year ${s.yearOfStudy} · ${s.semesterName} ${s.academicYear}`}
        subtitle={<>{s.programmeName} · {s.rows.length} student{s.rows.length === 1 ? '' : 's'} · {s.regulation}</>}
        action={
          <Stack direction="row" spacing={1}>
            <Button startIcon={<DownloadIcon />} onClick={() => download(exportUrl, 'staff', `${s.programmeCode} Y${s.yearOfStudy} ${s.semesterName} results.xlsx`)}>Excel</Button>
            <Button startIcon={<DownloadIcon />} onClick={() => download(exportUrl.replace('format=xlsx', 'format=pdf'), 'staff', `${s.programmeCode} Y${s.yearOfStudy} ${s.semesterName} results.pdf`)}>PDF</Button>
          </Stack>
        } />
      <Card sx={{ mb: 2 }}>
        <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
          <Stepper activeStep={active} alternativeLabel>
            {steps.map((x, i) => (
              <Step key={x}><StepLabel optional={<Typography variant="caption" color="text.secondary">
                {[s.computedAt && `${s.computedByName}, ${formatDate(s.computedAt)}`, s.recommendedAt && `${s.recommendedByName}, ${formatDate(s.recommendedAt)}`,
                  s.confirmedAt && `${s.confirmedByName}, ${formatDate(s.confirmedAt)}`, s.publishedAt && `${s.publishedByName}, ${formatDate(s.publishedAt)}`][i] || ''}
              </Typography>}>{x}</StepLabel></Step>
            ))}
          </Stepper>
        </CardContent>
      </Card>
      {s.status === 'COMPUTED' && s.returnedNote && <Alert severity="warning" sx={{ mb: 2 }}><strong>Returned by {s.returnedByName}{s.returnedAt && `, ${formatDateTime(s.returnedAt)}`}:</strong> {s.returnedNote}</Alert>}
      {s.committeeNote && <Alert severity="info" sx={{ mb: 2 }}><strong>Academic Committee:</strong> {s.committeeNote}</Alert>}
      {(s.status === 'CONFIRMED' || s.status === 'PUBLISHED') && <Alert severity="success" sx={{ mb: 2 }}>Confirmed by the Governing Board: these results are locked.</Alert>}
      <ErrorAlert error={act.error ?? recompute.error} />

      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} justifyContent="space-between" alignItems={{ md: 'center' }} sx={{ mb: 1.5 }}>
        <Stack direction="row" spacing={0.5} useFlexGap flexWrap="wrap">
          {Object.entries(s.decisions).map(([d, n]) => <Chip key={d} color={DECISION_COLOUR[d as Decision]} label={`${DECISION_LABEL[d as Decision]}: ${n}`} />)}
        </Stack>
        <Stack direction="row" spacing={1}>
          {s.can.compute && <Button onClick={() => recompute.mutate()} disabled={recompute.isPending}>Recompute</Button>}
          {s.can.returnSheet && <Button color="warning" onClick={() => setNoteFor('return')}>Return</Button>}
          {s.can.recommend && <Button variant="contained" onClick={() => setNoteFor('recommend')}>Recommend to the Board</Button>}
          {s.can.confirm && <Button variant="contained" color="success" disabled={act.isPending}
            onClick={() => { if (window.confirm('Confirm these results for the Governing Board? They will be locked.')) act.mutate('confirm'); }}>Confirm for the Board</Button>}
          {s.can.publish && <Button variant="contained" color="success" disabled={act.isPending}
            onClick={() => { if (window.confirm(`Publish to ${s.rows.length} students? They get an SMS and see their results in the portal.`)) act.mutate('publish'); }}>Publish to students</Button>}
        </Stack>
      </Stack>

      <TableContainer component={Card} sx={{ maxHeight: '64vh' }}>
        <Table size="small" stickyHeader>
          <TableHead><TableRow>
            <TableCell>Student</TableCell>
            {s.modules.map((m) => <TableCell key={m.code} align="center"><Tooltip title={`${m.title} (${m.credits} credits)`}><span>{m.code}</span></Tooltip></TableCell>)}
            <TableCell align="right">Credits</TableCell><TableCell align="right">GPA</TableCell><TableCell align="right">CGPA</TableCell>
            <TableCell>Decision</TableCell><TableCell>Classification</TableCell>
          </TableRow></TableHead>
          <TableBody>
            {s.rows.map((r) => (
              <TableRow key={r.studentId} hover>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{r.registrationNo}</Typography>
                  <Typography variant="caption" color="text.secondary">{r.name}</Typography>
                </TableCell>
                {s.modules.map((m) => {
                  const c = r.cells[m.code];
                  const colour = !c ? 'text.disabled' : c.remark === 'FAIL' ? 'error.main' : c.remark === 'ABSENT' || c.remark === 'INCOMPLETE' ? 'warning.main' : 'text.primary';
                  return (
                    <TableCell key={m.code} align="center" sx={{ color: colour, fontWeight: 600 }}>
                      <Tooltip title={c ? `${c.total ?? '—'} · ${c.remark.toLowerCase()}` : 'Not registered'}>
                        <span>{!c ? '' : c.grade ?? (c.remark === 'ABSENT' ? 'ABS' : 'I')}</span>
                      </Tooltip>
                    </TableCell>
                  );
                })}
                <TableCell align="right">{r.creditsPassed}/{r.creditsRegistered}</TableCell>
                <TableCell align="right" sx={{ fontWeight: 700 }}>{num(r.gpa)}</TableCell>
                <TableCell align="right">{num(r.cgpa)}</TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5} alignItems="center">
                    <Tooltip title={r.decisionReason ? `Committee: ${r.decisionReason} (proposed: ${DECISION_LABEL[r.proposedDecision]})` : ''}>
                      <Chip size="small" color={DECISION_COLOUR[r.decision]} variant={r.decision !== r.proposedDecision ? 'outlined' : 'filled'}
                        label={DECISION_LABEL[r.decision] + (r.decision !== r.proposedDecision ? ' *' : '')} />
                    </Tooltip>
                    {s.can.decide && <IconButton size="small" aria-label={`Change decision for ${r.registrationNo}`} onClick={() => setDeciding(r)}><EditIcon fontSize="small" /></IconButton>}
                  </Stack>
                </TableCell>
                <TableCell>{r.classification ?? '—'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
        Red: failed · ABS: absent from the examination · I: incomplete · * changed by the Academic Committee. Classification is provisional, on the CGPA so far.
      </Typography>
      {deciding && <DecisionDialog sheetId={s.id} row={deciding} onClose={() => setDeciding(null)} onDone={(x) => { done(x); setDeciding(null); }} />}
      {noteFor && <NoteDialog sheetId={s.id} what={noteFor} onClose={() => setNoteFor(null)} onDone={(x) => { done(x); setNoteFor(null); }} />}
    </Container>
  );
}

function DecisionDialog({ sheetId, row, onClose, onDone }: { sheetId: number; row: SheetRow; onClose: () => void; onDone: (x: ResultSheet) => void }) {
  const [decision, setDecision] = useState<Decision>(row.decision);
  const [reason, setReason] = useState(row.decisionReason ?? '');
  const m = useMutation({
    mutationFn: () => api<ResultSheet>(`/api/exams/sheets/${sheetId}/students/${row.studentId}/decision`, { ...staff, method: 'PUT', body: { decision, reason } }),
    onSuccess: onDone,
  });
  const differs = decision !== row.proposedDecision;
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{row.name}<Typography variant="body2" color="text.secondary">{row.registrationNo} · GPA {num(row.gpa)} · {row.modulesFailed} failed</Typography></DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 2 }}>Proposed by the regulations: <strong>{DECISION_LABEL[row.proposedDecision]}</strong></Typography>
        <TextField select fullWidth label="Committee decision" value={decision} onChange={(e) => setDecision(e.target.value as Decision)}>
          {DECISIONS.map((d) => <MenuItem key={d} value={d}>{DECISION_LABEL[d]}</MenuItem>)}
        </TextField>
        {differs && <TextField fullWidth multiline minRows={2} sx={{ mt: 2 }} label="Reason (recorded in the audit log)" value={reason} onChange={(e) => setReason(e.target.value)} />}
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={(differs && !reason.trim()) || m.isPending} onClick={() => m.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

function NoteDialog({ sheetId, what, onClose, onDone }: { sheetId: number; what: 'recommend' | 'return'; onClose: () => void; onDone: (x: ResultSheet) => void }) {
  const [note, setNote] = useState('');
  const m = useMutation({ mutationFn: () => api<ResultSheet>(`/api/exams/sheets/${sheetId}/${what}`, { ...staff, method: 'POST', body: { note } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{what === 'recommend' ? 'Recommend to the Governing Board' : 'Return to the Examinations Officer'}</DialogTitle>
      <DialogContent>
        <TextField autoFocus fullWidth multiline minRows={2} sx={{ mt: 1 }} value={note} onChange={(e) => setNote(e.target.value)}
          label={what === 'recommend' ? 'Academic Committee minute or note (optional)' : 'What needs to change?'} />
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color={what === 'return' ? 'warning' : 'primary'} disabled={(what === 'return' && !note.trim()) || m.isPending} onClick={() => m.mutate()}>
          {what === 'recommend' ? 'Recommend' : 'Return'}</Button></DialogActions>
    </Dialog>
  );
}

// ============================================================ examination regulations (FR-ACA-05)

type RegForm = Omit<Regulation, 'id' | 'effectiveYear' | 'caPassMark' | 'sePassMark' | 'createdByName' | 'inUse'> & { id?: number };

export function RegulationsPage() {
  const qc = useQueryClient();
  const can = useRoles();
  const editor = can(['INSTITUTION_ADMIN', 'DEPUTY_PRINCIPAL_ACADEMIC', 'EXAMINATIONS_OFFICER']);
  const list = useQuery({ queryKey: ['staff', 'regulations'], queryFn: () => api<Regulation[]>('/api/exams/regulations', staff) });
  const settings = useQuery({ queryKey: ['staff', 'exam-settings'], queryFn: () => api<{ useDepartmentBoard: boolean }>('/api/exams/settings', staff) });
  const saveSettings = useMutation({
    mutationFn: (v: boolean) => api('/api/exams/settings', { ...staff, method: 'PUT', body: { useDepartmentBoard: v } }),
    onSuccess: () => settings.refetch(),
  });
  const [form, setForm] = useState<RegForm | null>(null);
  const fromSet = (r: Regulation, asNew: boolean): RegForm => ({
    ...(asNew ? {} : { id: r.id }), name: r.name, awardFamily: r.awardFamily, levelFrom: r.levelFrom, levelTo: r.levelTo, effectiveYearId: r.effectiveYearId,
    caWeight: r.caWeight, seWeight: r.seWeight, caPassPercent: r.caPassPercent, sePassPercent: r.sePassPercent, gpaDecimals: r.gpaDecimals,
    gpaRounding: r.gpaRounding, suppMaxFailedPercent: r.suppMaxFailedPercent, suppMaxGrade: r.suppMaxGrade, discontinueGpaBelow: r.discontinueGpaBelow,
    notes: r.notes, gradeBands: r.gradeBands.map((b) => ({ ...b })), classifications: r.classifications.map((c) => ({ ...c })),
  });
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Examination regulations" subtitle="Grading scales, CA/SE weights and pass marks, GPA, classification and progression rules per award level, by the academic year they apply from."
        action={editor && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setForm({
          name: '', awardFamily: 'NTA', levelFrom: 4, levelTo: 5, effectiveYearId: 0, caWeight: 40, seWeight: 60, caPassPercent: 50, sePassPercent: 50,
          gpaDecimals: 1, gpaRounding: 'ROUND', suppMaxFailedPercent: 50, suppMaxGrade: 'C', discontinueGpaBelow: 2, notes: '',
          gradeBands: [{ grade: 'A', minMark: 80, maxMark: 100, points: 4, remark: 'Excellent', passing: true }, { grade: 'B', minMark: 65, maxMark: 79, points: 3, remark: 'Good', passing: true },
            { grade: 'C', minMark: 50, maxMark: 64, points: 2, remark: 'Average', passing: true }, { grade: 'D', minMark: 40, maxMark: 49, points: 1, remark: 'Poor', passing: false },
            { grade: 'F', minMark: 0, maxMark: 39, points: 0, remark: 'Failure', passing: false }],
          classifications: [{ name: 'First Class', minGpa: 3.5, maxGpa: 4 }, { name: 'Second Class', minGpa: 3, maxGpa: 3.4 }, { name: 'Pass', minGpa: 2, maxGpa: 2.9 }],
        })} sx={{ whiteSpace: 'nowrap' }}>New regulations</Button>} />
      <ErrorAlert error={list.error ?? saveSettings.error} />
      {settings.data && (
        <Card sx={{ mb: 2 }}>
          <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
            <FormControlLabel disabled={!can(['INSTITUTION_ADMIN', 'DEPUTY_PRINCIPAL_ACADEMIC'])}
              control={<Switch checked={settings.data.useDepartmentBoard} onChange={(e) => saveSettings.mutate(e.target.checked)} />}
              label="Module results go to a Department Board after the Head of Department" />
          </CardContent>
        </Card>
      )}
      {list.isLoading && <Loading />}
      <Grid container spacing={2}>
        {list.data?.map((r) => (
          <Grid key={r.id} size={{ xs: 12, md: 6 }}>
            <Card sx={{ height: '100%' }}>
              <CardContent>
                <Stack direction="row" spacing={1} alignItems="flex-start">
                  <Box sx={{ flexGrow: 1 }}>
                    <Typography variant="h6">{r.name}</Typography>
                    <Typography variant="body2" color="text.secondary">{r.awardFamily} level{r.levelFrom === r.levelTo ? ` ${r.levelFrom}` : `s ${r.levelFrom}–${r.levelTo}`} · from {r.effectiveYear}</Typography>
                  </Box>
                  {r.inUse ? <Chip size="small" label="In use: locked" /> : <Chip size="small" color="warning" variant="outlined" label="Not used yet" />}
                </Stack>
                <Table size="small" sx={{ my: 1.5 }}>
                  <TableHead><TableRow><TableCell>Marks</TableCell><TableCell>Grade</TableCell><TableCell align="right">Points</TableCell><TableCell>Remark</TableCell></TableRow></TableHead>
                  <TableBody>
                    {r.gradeBands.map((b) => (
                      <TableRow key={b.grade}>
                        <TableCell>{b.minMark}–{b.maxMark}</TableCell><TableCell sx={{ fontWeight: 700 }}>{b.grade}</TableCell>
                        <TableCell align="right">{b.points}</TableCell><TableCell>{b.remark}{!b.passing && <Typography component="span" color="error.main" variant="body2"> · fail</Typography>}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
                <Typography variant="body2">CA {r.caWeight} / SE {r.seWeight}; pass marks CA {r.caPassMark}, SE {r.sePassMark}</Typography>
                <Typography variant="body2">GPA to {r.gpaDecimals} decimal{r.gpaDecimals === 1 ? '' : 's'} ({r.gpaRounding === 'ROUND' ? 'rounded' : 'truncated'})</Typography>
                <Typography variant="body2">Supplementary when fewer than {r.suppMaxFailedPercent}% of modules are failed{r.suppMaxGrade && `, capped at ${r.suppMaxGrade}`}; otherwise
                  {r.discontinueGpaBelow != null ? ` discontinue below GPA ${r.discontinueGpaBelow}, else repeat` : ' repeat'}</Typography>
                <Typography variant="body2" sx={{ mt: 0.5 }}>{r.classifications.map((c) => `${c.name} ${c.minGpa}–${c.maxGpa}`).join(' · ')}</Typography>
                {r.notes && <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>{r.notes}</Typography>}
                {editor && (
                  <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
                    {!r.inUse && <Button size="small" startIcon={<EditIcon />} onClick={() => setForm(fromSet(r, false))}>Edit</Button>}
                    <Button size="small" onClick={() => setForm({ ...fromSet(r, true), name: `${r.name} (new version)`, effectiveYearId: 0 })}>New version from this</Button>
                  </Stack>
                )}
              </CardContent>
            </Card>
          </Grid>
        ))}
        {list.data && !list.data.length && <Grid size={12}><Alert severity="warning">No regulations yet: results cannot be computed until they are set up.</Alert></Grid>}
      </Grid>
      {form && <RegulationDialog form={form} onClose={() => setForm(null)} onSaved={() => { setForm(null); qc.invalidateQueries({ queryKey: ['staff', 'regulations'] }); }} />}
    </Container>
  );
}

function RegulationDialog({ form: initial, onClose, onSaved }: { form: RegForm; onClose: () => void; onSaved: () => void }) {
  const [f, setF] = useState<RegForm>(initial);
  const years = useQuery({ queryKey: ['staff', 'years'], queryFn: () => api<AcademicYear[]>('/api/academic/years', staff) });
  const save = useMutation({
    mutationFn: () => api(f.id ? `/api/exams/regulations/${f.id}` : '/api/exams/regulations', {
      ...staff, method: f.id ? 'PUT' : 'POST', body: { ...f, suppMaxGrade: f.suppMaxGrade || null, discontinueGpaBelow: f.discontinueGpaBelow === ('' as unknown) ? null : f.discontinueGpaBelow },
    }),
    onSuccess: onSaved,
  });
  const n = (k: keyof RegForm, label: string, width = 140, helper?: string) => (
    <TextField size="small" type="number" label={label} value={(f[k] as number | undefined) ?? ''} sx={{ width }} helperText={helper}
      onChange={(e) => setF({ ...f, [k]: e.target.value === '' ? '' : Number(e.target.value) })} />
  );
  const band = (i: number, patch: Partial<GradeBand>) => setF({ ...f, gradeBands: f.gradeBands.map((b, j) => (j === i ? { ...b, ...patch } : b)) });
  const cls = (i: number, patch: Partial<ClassificationBand>) => setF({ ...f, classifications: f.classifications.map((c, j) => (j === i ? { ...c, ...patch } : c)) });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{f.id ? 'Edit regulations' : 'New regulations'}</DialogTitle>
      <DialogContent dividers>
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, sm: 6 }}><TextField fullWidth size="small" label="Name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Grid>
          <Grid size={{ xs: 4, sm: 2 }}><TextField select fullWidth size="small" label="Award" value={f.awardFamily} onChange={(e) => setF({ ...f, awardFamily: e.target.value as 'NTA' | 'NVA' })}>
            <MenuItem value="NTA">NTA</MenuItem><MenuItem value="NVA">NVA</MenuItem></TextField></Grid>
          <Grid size={{ xs: 4, sm: 2 }}><TextField fullWidth size="small" type="number" label="From level" value={f.levelFrom} onChange={(e) => setF({ ...f, levelFrom: Number(e.target.value) })} /></Grid>
          <Grid size={{ xs: 4, sm: 2 }}><TextField fullWidth size="small" type="number" label="To level" value={f.levelTo} onChange={(e) => setF({ ...f, levelTo: Number(e.target.value) })} /></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><TextField select fullWidth size="small" label="Applies from" value={f.effectiveYearId || ''} onChange={(e) => setF({ ...f, effectiveYearId: Number(e.target.value) })}
            helperText="Results of this year and later use these rules, until a newer version applies">
            {(years.data ?? []).map((y) => <MenuItem key={y.id} value={y.id}>{y.code}</MenuItem>)}</TextField></Grid>
          <Grid size={12}><Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">
            {n('caWeight', 'CA weight', 110)}{n('seWeight', 'SE weight', 110)}{n('caPassPercent', 'CA pass % of weight', 160)}{n('sePassPercent', 'SE pass % of weight', 160)}
            {n('gpaDecimals', 'GPA decimals', 120)}
            <TextField select size="small" label="GPA" value={f.gpaRounding} sx={{ width: 130 }} onChange={(e) => setF({ ...f, gpaRounding: e.target.value as 'ROUND' | 'TRUNCATE' })}>
              <MenuItem value="ROUND">Rounded</MenuItem><MenuItem value="TRUNCATE">Truncated</MenuItem></TextField>
          </Stack></Grid>
          <Grid size={12}><Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">
            {n('suppMaxFailedPercent', 'Supp. if failed % below', 180)}
            <TextField size="small" label="Supp. grade cap" value={f.suppMaxGrade ?? ''} sx={{ width: 140 }} onChange={(e) => setF({ ...f, suppMaxGrade: e.target.value.toUpperCase() })} />
            {n('discontinueGpaBelow', 'Discontinue below GPA', 180, 'Else repeat')}
          </Stack></Grid>
        </Grid>
        <Typography variant="subtitle2" sx={{ mt: 2, mb: 1 }}>Grading scale (whole marks 0–100, each exactly once)</Typography>
        <Stack spacing={1}>
          {f.gradeBands.map((b, i) => (
            <Stack key={i} direction="row" spacing={1} alignItems="center">
              <TextField size="small" label="Grade" value={b.grade} sx={{ width: 80 }} onChange={(e) => band(i, { grade: e.target.value.toUpperCase() })} />
              <TextField size="small" type="number" label="From" value={b.minMark} sx={{ width: 80 }} onChange={(e) => band(i, { minMark: Number(e.target.value) })} />
              <TextField size="small" type="number" label="To" value={b.maxMark} sx={{ width: 80 }} onChange={(e) => band(i, { maxMark: Number(e.target.value) })} />
              <TextField size="small" type="number" label="Points" value={b.points} sx={{ width: 80 }} onChange={(e) => band(i, { points: Number(e.target.value) })} />
              <TextField size="small" label="Remark" value={b.remark ?? ''} sx={{ flexGrow: 1 }} onChange={(e) => band(i, { remark: e.target.value })} />
              <FormControlLabel control={<Checkbox checked={b.passing} onChange={(e) => band(i, { passing: e.target.checked })} />} label="Pass" />
              <IconButton aria-label={`Remove grade ${b.grade}`} onClick={() => setF({ ...f, gradeBands: f.gradeBands.filter((_, j) => j !== i) })}><DeleteIcon /></IconButton>
            </Stack>
          ))}
        </Stack>
        <Button startIcon={<AddIcon />} size="small" onClick={() => setF({ ...f, gradeBands: [...f.gradeBands, { grade: '', minMark: 0, maxMark: 0, points: 0, remark: '', passing: false }] })}>Add grade</Button>
        <Typography variant="subtitle2" sx={{ mt: 2, mb: 1 }}>Award classification (by GPA)</Typography>
        <Stack spacing={1}>
          {f.classifications.map((c, i) => (
            <Stack key={i} direction="row" spacing={1} alignItems="center">
              <TextField size="small" label="Class" value={c.name} sx={{ flexGrow: 1 }} onChange={(e) => cls(i, { name: e.target.value })} />
              <TextField size="small" type="number" label="From GPA" value={c.minGpa} sx={{ width: 110 }} onChange={(e) => cls(i, { minGpa: Number(e.target.value) })} />
              <TextField size="small" type="number" label="To GPA" value={c.maxGpa} sx={{ width: 110 }} onChange={(e) => cls(i, { maxGpa: Number(e.target.value) })} />
              <IconButton aria-label={`Remove ${c.name}`} onClick={() => setF({ ...f, classifications: f.classifications.filter((_, j) => j !== i) })}><DeleteIcon /></IconButton>
            </Stack>
          ))}
        </Stack>
        <Button startIcon={<AddIcon />} size="small" onClick={() => setF({ ...f, classifications: [...f.classifications, { name: '', minGpa: 0, maxGpa: 0 }] })}>Add class</Button>
        <TextField fullWidth multiline minRows={2} size="small" sx={{ mt: 2 }} label="Notes (e.g. approval reference)" value={f.notes ?? ''} onChange={(e) => setF({ ...f, notes: e.target.value })} />
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || !f.name.trim() || !f.effectiveYearId} onClick={() => save.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}
