import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ErrorIcon from '@mui/icons-material/Error';
import InfoIcon from '@mui/icons-material/Info';
import WarningIcon from '@mui/icons-material/Warning';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Divider, LinearProgress, MenuItem, Stack, Tab,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { tr } from '../../locale/ui';
import { api, formatDate, formatDateTime, formatTzs } from '../../api/client';
import { ErrorAlert, Loading, PageTitle, LinkRow } from '../../components/common';

const staff = { realm: 'staff' as const };

// ============================================================ Examinations Office board

interface Board {
  semesters: { id: number; name: string; academicYear: string; startDate: string; endDate: string }[];
  semester?: { id: number; name: string; academicYear: string };
  marksWindow?: { opens?: string; closes?: string; state: 'NOT_SET' | 'UPCOMING' | 'OPEN' | 'CLOSED' };
  modules?: { total: number; draft: number; submitted: number; hodApproved: number; boardApproved: number; withoutLecturer: number;
    behind: { offeringId: number; code: string; title: string; department?: string; status: string; lecturers?: string; students: number; since?: string }[] };
  eligibility?: { modulesGenerated: number; modules: number; students: number; ineligible: number; pendingExemptions: number };
  sheets?: { classes: number; computed: number; recommended: number; confirmed: number; published: number; classesWithoutSheet: string[];
    sheets: { id: number; programme: string; yearOfStudy: number; round: string; status: string; students: number; since?: string; nactvetSubmitted: boolean; nactvetStatus?: string }[] };
  resits?: { open: boolean; registrationCloses?: string; registered: number; unpaid: number; marksSubmitted: number; marksApproved: number };
  appeals?: { requested: number; awaitingPayment: number; withExaminer: number; toForward: number; withAdministrator: number; closed: number };
  amendmentsPending: number;
  next: { severity: 'success' | 'info' | 'warning' | 'error'; text: string; link?: string }[];
}

const SEV_ICON = { success: <CheckCircleIcon color="success" />, info: <InfoIcon color="info" />, warning: <WarningIcon color="warning" />, error: <ErrorIcon color="error" /> };
const WINDOW_LABEL = { NOT_SET: 'Not set', UPCOMING: 'Not open yet', OPEN: 'Open', CLOSED: 'Closed' } as const;
const SHEET_LABEL: Record<string, string> = { COMPUTED: 'Computed', RECOMMENDED: 'Recommended', CONFIRMED: 'Confirmed', PUBLISHED: 'Published' };

