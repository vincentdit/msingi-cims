import AddIcon from '@mui/icons-material/Add';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import CloudOffIcon from '@mui/icons-material/CloudOff';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import EligibilityIcon from '@mui/icons-material/HowToReg';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, LinearProgress,
  MenuItem, Stack, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, ToggleButton, ToggleButtonGroup,
  Tooltip, Typography, useMediaQuery, useTheme,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, formatDate } from '../../api/client';
import type {
  AttendanceStatus, AttendanceView, ClassSessionView, OfferingAttendance, RegisterLine, SessionKind, SessionRequest, WorkloadView,
} from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { OFFERING_STAGE, useRoles, useSemesterChoice } from './MarksPages';
import { ClassListButton, StartCheckInDialog } from './MyTeachingPages';
import PhoneIcon from '@mui/icons-material/PhoneAndroid';
import QrIcon from '@mui/icons-material/QrCode2';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };
const WIDE = ['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER', 'HEAD_OF_DEPARTMENT', 'DEPUTY_PRINCIPAL_ACADEMIC', 'MANAGEMENT_VIEWER'];
const EXAMS = ['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER', 'HEAD_OF_DEPARTMENT', 'DEPUTY_PRINCIPAL_ACADEMIC', 'MANAGEMENT_VIEWER'];
const KINDS: SessionKind[] = ['LECTURE', 'TUTORIAL', 'PRACTICAL', 'SEMINAR'];
const STATUS: { value: AttendanceStatus; short: string; label: string; colour: 'success' | 'info' | 'error' | 'default' }[] = [
  { value: 'PRESENT', short: 'P', label: 'Present', colour: 'success' },
  { value: 'LATE', short: 'L', label: 'Late', colour: 'info' },
  { value: 'ABSENT', short: 'A', label: 'Absent', colour: 'error' },
  { value: 'EXCUSED', short: 'E', label: 'Excused', colour: 'default' },
];
const pct = (n?: number | null) => (n == null ? '—' : `${Number(n).toLocaleString('en-GB', { maximumFractionDigits: 1 })}%`);
const hours = (minutes: number) => `${(minutes / 60).toLocaleString('en-GB', { maximumFractionDigits: 1 })} h`;
const kindLabel = (k: string) => k.charAt(0) + k.slice(1).toLowerCase();
const today = () => new Date(Date.now() + 3 * 3600_000).toISOString().slice(0, 10);   // East Africa Time

