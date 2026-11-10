import AddIcon from '@mui/icons-material/Add';
import DownloadIcon from '@mui/icons-material/Download';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, LinearProgress, MenuItem,
  Stack, Switch, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';

const staff = { realm: 'staff' as const };
const MANAGE = ['INSTITUTION_ADMIN', 'QUALITY_ASSURANCE_OFFICER'];
const READERS = ['INSTITUTION_ADMIN', 'QUALITY_ASSURANCE_OFFICER', 'DEPUTY_PRINCIPAL_ACADEMIC', 'MANAGEMENT_VIEWER', 'AUDITOR', 'HEAD_OF_DEPARTMENT'];

interface Question { id: number; section: string; kind: 'RATING' | 'TEXT'; text: string; sortOrder: number; active: boolean }
interface Round { id: number; semesterId: number; semester: string; name: string; opensOn: string; closesOn: string; minResponses: number; state: 'NOT_YET_OPEN' | 'OPEN' | 'CLOSED'; modules: number; responses: number; expected: number }
interface ModuleSummary { offeringId: number; code: string; title: string; department?: string; lecturers?: string; registered: number; responses: number; responseRate: number; average?: number; enough: boolean }
interface RoundDetail { round: Round; modules: ModuleSummary[]; questions: Question[] }
interface QResult { questionId: number; section: string; kind: string; text: string; average?: number; counts?: number[]; comments: string[] }
interface Report { round: Round; module: ModuleSummary; questions: QResult[]; note?: string }
interface LecturerRound { round: Round; modules: ModuleSummary[] }
interface Semester { id: number; name: string; academicYear?: string; startDate?: string }

const STATE: Record<string, { label: string; color: 'default' | 'success' | 'info' }> = {
  NOT_YET_OPEN: { label: 'Not yet open', color: 'default' }, OPEN: { label: 'Open', color: 'success' }, CLOSED: { label: 'Closed', color: 'info' },
};
const SECTION: Record<string, string> = { MODULE: 'The module', TEACHING: 'The teaching', GENERAL: 'Overall' };

function score(v?: number) {
  if (v == null) return '—';
  return v.toFixed(2);
}

function scoreColour(v?: number) {
  if (v == null) return undefined;
  return v >= 4 ? 'success.main' : v >= 3 ? 'text.primary' : 'error.main';
}

/** Quality assurance: students' evaluation of modules and teaching (FR-QA-01..03). */
export default function QualityPage() {
  const can = useRoles();
  const reader = can(READERS);
  const manager = can(MANAGE);
  const lecturer = can(['LECTURER']);
  const [tab, setTab] = useState(reader ? 'rounds' : 'mine');
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Quality assurance" subtitle="Each semester students evaluate their modules and the teaching. Answers carry no names; results show only when enough students answered." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
        {reader && <Tab value="rounds" label="Evaluations" />}
        {lecturer && <Tab value="mine" label="My modules" />}
        {manager && <Tab value="questions" label="Questionnaire" />}
      </Tabs>
      {tab === 'rounds' && reader && <Rounds manager={manager} />}
      {tab === 'mine' && <MyModules />}
      {tab === 'questions' && manager && <Questions />}
    </Container>
  );
}

