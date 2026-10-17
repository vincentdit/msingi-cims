import AddIcon from '@mui/icons-material/Add';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import DownloadIcon from '@mui/icons-material/Download';
import GroupIcon from '@mui/icons-material/Group';
import {
  Alert, Autocomplete, Box, Button, Card, CardContent, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton,
  MenuItem, Stack, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, ToggleButton, ToggleButtonGroup, Tooltip,
  Typography, useMediaQuery, useTheme,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles, useSemesterChoice } from './MarksPages';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };
const ALLOCATORS = ['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER', 'HEAD_OF_DEPARTMENT', 'DEPUTY_PRINCIPAL_ACADEMIC'];
const AWARDS = ['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER', 'DEPUTY_PRINCIPAL_ACADEMIC', 'MANAGEMENT_VIEWER'];
const KINDS = ['PRACTICAL', 'THEORY', 'WORKPLACE', 'TRADE_TEST', 'PROJECT'] as const;
type Kind = typeof KINDS[number];
const KIND_LABEL: Record<string, string> = { PRACTICAL: 'Practical task', THEORY: 'Theory', WORKPLACE: 'Workplace assessment', TRADE_TEST: 'Trade test', PROJECT: 'Project' };
const STATUS_LABEL: Record<string, string> = { DRAFT: 'With assessor', SUBMITTED: 'With Head of Department', VERIFIED: 'Verified' };
const STATUS_COLOUR = { DRAFT: 'default', SUBMITTED: 'info', VERIFIED: 'success' } as const;
const OUTCOME = {
  COMPETENT: { short: 'C', label: 'Competent', colour: 'success' as const },
  NOT_YET_COMPETENT: { short: 'NYC', label: 'Not yet competent', colour: 'error' as const },
  INCOMPLETE: { short: '—', label: 'Not fully assessed', colour: 'default' as const },
};
const today = () => new Date(Date.now() + 3 * 3600_000).toISOString().slice(0, 10);

interface UnitSummary {
  id: number; code: string; title: string; departmentName?: string; status: keyof typeof STATUS_COLOUR; students: number; tasks: number; assessors: string[];
  competent: number; notYetCompetent: number; incomplete: number; mine: boolean; returnedNote?: string;
}
interface Cell {
  id: number; attempt: number; outcome: 'COMPETENT' | 'NOT_YET_COMPETENT'; practicalScore?: number; theoryScore?: number; assessedOn: string; evidenceRef: string;
  comment?: string; assessorName: string; verified: boolean; attemptsLeft: number; earlier: Cell[];
}
interface UnitRow { moduleRegistrationId: number; studentId: number; registrationNo: string; name: string; programmeCode: string; tasks: Record<string, Cell>; outcome: keyof typeof OUTCOME }
interface Task { id: number; name: string; kind: Kind; evidenceRequired?: string; required: boolean }
interface UnitView {
  id: number; semesterId: number; semesterName: string; academicYear?: string; code: string; title: string; departmentName?: string; status: keyof typeof STATUS_COLOUR;
  maxAttempts: number; tasks: Task[]; assessors: { id: number; fullName: string }[]; rows: UnitRow[]; competent: number; notYetCompetent: number; incomplete: number;
  returnedNote?: string; returnedByName?: string; submittedByName?: string; submittedAt?: string; verifiedByName?: string; verifiedAt?: string;
  can: { editTasks: boolean; assign: boolean; record: boolean; submit: boolean; verify: boolean; returnUnit: boolean; reopen: boolean };
}
interface AwardClass {
  programmeId: number; programmeCode: string; programmeName: string; awardLevel: number; yearOfStudy: number; students: number; unitsVerified: number; unitsTotal: number;
  listId?: number; confirmedAt?: string; confirmedByName?: string; eligible?: number;
}
interface AwardList {
  semesterName: string; programmeCode: string; programmeName: string; award?: string; yearOfStudy: number; eligible: number; listId?: number; confirmedAt?: string;
  confirmedByName?: string; unitsNotVerified: string[]; canConfirm: boolean;
  lines: { studentId: number; registrationNo: string; name: string; sex?: string; unitsCompetent: number; unitsRequired: number; eligible: boolean; outstanding: string[] }[];
}