/** Where the semester's examinations stand, step by step, and what to do next (Examinations Officer). */
export function ExamsOfficePage() {
  const [semesterId, setSemesterId] = useState<number | ''>('');
  const q = useQuery({ queryKey: ['staff', 'exams-office', semesterId], refetchInterval: 60_000,
    queryFn: () => api<Board>(`/api/exams/office${semesterId ? `?semesterId=${semesterId}` : ''}`, staff) });
  const b = q.data;
  return (
    <>
      <PageTitle title="Examinations office" subtitle="Where this semester's examinations stand: marks, moderation, eligibility, result sheets, publication, resits, remarks and NACTVET."
        action={b?.semesters.length ? (
          <TextField select size="small" label="Semester" value={semesterId || b.semester?.id || ''} onChange={(e) => setSemesterId(Number(e.target.value))} sx={{ minWidth: 240 }}>
            {b.semesters.map((s) => <MenuItem key={s.id} value={s.id}>{s.name} · {s.academicYear}</MenuItem>)}
          </TextField>
        ) : undefined} />
      <ErrorAlert error={q.error} />
      {q.isLoading && <Loading />}
      {b && (
        <Grid container spacing={2.5}>
          <Grid size={{ xs: 12, lg: 5 }}>
            <Card sx={{ mb: 2.5 }}>
              <CardContent>
                <Typography variant="h6" sx={{ mb: 1 }}>Next</Typography>
                <Stack divider={<Divider flexItem />}>
                  {b.next.map((s, i) => (
                    <Stack key={i} direction="row" spacing={1.5} alignItems="center" sx={{ py: 1 }}>
                      {SEV_ICON[s.severity]}
                      <Typography variant="body2" sx={{ flex: 1 }}>{s.text}</Typography>
                      {s.link && <Button size="small" component={RouterLink} to={s.link} endIcon={<ArrowForwardIcon />}>Open</Button>}
                    </Stack>
                  ))}
                </Stack>
              </CardContent>
            </Card>
            {b.appeals && (
              <Card sx={{ mb: 2.5 }}>
                <CardContent>
                  <Stack direction="row" justifyContent="space-between" alignItems="center"><Typography variant="h6">Remarks and appeals</Typography>
                    <Button size="small" component={RouterLink} to="/staff/appeals">Open</Button></Stack>
                  <Figures items={[['Need an examiner', b.appeals.requested], ['Awaiting payment', b.appeals.awaitingPayment], ['With an examiner', b.appeals.withExaminer],
                    ['To forward', b.appeals.toForward], ['With the administrator', b.appeals.withAdministrator], ['Decided', b.appeals.closed]]} />
                  {b.amendmentsPending > 0 && <Typography variant="body2" sx={{ mt: 1 }}>{b.amendmentsPending} result amendment(s) wait for the administrator. <RouterLink to="/staff/amendments">Amendments</RouterLink></Typography>}
                </CardContent>
              </Card>
            )}
            {b.resits && (
              <Card>
                <CardContent>
                  <Typography variant="h6" sx={{ mb: 1 }}>Supplementary and special examinations</Typography>
                  {b.resits.open ? (
                    <>
                      <Typography variant="body2" color="text.secondary">Registration {b.resits.registrationCloses ? `closes ${formatDate(b.resits.registrationCloses)}` : 'open'}</Typography>
                      <Figures items={[['Registered', b.resits.registered], ['Not paid', b.resits.unpaid], ['Marks submitted', b.resits.marksSubmitted], ['Marks approved', b.resits.marksApproved]]} />
                    </>
                  ) : <Typography variant="body2" color="text.secondary">No session opened for this semester.</Typography>}
                </CardContent>
              </Card>
            )}
          </Grid>
          <Grid size={{ xs: 12, lg: 7 }}>
            {b.modules && b.marksWindow && (
              <Card sx={{ mb: 2.5 }}>
                <CardContent>
                  <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
                    <Typography variant="h6">Marks and moderation</Typography>
                    <Chip size="small" color={b.marksWindow.state === 'OPEN' ? 'success' : b.marksWindow.state === 'NOT_SET' ? 'warning' : 'default'}
                      label={`${tr('Marks entry')}: ${tr(WINDOW_LABEL[b.marksWindow.state], 'label')}${b.marksWindow.opens ? ` · ${formatDate(b.marksWindow.opens)} – ${formatDate(b.marksWindow.closes)}` : ''}`} />
                  </Stack>
                  <Pipeline total={b.modules.total} steps={[['In marks entry', b.modules.draft], ['With Head of Department', b.modules.submitted],
                    ['With Department Board', b.modules.hodApproved], ['Approved', b.modules.boardApproved]]} />
                  {b.modules.behind.length > 0 && (
                    <TableContainer sx={{ mt: 2 }}>
                      <Table size="small">
                        <TableHead><TableRow><TableCell>Module</TableCell><TableCell>Lecturer(s)</TableCell><TableCell>Stage</TableCell><TableCell align="right">Students</TableCell></TableRow></TableHead>
                        <TableBody>
                          {b.modules.behind.map((m) => (
                            <LinkRow key={m.offeringId} to={`/staff/marks/${m.offeringId}`}>
                              <TableCell><Typography variant="body2" fontWeight={600}>{m.code}</Typography><Typography variant="caption" color="text.secondary">{m.title}{m.department ? ` · ${m.department}` : ''}</Typography></TableCell>
                              <TableCell>{m.lecturers ?? <Typography variant="body2" color="warning.main">None allocated</Typography>}</TableCell>
                              <TableCell>{m.status === 'SUBMITTED' ? 'With Head of Department' : 'Marks entry'}</TableCell>
                              <TableCell align="right">{m.students}</TableCell>
                            </LinkRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  )}
                </CardContent>
              </Card>
            )}
            {b.eligibility && (
              <Card sx={{ mb: 2.5 }}>
                <CardContent>
                  <Stack direction="row" justifyContent="space-between" alignItems="center"><Typography variant="h6">Examination eligibility</Typography>
                    <Button size="small" component={RouterLink} to="/staff/eligibility">Open</Button></Stack>
                  <Figures items={[['Modules with a list', `${b.eligibility.modulesGenerated} of ${b.eligibility.modules}`], ['Students', b.eligibility.students],
                    ['Not eligible (module entries)', b.eligibility.ineligible], ['Exemptions to decide', b.eligibility.pendingExemptions]]} />
                </CardContent>
              </Card>
            )}
            {b.sheets && (
              <Card>
                <CardContent>
                  <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}><Typography variant="h6">Result sheets</Typography>
                    <Button size="small" component={RouterLink} to="/staff/results">Open</Button></Stack>
                  <Pipeline total={b.sheets.classes} steps={[['Not computed', Math.max(0, b.sheets.classes - b.sheets.computed - b.sheets.recommended - b.sheets.confirmed - b.sheets.published)],
                    ['Computed', b.sheets.computed], ['Recommended', b.sheets.recommended], ['Confirmed', b.sheets.confirmed], ['Published', b.sheets.published]]} unit="classes" />
                  {b.sheets.sheets.length > 0 && (
                    <TableContainer sx={{ mt: 2 }}>
                      <Table size="small">
                        <TableHead><TableRow><TableCell>Class</TableCell><TableCell>Round</TableCell><TableCell>Status</TableCell><TableCell align="right">Students</TableCell><TableCell>NACTVET</TableCell></TableRow></TableHead>
                        <TableBody>
                          {b.sheets.sheets.map((s) => (
                            <LinkRow key={s.id} to={`/staff/results/${s.id}`}>
                              <TableCell>{s.programme} year {s.yearOfStudy}</TableCell>
                              <TableCell>{s.round === 'MAIN' ? 'Main' : 'Resit'}</TableCell>
                              <TableCell><Chip size="small" color={s.status === 'PUBLISHED' ? 'success' : s.status === 'CONFIRMED' ? 'info' : 'default'} label={SHEET_LABEL[s.status] ?? s.status} />
                                {s.since && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{formatDateTime(s.since)}</Typography>}</TableCell>
                              <TableCell align="right">{s.students}</TableCell>
                              <TableCell>{s.nactvetStatus ? <Chip size="small" variant="outlined" color={s.nactvetStatus === 'QUERIED' ? 'error' : 'success'} label={s.nactvetStatus.toLowerCase()} />
                                : (s.status === 'CONFIRMED' || s.status === 'PUBLISHED') ? <Typography variant="body2" color="warning.main">Not submitted</Typography> : '—'}</TableCell>
                            </LinkRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableContainer>
                  )}
                  {b.sheets.classesWithoutSheet.length > 0 && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>No sheet yet: {b.sheets.classesWithoutSheet.join(', ')}</Typography>}
                </CardContent>
              </Card>
            )}
          </Grid>
        </Grid>
      )}
    </>
  );
}

function Figures({ items }: { items: [string, number | string][] }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(140px, 1fr))', gap: 1.5, mt: 1 }}>
      {items.map(([label, value]) => (
        <Box key={label}>
          <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 22 }}>{value}</Typography>
          <Typography variant="caption" color="text.secondary">{label}</Typography>
        </Box>
      ))}
    </Box>
  );
}

/** A row of stages with counts, and a bar showing how far through they are. */
function Pipeline({ total, steps, unit = 'modules' }: { total: number; steps: [string, number][]; unit?: string }) {
  const done = steps[steps.length - 1][1];
  return (
    <Box>
      <Stack direction="row" justifyContent="space-between"><Typography variant="body2" color="text.secondary">{`${done} of ${total} ${unit} through`}</Typography></Stack>
      <LinearProgress variant="determinate" value={total ? (done / total) * 100 : 0} sx={{ height: 8, borderRadius: 4, my: 1 }} />
      <Box sx={{ display: 'grid', gridTemplateColumns: `repeat(${steps.length}, 1fr)`, gap: 1 }}>
        {steps.map(([label, value]) => (
          <Box key={label} sx={{ p: 1, borderRadius: 1.5, bgcolor: value ? 'action.hover' : 'transparent', border: 1, borderColor: 'divider' }}>
            <Typography sx={{ fontWeight: 700, fontSize: 20 }}>{value}</Typography>
            <Typography variant="caption" color="text.secondary">{label}</Typography>
          </Box>
        ))}
      </Box>
    </Box>
  );
}

// ============================================================ remarks and appeals

interface Appeal {
  id: number; registrationNo: string; studentName: string; programmeCode: string; moduleCode: string; moduleTitle: string; semester: string;
  kind: 'REMARK' | 'APPEAL'; reason: string; fee: number; paid: boolean; invoiceNo?: string; status: string; outcome?: string;
  originalCaTotal?: number; originalSeTotal?: number; originalTotal?: number; originalGrade?: string; examinerName?: string; assignedByName?: string;
  assignedAt?: string; newCaTotal?: number; newSeTotal?: number; newTotal?: number; newGrade?: string; examinerComment?: string; markedAt?: string;
  amendmentId?: number; decisionNote?: string; decidedByName?: string; closedAt?: string; requestedAt: string;
  canAssign: boolean; canMark: boolean; canForward: boolean; canReject: boolean; caWeight: number; seWeight: number;
}

const STATUS_LABEL: Record<string, string> = {
  REQUESTED: 'Needs an examiner', ASSIGNED: 'With examiner', MARKED: 'To forward', AMENDMENT_PENDING: 'Amendment with administrator',
  CLOSED: 'Decided', REJECTED: 'Declined', WITHDRAWN: 'Withdrawn',
};
const STATUS_COLOUR: Record<string, 'default' | 'warning' | 'info' | 'success' | 'error'> = {
  REQUESTED: 'warning', ASSIGNED: 'info', MARKED: 'warning', AMENDMENT_PENDING: 'info', CLOSED: 'success', REJECTED: 'error', WITHDRAWN: 'default',
};
const n1 = (v?: number) => (v == null ? '—' : Number(v).toFixed(1).replace(/\.0$/, ''));

/** Remarks and appeals of published results (BP-11): the Examinations Office assigns examiners and forwards outcomes; examiners mark. */
export function AppealsPage() {
  const qc = useQueryClient();
  const [tab, setTab] = useState<'open' | 'all'>('open');
  const q = useQuery({ queryKey: ['staff', 'appeals', tab], queryFn: () => api<Appeal[]>(`/api/exams/appeals?open=${tab === 'open'}`, staff) });
  const [dialog, setDialog] = useState<{ a: Appeal; action: 'assign' | 'mark' | 'forward' | 'reject' } | null>(null);
  const done = () => { setDialog(null); qc.invalidateQueries({ queryKey: ['staff', 'appeals'] }); qc.invalidateQueries({ queryKey: ['staff', 'approvals'] }); qc.invalidateQueries({ queryKey: ['staff', 'exams-office'] }); };
  const rows = q.data ?? [];
  return (
    <>
      <PageTitle title="Remarks and appeals"
        subtitle="Students ask within the appeal period after results are published. The Examinations Office assigns an examiner who did not teach the module; the examiner records new marks; a changed result goes to the administrator as an amendment." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}><Tab value="open" label="In progress" /><Tab value="all" label="All recent" /></Tabs>
      <ErrorAlert error={q.error} />
      {q.isLoading ? <Loading /> : rows.length === 0 ? (
        <Card><CardContent><Typography color="text.secondary">{tab === 'open' ? 'No remark or appeal is in progress.' : 'No remark or appeal yet.'}</Typography></CardContent></Card>
      ) : (
        <Card>
          <TableContainer>
            <Table size="small">
              <TableHead><TableRow><TableCell>Student</TableCell><TableCell>Module</TableCell><TableCell>Request</TableCell><TableCell>Marks</TableCell><TableCell>Progress</TableCell><TableCell align="right" /></TableRow></TableHead>
              <TableBody>
                {rows.map((a) => (
                  <TableRow key={a.id} sx={{ verticalAlign: 'top' }}>
                    <TableCell><Typography variant="body2" fontWeight={600}>{a.studentName}</Typography>
                      <Typography variant="caption" sx={{ fontFamily: 'monospace' }}>{a.registrationNo}</Typography>
                      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{a.programmeCode}</Typography></TableCell>
                    <TableCell><Typography variant="body2" fontWeight={600}>{a.moduleCode}</Typography><Typography variant="caption" color="text.secondary">{a.moduleTitle} · {a.semester}</Typography></TableCell>
                    <TableCell sx={{ maxWidth: 260 }}>
                      <Typography variant="body2">{a.kind === 'REMARK' ? 'Remark of the examination' : 'Appeal'} · {formatDate(a.requestedAt)}</Typography>
                      <Typography variant="caption" color="text.secondary">{a.reason}</Typography>
                      {Number(a.fee) > 0 && <Typography variant="caption" sx={{ display: 'block' }} color={a.paid ? 'success.main' : 'warning.main'}>
                        {formatTzs(a.fee)} {a.paid ? 'paid' : `not paid${a.invoiceNo ? ` (${a.invoiceNo})` : ''}`}</Typography>}
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      <Typography variant="body2">Was {n1(a.originalTotal)} ({a.originalGrade ?? '—'})</Typography>
                      {a.newTotal != null && <Typography variant="body2" fontWeight={600}>Now {n1(a.newTotal)} ({a.newGrade})</Typography>}
                      <Typography variant="caption" color="text.secondary">CA {n1(a.originalCaTotal)}/{n1(a.caWeight)} · SE {n1(a.originalSeTotal)}/{n1(a.seWeight)}</Typography>
                    </TableCell>
                    <TableCell sx={{ minWidth: 200 }}>
                      <Chip size="small" color={STATUS_COLOUR[a.status]} label={a.outcome ? `${STATUS_LABEL[a.status]}: ${a.outcome === 'CHANGED' ? 'changed' : 'unchanged'}` : STATUS_LABEL[a.status]} />
                      {a.examinerName && <Typography variant="caption" sx={{ display: 'block', mt: 0.5 }} color="text.secondary">Examiner: {a.examinerName}{a.assignedAt ? ` (${formatDate(a.assignedAt)})` : ''}</Typography>}
                      {a.examinerComment && <Typography variant="caption" sx={{ display: 'block' }} color="text.secondary">“{a.examinerComment}”</Typography>}
                      {a.decisionNote && <Typography variant="caption" sx={{ display: 'block' }} color="text.secondary">Note: {a.decisionNote}</Typography>}
                    </TableCell>
                    <TableCell align="right">
                      <Stack spacing={0.5} alignItems="flex-end">
                        {a.canAssign && <Button size="small" variant="contained" onClick={() => setDialog({ a, action: 'assign' })}>{a.status === 'ASSIGNED' ? 'Reassign' : 'Assign examiner'}</Button>}
                        {a.canMark && <Button size="small" variant="contained" onClick={() => setDialog({ a, action: 'mark' })}>Record marks</Button>}
                        {a.canForward && <Button size="small" variant="contained" onClick={() => setDialog({ a, action: 'forward' })}>Forward outcome</Button>}
                        {a.canReject && <Button size="small" color="error" onClick={() => setDialog({ a, action: 'reject' })}>Decline</Button>}
                        {a.amendmentId && a.status === 'AMENDMENT_PENDING' && <Button size="small" component={RouterLink} to="/staff/amendments">Amendment</Button>}
                      </Stack>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Card>
      )}
      {dialog?.action === 'assign' && <AssignDialog a={dialog.a} onClose={() => setDialog(null)} onDone={done} />}
      {dialog?.action === 'mark' && <MarkDialog a={dialog.a} onClose={() => setDialog(null)} onDone={done} />}
      {(dialog?.action === 'forward' || dialog?.action === 'reject') && <NoteDialog a={dialog.a} action={dialog.action} onClose={() => setDialog(null)} onDone={done} />}
    </>
  );
}

function AssignDialog({ a, onClose, onDone }: { a: Appeal; onClose: () => void; onDone: () => void }) {
  const options = useQuery({ queryKey: ['staff', 'appeal-examiners', a.id], queryFn: () => api<{ id: number; name: string; roles: string[] }[]>(`/api/exams/appeals/${a.id}/examiners`, staff) });
  const [examiner, setExaminer] = useState<number | ''>('');
  const m = useMutation({ mutationFn: () => api(`/api/exams/appeals/${a.id}/assign`, { ...staff, method: 'POST', body: { examinerUserId: examiner } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Assign an examiner</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{a.moduleCode} · {a.registrationNo}. The module's own lecturers are not listed (conflict of interest).</Typography>
        <TextField select fullWidth label="Examiner" value={examiner} onChange={(e) => setExaminer(Number(e.target.value))}>
          {(options.data ?? []).map((o) => <MenuItem key={o.id} value={o.id}>{o.name}</MenuItem>)}
        </TextField>
        <ErrorAlert error={options.error ?? m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!examiner || m.isPending} onClick={() => m.mutate()}>Assign</Button></DialogActions>
    </Dialog>
  );
}

function MarkDialog({ a, onClose, onDone }: { a: Appeal; onClose: () => void; onDone: () => void }) {
  const remark = a.kind === 'REMARK';
  const [ca, setCa] = useState(String(a.originalCaTotal ?? ''));
  const [se, setSe] = useState('');
  const [comment, setComment] = useState('');
  const m = useMutation({ mutationFn: () => api(`/api/exams/appeals/${a.id}/marks`, { ...staff, method: 'POST', body: { caTotal: remark ? null : Number(ca), seTotal: Number(se), comment } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Record the {remark ? 're-marked examination' : 'appeal outcome'}</DialogTitle>
      <DialogContent>
        <Alert severity="info" sx={{ mb: 2 }}>{a.moduleCode} · {a.moduleTitle}. Recorded marks: CA {n1(a.originalCaTotal)} of {n1(a.caWeight)}, SE {n1(a.originalSeTotal)} of {n1(a.seWeight)},
          total {n1(a.originalTotal)} ({a.originalGrade}). The student's request: “{a.reason}”</Alert>
        <Stack direction="row" spacing={2}>
          <TextField label={`CA (of ${n1(a.caWeight)})`} type="number" value={ca} onChange={(e) => setCa(e.target.value)} disabled={remark}
            helperText={remark ? 'A remark keeps the coursework' : undefined} />
          <TextField label={`SE (of ${n1(a.seWeight)})`} type="number" value={se} onChange={(e) => setSe(e.target.value)} required autoFocus />
        </Stack>
        <TextField label="Comment on the marking" required fullWidth multiline minRows={2} sx={{ mt: 2 }} value={comment} onChange={(e) => setComment(e.target.value)} inputProps={{ maxLength: 1000 }} />
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={se === '' || !comment.trim() || m.isPending} onClick={() => m.mutate()}>Save marks</Button></DialogActions>
    </Dialog>
  );
}

function NoteDialog({ a, action, onClose, onDone }: { a: Appeal; action: 'forward' | 'reject'; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('');
  const changed = a.newTotal != null && (Number(a.newTotal) !== Number(a.originalTotal) || a.newGrade !== a.originalGrade);
  const m = useMutation({ mutationFn: () => api(`/api/exams/appeals/${a.id}/${action}`, { ...staff, method: 'POST', body: { note } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{action === 'forward' ? 'Forward the outcome' : 'Decline the request'}</DialogTitle>
      <DialogContent>
        {action === 'forward' ? (
          <Alert severity={changed ? 'warning' : 'success'} sx={{ mb: 2 }}>
            {changed
              ? <>The examiner changed the result from {n1(a.originalTotal)} ({a.originalGrade}) to {n1(a.newTotal)} ({a.newGrade}). It goes to the administrator as an amendment; on approval the student's results are recomputed and they are told by SMS.</>
              : <>The marks are unchanged ({a.originalGrade}). The request closes and the student is told by SMS.</>}
          </Alert>
        ) : <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>The student is told by SMS, with your reason.</Typography>}
        <TextField label={action === 'reject' ? 'Reason' : 'Note (optional)'} required={action === 'reject'} fullWidth multiline minRows={2} value={note} onChange={(e) => setNote(e.target.value)} inputProps={{ maxLength: 1000 }} />
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color={action === 'reject' ? 'error' : 'primary'} disabled={m.isPending || (action === 'reject' && !note.trim())} onClick={() => m.mutate()}>
          {action === 'forward' ? 'Forward' : 'Decline'}</Button></DialogActions>
    </Dialog>
  );
}
