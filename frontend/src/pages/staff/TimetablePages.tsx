import AddIcon from '@mui/icons-material/Add';
import CopyIcon from '@mui/icons-material/ContentCopy';
import DownloadIcon from '@mui/icons-material/Download';
import EditIcon from '@mui/icons-material/EditOutlined';
import PlaylistAddIcon from '@mui/icons-material/PlaylistAdd';
import PublishIcon from '@mui/icons-material/Publish';
import UnpublishedIcon from '@mui/icons-material/UnpublishedOutlined';
import {
  Alert, Autocomplete, Box, Button, Card, CardContent, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel,
  IconButton, MenuItem, Paper, Stack, Switch, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Tooltip,
  Typography, useMediaQuery, useTheme,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { api, download, formatDate, formatDateTime } from '../../api/client';
import type {
  Campus, ExamOffering, ExamTimetable, MyTimetable, Person, SemesterTimetable, SessionKind, SittingRequest, SittingView, SlotRequest, SlotView,
  TimetableOffering, TimetableWarning, VenueKind, VenueView,
} from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles, useSemesterChoice } from './MarksPages';
import { tr } from '../../locale/ui';

const staff = { realm: 'staff' as const };
const ACADEMIC = ['INSTITUTION_ADMIN', 'DEPUTY_PRINCIPAL_ACADEMIC'];
const WIDE = ['INSTITUTION_ADMIN', 'DEPUTY_PRINCIPAL_ACADEMIC', 'HEAD_OF_DEPARTMENT', 'PROGRAMME_COORDINATOR', 'EXAMINATIONS_OFFICER', 'REGISTRAR', 'MANAGEMENT_VIEWER'];
const ALLOCATORS = ['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER', 'HEAD_OF_DEPARTMENT', 'DEPUTY_PRINCIPAL_ACADEMIC'];
const VENUE_KEEPERS = ['INSTITUTION_ADMIN', 'DEPUTY_PRINCIPAL_ACADEMIC', 'EXAMINATIONS_OFFICER'];
const EXAMS_OFFICE = ['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER'];
const KINDS: SessionKind[] = ['LECTURE', 'TUTORIAL', 'PRACTICAL', 'SEMINAR'];
const VENUE_KINDS: { value: VenueKind; label: string }[] = [
  { value: 'LECTURE_HALL', label: 'Lecture hall' }, { value: 'CLASSROOM', label: 'Classroom' }, { value: 'LABORATORY', label: 'Laboratory' },
  { value: 'COMPUTER_LAB', label: 'Computer laboratory' }, { value: 'WORKSHOP', label: 'Workshop' }, { value: 'OTHER', label: 'Other' },
];
export const DAYS = ['', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
const SHORT = ['', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const KIND_COLOUR: Record<SessionKind, string> = { LECTURE: '#0B6E4F', TUTORIAL: '#1565C0', PRACTICAL: '#B26A00', SEMINAR: '#6A1B9A' };
export const hm = (t?: string | null) => (t ?? '').slice(0, 5);
const minutes = (t: string) => Number(t.slice(0, 2)) * 60 + Number(t.slice(3, 5));
export const kindLabel = (k: string) => k.charAt(0) + k.slice(1).toLowerCase();
const hours = (m: number) => `${(m / 60).toLocaleString('en-GB', { maximumFractionDigits: 1 })} h`;
const SEVERITY_ORDER = { error: 0, warning: 1, info: 2 } as const;

// ============================================================ the week as a grid

interface Placed { slot: SlotView; lane: number; lanes: number }

/** Classes of one day side by side where they overlap (greedy lanes). */
function lanes(slots: SlotView[]): Placed[] {
  const sorted = [...slots].sort((a, b) => a.startTime.localeCompare(b.startTime) || a.endTime.localeCompare(b.endTime));
  const out: Placed[] = [];
  let group: Placed[] = [];
  let groupEnd = '';
  const close = () => {
    const n = Math.max(1, ...group.map((p) => p.lane + 1));
    group.forEach((p) => { p.lanes = n; });
    out.push(...group);
    group = [];
  };
  for (const s of sorted) {
    if (group.length && s.startTime >= groupEnd) close();
    const used = new Set(group.filter((p) => p.slot.endTime > s.startTime).map((p) => p.lane));
    let lane = 0;
    while (used.has(lane)) lane++;
    group.push({ slot: s, lane, lanes: 1 });
    if (group.length === 1 || s.endTime > groupEnd) groupEnd = s.endTime;
  }
  if (group.length) close();
  return out;
}

/** The week of classes: a grid of days and hours on wide screens, a list by day on phones. Click a class to open it. */
export function WeekGrid({ slots, highlight, onOpen, showLecturer = true }: {
  slots: SlotView[]; highlight?: Set<number>; onOpen?: (s: SlotView) => void; showLecturer?: boolean;
}) {
  const theme = useTheme();
  const narrow = useMediaQuery(theme.breakpoints.down('md'));
  const days = [1, 2, 3, 4, 5, ...(slots.some((s) => s.dayOfWeek === 6) ? [6] : []), ...(slots.some((s) => s.dayOfWeek === 7) ? [7] : [])];
  if (!slots.length) return <Alert severity="info">No classes to show.</Alert>;
  if (narrow) {
    return (
      <Stack spacing={1.5}>
        {days.filter((d) => slots.some((s) => s.dayOfWeek === d)).map((d) => (
          <Card key={d} variant="outlined">
            <CardContent sx={{ py: 1.5 }}>
              <Typography variant="subtitle1" fontWeight={700}>{DAYS[d]}</Typography>
              {slots.filter((s) => s.dayOfWeek === d).sort((a, b) => a.startTime.localeCompare(b.startTime)).map((s) => (
                <Box key={s.id} onClick={() => onOpen?.(s)} sx={{ py: 0.75, borderTop: 1, borderColor: 'divider', cursor: onOpen ? 'pointer' : 'default',
                  bgcolor: highlight?.has(s.id) ? 'action.selected' : undefined }}>
                  <Typography variant="body2"><strong>{hm(s.startTime)}–{hm(s.endTime)}</strong> · {s.moduleCode} {kindLabel(s.kind)}{s.groupLabel ? ` (${s.groupLabel})` : ''}</Typography>
                  <Typography variant="caption" color="text.secondary">{s.venueCode} · {s.venueName}{showLecturer && s.lecturerName ? ` · ${s.lecturerName}` : ''}</Typography>
                </Box>
              ))}
            </CardContent>
          </Card>
        ))}
      </Stack>
    );
  }
  const first = Math.min(8 * 60, ...slots.map((s) => minutes(s.startTime)));
  const last = Math.max(17 * 60, ...slots.map((s) => minutes(s.endTime)));
  const top = Math.floor(first / 60) * 60;
  const bottom = Math.ceil(last / 60) * 60;
  const PX = 1.1;   // pixels per minute
  const height = (bottom - top) * PX;
  return (
    <Paper variant="outlined" sx={{ overflowX: 'auto' }}>
      <Box sx={{ display: 'grid', gridTemplateColumns: `56px repeat(${days.length}, minmax(150px, 1fr))`, minWidth: 56 + days.length * 150 }}>
        <Box />
        {days.map((d) => <Box key={d} sx={{ p: 1, fontWeight: 700, textAlign: 'center', borderLeft: 1, borderColor: 'divider' }}>{DAYS[d]}</Box>)}
        <Box sx={{ position: 'relative', height }}>
          {Array.from({ length: (bottom - top) / 60 }, (_, i) => (
            <Typography key={i} variant="caption" color="text.secondary" sx={{ position: 'absolute', top: i * 60 * PX - 2, right: 6 }}>
              {String(top / 60 + i).padStart(2, '0')}:00
            </Typography>
          ))}
        </Box>
        {days.map((d) => (
          <Box key={d} sx={{ position: 'relative', height, borderLeft: 1, borderColor: 'divider',
            backgroundImage: `repeating-linear-gradient(to bottom, ${theme.palette.divider} 0 1px, transparent 1px ${60 * PX}px)` }}>
            {lanes(slots.filter((s) => s.dayOfWeek === d)).map(({ slot: s, lane, lanes: n }) => (
              <Tooltip key={s.id} title={`${s.moduleCode} ${s.moduleTitle} · ${kindLabel(s.kind)}${s.groupLabel ? ` (${s.groupLabel})` : ''} · ${hm(s.startTime)}–${hm(s.endTime)} · ${s.venueCode} ${s.venueName}${s.lecturerName ? ` · ${s.lecturerName}` : ''}${s.note ? ` · ${s.note}` : ''}`}>
                <Box onClick={() => onOpen?.(s)} role={onOpen ? 'button' : undefined} aria-label={`${s.moduleCode} ${DAYS[d]} ${hm(s.startTime)}`}
                  sx={{ position: 'absolute', top: (minutes(s.startTime) - top) * PX + 1, height: (minutes(s.endTime) - minutes(s.startTime)) * PX - 2,
                    left: `calc(${(lane / n) * 100}% + 2px)`, width: `calc(${100 / n}% - 4px)`, overflow: 'hidden', borderRadius: 1, px: 0.75, py: 0.25,
                    bgcolor: `${KIND_COLOUR[s.kind]}1A`, borderLeft: `4px solid ${KIND_COLOUR[s.kind]}`, cursor: onOpen ? 'pointer' : 'default',
                    outline: highlight?.has(s.id) ? `2px solid ${theme.palette.warning.main}` : undefined }}>
                  <Typography variant="caption" fontWeight={700} display="block" noWrap>{s.moduleCode}{s.groupLabel ? ` · ${s.groupLabel}` : ''}</Typography>
                  <Typography variant="caption" display="block" noWrap>{hm(s.startTime)}–{hm(s.endTime)} · {s.venueCode}</Typography>
                  {showLecturer && s.lecturerName && <Typography variant="caption" color="text.secondary" display="block" noWrap>{s.lecturerName}</Typography>}
                </Box>
              </Tooltip>
            ))}
          </Box>
        ))}
      </Box>
    </Paper>
  );
}

function Warnings({ warnings, onShow }: { warnings: TimetableWarning[]; onShow?: (ids: number[]) => void }) {
  const [all, setAll] = useState(false);
  if (!warnings.length) return <Alert severity="success">No clashes found.</Alert>;
  const sorted = [...warnings].sort((a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity]);
  const shown = all ? sorted : sorted.slice(0, 6);
  return (
    <Stack spacing={1}>
      {shown.map((w, i) => (
        <Alert key={i} severity={w.severity}
          action={onShow && w.ids.length > 0 && w.kind !== 'UNSCHEDULED' ? <Button color="inherit" size="small" onClick={() => onShow(w.ids)}>Show</Button> : undefined}>
          {w.message}
        </Alert>
      ))}
      {sorted.length > 6 && <Button size="small" onClick={() => setAll(!all)}>{all ? 'Show fewer' : `Show all ${sorted.length}`}</Button>}
    </Stack>
  );
}

function PublishBar({ publishedAt, publishedBy, canPublish, kind, semesterId, onDone }: {
  publishedAt?: string | null; publishedBy?: string | null; canPublish: boolean; kind: 'CLASS' | 'EXAM'; semesterId: number; onDone: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [sms, setSms] = useState(true);
  const publish = useMutation({
    mutationFn: () => api<{ notified: number }>(`/api/timetable/semesters/${semesterId}/publish`, { method: 'POST', ...staff, body: { kind, sendSms: sms } }),
    onSuccess: () => { setOpen(false); onDone(); },
  });
  const withdraw = useMutation({
    mutationFn: () => api(`/api/timetable/semesters/${semesterId}/withdraw`, { method: 'POST', ...staff, body: { kind, sendSms: false } }),
    onSuccess: onDone,
  });
  return (
    <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
      {publishedAt
        ? <Chip color="success" label={<>{'Published'} {formatDateTime(publishedAt)}{publishedBy ? ` · ${publishedBy}` : ''}</>} />
        : <Chip label="Draft: students cannot see it yet" />}
      {canPublish && (
        <Button size="small" variant={publishedAt ? 'outlined' : 'contained'} startIcon={<PublishIcon />} onClick={() => setOpen(true)}>
          {publishedAt ? 'Publish again' : 'Publish to students'}
        </Button>
      )}
      {canPublish && publishedAt && (
        <Button size="small" color="inherit" startIcon={<UnpublishedIcon />} disabled={withdraw.isPending} onClick={() => withdraw.mutate()}>Withdraw</Button>
      )}
      {withdraw.error ? <ErrorAlert error={withdraw.error} /> : null}
      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="xs" fullWidth>
        <DialogTitle>{kind === 'CLASS' ? 'Publish the class timetable' : 'Publish the examination timetable'}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 1 }}>
            Students registered for the semester see their own {kind === 'CLASS' ? 'classes' : 'papers'} under Timetable in the student portal.
            Changes made afterwards are marked as changed.
          </Typography>
          <FormControlLabel control={<Checkbox checked={sms} onChange={(e) => setSms(e.target.checked)} />} label="Send an SMS to registered students" />
          {publish.error ? <ErrorAlert error={publish.error} /> : null}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={publish.isPending} onClick={() => publish.mutate()}>Publish</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

// ============================================================ the class timetable

export function TimetablePage() {
  const can = useRoles();
  const wide = can(WIDE);
  const mineToo = can(['LECTURER', 'ASSESSOR']);
  const [tab, setTab] = useState(wide ? 'week' : 'mine');
  return (
    <>
      <PageTitle title="Timetable" subtitle="The weekly classes of each semester: venues, lecturers and clashes, published to students when ready." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }} variant="scrollable" allowScrollButtonsMobile>
        {mineToo && <Tab value="mine" label="My timetable" />}
        {wide && <Tab value="week" label="Class timetable" />}
        {wide && <Tab value="venues" label="Venues" />}
      </Tabs>
      {tab === 'mine' && <MyTimetableView />}
      {tab === 'week' && wide && <ClassTimetable />}
      {tab === 'venues' && wide && <VenuesView />}
    </>
  );
}

function ClassTimetable() {
  const qc = useQueryClient();
  const can = useRoles();
  const { semesterId, semesters, picker } = useSemesterChoice();
  const [cohort, setCohort] = useState('');
  const [venue, setVenue] = useState<number | ''>('');
  const [lecturer, setLecturer] = useState('');
  const [editing, setEditing] = useState<Partial<SlotRequest> & { id?: number } | null>(null);
  const [highlight, setHighlight] = useState<Set<number>>(new Set());
  const [copyOpen, setCopyOpen] = useState(false);
  const [prepared, setPrepared] = useState<{ created: number; skipped: string[] } | null>(null);
  const q = useQuery({ queryKey: ['staff', 'timetable', semesterId], enabled: !!semesterId,
    queryFn: () => api<SemesterTimetable>(`/api/timetable/semesters/${semesterId}`, staff) });
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff', 'timetable'] });
  const prepare = useMutation({
    mutationFn: () => api<{ created: number; skipped: string[] }>('/api/exams/offerings/from-curricula', { method: 'POST', ...staff, body: { semesterId } }),
    onSuccess: (r) => { setPrepared(r); refresh(); qc.invalidateQueries({ queryKey: ['staff', 'offerings'] }); },
  });
  const t = q.data;
  const cohorts = useMemo(() => {
    const m = new Map<string, string>();
    t?.offerings.forEach((o) => o.cohorts.forEach((c) => m.set(`${c.programmeId}:${c.yearOfStudy}`, `${c.programmeCode} year ${c.yearOfStudy}`)));
    return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]));
  }, [t]);
  const lecturers = useMemo(() => [...new Set(t?.slots.map((s) => s.lecturerName).filter(Boolean) as string[])].sort(), [t]);
  const visible = useMemo(() => {
    if (!t) return [];
    const byOffering = new Map(t.offerings.map((o) => [o.id, o]));
    return t.slots.filter((s) => {
      if (venue && s.venueId !== venue) return false;
      if (lecturer && !(s.lecturerName ?? '').split(', ').includes(lecturer)) return false;
      if (cohort) {
        const o = byOffering.get(s.offeringId);
        if (!o?.cohorts.some((c) => `${c.programmeId}:${c.yearOfStudy}` === cohort)) return false;
      }
      return true;
    });
  }, [t, venue, lecturer, cohort]);
  if (semesters.isLoading || (semesterId && q.isLoading)) return <Loading />;
  if (semesters.error) return <ErrorAlert error={semesters.error} />;
  const openSlot = (s: SlotView) => setEditing({ id: s.id, offeringId: s.offeringId, kind: s.kind, dayOfWeek: s.dayOfWeek, startTime: hm(s.startTime),
    endTime: hm(s.endTime), venueId: s.venueId, lecturerId: s.lecturerId ?? null, groupLabel: s.groupLabel ?? '', note: s.note ?? '' });
  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }} flexWrap="wrap" useFlexGap>
        <Box sx={{ flex: '0 0 auto' }}>{picker}</Box>
        {t && <PublishBar kind="CLASS" semesterId={t.semesterId} publishedAt={t.publishedAt} publishedBy={t.publishedBy} canPublish={t.canPublish} onDone={refresh} />}
        <Box sx={{ flexGrow: 1 }} />
        {t?.canEdit && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setEditing({ kind: 'LECTURE', dayOfWeek: 1, startTime: '08:00', endTime: '10:00' })}>Add a class</Button>}
        {t && can(ALLOCATORS) && <Button startIcon={<PlaylistAddIcon />} disabled={prepare.isPending} onClick={() => prepare.mutate()}>Modules from curricula</Button>}
        {t && can(ACADEMIC) && <Button startIcon={<CopyIcon />} onClick={() => setCopyOpen(true)}>Copy a semester</Button>}
        {t && <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/timetable/semesters/${t.semesterId}/export?kind=CLASS`, 'staff', 'class-timetable.xlsx')}>Excel</Button>}
      </Stack>
      {prepare.error ? <ErrorAlert error={prepare.error} /> : null}
      {prepared && (
        <Alert severity={prepared.created ? 'success' : 'info'} onClose={() => setPrepared(null)}>
          {prepared.created} module offering(s) added from the approved curricula.{prepared.skipped.length ? ` Not added: ${prepared.skipped.join('; ')}.` : ''}
          {' '}Allocate lecturers on Marks, then add their classes.
        </Alert>
      )}
      {q.error ? <ErrorAlert error={q.error} onRetry={() => q.refetch()} /> : null}
      {t && !t.offerings.length && (
        <Alert severity="info">No modules are offered in {t.semesterName} yet. Use Modules from curricula to add the modules the approved curricula place in this semester, before students register.</Alert>
      )}
      {t && t.offerings.length > 0 && (
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, lg: 9 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 1.5 }}>
              <TextField select size="small" label="Class (programme and year)" value={cohort} onChange={(e) => setCohort(e.target.value)} sx={{ minWidth: 220 }}>
                <MenuItem value="">All</MenuItem>
                {cohorts.map(([k, label]) => <MenuItem key={k} value={k}>{label}</MenuItem>)}
              </TextField>
              <TextField select size="small" label="Venue" value={venue} onChange={(e) => setVenue(e.target.value === '' ? '' : Number(e.target.value))} sx={{ minWidth: 180 }}>
                <MenuItem value="">All</MenuItem>
                {t.venues.map((v) => <MenuItem key={v.id} value={v.id}>{v.code} · {v.name}</MenuItem>)}
              </TextField>
              <TextField select size="small" label="Lecturer" value={lecturer} onChange={(e) => setLecturer(e.target.value)} sx={{ minWidth: 200 }}>
                <MenuItem value="">All</MenuItem>
                {lecturers.flatMap((l) => l.split(', ')).filter((v, i, a) => a.indexOf(v) === i).map((l) => <MenuItem key={l} value={l}>{l}</MenuItem>)}
              </TextField>
              {highlight.size > 0 && <Button size="small" onClick={() => setHighlight(new Set())}>Clear highlight</Button>}
            </Stack>
            <WeekGrid slots={visible} highlight={highlight} onOpen={t.canEdit ? openSlot : undefined} />
          </Grid>
          <Grid size={{ xs: 12, lg: 3 }}>
            <Typography variant="subtitle1" fontWeight={700} sx={{ mb: 1 }}>Clashes and checks</Typography>
            <Warnings warnings={t.warnings} onShow={(ids) => setHighlight(new Set(ids))} />
          </Grid>
          <Grid size={12}>
            <ModulesTable offerings={t.offerings} onAdd={(o) => setEditing({ offeringId: o.id, kind: 'LECTURE', dayOfWeek: 1, startTime: '08:00', endTime: '10:00' })} />
          </Grid>
        </Grid>
      )}
      {editing && t && <SlotDialog timetable={t} initial={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh(); }} />}
      {copyOpen && t && (
        <CopyDialog semesterId={t.semesterId} semesters={(semesters.data ?? []).filter((s) => s.id !== t.semesterId)} onClose={() => setCopyOpen(false)} onDone={refresh} />
      )}
    </Stack>
  );
}

function ModulesTable({ offerings, onAdd }: { offerings: TimetableOffering[]; onAdd: (o: TimetableOffering) => void }) {
  return (
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" fontWeight={700} sx={{ mb: 1 }}>Modules this semester</Typography>
        <TableContainer>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Module</TableCell><TableCell>Classes (year)</TableCell><TableCell>Lecturers</TableCell>
                <TableCell align="right">Students</TableCell><TableCell align="right">Per week</TableCell><TableCell />
              </TableRow>
            </TableHead>
            <TableBody>
              {offerings.map((o) => (
                <TableRow key={o.id} sx={{ bgcolor: o.slots ? undefined : 'action.hover' }}>
                  <TableCell><strong>{o.code}</strong> {o.title}<Typography variant="caption" color="text.secondary" display="block">{o.departmentName ?? ''}</Typography></TableCell>
                  <TableCell>{o.cohorts.map((c) => `${c.programmeCode} (${c.yearOfStudy})${c.category === 'ELECTIVE' ? ` ${tr('elective')}` : ''}`).join(', ') || '—'}</TableCell>
                  <TableCell>{o.lecturers.map((p) => p.fullName).join(', ') || <Typography variant="body2" color="warning.main">Not allocated</Typography>}</TableCell>
                  <TableCell align="right">{o.students}</TableCell>
                  <TableCell align="right">{o.slots ? `${hours(o.minutesPerWeek)} · ${o.slots}` : 'No class'}</TableCell>
                  <TableCell align="right">{o.canEdit && <Button size="small" startIcon={<AddIcon />} onClick={() => onAdd(o)}>Add a class</Button>}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </CardContent>
    </Card>
  );
}

function SlotDialog({ timetable, initial, onClose, onSaved }: {
  timetable: SemesterTimetable; initial: Partial<SlotRequest> & { id?: number }; onClose: () => void; onSaved: () => void;
}) {
  const [f, setF] = useState(initial);
  const editable = timetable.offerings.filter((o) => o.canEdit);
  const offering = timetable.offerings.find((o) => o.id === f.offeringId);
  const save = useMutation({
    mutationFn: () => api(f.id ? `/api/timetable/slots/${f.id}` : `/api/timetable/semesters/${timetable.semesterId}/slots`, {
      method: f.id ? 'PUT' : 'POST', ...staff,
      body: { offeringId: f.offeringId, kind: f.kind, dayOfWeek: f.dayOfWeek, startTime: f.startTime, endTime: f.endTime, venueId: f.venueId,
        lecturerId: f.lecturerId || null, groupLabel: f.groupLabel || null, note: f.note || null },
    }),
    onSuccess: onSaved,
  });
  const remove = useMutation({ mutationFn: () => api(`/api/timetable/slots/${f.id}`, { method: 'DELETE', ...staff }), onSuccess: onSaved });
  const set = (k: keyof SlotRequest, v: unknown) => setF({ ...f, [k]: v });
  const ok = f.offeringId && f.kind && f.dayOfWeek && f.startTime && f.endTime && f.venueId;
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{f.id ? 'Change class' : 'Add a class'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select label="Module" value={f.offeringId ?? ''} onChange={(e) => setF({ ...f, offeringId: Number(e.target.value), lecturerId: null })}>
            {editable.map((o) => <MenuItem key={o.id} value={o.id}>{o.code} · {o.title}</MenuItem>)}
          </TextField>
          <Stack direction="row" spacing={2}>
            <TextField select label="Type" value={f.kind ?? 'LECTURE'} onChange={(e) => set('kind', e.target.value)} sx={{ flex: 1 }}>
              {KINDS.map((k) => <MenuItem key={k} value={k}>{kindLabel(k)}</MenuItem>)}
            </TextField>
            <TextField label="Group" placeholder="e.g. Group A" value={f.groupLabel ?? ''} onChange={(e) => set('groupLabel', e.target.value)} sx={{ flex: 1 }}
              helperText="Only for part of the class" />
          </Stack>
          <Stack direction="row" spacing={2}>
            <TextField select label="Day" value={f.dayOfWeek ?? 1} onChange={(e) => set('dayOfWeek', Number(e.target.value))} sx={{ flex: 1.4 }}>
              {[1, 2, 3, 4, 5, 6, 7].map((d) => <MenuItem key={d} value={d}>{DAYS[d]}</MenuItem>)}
            </TextField>
            <TextField label="Starts" type="time" value={f.startTime ?? ''} onChange={(e) => set('startTime', e.target.value)} sx={{ flex: 1 }} slotProps={{ inputLabel: { shrink: true } }} />
            <TextField label="Ends" type="time" value={f.endTime ?? ''} onChange={(e) => set('endTime', e.target.value)} sx={{ flex: 1 }} slotProps={{ inputLabel: { shrink: true } }} />
          </Stack>
          <TextField select label="Venue" value={f.venueId ?? ''} onChange={(e) => set('venueId', Number(e.target.value))}
            helperText={offering && f.venueId ? `${offering.students} student(s) registered; the room holds ${timetable.venues.find((v) => v.id === f.venueId)?.capacity ?? '?'}` : undefined}>
            {timetable.venues.map((v) => <MenuItem key={v.id} value={v.id}>{v.code} · {v.name} ({v.capacity})</MenuItem>)}
          </TextField>
          <TextField select label="Lecturer" value={f.lecturerId ?? ''} onChange={(e) => set('lecturerId', e.target.value === '' ? null : Number(e.target.value))}
            helperText="Name the lecturer when the module has several and only one takes this class">
            <MenuItem value="">Any lecturer allocated to the module</MenuItem>
            {(offering?.lecturers ?? []).map((p) => <MenuItem key={p.id} value={p.id}>{p.fullName}</MenuItem>)}
          </TextField>
          <TextField label="Note" value={f.note ?? ''} onChange={(e) => set('note', e.target.value)} />
          {save.error ? <ErrorAlert error={save.error} /> : null}
          {remove.error ? <ErrorAlert error={remove.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        {f.id && <Button color="error" onClick={() => remove.mutate()} disabled={remove.isPending} sx={{ mr: 'auto' }}>Remove</Button>}
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!ok || save.isPending} onClick={() => save.mutate()}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}

function CopyDialog({ semesterId, semesters, onClose, onDone }: {
  semesterId: number; semesters: { id: number; name: string; academicYear?: string }[]; onClose: () => void; onDone: () => void;
}) {
  const [from, setFrom] = useState<number | ''>('');
  const copy = useMutation({
    mutationFn: () => api<{ copied: number; skipped: number; notes: string[] }>(`/api/timetable/semesters/${semesterId}/copy`, { method: 'POST', ...staff, body: { fromSemesterId: from } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Copy classes from another semester</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 2 }}>
          Each module offered in both semesters gets the same classes (day, time, venue, group), unless it already has classes here. Classes that would clash are left out.
        </Typography>
        <TextField select fullWidth label="Copy from" value={from} onChange={(e) => setFrom(Number(e.target.value))}>
          {semesters.map((s) => <MenuItem key={s.id} value={s.id}>{s.name} · {s.academicYear}</MenuItem>)}
        </TextField>
        {copy.error ? <Box sx={{ mt: 2 }}><ErrorAlert error={copy.error} /></Box> : null}
        {copy.data && (
          <Alert severity={copy.data.copied ? 'success' : 'info'} sx={{ mt: 2 }}>
            {copy.data.copied} class(es) copied, {copy.data.skipped} left out.
            {copy.data.notes.length > 0 && <Box component="ul" sx={{ m: 0, pl: 2 }}>{copy.data.notes.map((n, i) => <li key={i}>{n}</li>)}</Box>}
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={!from || copy.isPending || !!copy.data} onClick={() => copy.mutate()}>Copy</Button>
      </DialogActions>
    </Dialog>
  );
}

// ============================================================ venues

function VenuesView() {
  const qc = useQueryClient();
  const can = useRoles();
  const keeper = can(VENUE_KEEPERS);
  const q = useQuery({ queryKey: ['staff', 'venues'], queryFn: () => api<VenueView[]>('/api/timetable/venues', staff) });
  const campuses = useQuery({ queryKey: ['staff', 'campuses'], queryFn: () => api<Campus[]>('/api/academic/campuses', staff), enabled: keeper });
  const [editing, setEditing] = useState<Partial<VenueView> | null>(null);
  const save = useMutation({
    mutationFn: (v: Partial<VenueView>) => api(v.id ? `/api/timetable/venues/${v.id}` : '/api/timetable/venues', { method: v.id ? 'PUT' : 'POST', ...staff,
      body: { code: v.code, name: v.name, kind: v.kind, capacity: v.capacity, examCapacity: v.examCapacity || null, campusId: v.campusId || null, active: v.active ?? true } }),
    onSuccess: () => { setEditing(null); qc.invalidateQueries({ queryKey: ['staff', 'venues'] }); qc.invalidateQueries({ queryKey: ['staff', 'timetable'] }); },
  });
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  return (
    <Stack spacing={2}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography color="text.secondary">Rooms where classes are taught and examinations are sat. Examination seats are the candidates a room holds when spaced out.</Typography>
        {keeper && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setEditing({ kind: 'CLASSROOM', active: true })}>Add venue</Button>}
      </Stack>
      <TableContainer component={Paper} variant="outlined">
        <Table size="small">
          <TableHead>
            <TableRow>
              <TableCell>Code</TableCell><TableCell>Name</TableCell><TableCell>Type</TableCell><TableCell>Campus</TableCell>
              <TableCell align="right">Capacity</TableCell><TableCell align="right">Examination seats</TableCell><TableCell>Status</TableCell><TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {q.data.map((v) => (
              <TableRow key={v.id}>
                <TableCell><strong>{v.code}</strong></TableCell><TableCell>{v.name}</TableCell>
                <TableCell>{VENUE_KINDS.find((k) => k.value === v.kind)?.label}</TableCell><TableCell>{v.campusName ?? '—'}</TableCell>
                <TableCell align="right">{v.capacity}</TableCell><TableCell align="right">{v.examSeats}</TableCell>
                <TableCell>{v.active ? <Chip size="small" color="success" label="In use" /> : <Chip size="small" label="Not in use" />}</TableCell>
                <TableCell align="right">{keeper && <IconButton size="small" aria-label={`Edit ${v.code}`} onClick={() => setEditing(v)}><EditIcon fontSize="small" /></IconButton>}</TableCell>
              </TableRow>
            ))}
            {!q.data.length && <TableRow><TableCell colSpan={8}><Typography color="text.secondary">No venues yet.</Typography></TableCell></TableRow>}
          </TableBody>
        </Table>
      </TableContainer>
      {editing && (
        <Dialog open onClose={() => setEditing(null)} maxWidth="sm" fullWidth>
          <DialogTitle>{editing.id ? `Change ${editing.code}` : 'Add venue'}</DialogTitle>
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              <Stack direction="row" spacing={2}>
                <TextField label="Code" value={editing.code ?? ''} onChange={(e) => setEditing({ ...editing, code: e.target.value })} sx={{ flex: 1 }} />
                <TextField label="Name" value={editing.name ?? ''} onChange={(e) => setEditing({ ...editing, name: e.target.value })} sx={{ flex: 2 }} />
              </Stack>
              <Stack direction="row" spacing={2}>
                <TextField select label="Type" value={editing.kind ?? 'CLASSROOM'} onChange={(e) => setEditing({ ...editing, kind: e.target.value as VenueKind })} sx={{ flex: 1 }}>
                  {VENUE_KINDS.map((k) => <MenuItem key={k.value} value={k.value}>{k.label}</MenuItem>)}
                </TextField>
                <TextField select label="Campus" value={editing.campusId ?? ''} onChange={(e) => setEditing({ ...editing, campusId: e.target.value === '' ? null : Number(e.target.value) })} sx={{ flex: 1 }}>
                  <MenuItem value="">—</MenuItem>
                  {(campuses.data ?? []).map((c) => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}
                </TextField>
              </Stack>
              <Stack direction="row" spacing={2}>
                <TextField type="number" label="Capacity" value={editing.capacity ?? ''} onChange={(e) => setEditing({ ...editing, capacity: Number(e.target.value) })} sx={{ flex: 1 }} />
                <TextField type="number" label="Examination seats" value={editing.examCapacity ?? ''} helperText="Empty: the capacity"
                  onChange={(e) => setEditing({ ...editing, examCapacity: e.target.value === '' ? null : Number(e.target.value) })} sx={{ flex: 1 }} />
              </Stack>
              <FormControlLabel control={<Switch checked={editing.active ?? true} onChange={(e) => setEditing({ ...editing, active: e.target.checked })} />} label="In use" />
              {save.error ? <ErrorAlert error={save.error} /> : null}
            </Stack>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setEditing(null)}>Cancel</Button>
            <Button variant="contained" disabled={!editing.code || !editing.name || !editing.capacity || save.isPending} onClick={() => save.mutate(editing)}>Save</Button>
          </DialogActions>
        </Dialog>
      )}
    </Stack>
  );
}

// ============================================================ a lecturer's own timetable

function MyTimetableView() {
  const [semesterId, setSemesterId] = useState<number>();
  const q = useQuery({ queryKey: ['staff', 'timetable', 'mine', semesterId ?? 0],
    queryFn: () => api<MyTimetable>(`/api/timetable/mine${semesterId ? `?semesterId=${semesterId}` : ''}`, staff) });
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  const d = q.data;
  if (!d.semester) return <Alert severity="info">You have no classes or invigilation yet. Your Head of Department allocates modules each semester.</Alert>;
  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }}>
        <TextField select size="small" label="Semester" value={d.semester.id} onChange={(e) => setSemesterId(Number(e.target.value))} sx={{ minWidth: 240, flex: '0 0 auto' }}>
          {d.semesters.map((s) => <MenuItem key={s.id} value={s.id}>{s.name} · {s.yearCode}</MenuItem>)}
        </TextField>
        <Chip label={d.classPublished ? 'Class timetable published' : 'Class timetable not published yet'} color={d.classPublished ? 'success' : 'default'} />
        <Chip label={d.examPublished ? 'Examination timetable published' : 'Examination timetable not published yet'} color={d.examPublished ? 'success' : 'default'} />
      </Stack>
      <Typography variant="h6">My classes</Typography>
      <WeekGrid slots={d.slots} showLecturer={false} />
      <Typography variant="h6">Examinations</Typography>
      {d.exams.length ? <PapersTable sittings={d.exams.map((e) => e.sitting)} invigilating={new Set(d.exams.filter((e) => e.invigilating).map((e) => e.sitting.id))} />
        : <Alert severity="info">No papers of your modules, or to invigilate, yet.</Alert>}
    </Stack>
  );
}

// ============================================================ the examination timetable

function PapersTable({ sittings, onOpen, invigilating }: { sittings: SittingView[]; onOpen?: (s: SittingView) => void; invigilating?: Set<number> }) {
  return (
    <TableContainer component={Paper} variant="outlined">
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Date</TableCell><TableCell>Time</TableCell><TableCell>Module</TableCell><TableCell>Paper</TableCell><TableCell>Venues</TableCell>
            <TableCell align="right">Candidates / seats</TableCell><TableCell>Invigilators</TableCell>{onOpen && <TableCell />}
          </TableRow>
        </TableHead>
        <TableBody>
          {sittings.map((s) => (
            <TableRow key={s.id}>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDate(s.date)}</TableCell>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{hm(s.startTime)}–{hm(s.endTime)}</TableCell>
              <TableCell><strong>{s.moduleCode}</strong> {s.moduleTitle}</TableCell>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{s.paper}{invigilating?.has(s.id) && <Chip size="small" color="primary" label="Invigilating" sx={{ ml: 1 }} />}</TableCell>
              <TableCell>{s.venues.map((v) => v.code).join(', ')}</TableCell>
              <TableCell align="right" sx={{ color: s.candidates > s.seats ? 'error.main' : undefined }}>{s.candidates} / {s.seats}</TableCell>
              <TableCell>{s.invigilators.map((p) => p.fullName).join(', ') || '—'}</TableCell>
              {onOpen && <TableCell align="right"><IconButton size="small" aria-label={`Change ${s.moduleCode} ${s.paper}`} onClick={() => onOpen(s)}><EditIcon fontSize="small" /></IconButton></TableCell>}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

export function ExamTimetablePage() {
  const qc = useQueryClient();
  const can = useRoles();
  const { semesterId, semesters, picker } = useSemesterChoice();
  const [editing, setEditing] = useState<Partial<SittingRequest> & { id?: number } | null>(null);
  const q = useQuery({ queryKey: ['staff', 'exam-timetable', semesterId], enabled: !!semesterId,
    queryFn: () => api<ExamTimetable>(`/api/timetable/semesters/${semesterId}/exams`, staff) });
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff', 'exam-timetable'] });
  if (semesters.isLoading || (semesterId && q.isLoading)) return <Loading />;
  const t = q.data;
  const unscheduled = (t?.offerings ?? []).filter((o) => o.candidates > 0 && o.papers === 0);
  return (
    <>
      <PageTitle title="Examination timetable" subtitle="When and where each paper is sat, who invigilates, and the checks that no student sits two papers at once." />
      <Stack spacing={2}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }} flexWrap="wrap" useFlexGap>
          <Box sx={{ flex: '0 0 auto' }}>{picker}</Box>
          {t && <PublishBar kind="EXAM" semesterId={t.semesterId} publishedAt={t.publishedAt} publishedBy={t.publishedBy} canPublish={t.canEdit} onDone={refresh} />}
          <Box sx={{ flexGrow: 1 }} />
          {t?.canEdit && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setEditing({ paper: 'Paper 1', startTime: '09:00', endTime: '12:00', venueIds: [], invigilatorIds: [] })}>Add paper</Button>}
          {t && <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/timetable/semesters/${t.semesterId}/export?kind=EXAM`, 'staff', 'exam-timetable.xlsx')}>Excel</Button>}
        </Stack>
        {q.error ? <ErrorAlert error={q.error} onRetry={() => q.refetch()} /> : null}
        {t && (
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, lg: 9 }}>
              {t.sittings.length ? <PapersTable sittings={t.sittings} onOpen={t.canEdit ? (s) => setEditing({ id: s.id, offeringId: s.offeringId, paper: s.paper, date: s.date,
                startTime: hm(s.startTime), endTime: hm(s.endTime), venueIds: s.venues.map((v) => v.id), invigilatorIds: s.invigilators.map((p) => p.id), note: s.note ?? '' }) : undefined} />
                : <Alert severity="info">No papers yet for {t.semesterName}.</Alert>}
            </Grid>
            <Grid size={{ xs: 12, lg: 3 }}>
              <Typography variant="subtitle1" fontWeight={700} sx={{ mb: 1 }}>Clashes and checks</Typography>
              <Warnings warnings={t.warnings} />
              {unscheduled.length > 0 && can(EXAMS_OFFICE) && (
                <Card variant="outlined" sx={{ mt: 2 }}>
                  <CardContent>
                    <Typography variant="subtitle2" sx={{ mb: 1 }}>Modules with candidates but no paper</Typography>
                    {unscheduled.map((o) => (
                      <Stack key={o.id} direction="row" justifyContent="space-between" alignItems="center">
                        <Typography variant="body2">{o.code} · {o.candidates}</Typography>
                        <Button size="small" onClick={() => setEditing({ offeringId: o.id, paper: 'Paper 1', startTime: '09:00', endTime: '12:00', venueIds: [], invigilatorIds: [] })}>Add</Button>
                      </Stack>
                    ))}
                  </CardContent>
                </Card>
              )}
            </Grid>
          </Grid>
        )}
        {editing && t && <PaperDialog timetable={t} initial={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh(); }} />}
      </Stack>
    </>
  );
}