function uuid(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

// ============================================================ sessions kept on the device while offline

const OUTBOX = 'cims.attendance.outbox';
interface Pending { offeringId: number; code: string; body: SessionRequest; savedAt: string }

function readOutbox(): Pending[] {
  try {
    return JSON.parse(localStorage.getItem(OUTBOX) ?? '[]') as Pending[];
  } catch {
    return [];
  }
}

function writeOutbox(items: Pending[]) {
  try {
    if (items.length) localStorage.setItem(OUTBOX, JSON.stringify(items));
    else localStorage.removeItem(OUTBOX);
  } catch {
    /* storage unavailable: nothing more can be done */
  }
}

const unreachable = (e: unknown) => e instanceof ApiError && e.problem.code === 'SERVER_UNREACHABLE';

/**
 * Sessions saved on this device because the server could not be reached. Each keeps its reference, so sending it again never
 * records it twice; they are sent when the connection returns or when the lecturer asks.
 */
function useOutbox(onSent: (sent: number) => void) {
  const [items, setItems] = useState<Pending[]>(readOutbox);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const add = (p: Pending) => {
    const next = [...readOutbox().filter((x) => x.body.clientRef !== p.body.clientRef), p];
    writeOutbox(next);
    setItems(next);
  };
  const flush = useCallback(async () => {
    const queue = readOutbox();
    if (!queue.length) return;
    setSending(true);
    setError(null);
    const left: Pending[] = [];
    let sent = 0;
    for (const p of queue) {
      try {
        await api(`/api/teaching/offerings/${p.offeringId}/sessions`, { ...staff, method: 'POST', body: p.body });
        sent++;
      } catch (e) {
        if (unreachable(e)) left.push(p);
        else setError(e);   // refused (e.g. a session already recorded at that time): shown, and dropped from the device
      }
    }
    writeOutbox(left);
    setItems(left);
    setSending(false);
    if (sent) onSent(sent);
  }, [onSent]);
  useEffect(() => {
    flush();
    const on = () => flush();
    window.addEventListener('online', on);
    return () => window.removeEventListener('online', on);
  }, [flush]);
  return { items, add, flush, sending, error };
}

function OutboxBanner({ outbox }: { outbox: ReturnType<typeof useOutbox> }) {
  if (!outbox.items.length && !outbox.error) return null;
  return (
    <>
      {outbox.items.length > 0 && (
        <Alert severity="warning" icon={<CloudOffIcon />} sx={{ mb: 2 }}
          action={<Button color="inherit" size="small" onClick={() => outbox.flush()} disabled={outbox.sending}>{outbox.sending ? 'Sending…' : 'Send now'}</Button>}>
          {outbox.items.length} session{outbox.items.length === 1 ? '' : 's'} saved on this device but not yet sent
          ({outbox.items.map((p) => `${p.code} ${formatDate(p.body.sessionDate)}`).join(', ')}). They will be sent when the connection returns.
        </Alert>
      )}
      <ErrorAlert error={outbox.error} />
    </>
  );
}

// ============================================================ module list and workload

export function TeachingPage() {
  const navigate = useNavigate();
  const can = useRoles();
  const wide = can(WIDE);
  const oversight = can(EXAMS);
  const { semesterId, semesters, picker } = useSemesterChoice();
  const [tab, setTab] = useState<'attendance' | 'workload'>('attendance');
  const [mine, setMine] = useState(!wide);
  const list = useQuery({
    queryKey: ['staff', 'attendance-summary', semesterId, mine], enabled: !!semesterId && tab === 'attendance',
    queryFn: () => api<OfferingAttendance[]>(`/api/teaching/attendance?semesterId=${semesterId}${mine ? '&mine=true' : ''}`, staff),
  });
  const workload = useQuery({
    queryKey: ['staff', 'workload', semesterId], enabled: !!semesterId && tab === 'workload' && oversight,
    queryFn: () => api<WorkloadView>(`/api/teaching/workload?semesterId=${semesterId}`, staff),
  });
  const qc = useQueryClient();
  const outbox = useOutbox(useCallback(() => qc.invalidateQueries({ queryKey: ['staff', 'attendance-summary'] }), [qc]));
  const atRisk = (list.data ?? []).reduce((n, o) => n + o.below, 0);

  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Teaching & attendance" subtitle="Class lists, sessions and attendance for each module, and who is below the examination threshold."
        action={picker} />
      <OutboxBanner outbox={outbox} />
      <ErrorAlert error={semesters.error ?? list.error ?? workload.error} />
      {oversight && (
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2, borderBottom: 1, borderColor: 'divider' }}>
          <Tab value="attendance" label="Attendance" />
          <Tab value="workload" label="Lecturer workload" />
        </Tabs>
      )}
      {tab === 'attendance' && (
        <>
          <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" sx={{ mb: 2 }}>
            {wide && (
              <>
                <Chip clickable label="All modules" color={!mine ? 'primary' : 'default'} variant={!mine ? 'filled' : 'outlined'} onClick={() => setMine(false)} />
                <Chip clickable label="My modules" color={mine ? 'primary' : 'default'} variant={mine ? 'filled' : 'outlined'} onClick={() => setMine(true)} />
              </>
            )}
            {atRisk > 0 && <Chip size="small" color="warning" variant="outlined" sx={{ alignSelf: 'center' }}
              label={`${atRisk} student${atRisk === 1 ? '' : 's'} below the threshold across these modules`} />}
          </Stack>
          {list.isLoading && <Loading />}
          {list.data && (
            <TableContainer component={Card}>
              <Table size="small">
                <TableHead><TableRow>
                  <TableCell>Module</TableCell><TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Lecturers</TableCell>
                  <TableCell align="right">Students</TableCell><TableCell align="right">Sessions</TableCell>
                  <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' } }}>Last session</TableCell>
                  <TableCell align="right">Average</TableCell><TableCell align="right">Below threshold</TableCell>
                </TableRow></TableHead>
                <TableBody>
                  {list.data.map((o) => (
                    <TableRow key={o.offeringId} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/teaching/${o.offeringId}`)}>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>{o.code}</Typography>
                        <Typography variant="body2" color="text.secondary">{o.title}</Typography>
                      </TableCell>
                      <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>
                        {o.lecturers.length ? o.lecturers.join(', ') : <Typography variant="body2" color="warning.main">Not allocated</Typography>}
                      </TableCell>
                      <TableCell align="right">{o.students}</TableCell>
                      <TableCell align="right">{o.sessions}</TableCell>
                      <TableCell align="right" sx={{ display: { xs: 'none', sm: 'table-cell' }, whiteSpace: 'nowrap' }}>{o.lastSession ? formatDate(o.lastSession) : '—'}</TableCell>
                      <TableCell align="right">{pct(o.averagePercent)}</TableCell>
                      <TableCell align="right">{o.below ? <Chip size="small" color="warning" label={o.below} /> : '—'}</TableCell>
                    </TableRow>
                  ))}
                  {!list.data.length && (
                    <TableRow><TableCell colSpan={7}>
                      <Typography color="text.secondary" sx={{ py: 1 }}>
                        {mine ? 'No modules are allocated to you this semester.' : 'No modules set up for this semester yet (Marks › Set up modules from registrations).'}
                      </Typography>
                    </TableCell></TableRow>
                  )}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </>
      )}
      {tab === 'workload' && <WorkloadTable query={workload} />}
    </Container>
  );
}

function WorkloadTable({ query }: { query: { isLoading: boolean; data?: WorkloadView } }) {
  const navigate = useNavigate();
  if (query.isLoading) return <Loading />;
  const w = query.data;
  if (!w) return null;
  return (
    <Stack spacing={2}>
      {w.unallocated.length > 0 && (
        <Alert severity="warning">
          Not allocated to a lecturer yet: {w.unallocated.map((m) => m.code).join(', ')}. Allocate lecturers under Marks.
        </Alert>
      )}
      <TableContainer component={Card}>
        <Table size="small">
          <TableHead><TableRow>
            <TableCell>Lecturer</TableCell><TableCell>Modules</TableCell><TableCell align="right">Credits</TableCell>
            <TableCell align="right">Students</TableCell><TableCell align="right">Sessions</TableCell><TableCell align="right">Hours taught</TableCell>
          </TableRow></TableHead>
          <TableBody>
            {w.staff.map((p) => (
              <TableRow key={p.userId}>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{p.fullName}</Typography>
                  <Typography variant="caption" color="text.secondary">{p.roles.map((r) => r.replace(/_/g, ' ').toLowerCase()).join(', ')}</Typography>
                </TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5} useFlexGap flexWrap="wrap">
                    {p.offerings.map((m) => (
                      <Tooltip key={m.offeringId} title={`${m.title} · ${m.students} students · ${m.sessions} sessions${m.sharedWith > 1 ? ` · shared by ${m.sharedWith} lecturers` : ''}`}>
                        <Chip size="small" variant="outlined" label={m.code} onClick={() => navigate(`/staff/teaching/${m.offeringId}`)} />
                      </Tooltip>
                    ))}
                  </Stack>
                </TableCell>
                <TableCell align="right">{Number(p.credits).toLocaleString('en-GB')}</TableCell>
                <TableCell align="right">{p.students}</TableCell>
                <TableCell align="right">{p.sessions}</TableCell>
                <TableCell align="right">{Number(p.hoursTaught).toLocaleString('en-GB', { maximumFractionDigits: 1 })}</TableCell>
              </TableRow>
            ))}
            {!w.staff.length && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 1 }}>No lecturers allocated this semester.</Typography></TableCell></TableRow>}
          </TableBody>
        </Table>
      </TableContainer>
      <Typography variant="caption" color="text.secondary">Students are counted once per module; a module shared by several lecturers counts for each of them.</Typography>
    </Stack>
  );
}

// ============================================================ one module's attendance

export function AttendancePage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const can = useRoles();
  const [session, setSession] = useState<{ id?: number } | null>(null);
  const [checkIn, setCheckIn] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const v = useQuery({ queryKey: ['staff', 'attendance', id], queryFn: () => api<AttendanceView>(`/api/teaching/offerings/${id}/attendance`, staff) });
  const refresh = useCallback(() => {
    qc.invalidateQueries({ queryKey: ['staff', 'attendance'] });
    qc.invalidateQueries({ queryKey: ['staff', 'attendance-summary'] });
  }, [qc]);
  const outbox = useOutbox(useCallback((n: number) => {
    refresh();
    setNotice(`${n} session${n === 1 ? '' : 's'} saved on this device ${n === 1 ? 'has' : 'have'} been sent.`);
  }, [refresh]));
  const a = v.data;
  const openCheckIn = a?.sessionList.find((x) => x.checkInOpen);

  return (
    <Container maxWidth="lg" disableGutters>
      <Button component={StaffLink} to="/staff/teaching" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>Teaching & attendance</Button>
      <ErrorAlert error={v.error} />
      {v.isLoading && <Loading />}
      {a && (
        <>
          <PageTitle title={`${a.code} · ${a.title}`}
            subtitle={`${a.semesterName}${a.departmentName ? ` · ${a.departmentName}` : ''} · ${a.lecturers.length ? a.lecturers.join(', ') : 'no lecturer allocated'}`}
            action={(
              <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" justifyContent="flex-end">
                {can(EXAMS) && <Button component={StaffLink} to={`/staff/eligibility/${a.offeringId}`} startIcon={<EligibilityIcon />}>Eligibility</Button>}
                <ClassListButton offeringId={a.offeringId} code={a.code} />
                {a.canRecord && !openCheckIn && <Button startIcon={<QrIcon />} onClick={() => setCheckIn(true)} sx={{ whiteSpace: 'nowrap' }}>Self check-in</Button>}
                {a.canRecord && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setSession({})} sx={{ whiteSpace: 'nowrap' }}>Record session</Button>}
              </Stack>
            )} />
          <OutboxBanner outbox={outbox} />
          {notice && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>{notice}</Alert>}
          {openCheckIn && (
            <Alert severity="info" icon={<QrIcon />} sx={{ mb: 2 }}
              action={<Button color="inherit" size="small" component={StaffLink} to={`/staff/teaching/check-in/${openCheckIn.id}`}>Show the code</Button>}>
              Self check-in is open for the session of {formatDate(openCheckIn.sessionDate)} at {openCheckIn.startTime.slice(0, 5)}.
            </Alert>
          )}
          {!a.canRecord && a.status !== 'DRAFT' && (
            <Alert severity="info" sx={{ mb: 2 }}>Results are {OFFERING_STAGE[a.status].toLowerCase()}, so attendance can no longer change.</Alert>
          )}
          <Grid container spacing={2} sx={{ mb: 2 }}>
            <Kpi label="Sessions" value={String(a.sessions)} hint={hours(a.minutesTaught) + ' taught'} />
            <Kpi label="Average attendance" value={pct(a.averagePercent)} hint={`${a.students.length} students`} />
            <Kpi label="Examination threshold" value={pct(a.threshold)} hint="Late counts as attended; excused is left out" />
            <Kpi label="Below threshold" value={String(a.below)} hint={a.below ? 'Warned by SMS once' : 'Everyone on track'} warn={a.below > 0} />
          </Grid>
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, md: 7 }}>
              <Card>
                <CardContent sx={{ pb: 0 }}><Typography variant="h6">Students</Typography></CardContent>
                <TableContainer>
                  <Table size="small">
                    <TableHead><TableRow>
                      <TableCell>Student</TableCell><TableCell align="right">Attended</TableCell><TableCell sx={{ minWidth: 130 }}>Attendance</TableCell>
                      <TableCell>Exam</TableCell>
                    </TableRow></TableHead>
                    <TableBody>
                      {a.students.map((s) => (
                        <TableRow key={s.moduleRegistrationId}>
                          <TableCell sx={{ whiteSpace: 'nowrap' }}>
                            <Typography variant="body2" sx={{ fontWeight: 600 }}>{s.registrationNo}</Typography>
                            <Typography variant="caption" color="text.secondary">{s.name} · {s.programmeCode}</Typography>
                          </TableCell>
                          <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                            {s.attended} / {s.counted}{s.excused > 0 && <Typography component="span" variant="caption" color="text.secondary"> (+{s.excused} excused)</Typography>}
                          </TableCell>
                          <TableCell>
                            <Stack direction="row" spacing={1} alignItems="center">
                              <LinearProgress variant="determinate" value={Math.min(100, Number(s.percent ?? 0))} color={s.below ? 'warning' : 'success'}
                                sx={{ flexGrow: 1, height: 6, borderRadius: 3, opacity: s.percent == null ? 0.3 : 1 }} />
                              <Typography variant="body2" sx={{ minWidth: 44, textAlign: 'right', fontWeight: s.below ? 700 : 400 }}
                                color={s.below ? 'warning.main' : undefined}>{pct(s.percent)}</Typography>
                            </Stack>
                            {s.alertedAt && <Typography variant="caption" color="text.secondary">Warned {formatDate(s.alertedAt)}</Typography>}
                          </TableCell>
                          <TableCell>
                            {s.eligible == null ? <Typography variant="caption" color="text.secondary">No list yet</Typography>
                              : s.eligible ? <Chip size="small" color="success" variant="outlined" label={s.exempted ? 'Exempted' : 'Eligible'} />
                                : <Chip size="small" color="error" label="Not eligible" />}
                          </TableCell>
                        </TableRow>
                      ))}
                      {!a.students.length && <TableRow><TableCell colSpan={4}><Typography color="text.secondary" sx={{ py: 1 }}>No students registered for this module.</Typography></TableCell></TableRow>}
                    </TableBody>
                  </Table>
                </TableContainer>
              </Card>
            </Grid>
            <Grid size={{ xs: 12, md: 5 }}>
              <Card>
                <CardContent sx={{ pb: 0 }}><Typography variant="h6">Sessions</Typography></CardContent>
                <TableContainer>
                  <Table size="small">
                    <TableHead><TableRow><TableCell>Date</TableCell><TableCell>Session</TableCell><TableCell align="right">P / L / A / E</TableCell></TableRow></TableHead>
                    <TableBody>
                      {a.sessionList.map((s) => (
                        <TableRow key={s.id} hover sx={{ cursor: 'pointer' }}
                          onClick={() => (s.checkInOpen && a.canRecord ? navigate(`/staff/teaching/check-in/${s.id}`) : setSession({ id: s.id }))}>
                          <TableCell sx={{ whiteSpace: 'nowrap' }}>
                            <Typography variant="body2">{formatDate(s.sessionDate)}</Typography>
                            <Typography variant="caption" color="text.secondary">{s.startTime.slice(0, 5)} · {s.durationMinutes} min</Typography>
                          </TableCell>
                          <TableCell>
                            <Typography variant="body2">{kindLabel(s.kind)}
                              {s.checkInOpen && <Chip size="small" color="info" label="Check-in open" sx={{ ml: 0.75, height: 20 }} />}
                            </Typography>
                            {s.topic && <Typography variant="caption" color="text.secondary" display="block">{s.topic}</Typography>}
                            {s.selfCheckIns > 0 && <Typography variant="caption" color="text.secondary" display="block">{s.selfCheckIns} checked in themselves</Typography>}
                          </TableCell>
                          <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{s.present} / {s.late} / {s.absent} / {s.excused}</TableCell>
                        </TableRow>
                      ))}
                      {!a.sessionList.length && (
                        <TableRow><TableCell colSpan={3}><Typography color="text.secondary" sx={{ py: 1 }}>
                          {a.canRecord ? 'No sessions yet. Record each class as it is held.' : 'No sessions recorded.'}
                        </Typography></TableCell></TableRow>
                      )}
                    </TableBody>
                  </Table>
                </TableContainer>
              </Card>
            </Grid>
          </Grid>
          {checkIn && <StartCheckInDialog offeringId={a.offeringId} code={a.code} onClose={() => setCheckIn(false)} />}
          {session && (
            <SessionDialog offeringId={a.offeringId} code={a.code} sessionId={session.id} canRecord={a.canRecord} onClose={() => setSession(null)}
              onSaved={(msg) => { setSession(null); setNotice(msg); refresh(); }}
              onOffline={(p) => { outbox.add(p); setSession(null); }} />
          )}
        </>
      )}
    </Container>
  );
}

function Kpi({ label, value, hint, warn }: { label: string; value: string; hint?: string; warn?: boolean }) {
  return (
    <Grid size={{ xs: 6, md: 3 }}>
      <Card sx={{ height: '100%' }}>
        <CardContent>
          <Typography variant="caption" color="text.secondary">{label}</Typography>
          <Typography variant="h5" sx={{ fontWeight: 700 }} color={warn ? 'warning.main' : undefined}>{value}</Typography>
          {hint && <Typography variant="caption" color="text.secondary">{hint}</Typography>}
        </CardContent>
      </Card>
    </Grid>
  );
}

/** The register for a new or existing session: everyone starts present; tap to change. */
function SessionDialog({ offeringId, code, sessionId, canRecord, onClose, onSaved, onOffline }: {
  offeringId: number; code: string; sessionId?: number; canRecord: boolean; onClose: () => void; onSaved: (msg: string) => void; onOffline: (p: { offeringId: number; code: string; body: SessionRequest; savedAt: string }) => void;
}) {
  const theme = useTheme();
  const phone = !useMediaQuery(theme.breakpoints.up('sm'));
  const existing = useQuery({
    queryKey: ['staff', 'class-session', sessionId], enabled: !!sessionId,
    queryFn: () => api<ClassSessionView>(`/api/teaching/sessions/${sessionId}`, staff),
  });
  const blank = useQuery({
    queryKey: ['staff', 'register', offeringId], enabled: !sessionId,
    queryFn: () => api<RegisterLine[]>(`/api/teaching/offerings/${offeringId}/register`, staff),
  });
  const [ref] = useState(uuid);
  const [date, setDate] = useState(today());
  const [time, setTime] = useState('08:00');
  const [duration, setDuration] = useState(120);
  const [kind, setKind] = useState<SessionKind>('LECTURE');
  const [topic, setTopic] = useState('');
  const [marks, setMarks] = useState<Record<number, AttendanceStatus | undefined>>({});
  const [lines, setLines] = useState<RegisterLine[]>([]);
  const [confirmDelete, setConfirmDelete] = useState(false);
  useEffect(() => {
    const s = existing.data;
    if (s) {
      setDate(s.sessionDate);
      setTime(s.startTime.slice(0, 5));
      setDuration(s.durationMinutes);
      setKind(s.kind);
      setTopic(s.topic ?? '');
      setLines(s.register);
      setMarks(Object.fromEntries(s.register.map((l) => [l.moduleRegistrationId, l.status ?? undefined])));
    }
  }, [existing.data]);
  useEffect(() => {
    if (blank.data) {
      setLines(blank.data);
      setMarks(Object.fromEntries(blank.data.map((l) => [l.moduleRegistrationId, 'PRESENT' as AttendanceStatus])));
    }
  }, [blank.data]);
  const editable = sessionId ? !!existing.data?.editable : canRecord;
  const body = (): SessionRequest => ({
    clientRef: ref, sessionDate: date, startTime: time, durationMinutes: duration, kind, topic: topic.trim() || undefined,
    marks: lines.filter((l) => marks[l.moduleRegistrationId]).map((l) => ({ moduleRegistrationId: l.moduleRegistrationId, status: marks[l.moduleRegistrationId]! })),
  });
  const save = useMutation({
    mutationFn: () => (sessionId
      ? api<ClassSessionView>(`/api/teaching/sessions/${sessionId}`, { ...staff, method: 'PUT', body: body() })
      : api<ClassSessionView>(`/api/teaching/offerings/${offeringId}/sessions`, { ...staff, method: 'POST', body: body() })),
    onSuccess: () => onSaved(sessionId ? 'Session updated.' : `Attendance for ${formatDate(date)} saved.`),
    onError: (e) => {
      if (!sessionId && unreachable(e)) onOffline({ offeringId, code, body: body(), savedAt: new Date().toISOString() });
    },
  });
  const remove = useMutation({
    mutationFn: () => api(`/api/teaching/sessions/${sessionId}`, { ...staff, method: 'DELETE' }),
    onSuccess: () => onSaved('Session deleted.'),
  });
  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    Object.values(marks).forEach((s) => { if (s) c[s] = (c[s] ?? 0) + 1; });
    return c;
  }, [marks]);
  const setAll = (s: AttendanceStatus) => setMarks(Object.fromEntries(lines.map((l) => [l.moduleRegistrationId, s])));
  const loading = existing.isLoading || blank.isLoading;
  const future = date > today();

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md" fullScreen={phone}>
      <DialogTitle>{sessionId ? `${code} session · ${formatDate(date)}` : `Record a ${code} session`}</DialogTitle>
      <DialogContent dividers>
        <ErrorAlert error={existing.error ?? blank.error ?? (unreachable(save.error) && sessionId ? null : save.error) ?? remove.error} />
        {loading && <Loading />}
        {!loading && (
          <Stack spacing={2}>
            {existing.data?.checkIn?.open && (
              <Alert severity="info">Self check-in for this session is open until {new Date(existing.data.checkIn.closesAt).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })}.
                Close it from the check-in screen, then correct the register here.</Alert>
            )}
            {existing.data?.checkIn && !existing.data.checkIn.open && existing.data.checkIn.sharedDevices > 0 && (
              <Alert severity="warning" icon={<PhoneIcon />}>{existing.data.checkIn.sharedDevices} students checked in from a phone used for more than one student. Check whether they were in class.</Alert>
            )}
            <Grid container spacing={1.5}>
              <Grid size={{ xs: 6, sm: 3 }}>
                <TextField fullWidth size="small" type="date" label="Date" value={date} onChange={(e) => setDate(e.target.value)} disabled={!editable}
                  error={future} helperText={future ? 'Not in the future' : undefined}
                  slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: today() } }} />
              </Grid>
              <Grid size={{ xs: 6, sm: 2 }}>
                <TextField fullWidth size="small" type="time" label="Start" value={time} onChange={(e) => setTime(e.target.value)} disabled={!editable}
                  slotProps={{ inputLabel: { shrink: true } }} />
              </Grid>
              <Grid size={{ xs: 6, sm: 2 }}>
                <TextField fullWidth size="small" select label="Length" value={duration} onChange={(e) => setDuration(Number(e.target.value))} disabled={!editable}>
                  {[30, 60, 90, 120, 180, 240].map((m) => <MenuItem key={m} value={m}>{m < 60 ? `${m} min` : `${m / 60} h`}</MenuItem>)}
                </TextField>
              </Grid>
              <Grid size={{ xs: 6, sm: 2 }}>
                <TextField fullWidth size="small" select label="Type" value={kind} onChange={(e) => setKind(e.target.value as SessionKind)} disabled={!editable}>
                  {KINDS.map((k) => <MenuItem key={k} value={k}>{kindLabel(k)}</MenuItem>)}
                </TextField>
              </Grid>
              <Grid size={{ xs: 12, sm: 3 }}>
                <TextField fullWidth size="small" label="Topic (optional)" value={topic} onChange={(e) => setTopic(e.target.value)} disabled={!editable}
                  slotProps={{ htmlInput: { maxLength: 200 } }} />
              </Grid>
            </Grid>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between" alignItems={{ sm: 'center' }}>
              <Stack direction="row" spacing={0.5} useFlexGap flexWrap="wrap">
                {STATUS.map((s) => <Chip key={s.value} size="small" color={s.colour} variant="outlined" label={`${s.label} ${counts[s.value] ?? 0}`} />)}
              </Stack>
              {editable && (
                <Stack direction="row" spacing={1}>
                  <Button size="small" onClick={() => setAll('PRESENT')}>All present</Button>
                  <Button size="small" onClick={() => setAll('ABSENT')}>All absent</Button>
                </Stack>
              )}
            </Stack>
            <Box>
              {lines.map((l) => (
                <Stack key={l.moduleRegistrationId} direction="row" spacing={1} alignItems="center" justifyContent="space-between"
                  sx={{ py: 0.75, borderBottom: 1, borderColor: 'divider' }}>
                  <Box sx={{ minWidth: 0 }}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }} noWrap>{l.name}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {l.registrationNo} · {l.programmeCode}
                      {l.checkedInAt && <> · checked in {new Date(l.checkedInAt).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })}{l.source === 'REGISTER' ? ' (corrected)' : ''}</>}
                    </Typography>
                    {l.sharedDevice && (
                      <Typography variant="caption" color="warning.main" display="flex" alignItems="center" gap={0.5}>
                        <PhoneIcon sx={{ fontSize: 14 }} /> Same phone as another student
                      </Typography>
                    )}
                  </Box>
                  <ToggleButtonGroup size="small" exclusive value={marks[l.moduleRegistrationId] ?? null} disabled={!editable}
                    onChange={(_, v: AttendanceStatus | null) => { if (v) setMarks((m) => ({ ...m, [l.moduleRegistrationId]: v })); }}
                    aria-label={`Attendance of ${l.name}`}>
                    {STATUS.map((s) => (
                      <ToggleButton key={s.value} value={s.value} aria-label={s.label} color={s.colour === 'default' ? 'standard' : s.colour}
                        sx={{ px: { xs: 1.25, sm: 1.5 }, fontWeight: 700 }}>
                        <Tooltip title={s.label}><span>{s.short}</span></Tooltip>
                      </ToggleButton>
                    ))}
                  </ToggleButtonGroup>
                </Stack>
              ))}
              {!lines.length && <Typography color="text.secondary">No students registered for this module.</Typography>}
              {sessionId && lines.some((l) => !marks[l.moduleRegistrationId]) && (
                <Typography variant="caption" color="text.secondary">Students without a mark joined the class after this session and are not counted for it.</Typography>
              )}
            </Box>
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        {sessionId && editable && (
          confirmDelete
            ? <Button color="error" onClick={() => remove.mutate()} disabled={remove.isPending}>Delete this session?</Button>
            : <IconButton aria-label="Delete session" onClick={() => setConfirmDelete(true)}><DeleteIcon /></IconButton>
        )}
        <Box sx={{ flexGrow: 1 }} />
        <Button onClick={onClose}>{editable ? 'Cancel' : 'Close'}</Button>
        {editable && (
          <Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending || loading || future || !lines.length}>
            {sessionId ? 'Save changes' : 'Save attendance'}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}