function OutcomeChip({ outcome, attempt }: { outcome: keyof typeof OUTCOME; attempt?: number }) {
  const o = OUTCOME[outcome];
  return <Chip size="small" color={o.colour} variant={outcome === 'COMPETENT' ? 'filled' : 'outlined'} label={`${o.short}${attempt && attempt > 1 ? ` · ${attempt}` : ''}`} />;
}

// ============================================================ list of units and award eligibility

/** NVA competence assessment (BP-09): units per semester, and award eligibility for the Examinations Office. */
export function CompetencePage() {
  const can = useRoles();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { semesterId, semesters, picker } = useSemesterChoice();
  const [tab, setTab] = useState<'units' | 'awards'>('units');
  const [notice, setNotice] = useState<string | null>(null);
  const list = useQuery({ queryKey: ['staff', 'competence', semesterId], enabled: !!semesterId,
    queryFn: () => api<UnitSummary[]>(`/api/competence/units?semesterId=${semesterId}`, staff) });
  const setup = useMutation({
    mutationFn: () => api<{ created: number; skipped: string[] }>(`/api/competence/semesters/${semesterId}/setup`, { ...staff, method: 'POST' }),
    onSuccess: (r) => {
      setNotice(r.created ? `${r.created} competence unit${r.created === 1 ? '' : 's'} set up from approved registrations.` : 'No new competence units to set up.');
      qc.invalidateQueries({ queryKey: ['staff', 'competence'] });
    },
  });
  return (
    <>
      <PageTitle title="Competence assessment (NVA)" subtitle="Tasks, evidence and outcomes per unit; verification by the Head of Department; award eligibility."
        action={<Stack direction="row" spacing={1}>{picker}
          {can(ALLOCATORS) && <Button variant="contained" onClick={() => setup.mutate()} disabled={!semesterId || setup.isPending} sx={{ whiteSpace: 'nowrap' }}>Set up units</Button>}
        </Stack>} />
      <ErrorAlert error={semesters.error ?? list.error ?? setup.error} />
      {notice && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>{notice}</Alert>}
      {can(AWARDS) && (
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2, borderBottom: 1, borderColor: 'divider' }}>
          <Tab value="units" label="Units" /><Tab value="awards" label="Award eligibility" />
        </Tabs>
      )}
      {tab === 'units' && (
        <>
          {list.isLoading && <Loading />}
          {list.data && (
            <TableContainer component={Card}>
              <Table size="small">
                <TableHead><TableRow>
                  <TableCell>Unit</TableCell><TableCell>Stage</TableCell><TableCell align="right">Trainees</TableCell>
                  <TableCell>Outcomes</TableCell><TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Assessors</TableCell>
                </TableRow></TableHead>
                <TableBody>
                  {list.data.map((u) => (
                    <TableRow key={u.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/competence/${u.id}`)}>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>{u.code}{u.mine && <Chip size="small" label="Yours" sx={{ ml: 1, height: 20 }} />}</Typography>
                        <Typography variant="body2" color="text.secondary">{u.title}</Typography>
                      </TableCell>
                      <TableCell>
                        <Chip size="small" color={STATUS_COLOUR[u.status]} label={STATUS_LABEL[u.status]} variant={u.status === 'DRAFT' ? 'outlined' : 'filled'} />
                        {u.returnedNote && u.status === 'DRAFT' && <Typography variant="caption" color="error" display="block">Returned</Typography>}
                      </TableCell>
                      <TableCell align="right">{u.students}</TableCell>
                      <TableCell>
                        <Stack direction="row" spacing={0.5}>
                          <Chip size="small" color="success" label={`C ${u.competent}`} />
                          {u.notYetCompetent > 0 && <Chip size="small" color="error" variant="outlined" label={`NYC ${u.notYetCompetent}`} />}
                          {u.incomplete > 0 && <Chip size="small" variant="outlined" label={`To assess ${u.incomplete}`} />}
                        </Stack>
                      </TableCell>
                      <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>
                        {u.assessors.length ? u.assessors.join(', ') : <Typography variant="body2" color="warning.main">Not allocated</Typography>}
                      </TableCell>
                    </TableRow>
                  ))}
                  {!list.data.length && (
                    <TableRow><TableCell colSpan={5}><Typography color="text.secondary" sx={{ py: 1 }}>
                      No competence units for this semester{can(ALLOCATORS) ? '. Set them up from approved registrations.' : '.'}
                    </Typography></TableCell></TableRow>
                  )}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </>
      )}
      {tab === 'awards' && semesterId && <AwardEligibility semesterId={semesterId} />}
    </>
  );
}

function AwardEligibility({ semesterId }: { semesterId: number }) {
  const qc = useQueryClient();
  const [chosen, setChosen] = useState<AwardClass | null>(null);
  const classes = useQuery({ queryKey: ['staff', 'nva-awards', semesterId], queryFn: () => api<AwardClass[]>(`/api/competence/awards?semesterId=${semesterId}`, staff) });
  const qs = chosen ? `semesterId=${semesterId}&programmeId=${chosen.programmeId}&yearOfStudy=${chosen.yearOfStudy}` : '';
  const list = useQuery({ queryKey: ['staff', 'nva-award-list', qs], enabled: !!chosen, queryFn: () => api<AwardList>(`/api/competence/awards/list?${qs}`, staff) });
  const confirm = useMutation({
    mutationFn: () => api<AwardList>('/api/competence/awards/confirm', { ...staff, method: 'POST',
      body: { semesterId, programmeId: chosen!.programmeId, yearOfStudy: chosen!.yearOfStudy } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staff'] }),
  });
  const [err, setErr] = useState<unknown>(null);
  const l = list.data;
  return (
    <Grid container spacing={2.5}>
      <Grid size={{ xs: 12, md: 4 }}>
        <Card>
          <CardContent sx={{ pb: 0 }}><Typography variant="h6">NVA classes</Typography></CardContent>
          <ErrorAlert error={classes.error} />
          {classes.isLoading && <Loading />}
          <Table size="small">
            <TableBody>
              {(classes.data ?? []).map((c) => (
                <TableRow key={`${c.programmeId}-${c.yearOfStudy}`} hover selected={chosen?.programmeId === c.programmeId && chosen.yearOfStudy === c.yearOfStudy}
                  sx={{ cursor: 'pointer' }} onClick={() => setChosen(c)}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{c.programmeCode} · year {c.yearOfStudy}</Typography>
                    <Typography variant="caption" color="text.secondary">{c.students} trainees · units verified {c.unitsVerified} of {c.unitsTotal}</Typography>
                  </TableCell>
                  <TableCell align="right">
                    {c.listId ? <Chip size="small" color="success" label={`Confirmed · ${c.eligible} eligible`} /> : <Chip size="small" variant="outlined" label="Not confirmed" />}
                  </TableCell>
                </TableRow>
              ))}
              {classes.data && !classes.data.length && <TableRow><TableCell><Typography color="text.secondary">No NVA classes registered this semester.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </Card>
      </Grid>
      <Grid size={{ xs: 12, md: 8 }}>
        {!chosen && <Alert severity="info">Choose a class to see which trainees are eligible for the award.</Alert>}
        {list.isLoading && <Loading />}
        <ErrorAlert error={list.error ?? confirm.error ?? err} />
        {l && (
          <Card>
            <CardContent>
              <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
                <Box>
                  <Typography variant="h6">{l.programmeCode} · year {l.yearOfStudy}</Typography>
                  <Typography variant="body2" color="text.secondary">{l.award ?? l.programmeName} · {l.semesterName} · {l.eligible} of {l.lines.length} eligible</Typography>
                </Box>
                <Stack direction="row" spacing={1} alignItems="flex-start">
                  <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/competence/awards/list.xlsx?${qs}`, 'staff', `nva-award-${l.programmeCode}-y${l.yearOfStudy}.xlsx`).catch(setErr)}>Excel</Button>
                  {l.canConfirm && <Button variant="contained" onClick={() => confirm.mutate()} disabled={confirm.isPending}>{l.listId ? 'Confirm again' : 'Confirm eligibility'}</Button>}
                </Stack>
              </Stack>
              {l.unitsNotVerified.length > 0 && <Alert severity="warning" sx={{ mt: 1.5 }}>Units not yet verified by the Head of Department: {l.unitsNotVerified.join(', ')}.</Alert>}
              {l.confirmedAt && <Alert severity="success" sx={{ mt: 1.5 }}>Confirmed by {l.confirmedByName} on {formatDateTime(l.confirmedAt)}. Send the Excel list to NACTVET.</Alert>}
            </CardContent>
            <TableContainer>
              <Table size="small">
                <TableHead><TableRow><TableCell>Trainee</TableCell><TableCell align="right">Units competent</TableCell><TableCell>Award</TableCell></TableRow></TableHead>
                <TableBody>
                  {l.lines.map((a) => (
                    <TableRow key={a.studentId}>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>{a.name}</Typography>
                        <Typography variant="caption" color="text.secondary">{a.registrationNo}</Typography>
                        {a.outstanding.length > 0 && <Typography variant="caption" color="error" display="block">{a.outstanding.join('; ')}</Typography>}
                      </TableCell>
                      <TableCell align="right">{a.unitsCompetent} / {a.unitsRequired}</TableCell>
                      <TableCell>{a.eligible ? <Chip size="small" color="success" label="Eligible" /> : <Chip size="small" variant="outlined" label="Not yet" />}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </Card>
        )}
      </Grid>
    </Grid>
  );
}

// ============================================================ one unit

export function CompetenceUnitPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const theme = useTheme();
  const narrow = !useMediaQuery(theme.breakpoints.up('md'));
  const v = useQuery({ queryKey: ['staff', 'competence-unit', id], queryFn: () => api<UnitView>(`/api/competence/units/${id}`, staff) });
  const [assessing, setAssessing] = useState<UnitRow | null>(null);
  const [editTasks, setEditTasks] = useState(false);
  const [assign, setAssign] = useState(false);
  const [returning, setReturning] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff'] });
  const act = useMutation({
    mutationFn: (what: 'submit' | 'verify' | 'reopen') => api<UnitView>(`/api/competence/units/${id}/${what}`, { ...staff, method: 'POST' }),
    onSuccess: (_, what) => { setNotice({ submit: 'Submitted to the Head of Department.', verify: 'Outcomes verified.', reopen: 'Reopened for re-assessment.' }[what]); refresh(); },
  });
  const u = v.data;
  return (
    <>
      <Button component={StaffLink} to="/staff/competence" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>Competence assessment</Button>
      <ErrorAlert error={v.error ?? act.error} />
      {v.isLoading && <Loading />}
      {u && (
        <>
          <PageTitle title={`${u.code} · ${u.title}`}
            subtitle={`${u.semesterName}${u.academicYear ? ` ${u.academicYear}` : ''}${u.departmentName ? ` · ${u.departmentName}` : ''} · assessors: ${u.assessors.map((a) => a.fullName).join(', ') || 'not allocated'}`}
            action={(
              <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" justifyContent="flex-end">
                <Chip color={STATUS_COLOUR[u.status]} label={STATUS_LABEL[u.status]} />
                {u.can.assign && <Button startIcon={<GroupIcon />} onClick={() => setAssign(true)}>Assessors</Button>}
                {u.can.submit && <Button variant="contained" onClick={() => act.mutate('submit')} disabled={act.isPending}>Submit</Button>}
                {u.can.returnUnit && <Button color="warning" onClick={() => setReturning(true)}>Return</Button>}
                {u.can.verify && <Button variant="contained" color="success" onClick={() => act.mutate('verify')} disabled={act.isPending}>Verify</Button>}
                {u.can.reopen && <Button onClick={() => act.mutate('reopen')} disabled={act.isPending}>Reopen for re-assessment</Button>}
              </Stack>
            )} />
          {notice && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>{notice}</Alert>}
          {u.returnedNote && u.status === 'DRAFT' && <Alert severity="error" sx={{ mb: 2 }}>Returned by {u.returnedByName}: {u.returnedNote}</Alert>}
          {u.status === 'SUBMITTED' && <Alert severity="info" sx={{ mb: 2 }}>Submitted by {u.submittedByName} on {formatDateTime(u.submittedAt)}; the Head of Department verifies or returns it.</Alert>}
          {u.status === 'VERIFIED' && <Alert severity="success" sx={{ mb: 2 }}>Verified by {u.verifiedByName} on {formatDateTime(u.verifiedAt)}. Verified attempts no longer change.</Alert>}
          <Grid container spacing={2} sx={{ mb: 2 }}>
            <Grid size={{ xs: 12, md: 8 }}>
              <Card sx={{ height: '100%' }}>
                <CardContent>
                  <Stack direction="row" justifyContent="space-between" alignItems="center">
                    <Typography variant="h6">Tasks</Typography>
                    {u.can.editTasks && <Button size="small" onClick={() => setEditTasks(true)}>Change tasks</Button>}
                  </Stack>
                  {u.tasks.map((t, i) => (
                    <Box key={t.id} sx={{ py: 0.75, borderBottom: i < u.tasks.length - 1 ? 1 : 0, borderColor: 'divider' }}>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{t.name}{!t.required && <Typography component="span" variant="caption" color="text.secondary"> · optional</Typography>}</Typography>
                      <Typography variant="caption" color="text.secondary">{KIND_LABEL[t.kind]}{t.evidenceRequired ? ` · evidence: ${t.evidenceRequired}` : ''}</Typography>
                    </Box>
                  ))}
                </CardContent>
              </Card>
            </Grid>
            <Grid size={{ xs: 12, md: 4 }}>
              <Card sx={{ height: '100%' }}>
                <CardContent>
                  <Typography variant="h6">Outcomes</Typography>
                  <Stack direction="row" spacing={2} sx={{ mt: 1 }}>
                    <Box><Typography variant="h4" color="success.main" sx={{ fontWeight: 700 }}>{u.competent}</Typography><Typography variant="caption">Competent</Typography></Box>
                    <Box><Typography variant="h4" color="error.main" sx={{ fontWeight: 700 }}>{u.notYetCompetent}</Typography><Typography variant="caption">Not yet</Typography></Box>
                    <Box><Typography variant="h4" sx={{ fontWeight: 700 }}>{u.incomplete}</Typography><Typography variant="caption">To assess</Typography></Box>
                  </Stack>
                  <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>Up to {u.maxAttempts} attempts per task.</Typography>
                </CardContent>
              </Card>
            </Grid>
          </Grid>
          <TableContainer component={Card}>
            <Table size="small">
              <TableHead><TableRow>
                <TableCell>Trainee</TableCell>
                {!narrow && u.tasks.map((t) => <TableCell key={t.id} align="center">{t.name}</TableCell>)}
                <TableCell>Unit</TableCell>
                {u.can.record && <TableCell />}
              </TableRow></TableHead>
              <TableBody>
                {u.rows.map((r) => (
                  <TableRow key={r.moduleRegistrationId} hover>
                    <TableCell>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{r.name}</Typography>
                      <Typography variant="caption" color="text.secondary">{r.registrationNo} · {r.programmeCode}</Typography>
                    </TableCell>
                    {!narrow && u.tasks.map((t) => {
                      const c = r.tasks[t.id];
                      return (
                        <TableCell key={t.id} align="center">
                          {c ? (
                            <Tooltip title={`${formatDate(c.assessedOn)} · ${c.assessorName} · evidence: ${c.evidenceRef}${c.comment ? ` · ${c.comment}` : ''}${c.verified ? ' · verified' : ''}`}>
                              <span><OutcomeChip outcome={c.outcome} attempt={c.attempt} /></span>
                            </Tooltip>
                          ) : <Typography variant="body2" color="text.secondary">—</Typography>}
                        </TableCell>
                      );
                    })}
                    <TableCell><OutcomeChip outcome={r.outcome} /></TableCell>
                    {u.can.record && <TableCell align="right"><Button size="small" onClick={() => setAssessing(r)}>Assess</Button></TableCell>}
                  </TableRow>
                ))}
                {!u.rows.length && <TableRow><TableCell colSpan={u.tasks.length + 3}><Typography color="text.secondary" sx={{ py: 1 }}>No trainees registered for this unit.</Typography></TableCell></TableRow>}
              </TableBody>
            </Table>
          </TableContainer>
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>C: competent · NYC: not yet competent · a number after the outcome is the attempt.</Typography>
          {assessing && <AssessDialog unit={u} row={assessing} onClose={() => setAssessing(null)} onDone={() => { setAssessing(null); setNotice(`Outcomes for ${assessing.name} saved.`); refresh(); }} />}
          {editTasks && <TasksDialog unit={u} onClose={() => setEditTasks(false)} onDone={() => { setEditTasks(false); refresh(); }} />}
          {assign && <AssessorsDialog unit={u} onClose={() => setAssign(false)} onDone={() => { setAssign(false); refresh(); }} />}
          {returning && <ReturnDialog id={u.id} onClose={() => setReturning(false)} onDone={() => { setReturning(false); setNotice('Returned to the assessor.'); refresh(); }} />}
        </>
      )}
    </>
  );
}

interface Draft { outcome?: 'COMPETENT' | 'NOT_YET_COMPETENT'; practical: string; theory: string; evidence: string; comment: string; reassess: boolean; touched: boolean }

/** Tablet-friendly entry: every task for one trainee, with outcome, scores, date and the evidence reference. */
function AssessDialog({ unit, row, onClose, onDone }: { unit: UnitView; row: UnitRow; onClose: () => void; onDone: () => void }) {
  const theme = useTheme();
  const phone = !useMediaQuery(theme.breakpoints.up('sm'));
  const [date, setDate] = useState(today());
  const [drafts, setDrafts] = useState<Record<number, Draft>>(() => Object.fromEntries(unit.tasks.map((t) => {
    const c = row.tasks[t.id];
    const editable = c && !c.verified;
    return [t.id, { outcome: editable ? c.outcome : undefined, practical: editable && c.practicalScore != null ? String(c.practicalScore) : '',
      theory: editable && c.theoryScore != null ? String(c.theoryScore) : '', evidence: editable ? c.evidenceRef : '', comment: editable ? c.comment ?? '' : '',
      reassess: false, touched: false }];
  })));
  const set = (taskId: number, patch: Partial<Draft>) => setDrafts((d) => ({ ...d, [taskId]: { ...d[taskId], ...patch, touched: true } }));
  const entries = unit.tasks.filter((t) => drafts[t.id].touched && drafts[t.id].outcome).map((t) => {
    const d = drafts[t.id];
    return { moduleRegistrationId: row.moduleRegistrationId, taskId: t.id, outcome: d.outcome, practicalScore: d.practical === '' ? undefined : Number(d.practical),
      theoryScore: d.theory === '' ? undefined : Number(d.theory), assessedOn: date, evidenceRef: d.evidence.trim(), comment: d.comment.trim() || undefined, reassess: d.reassess };
  });
  const missingEvidence = entries.some((e) => !e.evidenceRef);
  const save = useMutation({ mutationFn: () => api(`/api/competence/units/${unit.id}/assessments`, { ...staff, method: 'PUT', body: { entries } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md" fullScreen={phone}>
      <DialogTitle>{row.name} · {unit.code}</DialogTitle>
      <DialogContent dividers>
        <ErrorAlert error={save.error} />
        <TextField size="small" type="date" label="Date assessed" value={date} onChange={(e) => setDate(e.target.value)} sx={{ mb: 2 }}
          slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: today() } }} />
        <Stack spacing={2}>
          {unit.tasks.map((t) => {
            const c = row.tasks[t.id];
            const d = drafts[t.id];
            const lockedCompetent = c?.verified && c.outcome === 'COMPETENT';
            const newAttempt = c && c.outcome === 'NOT_YET_COMPETENT' && (c.verified || d.reassess);
            const noneLeft = newAttempt && c.attemptsLeft <= 0;
            return (
              <Card key={t.id} variant="outlined">
                <CardContent sx={{ pb: '12px !important' }}>
                  <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
                    <Box>
                      <Typography variant="subtitle2">{t.name}{!t.required && <Typography component="span" variant="caption" color="text.secondary"> · optional</Typography>}</Typography>
                      <Typography variant="caption" color="text.secondary">{KIND_LABEL[t.kind]}{t.evidenceRequired ? ` · evidence: ${t.evidenceRequired}` : ''}</Typography>
                      {c && <Typography variant="caption" display="block" sx={{ mb: 1 }}>Attempt {c.attempt}: {OUTCOME[c.outcome].label} on {formatDate(c.assessedOn)} by {c.assessorName}{c.verified ? ' (verified)' : ''}</Typography>}
                    </Box>
                    {lockedCompetent ? <Chip color="success" label="Verified competent" /> : (
                      <ToggleButtonGroup size="small" exclusive value={d.outcome ?? null} onChange={(_, val) => val && set(t.id, { outcome: val })} aria-label={`Outcome for ${t.name}`}>
                        <ToggleButton value="COMPETENT" color="success" sx={{ px: 2, fontWeight: 700 }}>Competent</ToggleButton>
                        <ToggleButton value="NOT_YET_COMPETENT" color="error" sx={{ px: 2, fontWeight: 700 }}>Not yet</ToggleButton>
                      </ToggleButtonGroup>
                    )}
                  </Stack>
                  {!lockedCompetent && (
                    <Grid container spacing={1.5} sx={{ mt: 0.5 }}>
                      <Grid size={{ xs: 6, sm: 2 }}><TextField fullWidth size="small" label="Practical %" value={d.practical} onChange={(e) => set(t.id, { practical: e.target.value.replace(/[^\d.]/g, '') })} slotProps={{ htmlInput: { inputMode: 'decimal' } }} /></Grid>
                      <Grid size={{ xs: 6, sm: 2 }}><TextField fullWidth size="small" label="Theory %" value={d.theory} onChange={(e) => set(t.id, { theory: e.target.value.replace(/[^\d.]/g, '') })} slotProps={{ htmlInput: { inputMode: 'decimal' } }} /></Grid>
                      <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth size="small" required label="Evidence reference" value={d.evidence} onChange={(e) => set(t.id, { evidence: e.target.value })}
                        error={d.touched && !!d.outcome && !d.evidence.trim()} slotProps={{ htmlInput: { maxLength: 300 } }} /></Grid>
                      <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth size="small" label="Comment" value={d.comment} onChange={(e) => set(t.id, { comment: e.target.value })} slotProps={{ htmlInput: { maxLength: 500 } }} /></Grid>
                      {c && c.outcome === 'NOT_YET_COMPETENT' && !c.verified && (
                        <Grid size={12}><FormControlLabel control={<Checkbox checked={d.reassess} onChange={(e) => set(t.id, { reassess: e.target.checked })} />}
                          label={`This is a re-assessment (attempt ${c.attempt + 1} of ${unit.maxAttempts})`} /></Grid>
                      )}
                      {newAttempt && <Grid size={12}><Typography variant="caption" color={noneLeft ? 'error' : 'text.secondary'}>
                        {noneLeft ? 'All attempts are used; refer the trainee to the Examinations Officer.' : `Saved as attempt ${c.attempt + 1} of ${unit.maxAttempts}.`}</Typography></Grid>}
                    </Grid>
                  )}
                </CardContent>
              </Card>
            );
          })}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={() => save.mutate()} disabled={!entries.length || missingEvidence || save.isPending || date > today()}>Save outcomes</Button>
      </DialogActions>
    </Dialog>
  );
}

function TasksDialog({ unit, onClose, onDone }: { unit: UnitView; onClose: () => void; onDone: () => void }) {
  const [tasks, setTasks] = useState(unit.tasks.map((t) => ({ id: t.id as number | undefined, name: t.name, kind: t.kind, evidenceRequired: t.evidenceRequired ?? '', required: t.required })));
  const save = useMutation({ mutationFn: () => api(`/api/competence/units/${unit.id}/tasks`, { ...staff, method: 'PUT', body: { tasks } }), onSuccess: onDone });
  const set = (i: number, patch: Partial<typeof tasks[number]>) => setTasks((ts) => ts.map((t, j) => (j === i ? { ...t, ...patch } : t)));
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Tasks for {unit.code}</DialogTitle>
      <DialogContent dividers>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>A trainee is competent in the unit when competent in every required task. Tasks that already have outcomes cannot be removed.</Typography>
        <ErrorAlert error={save.error} />
        <Stack spacing={1.5}>
          {tasks.map((t, i) => (
            <Grid container spacing={1} key={t.id ?? `new-${i}`} alignItems="center">
              <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth size="small" label="Task" value={t.name} onChange={(e) => set(i, { name: e.target.value })} /></Grid>
              <Grid size={{ xs: 6, sm: 2 }}><TextField fullWidth size="small" select label="Kind" value={t.kind} onChange={(e) => set(i, { kind: e.target.value as Kind })}>
                {KINDS.map((k) => <MenuItem key={k} value={k}>{KIND_LABEL[k]}</MenuItem>)}</TextField></Grid>
              <Grid size={{ xs: 6, sm: 3 }}><TextField fullWidth size="small" label="Evidence needed" value={t.evidenceRequired} onChange={(e) => set(i, { evidenceRequired: e.target.value })} /></Grid>
              <Grid size={{ xs: 8, sm: 2 }}><FormControlLabel control={<Checkbox checked={t.required} onChange={(e) => set(i, { required: e.target.checked })} />} label="Required" /></Grid>
              <Grid size={{ xs: 4, sm: 1 }}><IconButton aria-label="Remove task" onClick={() => setTasks((ts) => ts.filter((_, j) => j !== i))}><DeleteIcon /></IconButton></Grid>
            </Grid>
          ))}
        </Stack>
        <Button startIcon={<AddIcon />} sx={{ mt: 1 }} onClick={() => setTasks((ts) => [...ts, { id: undefined, name: '', kind: 'PRACTICAL', evidenceRequired: '', required: true }])}>Add task</Button>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending || !tasks.length || tasks.some((t) => !t.name.trim())}>Save tasks</Button></DialogActions>
    </Dialog>
  );
}

function AssessorsDialog({ unit, onClose, onDone }: { unit: UnitView; onClose: () => void; onDone: () => void }) {
  const people = useQuery({ queryKey: ['staff', 'exam-staff'], queryFn: () => api<{ id: number; fullName: string; roles: string[] }[]>('/api/exams/staff', staff) });
  const [chosen, setChosen] = useState<number[]>(unit.assessors.map((a) => a.id));
  const save = useMutation({ mutationFn: () => api(`/api/competence/units/${unit.id}/assessors`, { ...staff, method: 'PUT', body: { userIds: chosen } }), onSuccess: onDone });
  const options = people.data ?? [];
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Assessors for {unit.code}</DialogTitle>
      <DialogContent>
        <Autocomplete multiple sx={{ mt: 1 }} options={options} value={options.filter((o) => chosen.includes(o.id))} getOptionLabel={(o) => o.fullName}
          onChange={(_, val) => setChosen(val.map((x) => x.id))}
          renderInput={(p) => <TextField {...p} label="Assessors" helperText="They record the trainees' outcomes and submit the unit." />} />
        <ErrorAlert error={people.error ?? save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending}>Save</Button></DialogActions>
    </Dialog>
  );
}

function ReturnDialog({ id, onClose, onDone }: { id: number; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('');
  const save = useMutation({ mutationFn: () => api(`/api/competence/units/${id}/return`, { ...staff, method: 'POST', body: { note } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Return to the assessor</DialogTitle>
      <DialogContent>
        <ErrorAlert error={save.error} />
        <TextField fullWidth multiline minRows={3} sx={{ mt: 1 }} label="What needs correcting" value={note} onChange={(e) => setNote(e.target.value)} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" color="warning" onClick={() => save.mutate()} disabled={!note.trim() || save.isPending}>Return</Button></DialogActions>
    </Dialog>
  );
}