function Rounds({ manager }: { manager: boolean }) {
  const navigate = useNavigate();
  const [adding, setAdding] = useState(false);
  const q = useQuery({ queryKey: ['staff', 'qa', 'rounds'], queryFn: () => api<Round[]>('/api/qa/rounds', staff) });
  return (
    <Card><CardContent>
      {manager && <Button variant="contained" startIcon={<AddIcon />} sx={{ mb: 2 }} onClick={() => setAdding(true)}>Open an evaluation</Button>}
      <ErrorAlert error={q.error} />
      {q.isLoading && <Loading />}
      {q.data?.length === 0 && <Typography color="text.secondary">No evaluation yet. Open one near the end of each semester.</Typography>}
      {!!q.data?.length && (
        <Table size="small">
          <TableHead><TableRow><TableCell>Evaluation</TableCell><TableCell>Dates</TableCell><TableCell>State</TableCell><TableCell>Answers</TableCell></TableRow></TableHead>
          <TableBody>
            {q.data.map((r) => (
              <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/quality/rounds/${r.id}`)}>
                <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{r.name}</Typography><Typography variant="caption" color="text.secondary">{r.semester}</Typography></TableCell>
                <TableCell>{formatDate(r.opensOn)} – {formatDate(r.closesOn)}</TableCell>
                <TableCell><Chip size="small" color={STATE[r.state].color} label={STATE[r.state].label} /></TableCell>
                <TableCell sx={{ minWidth: 160 }}>
                  <Typography variant="caption">{r.responses} of {r.expected}</Typography>
                  <LinearProgress variant="determinate" value={r.expected ? Math.min(100, (r.responses * 100) / r.expected) : 0} />
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {adding && <RoundDialog onClose={() => setAdding(false)} />}
    </CardContent></Card>
  );
}

function RoundDialog({ round, onClose }: { round?: Round; onClose: () => void }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const semesters = useQuery({ queryKey: ['staff', 'semesters'], enabled: !round, queryFn: () => api<Semester[]>('/api/academic/semesters', staff) });
  const today = new Date().toISOString().slice(0, 10);
  const [f, setF] = useState({ semesterId: round?.semesterId ?? '', name: round?.name ?? '', opensOn: round?.opensOn ?? today, closesOn: round?.closesOn ?? '',
    minResponses: round?.minResponses ?? 5, tellStudents: true });
  const save = useMutation({
    mutationFn: () => api<RoundDetail>(round ? `/api/qa/rounds/${round.id}` : '/api/qa/rounds', { ...staff, method: round ? 'PUT' : 'POST', body: f }),
    onSuccess: (d) => { qc.invalidateQueries({ queryKey: ['staff', 'qa'] }); onClose(); if (!round) navigate(`/staff/quality/rounds/${d.round.id}`); },
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{round ? 'Change the evaluation' : 'Open an evaluation'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {!round && (
            <TextField select label="Semester" value={f.semesterId} onChange={(e) => setF({ ...f, semesterId: Number(e.target.value) })}>
              {(semesters.data ?? []).map((s) => <MenuItem key={s.id} value={s.id}>{s.name}{s.academicYear ? ` · ${s.academicYear}` : ''}</MenuItem>)}
            </TextField>
          )}
          <TextField label="Name (optional)" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="Module evaluation, Semester 1" />
          <Stack direction="row" spacing={1}>
            <TextField fullWidth type="date" label="Opens" value={f.opensOn} disabled={!!round && round.responses > 0} onChange={(e) => setF({ ...f, opensOn: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
            <TextField fullWidth type="date" label="Closes" value={f.closesOn} onChange={(e) => setF({ ...f, closesOn: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
          </Stack>
          <TextField type="number" label="Fewest answers before a module's results are shown" helperText="At least 3, so that no student can be identified" value={f.minResponses}
            onChange={(e) => setF({ ...f, minResponses: Number(e.target.value) })} />
          <FormControlLabel control={<Checkbox checked={f.tellStudents} onChange={(e) => setF({ ...f, tellStudents: e.target.checked })} />} label="Tell the registered students by SMS (when it is open)" />
        </Stack>
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={save.isPending || (!round && !f.semesterId) || !f.closesOn} onClick={() => save.mutate()}>{round ? 'Save' : 'Open'}</Button></DialogActions>
    </Dialog>
  );
}

export function QualityRoundPage() {
  const { id } = useParams();
  const manager = useRoles()(MANAGE);
  const q = useQuery({ queryKey: ['staff', 'qa', 'round', id], queryFn: () => api<RoundDetail>(`/api/qa/rounds/${id}`, staff) });
  const [open, setOpen] = useState<number | null>(null);
  const [editing, setEditing] = useState(false);
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} />;
  const { round, modules } = q.data;
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title={round.name} subtitle={`${round.semester} · ${formatDate(round.opensOn)} to ${formatDate(round.closesOn)} · results shown from ${round.minResponses} answers`}
        action={<Chip color={STATE[round.state].color} label={STATE[round.state].label} />} />
      <Stack direction="row" spacing={1} sx={{ mb: 2 }}>
        <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/qa/rounds/${id}/workbook`, 'staff', `module-evaluation-${id}.xlsx`)}>Excel</Button>
        {manager && <Button onClick={() => setEditing(true)}>Change dates</Button>}
      </Stack>
      <Card><CardContent>
        <TableContainer>
          <Table size="small" sx={{ minWidth: 760 }}>
            <TableHead><TableRow><TableCell>Module</TableCell><TableCell>Lecturers</TableCell><TableCell align="right">Answered</TableCell><TableCell align="right">Average (1–5)</TableCell></TableRow></TableHead>
            <TableBody>
              {modules.map((m) => (
                <TableRow key={m.offeringId} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(m.offeringId)}>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{m.code} · {m.title}</Typography><Typography variant="caption" color="text.secondary">{m.department}</Typography></TableCell>
                  <TableCell>{m.lecturers ?? '—'}</TableCell>
                  <TableCell align="right">{m.responses} of {m.registered} ({m.responseRate}%)</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700, color: scoreColour(m.average) }}>{m.enough ? score(m.average) : <Typography variant="caption" color="text.secondary">Too few answers</Typography>}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </CardContent></Card>
      {open !== null && <ReportDialog roundId={round.id} offeringId={open} onClose={() => setOpen(null)} />}
      {editing && <RoundDialog round={round} onClose={() => setEditing(false)} />}
    </Container>
  );
}

function ReportDialog({ roundId, offeringId, onClose }: { roundId: number; offeringId: number; onClose: () => void }) {
  const q = useQuery({ queryKey: ['staff', 'qa', 'report', roundId, offeringId], queryFn: () => api<Report>(`/api/qa/rounds/${roundId}/offerings/${offeringId}`, staff) });
  const r = q.data;
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{r ? `${r.module.code} · ${r.module.title}` : 'Results'}</DialogTitle>
      <DialogContent>
        {q.isLoading && <Loading />}
        <ErrorAlert error={q.error} />
        {r && (
          <>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
              {r.module.lecturers ? `${r.module.lecturers} · ` : ''}{r.module.responses} of {r.module.registered} students answered
            </Typography>
            {r.note && <Alert severity="info" sx={{ mb: 2 }}>{r.note}</Alert>}
            {['MODULE', 'TEACHING', 'GENERAL'].map((sec) => {
              const qs = r.questions.filter((x) => x.section === sec);
              if (!qs.length) return null;
              return (
                <Box key={sec} sx={{ mb: 2 }}>
                  <Typography variant="subtitle1">{SECTION[sec]}</Typography>
                  {qs.map((x) => x.kind === 'RATING' ? (
                    <Stack key={x.questionId} direction="row" spacing={2} alignItems="center" sx={{ py: 0.75, borderBottom: '1px solid', borderColor: 'divider' }}>
                      <Typography variant="body2" sx={{ flexGrow: 1 }}>{x.text}</Typography>
                      <Box sx={{ display: 'flex', gap: 0.25, alignItems: 'flex-end', height: 28 }} aria-label={`Answers 1 to 5: ${x.counts?.join(', ')}`}>
                        {(x.counts ?? []).map((c, i) => {
                          const max = Math.max(1, ...(x.counts ?? [1]));
                          return <Box key={i} title={`${i + 1}: ${c}`} sx={{ width: 8, height: `${Math.max(2, (c / max) * 28)}px`, bgcolor: i >= 3 ? 'success.light' : i === 2 ? 'grey.400' : 'error.light', borderRadius: 0.5 }} />;
                        })}
                      </Box>
                      <Typography sx={{ width: 48, textAlign: 'right', fontWeight: 700, color: scoreColour(x.average) }}>{score(x.average)}</Typography>
                    </Stack>
                  ) : (
                    <Box key={x.questionId} sx={{ mt: 1 }}>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{x.text}</Typography>
                      {x.comments.length === 0 ? <Typography variant="caption" color="text.secondary">No comments shown.</Typography>
                        : x.comments.map((c, i) => <Typography key={i} variant="body2" sx={{ pl: 1.5, borderLeft: '3px solid', borderColor: 'divider', my: 0.75 }}>{c}</Typography>)}
                    </Box>
                  ))}
                </Box>
              );
            })}
          </>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
    </Dialog>
  );
}

function MyModules() {
  const q = useQuery({ queryKey: ['staff', 'qa', 'mine'], queryFn: () => api<LecturerRound[]>('/api/qa/my-modules', staff) });
  const [open, setOpen] = useState<{ round: number; offering: number } | null>(null);
  return (
    <Stack spacing={2}>
      {q.isLoading && <Loading />}
      <ErrorAlert error={q.error} />
      {q.data?.length === 0 && <Typography color="text.secondary">None of your modules has been evaluated yet.</Typography>}
      {q.data?.map((lr) => (
        <Card key={lr.round.id}><CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="center"><Typography variant="subtitle1">{lr.round.name}</Typography>
            <Chip size="small" color={STATE[lr.round.state].color} label={STATE[lr.round.state].label} /></Stack>
          {lr.round.state !== 'CLOSED' && <Typography variant="caption" color="text.secondary">Your results show when the evaluation closes on {formatDate(lr.round.closesOn)}.</Typography>}
          <Table size="small" sx={{ mt: 1 }}>
            <TableBody>
              {lr.modules.map((m) => (
                <TableRow key={m.offeringId} hover={lr.round.state === 'CLOSED'} sx={{ cursor: lr.round.state === 'CLOSED' ? 'pointer' : 'default' }}
                  onClick={() => lr.round.state === 'CLOSED' && setOpen({ round: lr.round.id, offering: m.offeringId })}>
                  <TableCell>{m.code} · {m.title}</TableCell>
                  <TableCell align="right">{m.responses} of {m.registered} answered</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700, color: scoreColour(m.average) }}>{lr.round.state === 'CLOSED' ? (m.enough ? score(m.average) : 'Too few answers') : ''}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent></Card>
      ))}
      {open && <ReportDialog roundId={open.round} offeringId={open.offering} onClose={() => setOpen(null)} />}
    </Stack>
  );
}

function Questions() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'qa', 'questions'], queryFn: () => api<Question[]>('/api/qa/questions', staff) });
  const [edit, setEdit] = useState<Partial<Question> | null>(null);
  const save = useMutation({
    mutationFn: (x: Partial<Question>) => api<Question[]>(x.id ? `/api/qa/questions/${x.id}` : '/api/qa/questions', { ...staff, method: x.id ? 'PUT' : 'POST', body: x }),
    onSuccess: (d) => { qc.setQueryData(['staff', 'qa', 'questions'], d); setEdit(null); },
  });
  return (
    <Card><CardContent>
      <Alert severity="info" sx={{ mb: 2 }}>Changes apply to evaluations opened afterwards; an open evaluation keeps the questions it started with.</Alert>
      <Button startIcon={<AddIcon />} sx={{ mb: 1 }} onClick={() => setEdit({ section: 'MODULE', kind: 'RATING', active: true })}>Add a question</Button>
      <ErrorAlert error={q.error ?? save.error} />
      <Table size="small">
        <TableBody>
          {q.data?.map((x) => (
            <TableRow key={x.id} hover sx={{ cursor: 'pointer', opacity: x.active ? 1 : 0.5 }} onClick={() => setEdit(x)}>
              <TableCell sx={{ width: 120 }}>{SECTION[x.section]}</TableCell>
              <TableCell>{x.text}</TableCell>
              <TableCell sx={{ width: 120 }}>{x.kind === 'RATING' ? 'Scale 1–5' : 'Written'}{!x.active ? ' · off' : ''}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {edit && (
        <Dialog open onClose={() => setEdit(null)} fullWidth maxWidth="sm">
          <DialogTitle>{edit.id ? 'Change the question' : 'Add a question'}</DialogTitle>
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              <TextField select label="Section" value={edit.section} onChange={(e) => setEdit({ ...edit, section: e.target.value })}>
                {Object.entries(SECTION).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField>
              {!edit.id && <TextField select label="Answer" value={edit.kind} onChange={(e) => setEdit({ ...edit, kind: e.target.value as Question['kind'] })}>
                <MenuItem value="RATING">Scale 1 (strongly disagree) to 5 (strongly agree)</MenuItem><MenuItem value="TEXT">Written comment</MenuItem></TextField>}
              <TextField label="Question" multiline value={edit.text ?? ''} onChange={(e) => setEdit({ ...edit, text: e.target.value })} inputProps={{ maxLength: 300 }} />
              <TextField type="number" label="Order" value={edit.sortOrder ?? ''} onChange={(e) => setEdit({ ...edit, sortOrder: Number(e.target.value) })} />
              {edit.id && <FormControlLabel control={<Switch checked={edit.active ?? true} onChange={(e) => setEdit({ ...edit, active: e.target.checked })} />} label="Asked" />}
            </Stack>
          </DialogContent>
          <DialogActions><Button onClick={() => setEdit(null)}>Cancel</Button>
            <Button variant="contained" disabled={!edit.text?.trim() || save.isPending} onClick={() => save.mutate(edit)}>Save</Button></DialogActions>
        </Dialog>
      )}
    </CardContent></Card>
  );
}
