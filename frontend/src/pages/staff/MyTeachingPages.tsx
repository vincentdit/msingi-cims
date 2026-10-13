import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import CloseIcon from '@mui/icons-material/Close';
import DownloadIcon from '@mui/icons-material/Download';
import ErrorIcon from '@mui/icons-material/Error';
import FullscreenIcon from '@mui/icons-material/Fullscreen';
import InfoIcon from '@mui/icons-material/Info';
import PhoneIcon from '@mui/icons-material/PhoneAndroid';
import QrIcon from '@mui/icons-material/QrCode2';
import WarningIcon from '@mui/icons-material/Warning';
import {
  Alert, Box, Button, Card, CardActions, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Divider, LinearProgress, Menu,
  MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime } from '../../api/client';
import type { CheckInScreen, ClassSessionView, MyModule, MyTeaching, SessionKind } from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { OFFERING_STAGE } from './MarksPages';

const staff = { realm: 'staff' as const };
const SEV_ICON = { success: <CheckCircleIcon color="success" />, info: <InfoIcon color="info" />, warning: <WarningIcon color="warning" />, error: <ErrorIcon color="error" /> };
const WINDOW_LABEL = { NOT_SET: 'Not set', UPCOMING: 'Not open yet', OPEN: 'Open', CLOSED: 'Closed' } as const;
const STAGE_COLOUR = { DRAFT: 'default', SUBMITTED: 'info', HOD_APPROVED: 'warning', BOARD_APPROVED: 'success' } as const;
const KINDS: SessionKind[] = ['LECTURE', 'TUTORIAL', 'PRACTICAL', 'SEMINAR'];
const pct = (n?: number | null) => (n == null ? '—' : `${Number(n).toLocaleString('en-GB', { maximumFractionDigits: 1 })}%`);
const kindLabel = (k: string) => k.charAt(0) + k.slice(1).toLowerCase();

// ============================================================ the lecturer's landing page