function PaperDialog({ timetable, initial, onClose, onSaved }: {
  timetable: ExamTimetable; initial: Partial<SittingRequest> & { id?: number }; onClose: () => void; onSaved: () => void;
}) {
  const [f, setF] = useState(initial);
  const offering: ExamOffering | undefined = timetable.offerings.find((o) => o.id === f.offeringId);
  const chosen = timetable.venues.filter((v) => f.venueIds?.includes(v.id));
  const seats = chosen.reduce((n, v) => n + v.examSeats, 0);
  const save = useMutation({
    mutationFn: () => api(f.id ? `/api/timetable/exams/${f.id}` : `/api/timetable/semesters/${timetable.semesterId}/exams`, {
      method: f.id ? 'PUT' : 'POST', ...staff,
      body: { offeringId: f.offeringId, paper: f.paper, date: f.date, startTime: f.startTime, endTime: f.endTime, venueIds: f.venueIds ?? [],
        invigilatorIds: f.invigilatorIds ?? [], note: f.note || null },
    }),
    onSuccess: onSaved,
  });
  const remove = useMutation({ mutationFn: () => api(`/api/timetable/exams/${f.id}`, { method: 'DELETE', ...staff }), onSuccess: onSaved });
  const ok = f.offeringId && f.date && f.startTime && f.endTime && (f.venueIds?.length ?? 0) > 0;
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{f.id ? 'Change paper' : 'Add paper'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select label="Module" value={f.offeringId ?? ''} disabled={!!f.id} onChange={(e) => setF({ ...f, offeringId: Number(e.target.value) })}
            helperText={offering ? `${offering.candidates} candidate(s) registered` : undefined}>
            {timetable.offerings.map((o) => <MenuItem key={o.id} value={o.id}>{o.code} · {o.title}</MenuItem>)}
          </TextField>
          <TextField label="Paper" value={f.paper ?? ''} onChange={(e) => setF({ ...f, paper: e.target.value })} helperText="e.g. Paper 1, Practical" />
          <Stack direction="row" spacing={2}>
            <TextField label="Date" type="date" value={f.date ?? ''} onChange={(e) => setF({ ...f, date: e.target.value })} sx={{ flex: 1.4 }}
              slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: timetable.startDate, max: timetable.endDate } }} />
            <TextField label="Starts" type="time" value={f.startTime ?? ''} onChange={(e) => setF({ ...f, startTime: e.target.value })} sx={{ flex: 1 }} slotProps={{ inputLabel: { shrink: true } }} />
            <TextField label="Ends" type="time" value={f.endTime ?? ''} onChange={(e) => setF({ ...f, endTime: e.target.value })} sx={{ flex: 1 }} slotProps={{ inputLabel: { shrink: true } }} />
          </Stack>
          <Autocomplete multiple options={timetable.venues} value={chosen} getOptionLabel={(v: VenueView) => `${v.code} · ${v.name} (${v.examSeats} seats)`}
            onChange={(_, v) => setF({ ...f, venueIds: v.map((x) => x.id) })} isOptionEqualToValue={(a, b) => a.id === b.id}
            renderInput={(p) => <TextField {...p} label="Venues" helperText={offering ? `${seats} seat(s) for ${offering.candidates} candidate(s)` : undefined}
              error={!!offering && seats > 0 && seats < offering.candidates} />} />
          <Autocomplete multiple options={timetable.staff} value={timetable.staff.filter((p) => f.invigilatorIds?.includes(p.id))}
            getOptionLabel={(p: Person) => p.fullName} isOptionEqualToValue={(a, b) => a.id === b.id}
            onChange={(_, v) => setF({ ...f, invigilatorIds: v.map((x) => x.id) })} renderInput={(p) => <TextField {...p} label="Invigilators" />} />
          <TextField label="Note" value={f.note ?? ''} onChange={(e) => setF({ ...f, note: e.target.value })} />
          {save.error ? <ErrorAlert error={save.error} /> : null}
          {remove.error ? <ErrorAlert error={remove.error} /> : null}
        </Stack>
      </DialogContent>
      <DialogActions>
        {f.id && <Button color="error" onClick={() => remove.mutate()} disabled={remove.isPending} sx={{ mr: 'auto' }}>Remove</Button>}
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!ok || save.isPending} onClick={() => save.mutate()}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}

export { SHORT as DAY_SHORT };