/** My teaching: the signed-in lecturer's modules this semester, where each stands, and what to do next. */
export function MyTeachingPage() {
  const [semesterId, setSemesterId] = useState<number | ''>('');
  const [checkIn, setCheckIn] = useState<MyModule | null>(null);
  const q = useQuery({ queryKey: ['staff', 'my-teaching', semesterId], refetchInterval: 60_000,
    queryFn: () => api<MyTeaching>(`/api/teaching/mine${semesterId ? `?semesterId=${semesterId}` : ''}`, staff) });
  const b = q.data;
  const closing = b?.marksWindow === 'OPEN' && b.daysToClose != null && b.daysToClose <= 7;

  return (
    <>
      <PageTitle title="My teaching" subtitle="Your modules this semester: class lists, attendance, marks and what is waiting for you."
        action={b?.semesters.length ? (
          <TextField select size="small" label="Semester" value={semesterId || b.semester?.id || ''} onChange={(e) => setSemesterId(Number(e.target.value))} sx={{ minWidth: 240 }}>
            {b.semesters.map((s) => <MenuItem key={s.id} value={s.id}>{s.name} · {s.academicYear}</MenuItem>)}
          </TextField>
        ) : undefined} />
      <ErrorAlert error={q.error} />
      {q.isLoading && <Loading />}
      {b && (
        <>
          {b.modules.length > 0 && (
            <Grid container spacing={2} sx={{ mb: 2.5 }}>
              <Kpi label="Modules" value={String(b.modules.length)} hint={`${b.students} student places`} />
              <Kpi label="Sessions recorded" value={String(b.sessions)} hint={`${Number(b.hoursTaught).toLocaleString('en-GB')} hours taught`} />
              <Kpi label="Below the threshold" value={String(b.atRisk.length)} hint={`Attendance under ${pct(b.threshold)}`} tone={b.atRisk.length ? 'warning.main' : undefined} />
              <Kpi label="Marks entry" value={WINDOW_LABEL[b.marksWindow]} tone={b.marksWindow === 'CLOSED' ? 'error.main' : closing ? 'warning.main' : undefined}
                hint={b.marksWindow === 'NOT_SET' ? 'Set by the Examinations Office'
                  : b.marksWindow === 'UPCOMING' ? `Opens ${formatDate(b.marksEntryOpens)}`
                    : b.marksWindow === 'OPEN' ? (b.marksEntryCloses ? `Closes ${formatDate(b.marksEntryCloses)}${b.daysToClose != null ? ` · ${b.daysToClose === 0 ? 'today' : `${b.daysToClose} day${b.daysToClose === 1 ? '' : 's'} left`}` : ''}` : 'Open')
                      : `Closed ${formatDate(b.marksEntryCloses)}`} />
            </Grid>
          )}
          <Grid container spacing={2.5}>
            <Grid size={{ xs: 12, lg: 4 }}>
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
              {b.atRisk.length > 0 && (
                <Card sx={{ mb: 2.5 }}>
                  <CardContent sx={{ pb: 0 }}>
                    <Typography variant="h6">Students at risk</Typography>
                    <Typography variant="body2" color="text.secondary">Below {pct(b.threshold)} attendance; they may not sit the examination.</Typography>
                  </CardContent>
                  <TableContainer>
                    <Table size="small">
                      <TableBody>
                        {b.atRisk.map((s) => (
                          <TableRow key={`${s.offeringId}-${s.studentId}`} hover component={RouterLink} to={`/staff/teaching/${s.offeringId}`} sx={{ textDecoration: 'none' }}>
                            <TableCell>
                              <Typography variant="body2" sx={{ fontWeight: 600 }}>{s.name}</Typography>
                              <Typography variant="caption" color="text.secondary">{s.registrationNo} · {s.moduleCode}</Typography>
                            </TableCell>
                            <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                              <Typography variant="body2" color="warning.main" sx={{ fontWeight: 700 }}>{pct(s.percent)}</Typography>
                              <Typography variant="caption" color="text.secondary">{s.attended} of {s.counted}{s.warned ? ' · warned' : ''}</Typography>
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </Card>
              )}
              {b.remarks.length > 0 && (
                <Card>
                  <CardContent>
                    <Stack direction="row" justifyContent="space-between" alignItems="center"><Typography variant="h6">Assigned to you</Typography>
                      <Button size="small" component={RouterLink} to="/staff/appeals">Remarks & appeals</Button></Stack>
                    {b.remarks.map((r) => (
                      <Typography key={r.id} variant="body2" sx={{ mt: 1 }}>
                        {r.kind === 'REMARK' ? 'Re-mark' : 'Appeal'} · <strong>{r.moduleCode}</strong> {r.moduleTitle}
                        <Typography component="span" variant="caption" color="text.secondary"> · assigned {formatDate(r.assignedAt)}</Typography>
                      </Typography>
                    ))}
                  </CardContent>
                </Card>
              )}
            </Grid>
            <Grid size={{ xs: 12, lg: 8 }}>
              <Grid container spacing={2}>
                {b.modules.map((m) => <Grid key={m.offeringId} size={{ xs: 12, md: 6 }}><ModuleCard m={m} onCheckIn={() => setCheckIn(m)} /></Grid>)}
                {!b.modules.length && (
                  <Grid size={12}><Card><CardContent><Typography color="text.secondary">No modules are allocated to you for this semester.</Typography></CardContent></Card></Grid>
                )}
              </Grid>
            </Grid>
          </Grid>
        </>
      )}
      {checkIn && <StartCheckInDialog offeringId={checkIn.offeringId} code={checkIn.code} onClose={() => setCheckIn(null)} />}
    </>
  );
}

function Kpi({ label, value, hint, tone }: { label: string; value: string; hint?: string; tone?: string }) {
  return (
    <Grid size={{ xs: 6, md: 3 }}>
      <Card sx={{ height: '100%' }}>
        <CardContent>
          <Typography variant="caption" color="text.secondary">{label}</Typography>
          <Typography variant="h5" sx={{ fontWeight: 700 }} color={tone}>{value}</Typography>
          {hint && <Typography variant="caption" color="text.secondary">{hint}</Typography>}
        </CardContent>
      </Card>
    </Grid>
  );
}

function Progress({ label, value, hint }: { label: string; value?: number | null; hint?: ReactNode }) {
  return (
    <Box>
      <Stack direction="row" justifyContent="space-between"><Typography variant="caption" color="text.secondary">{label}</Typography>
        <Typography variant="caption" sx={{ fontWeight: 600 }}>{hint ?? pct(value)}</Typography></Stack>
      <LinearProgress variant="determinate" value={Math.min(100, Number(value ?? 0))} sx={{ height: 6, borderRadius: 3, opacity: value == null ? 0.3 : 1 }}
        color={value != null && value >= 100 ? 'success' : 'primary'} />
    </Box>
  );
}

function ModuleCard({ m, onCheckIn }: { m: MyModule; onCheckIn: () => void }) {
  const navigate = useNavigate();
  return (
    <Card sx={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <CardContent sx={{ flexGrow: 1 }}>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="subtitle1" sx={{ fontWeight: 700, lineHeight: 1.2 }}>{m.code}</Typography>
            <Typography variant="body2" color="text.secondary" noWrap title={m.title}>{m.title}</Typography>
          </Box>
          <Chip size="small" color={STAGE_COLOUR[m.status]} label={OFFERING_STAGE[m.status]} variant={m.status === 'DRAFT' ? 'outlined' : 'filled'} />
        </Stack>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>
          {m.students} students{m.programmes.length ? ` · ${m.programmes.join(', ')}` : ''}{m.coLecturers.length ? ` · with ${m.coLecturers.join(', ')}` : ''}
        </Typography>
        {m.returnedNote && m.status === 'DRAFT' && (
          <Alert severity="error" sx={{ mt: 1.5, py: 0 }}>Returned{m.returnedByName ? ` by ${m.returnedByName}` : ''}: {m.returnedNote}</Alert>
        )}
        {m.openCheckInSessionId && (
          <Alert severity="info" sx={{ mt: 1.5, py: 0 }} action={<Button size="small" color="inherit" component={RouterLink} to={`/staff/teaching/check-in/${m.openCheckInSessionId}`}>Show code</Button>}>
            Self check-in is open.
          </Alert>
        )}
        <Stack spacing={1.25} sx={{ mt: 1.5 }}>
          <Tooltip title={m.lastSession ? `Last session ${formatDate(m.lastSession)}` : 'No sessions recorded yet'} placement="top-start">
            <Box><Progress label={`Attendance · ${m.sessions} session${m.sessions === 1 ? '' : 's'}`}
              value={m.averagePercent} hint={m.sessions ? `${pct(m.averagePercent)}${m.below ? ` · ${m.below} below` : ''}` : 'None yet'} /></Box>
          </Tooltip>
          {m.planSet ? (
            <Stack direction="row" spacing={2}>
              <Box sx={{ flex: 1 }}><Progress label="CA marks entered" value={m.caPercent} /></Box>
              <Box sx={{ flex: 1 }}><Progress label="SE marks entered" value={m.sePercent} /></Box>
            </Stack>
          ) : <Typography variant="body2" color="warning.main">No assessment plan yet.</Typography>}
          <Stack direction="row" spacing={0.75} useFlexGap flexWrap="wrap">
            <Chip size="small" variant="outlined" label={`${m.complete} of ${m.students} graded`} />
            {m.eligibilityGenerated && <Chip size="small" variant="outlined" color={m.notEligible ? 'error' : 'success'}
              label={m.notEligible ? `${m.notEligible} not eligible for the exam` : 'All eligible for the exam'} />}
            {m.resitsToMark > 0 && <Chip size="small" color="warning" variant="outlined" label={`${m.resitsToMark} supplementary to mark`} />}
          </Stack>
        </Stack>
      </CardContent>
      <CardActions sx={{ px: 2, pb: 1.5, flexWrap: 'wrap', gap: 0.5 }}>
        <Button size="small" variant="contained" onClick={() => navigate(`/staff/teaching/${m.offeringId}`)}>Attendance</Button>
        {m.canRecord && !m.openCheckInSessionId && <Button size="small" startIcon={<QrIcon />} onClick={onCheckIn}>Self check-in</Button>}
        <Button size="small" onClick={() => navigate(`/staff/marks/${m.offeringId}`)}>Marks</Button>
        <Box sx={{ flexGrow: 1 }} />
        <ClassListButton offeringId={m.offeringId} code={m.code} size="small" />
      </CardActions>
    </Card>
  );
}

/** Class list downloads for one module (used on the attendance page). */
export function ClassListButton({ offeringId, code, size }: { offeringId: number; code: string; size?: 'small' | 'medium' }) {
  const [menu, setMenu] = useState<HTMLElement | null>(null);
  const [error, setError] = useState<unknown>(null);
  const get = (format: 'pdf' | 'xlsx') => {
    setMenu(null);
    download(`/api/teaching/offerings/${offeringId}/class-list?format=${format}`, 'staff', `class-list-${code.replace(/\s+/g, '-')}.${format}`).catch(setError);
  };
  return (
    <>
      <Button size={size} startIcon={<DownloadIcon />} onClick={(e) => setMenu(e.currentTarget)} sx={{ whiteSpace: 'nowrap' }}>Class list</Button>
      <Menu anchorEl={menu} open={!!menu} onClose={() => setMenu(null)}>
        <MenuItem onClick={() => get('pdf')}>Printable register (PDF)</MenuItem>
        <MenuItem onClick={() => get('xlsx')}>With attendance (Excel)</MenuItem>
      </Menu>
      {!!error && <Dialog open onClose={() => setError(null)}><DialogContent><ErrorAlert error={error} /></DialogContent></Dialog>}
    </>
  );
}

// ============================================================ self check-in (FR-TCH-03)

/** Opens self check-in for a class in progress, then shows the code screen. */
export function StartCheckInDialog({ offeringId, code, onClose }: { offeringId: number; code: string; onClose: () => void }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [kind, setKind] = useState<SessionKind>('LECTURE');
  const [topic, setTopic] = useState('');
  const [duration, setDuration] = useState(120);
  const [openMinutes, setOpenMinutes] = useState(15);
  const [lateAfter, setLateAfter] = useState(10);
  const start = useMutation({
    mutationFn: () => api<ClassSessionView>(`/api/teaching/offerings/${offeringId}/check-in`, { ...staff, method: 'POST',
      body: { kind, topic: topic.trim() || undefined, durationMinutes: duration, openMinutes, lateAfterMinutes: lateAfter } }),
    onSuccess: (s) => {
      qc.invalidateQueries({ queryKey: ['staff'] });
      navigate(`/staff/teaching/check-in/${s.id}`);
    },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Self check-in for {code}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Starts a session now. Show the code on the projector; students in the class check in from the student portal by scanning the QR code
          or typing the code, which changes every 20 seconds. When check-in closes, everyone who did not check in is marked absent, and you can
          still correct the register.
        </Typography>
        <ErrorAlert error={start.error} />
        <Grid container spacing={1.5}>
          <Grid size={{ xs: 6 }}>
            <TextField fullWidth size="small" select label="Type" value={kind} onChange={(e) => setKind(e.target.value as SessionKind)}>
              {KINDS.map((k) => <MenuItem key={k} value={k}>{kindLabel(k)}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid size={{ xs: 6 }}>
            <TextField fullWidth size="small" select label="Length of the class" value={duration} onChange={(e) => setDuration(Number(e.target.value))}>
              {[30, 60, 90, 120, 180, 240].map((m) => <MenuItem key={m} value={m}>{m < 60 ? `${m} min` : `${m / 60} h`}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid size={12}>
            <TextField fullWidth size="small" label="Topic (optional)" value={topic} onChange={(e) => setTopic(e.target.value)} slotProps={{ htmlInput: { maxLength: 200 } }} />
          </Grid>
          <Grid size={{ xs: 6 }}>
            <TextField fullWidth size="small" select label="Check-in stays open" value={openMinutes} onChange={(e) => setOpenMinutes(Number(e.target.value))}>
              {[5, 10, 15, 20, 30, 45, 60].map((m) => <MenuItem key={m} value={m}>{m} minutes</MenuItem>)}
            </TextField>
          </Grid>
          <Grid size={{ xs: 6 }}>
            <TextField fullWidth size="small" select label="Late after" value={lateAfter} onChange={(e) => setLateAfter(Number(e.target.value))}>
              {[0, 5, 10, 15, 20, 30].map((m) => <MenuItem key={m} value={m}>{m === 0 ? 'Never late' : `${m} minutes`}</MenuItem>)}
            </TextField>
          </Grid>
        </Grid>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" startIcon={<QrIcon />} onClick={() => start.mutate()} disabled={start.isPending}>Start check-in</Button>
      </DialogActions>
    </Dialog>
  );
}

/** The screen to project while check-in is open: the rotating code, its QR code, and who has checked in. */
export function CheckInScreenPage() {
  const { sessionId } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [confirm, setConfirm] = useState(false);
  const q = useQuery({
    queryKey: ['staff', 'check-in', sessionId],
    queryFn: () => api<CheckInScreen>(`/api/teaching/sessions/${sessionId}/check-in?base=${encodeURIComponent(window.location.origin)}`, staff),
    refetchInterval: (query) => {
      const d = query.state.data;
      if (!d?.state.open) return false;
      return Math.min(3000, Math.max(500, d.secondsLeft * 1000 + 300));   // the new code as soon as it changes, and the list every few seconds
    },
  });
  const [left, setLeft] = useState(0);
  useEffect(() => {
    if (!q.data) return;
    setLeft(q.data.secondsLeft);
    const t = setInterval(() => setLeft((x) => Math.max(0, x - 1)), 1000);
    return () => clearInterval(t);
  }, [q.data]);
  const close = useMutation({
    mutationFn: () => api<ClassSessionView>(`/api/teaching/sessions/${sessionId}/check-in/close`, { ...staff, method: 'POST' }),
    onSuccess: (s) => {
      qc.invalidateQueries({ queryKey: ['staff'] });
      navigate(`/staff/teaching/${s.offeringId}`);
    },
  });
  const d = q.data;
  const closesIn = d?.state.closesAt ? Math.max(0, Math.round((new Date(d.state.closesAt).getTime() - Date.now()) / 60000)) : 0;
  const offeringLink = (id: number) => `/staff/teaching/${id}`;

  return (
    <>
      <ErrorAlert error={q.error ?? close.error} />
      {q.isLoading && <Loading />}
      {d && (
        <>
          <PageTitle title={`Check in · ${d.moduleCode}`} subtitle={d.moduleTitle}
            action={(
              <Stack direction="row" spacing={1}>
                <Tooltip title="Full screen for the projector">
                  <Button startIcon={<FullscreenIcon />} onClick={() => document.documentElement.requestFullscreen?.().catch(() => undefined)}>Full screen</Button>
                </Tooltip>
                {d.state.open && <Button variant="contained" color="warning" startIcon={<CloseIcon />} onClick={() => setConfirm(true)}>Close check-in</Button>}
              </Stack>
            )} />
          {!d.state.open ? (
            <Alert severity="success" action={<CloseLink sessionId={d.sessionId} toOffering={offeringLink} />}>
              Check-in closed {formatDateTime(d.state.closedAt)}: {d.state.checkedIn} of {d.state.classSize} students checked in; the others are marked absent.
            </Alert>
          ) : (
            <Grid container spacing={2.5}>
              <Grid size={{ xs: 12, md: 7 }}>
                <Card sx={{ textAlign: 'center' }}>
                  <CardContent sx={{ py: 4 }}>
                    <Typography variant="h6" color="text.secondary">Open the student portal and check in with</Typography>
                    <Typography data-testid="checkin-code" sx={{ fontFamily: 'ui-monospace, Menlo, Consolas, monospace', fontWeight: 700, letterSpacing: { xs: 6, md: 14 },
                      fontSize: { xs: 56, sm: 80, md: 104 }, lineHeight: 1.1, my: 1 }}>
                      {d.code ? `${d.code.slice(0, 3)} ${d.code.slice(3)}` : '— — —'}
                    </Typography>
                    <LinearProgress variant="determinate" value={(left / d.stepSeconds) * 100} sx={{ height: 6, borderRadius: 3, maxWidth: 420, mx: 'auto' }} />
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                      New code in {left} s · check-in closes in {closesIn} min{d.state.lateAfterMinutes > 0 ? ` · late after ${d.state.lateAfterMinutes} min` : ''}
                    </Typography>
                    {d.qrPng && (
                      <Box component="img" src={d.qrPng} alt="QR code to check in" sx={{ width: { xs: 220, md: 300 }, height: { xs: 220, md: 300 }, mt: 3, imageRendering: 'pixelated' }} />
                    )}
                    {d.checkInUrl && <Typography variant="caption" color="text.secondary" display="block">{d.checkInUrl.replace(/\?.*$/, '')}</Typography>}
                  </CardContent>
                </Card>
              </Grid>
              <Grid size={{ xs: 12, md: 5 }}>
                <Card>
                  <CardContent>
                    <Typography variant="caption" color="text.secondary">Checked in</Typography>
                    <Typography sx={{ fontSize: 48, fontWeight: 700, lineHeight: 1.1 }}>{d.state.checkedIn}<Typography component="span" variant="h5" color="text.secondary"> / {d.state.classSize}</Typography></Typography>
                    <LinearProgress variant="determinate" color="success" value={d.state.classSize ? (d.state.checkedIn / d.state.classSize) * 100 : 0} sx={{ height: 8, borderRadius: 4, my: 1 }} />
                    {d.state.sharedDevices > 0 && (
                      <Alert severity="warning" icon={<PhoneIcon />} sx={{ my: 1 }}>
                        {d.state.sharedDevices} check-ins came from a phone used for more than one student. Check them in the register after closing.
                      </Alert>
                    )}
                    <Typography variant="subtitle2" sx={{ mt: 2 }}>Latest</Typography>
                    {d.recent.map((l) => (
                      <Stack key={l.moduleRegistrationId} direction="row" justifyContent="space-between" sx={{ py: 0.5, borderBottom: 1, borderColor: 'divider' }}>
                        <Typography variant="body2" noWrap sx={{ mr: 1 }}>{l.name}{l.sharedDevice && <PhoneIcon fontSize="inherit" color="warning" sx={{ ml: 0.5, verticalAlign: 'middle' }} />}</Typography>
                        <Typography variant="caption" color={l.status === 'LATE' ? 'info.main' : 'text.secondary'} sx={{ whiteSpace: 'nowrap' }}>
                          {l.status === 'LATE' ? 'Late · ' : ''}{l.checkedInAt ? new Date(l.checkedInAt).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }) : ''}
                        </Typography>
                      </Stack>
                    ))}
                    {!d.recent.length && <Typography variant="body2" color="text.secondary">Nobody yet.</Typography>}
                  </CardContent>
                </Card>
              </Grid>
            </Grid>
          )}
          <Dialog open={confirm} onClose={() => setConfirm(false)}>
            <DialogTitle>Close check-in?</DialogTitle>
            <DialogContent>
              <Typography>{d.state.classSize - d.state.checkedIn} student(s) who have not checked in will be marked absent. You can still correct the register afterwards.</Typography>
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setConfirm(false)}>Keep open</Button>
              <Button variant="contained" color="warning" onClick={() => close.mutate()} disabled={close.isPending}>Close check-in</Button>
            </DialogActions>
          </Dialog>
        </>
      )}
    </>
  );
}

function CloseLink({ sessionId, toOffering }: { sessionId: number; toOffering: (id: number) => string }) {
  const s = useQuery({ queryKey: ['staff', 'class-session', sessionId], queryFn: () => api<ClassSessionView>(`/api/teaching/sessions/${sessionId}`, staff) });
  if (!s.data) return null;
  return <Button color="inherit" size="small" component={RouterLink} to={toOffering(s.data.offeringId)}>Open the register</Button>;
}
