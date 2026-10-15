import AddIcon from '@mui/icons-material/Add';
import EditIcon from '@mui/icons-material/EditOutlined';
import DownloadIcon from '@mui/icons-material/Download';
import {
  Alert, Autocomplete, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, Divider, IconButton,
  MenuItem, Stack, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, download, formatDate, formatDateTime } from '../../api/client';
import type {
  IptEligibleStudent, IptLogbookEntry, IptOrganisation, IptPeriod, IptPlacementSummary, IptPlacementView, IptStatus, Semester, StaffOption,
} from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';

const staff = { realm: 'staff' as const };
const COORD = ['INSTITUTION_ADMIN', 'IPT_COORDINATOR'];
const VIEWERS = ['INSTITUTION_ADMIN', 'IPT_COORDINATOR', 'REGISTRAR', 'DEPUTY_PRINCIPAL_ACADEMIC', 'HEAD_OF_DEPARTMENT', 'EXAMINATIONS_OFFICER', 'MANAGEMENT_VIEWER'];
export const IPT_COLOUR = { REQUESTED: 'warning', CONFIRMED: 'info', COMPLETED: 'success', CANCELLED: 'default', DECLINED: 'error' } as const;
export const LOG_COLOUR = { DRAFT: 'default', SUBMITTED: 'info', APPROVED: 'success', RETURNED: 'warning' } as const;
const title = (s: string) => s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ');
const blank = (s: string) => (s.trim() ? s.trim() : null);

/** Industrial practical training (FR-IPT-01..03): placements, organisations and periods for the coordinator; supervised students for staff. */
export function IptPage() {
  const can = useRoles();
  const viewer = can(VIEWERS);
  const coord = can(COORD);
  const [tab, setTab] = useState<'placements' | 'supervised' | 'organisations' | 'periods'>(viewer ? 'placements' : 'supervised');
  const [open, setOpen] = useState<number | null>(null);
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Industrial practical training" subtitle="Placements, letters, weekly logbooks, supervision visits, the three IPT marks and their transfer to results." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2, borderBottom: 1, borderColor: 'divider' }} variant="scrollable">
        {viewer && <Tab value="placements" label="Placements" />}
        <Tab value="supervised" label="My students" />
        {viewer && <Tab value="organisations" label="Organisations" />}
        {viewer && <Tab value="periods" label="IPT periods" />}
      </Tabs>
      {tab === 'placements' && <PlacementsTab coord={coord} onOpen={setOpen} />}
      {tab === 'supervised' && <SupervisedTab onOpen={setOpen} />}
      {tab === 'organisations' && <OrganisationsTab coord={coord} />}
      {tab === 'periods' && <PeriodsTab coord={coord} />}
      {open != null && <PlacementDialog id={open} onClose={() => setOpen(null)} />}
    </Container>
  );
}

function PlacementTable({ rows, onOpen, empty }: { rows: IptPlacementSummary[]; onOpen: (id: number) => void; empty: string }) {
  return (
    <TableContainer component={Card}>
      <Table size="small">
        <TableHead><TableRow>
          <TableCell>Student</TableCell><TableCell>Organisation</TableCell><TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Supervisor</TableCell>
          <TableCell align="center">Logbook</TableCell><TableCell align="right">IPT mark</TableCell><TableCell>Status</TableCell>
        </TableRow></TableHead>
        <TableBody>
          {rows.map((p) => (
            <TableRow key={p.id} hover sx={{ cursor: 'pointer' }} onClick={() => onOpen(p.id)}>
              <TableCell>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>{p.registrationNo}</Typography>
                <Typography variant="caption" color="text.secondary">{p.studentName} · {p.programmeCode}</Typography>
              </TableCell>
              <TableCell>
                <Typography variant="body2">{p.organisation}</Typography>
                <Typography variant="caption" color="text.secondary">{p.region ? `${p.region} · ` : ''}{formatDate(p.startsOn)} – {formatDate(p.endsOn)}</Typography>
              </TableCell>
              <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>
                {p.institutionSupervisorName ?? <Typography variant="body2" color="warning.main">Not assigned</Typography>}
              </TableCell>
              <TableCell align="center">
                {p.logbookApproved}/{p.logbookWeeks}
                {p.logbookPending > 0 && <Chip size="small" color="info" label={`${p.logbookPending} to review`} sx={{ ml: 0.5 }} />}
              </TableCell>
              <TableCell align="right">{p.totalMark ?? (p.scores ? `${p.scores}/3 marks` : '—')}{p.transferredAt && <Tooltip title={`Sent to results ${formatDateTime(p.transferredAt)}`}><Chip size="small" label="sent" sx={{ ml: 0.5 }} /></Tooltip>}</TableCell>
              <TableCell><Chip size="small" color={IPT_COLOUR[p.status]} label={title(p.status)} /></TableCell>
            </TableRow>
          ))}
          {!rows.length && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 1 }}>{empty}</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

// ============================================================ placements of a period

function PlacementsTab({ coord, onOpen }: { coord: boolean; onOpen: (id: number) => void }) {
  const qc = useQueryClient();
  const periods = useQuery({ queryKey: ['staff', 'ipt-periods'], queryFn: () => api<IptPeriod[]>('/api/ipt/periods', staff) });
  const [periodId, setPeriodId] = useState<number>();
  useEffect(() => { if (!periodId && periods.data?.length) setPeriodId(periods.data[0].id); }, [periods.data, periodId]);
  const [view, setView] = useState<'placements' | 'students'>('placements');
  const [assigning, setAssigning] = useState<IptEligibleStudent | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const list = useQuery({ queryKey: ['staff', 'ipt-placements', periodId], enabled: !!periodId, queryFn: () => api<IptPlacementSummary[]>(`/api/ipt/periods/${periodId}/placements`, staff) });
  const students = useQuery({ queryKey: ['staff', 'ipt-students', periodId], enabled: !!periodId && view === 'students', queryFn: () => api<IptEligibleStudent[]>(`/api/ipt/periods/${periodId}/students`, staff) });
  const transfer = useMutation({
    mutationFn: () => api<{ transferred: number; notReady: string[]; moduleCode: string }>(`/api/ipt/periods/${periodId}/transfer`, { ...staff, method: 'POST' }),
    onSuccess: (r) => { setNotice(`${r.transferred} IPT mark${r.transferred === 1 ? '' : 's'} sent to ${r.moduleCode}; the results now go through the usual approval.${r.notReady.length ? ' Not ready: ' + r.notReady.join('; ') : ''}`); qc.invalidateQueries({ queryKey: ['staff'] }); },
  });
  const p = periods.data?.find((x) => x.id === periodId);
  if (periods.isLoading) return <Loading />;
  if (!periods.data?.length) return <Alert severity="info">No IPT periods yet. Create one under IPT periods.</Alert>;
  const live = (list.data ?? []).filter((x) => x.status !== 'CANCELLED' && x.status !== 'DECLINED');
  const requests = live.filter((x) => x.status === 'REQUESTED');
  return (
    <>
      <ErrorAlert error={periods.error ?? list.error ?? transfer.error} />
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} sx={{ mb: 1.5 }} alignItems={{ md: 'center' }} justifyContent="space-between">
        <TextField select size="small" label="Period" value={periodId ?? ''} onChange={(e) => setPeriodId(Number(e.target.value))} sx={{ minWidth: 280 }}>
          {periods.data.map((x) => <MenuItem key={x.id} value={x.id}>{x.name} · {x.semester}</MenuItem>)}
        </TextField>
        {coord && p?.moduleCode && (
          <Tooltip title={`Sends the IPT marks of completed placements to ${p.moduleCode}; its results then go to the Head of Department as usual`}>
            <span><Button variant="contained" onClick={() => transfer.mutate()} disabled={transfer.isPending || !live.some((x) => x.totalMark != null)}>Send marks to {p.moduleCode}</Button></span>
          </Tooltip>
        )}
      </Stack>
      {p && (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          {formatDate(p.startsOn)} – {formatDate(p.endsOn)} · {p.moduleCode ? `${p.moduleCode} ${p.moduleTitle}` : 'not linked to a module'} · marks: workplace {p.workplaceWeight}%,
          institution {p.institutionWeight}%, logbook {p.logbookWeight}% · {p.eligible} students, {p.confirmed + p.completed} placed{p.resultsTransferredAt ? ` · marks sent ${formatDateTime(p.resultsTransferredAt)}` : ''}
        </Typography>
      )}
      {notice && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>{notice}</Alert>}
      {requests.length > 0 && <Alert severity="warning" sx={{ mb: 2 }}>{requests.length} placement request{requests.length === 1 ? '' : 's'} from students waiting for a decision.</Alert>}
      <Stack direction="row" spacing={1} sx={{ mb: 1.5 }}>
        <Chip clickable label={`Placements (${live.length})`} color={view === 'placements' ? 'primary' : 'default'} variant={view === 'placements' ? 'filled' : 'outlined'} onClick={() => setView('placements')} />
        <Chip clickable label={`Students (${p?.eligible ?? 0})`} color={view === 'students' ? 'primary' : 'default'} variant={view === 'students' ? 'filled' : 'outlined'} onClick={() => setView('students')} />
      </Stack>
      {view === 'placements' && (list.isLoading ? <Loading /> : <PlacementTable rows={live} onOpen={onOpen} empty="No placements yet." />)}
      {view === 'students' && (students.isLoading ? <Loading /> : (
        <TableContainer component={Card}>
          <Table size="small">
            <TableHead><TableRow><TableCell>Student</TableCell><TableCell>Phone</TableCell><TableCell>Placement</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {(students.data ?? []).map((s) => (
                <TableRow key={s.studentId}>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{s.registrationNo}</Typography><Typography variant="caption" color="text.secondary">{s.name} · {s.programmeCode} year {s.yearOfStudy}</Typography></TableCell>
                  <TableCell>{s.phone ?? '—'}</TableCell>
                  <TableCell>{s.placementStatus ? <Chip size="small" clickable color={IPT_COLOUR[s.placementStatus as IptStatus]} label={title(s.placementStatus)} onClick={() => onOpen(s.placementId!)} /> : <Typography variant="body2" color="warning.main">Not placed</Typography>}</TableCell>
                  <TableCell align="right">{coord && !s.placementId && <Button size="small" onClick={() => setAssigning(s)}>Place</Button>}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      ))}
      {assigning && periodId && <AssignDialog periodId={periodId} student={assigning} onClose={() => setAssigning(null)}
        onDone={() => { setAssigning(null); setNotice('Placement confirmed; the letter is ready and the student was told by SMS.'); qc.invalidateQueries({ queryKey: ['staff'] }); }} />}
    </>
  );
}

function useOrganisations() {
  return useQuery({ queryKey: ['staff', 'ipt-orgs'], queryFn: () => api<IptOrganisation[]>('/api/ipt/organisations', staff) });
}

function useSupervisors(enabled = true) {
  return useQuery({ queryKey: ['staff', 'ipt-staff'], enabled, queryFn: () => api<StaffOption[]>('/api/ipt/staff', staff) });
}

function AssignDialog({ periodId, student, onClose, onDone }: { periodId: number; student: IptEligibleStudent; onClose: () => void; onDone: () => void }) {
  const orgs = useOrganisations();
  const sups = useSupervisors();
  const [org, setOrg] = useState<IptOrganisation | null>(null);
  const [sup, setSup] = useState<StaffOption | null>(null);
  const [unit, setUnit] = useState('');
  const [wsName, setWsName] = useState('');
  const [wsPhone, setWsPhone] = useState('');
  const m = useMutation({
    mutationFn: () => api('/api/ipt/placements', { ...staff, method: 'POST', body: { periodId, studentId: student.studentId, organisationId: org?.id,
      institutionSupervisorId: sup?.id ?? null, unit: blank(unit), workplaceSupervisorName: blank(wsName), workplaceSupervisorPhone: blank(wsPhone) } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Place {student.registrationNo} · {student.name}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Autocomplete options={(orgs.data ?? []).filter((o) => o.active)} value={org} onChange={(_, v) => setOrg(v)} getOptionLabel={(o) => o.name + (o.region ? ` (${o.region})` : '')}
            renderInput={(p) => <TextField {...p} label="Organisation" />} />
          <Autocomplete options={sups.data ?? []} value={sup} onChange={(_, v) => setSup(v)} getOptionLabel={(o) => o.fullName}
            renderInput={(p) => <TextField {...p} label="Institution supervisor" />} />
          <TextField label="Department / unit" value={unit} onChange={(e) => setUnit(e.target.value)} />
          <Stack direction="row" spacing={1.5}>
            <TextField fullWidth label="Workplace supervisor" value={wsName} onChange={(e) => setWsName(e.target.value)} />
            <TextField fullWidth label="Their phone" value={wsPhone} onChange={(e) => setWsPhone(e.target.value)} />
          </Stack>
          <ErrorAlert error={m.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!org || m.isPending} onClick={() => m.mutate()}>Confirm placement</Button></DialogActions>
    </Dialog>
  );
}

function SupervisedTab({ onOpen }: { onOpen: (id: number) => void }) {
  const q = useQuery({ queryKey: ['staff', 'ipt-supervised'], queryFn: () => api<IptPlacementSummary[]>('/api/ipt/supervised', staff) });
  if (q.isLoading) return <Loading />;
  return (
    <>
      <ErrorAlert error={q.error} />
      <PlacementTable rows={q.data ?? []} onOpen={onOpen} empty="You are not supervising any student on IPT." />
    </>
  );
}

// ============================================================ one placement

function PlacementDialog({ id, onClose }: { id: number; onClose: () => void }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'ipt-placement', id], queryFn: () => api<IptPlacementView>(`/api/ipt/placements/${id}`, staff) });
  const v = q.data;
  const refresh = (d?: unknown) => { if (d) qc.setQueryData(['staff', 'ipt-placement', id], d); qc.invalidateQueries({ queryKey: ['staff', 'ipt-placements'] }); qc.invalidateQueries({ queryKey: ['staff', 'ipt-supervised'] }); };
  const sups = useSupervisors(!!v?.can.manage);
  const [sup, setSup] = useState<StaffOption | null>(null);
  const [note, setNote] = useState('');
  const decide = useMutation({
    mutationFn: (confirm: boolean) => api<IptPlacementView>(`/api/ipt/placements/${id}/decision`, { ...staff, method: 'POST', body: { confirm, note: blank(note), institutionSupervisorId: sup?.id ?? null } }),
    onSuccess: refresh,
  });
  const review = useMutation({
    mutationFn: (p: { entry: IptLogbookEntry; approve: boolean; comment: string }) => api(`/api/ipt/logbook/${p.entry.id}/review`, { ...staff, method: 'POST', body: { approve: p.approve, comment: blank(p.comment) } }),
    onSuccess: () => { q.refetch(); refresh(); },
  });
  const [comments, setComments] = useState<Record<number, string>>({});
  const [visit, setVisit] = useState({ visitedOn: new Date().toISOString().slice(0, 10), mode: 'VISIT', notes: '' });
  const addVisit = useMutation({ mutationFn: () => api<IptPlacementView>(`/api/ipt/placements/${id}/visits`, { ...staff, method: 'POST', body: visit }), onSuccess: (d) => { setVisit({ ...visit, notes: '' }); refresh(d); } });
  const [marks, setMarks] = useState<Record<string, string>>({});
  const assess = useMutation({
    mutationFn: (kind: string) => api<IptPlacementView>(`/api/ipt/placements/${id}/assessments`, { ...staff, method: 'PUT', body: { kind, score: Number(marks[kind]) } }),
    onSuccess: refresh,
  });
  const [linkOpen, setLinkOpen] = useState(false);
  const s = v?.summary;
  const weight = { WORKPLACE: v?.workplaceWeight, INSTITUTION: v?.institutionWeight, LOGBOOK: v?.logbookWeight } as Record<string, number | undefined>;
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{s ? `${s.registrationNo} · ${s.studentName}` : 'Placement'}</DialogTitle>
      <DialogContent dividers>
        <ErrorAlert error={q.error ?? decide.error ?? review.error ?? addVisit.error ?? assess.error} />
        {q.isLoading && <Loading />}
        {v && s && (
          <Stack spacing={2}>
            <Grid container spacing={2}>
              <Grid size={{ xs: 12, md: 7 }}>
                <Stack direction="row" spacing={1} alignItems="center"><Typography variant="h6">{s.organisation}</Typography><Chip size="small" color={IPT_COLOUR[s.status]} label={title(s.status)} /></Stack>
                <Typography variant="body2" color="text.secondary">{[s.region, v.organisationAddress].filter(Boolean).join(' · ')}</Typography>
                <Typography variant="body2" sx={{ mt: 1 }}>{formatDate(s.startsOn)} – {formatDate(s.endsOn)}{v.unit ? ` · ${v.unit}` : ''}</Typography>
                <Typography variant="body2">Workplace supervisor: {s.workplaceSupervisorName ?? '—'}{v.workplaceSupervisorPhone ? ` · ${v.workplaceSupervisorPhone}` : ''}</Typography>
                <Typography variant="body2">Institution supervisor: {s.institutionSupervisorName ?? '—'}</Typography>
                {v.organisationContact && <Typography variant="caption" color="text.secondary">Organisation contact: {v.organisationContact}{v.organisationPhone ? `, ${v.organisationPhone}` : ''}</Typography>}
                {v.studentNote && <Typography variant="body2" sx={{ mt: 1 }}>Student's note: “{v.studentNote}”</Typography>}
                {v.decisionNote && <Typography variant="body2" color="text.secondary">Note: {v.decisionNote}</Typography>}
              </Grid>
              <Grid size={{ xs: 12, md: 5 }}>
                <Card variant="outlined"><CardContent>
                  <Typography variant="caption" color="text.secondary">IPT mark</Typography>
                  <Typography variant="h4" sx={{ fontWeight: 700 }}>{s.totalMark ?? '—'}</Typography>
                  <Typography variant="caption" color="text.secondary">{s.transferredAt ? `Sent to results ${formatDateTime(s.transferredAt)}` : `${s.scores} of 3 marks recorded`}</Typography>
                  {s.letterNo && <Box sx={{ mt: 1 }}><Button size="small" startIcon={<DownloadIcon />} onClick={() => download(`/api/ipt/placements/${id}/letter`, 'staff', `placement-letter-${s.registrationNo.replace(/\W+/g, '-')}.pdf`)}>Placement letter</Button></Box>}
                </CardContent></Card>
              </Grid>
            </Grid>

            {v.can.manage && s.status === 'REQUESTED' && (
              <Card variant="outlined" sx={{ borderColor: 'warning.main' }}><CardContent>
                <Typography variant="subtitle2" gutterBottom>Placement request</Typography>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
                  <Autocomplete sx={{ minWidth: 240 }} options={sups.data ?? []} value={sup} onChange={(_, x) => setSup(x)} getOptionLabel={(o) => o.fullName}
                    renderInput={(p) => <TextField {...p} size="small" label="Institution supervisor" />} />
                  <TextField size="small" fullWidth label="Note (required to decline)" value={note} onChange={(e) => setNote(e.target.value)} />
                </Stack>
                <Stack direction="row" spacing={1} justifyContent="flex-end" sx={{ mt: 1.5 }}>
                  <Button color="error" onClick={() => decide.mutate(false)} disabled={decide.isPending || !note.trim()}>Decline</Button>
                  <Button variant="contained" onClick={() => decide.mutate(true)} disabled={decide.isPending}>Confirm and issue letter</Button>
                </Stack>
              </CardContent></Card>
            )}

            <Box>
              <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>Logbook</Typography>
              {!v.logbook.length && <Typography variant="body2" color="text.secondary">No entries yet.</Typography>}
              {v.logbook.map((e) => (
                <Box key={e.id} sx={{ py: 1, borderBottom: 1, borderColor: 'divider' }}>
                  <Stack direction="row" justifyContent="space-between" alignItems="center">
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>Week {e.weekNo} · from {formatDate(e.weekStarting)}{e.hours != null ? ` · ${e.hours} h` : ''}</Typography>
                    <Chip size="small" color={LOG_COLOUR[e.status]} label={title(e.status)} />
                  </Stack>
                  <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{e.activities}</Typography>
                  {e.skills && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>Skills: {e.skills}</Typography>}
                  {e.challenges && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>Challenges: {e.challenges}</Typography>}
                  {e.reviewerComment && <Typography variant="caption" sx={{ display: 'block' }}>{e.reviewedByName}: “{e.reviewerComment}”</Typography>}
                  {v.can.supervise && e.status === 'SUBMITTED' && (
                    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1 }}>
                      <TextField size="small" fullWidth placeholder="Comment (required to return)" value={comments[e.id] ?? ''} onChange={(ev) => setComments({ ...comments, [e.id]: ev.target.value })} />
                      <Button size="small" color="warning" disabled={review.isPending || !(comments[e.id] ?? '').trim()} onClick={() => review.mutate({ entry: e, approve: false, comment: comments[e.id] ?? '' })}>Return</Button>
                      <Button size="small" variant="contained" color="success" disabled={review.isPending} onClick={() => review.mutate({ entry: e, approve: true, comment: comments[e.id] ?? '' })}>Approve</Button>
                    </Stack>
                  )}
                </Box>
              ))}
            </Box>

            <Divider />
            <Grid container spacing={2}>
              <Grid size={{ xs: 12, md: 6 }}>
                <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>Supervision visits</Typography>
                {v.visits.map((x) => (
                  <Box key={x.id} sx={{ py: 0.75 }}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{formatDate(x.visitedOn)} · {title(x.mode)}</Typography>
                    <Typography variant="body2">{x.notes}</Typography>
                    <Typography variant="caption" color="text.secondary">{x.recordedByName}</Typography>
                  </Box>
                ))}
                {!v.visits.length && <Typography variant="body2" color="text.secondary">None recorded.</Typography>}
                {v.can.supervise && (
                  <Stack spacing={1} sx={{ mt: 1 }}>
                    <Stack direction="row" spacing={1}>
                      <TextField size="small" type="date" label="Date" value={visit.visitedOn} onChange={(e) => setVisit({ ...visit, visitedOn: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
                      <TextField size="small" select label="How" value={visit.mode} onChange={(e) => setVisit({ ...visit, mode: e.target.value })} sx={{ minWidth: 110 }}>
                        <MenuItem value="VISIT">Visit</MenuItem><MenuItem value="PHONE">Phone</MenuItem><MenuItem value="ONLINE">Online</MenuItem>
                      </TextField>
                    </Stack>
                    <TextField size="small" multiline minRows={2} label="What you found" value={visit.notes} onChange={(e) => setVisit({ ...visit, notes: e.target.value })} />
                    <Button size="small" variant="outlined" disabled={!visit.notes.trim() || addVisit.isPending} onClick={() => addVisit.mutate()}>Record visit</Button>
                  </Stack>
                )}
              </Grid>
              <Grid size={{ xs: 12, md: 6 }}>
                <Stack direction="row" justifyContent="space-between" alignItems="center">
                  <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>Marks (out of 100)</Typography>
                  {v.can.supervise && !s.transferredAt && (s.status === 'CONFIRMED' || s.status === 'COMPLETED') && (
                    <Button size="small" onClick={() => setLinkOpen(true)}>Send workplace assessment link</Button>
                  )}
                </Stack>
                {(['WORKPLACE', 'INSTITUTION', 'LOGBOOK'] as const).map((k) => {
                  const a = v.assessments.find((x) => x.kind === k);
                  return (
                    <Stack key={k} direction="row" spacing={1} alignItems="center" sx={{ py: 0.75 }}>
                      <Box sx={{ flexGrow: 1 }}>
                        <Typography variant="body2">{k === 'WORKPLACE' ? 'Workplace supervisor' : k === 'INSTITUTION' ? 'Institution supervisor' : 'Logbook'} <Typography component="span" variant="caption" color="text.secondary">({weight[k]}%)</Typography></Typography>
                        {a && <Typography variant="caption" color="text.secondary">{a.assessedByName}, {formatDate(a.assessedAt)}</Typography>}
                      </Box>
                      {v.can.supervise && !s.transferredAt ? (
                        <>
                          <TextField size="small" type="number" value={marks[k] ?? (a ? String(a.score) : '')} onChange={(e) => setMarks({ ...marks, [k]: e.target.value })}
                            sx={{ width: 90 }} slotProps={{ htmlInput: { min: 0, max: 100, 'aria-label': `${k} mark` } }} />
                          <Button size="small" disabled={marks[k] == null || marks[k] === '' || assess.isPending} onClick={() => assess.mutate(k)}>Save</Button>
                        </>
                      ) : <Typography sx={{ fontWeight: 700 }}>{a ? a.score : '—'}</Typography>}
                    </Stack>
                  );
                })}
              </Grid>
            </Grid>
          </Stack>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
      {linkOpen && v && <WorkplaceLinkDialog placementId={id} phone={v.workplaceSupervisorPhone} email={v.workplaceSupervisorEmail}
        name={v.summary.workplaceSupervisorName} onClose={() => setLinkOpen(false)} />}
    </Dialog>
  );
}

/** Sends the workplace supervisor a personal link to assess the student online (FR-IPT-03). */
function WorkplaceLinkDialog({ placementId, phone, email, name, onClose }: { placementId: number; phone?: string; email?: string; name?: string; onClose: () => void }) {
  const [p, setP] = useState(phone ?? '');
  const [e, setE] = useState(email ?? '');
  const send = useMutation({ mutationFn: () => api<{ url: string; expiresAt: string; sentToPhone?: string; sentToEmail?: string }>(`/api/ipt/placements/${placementId}/workplace-link`,
    { ...staff, method: 'POST', body: { phone: blank(p), email: blank(e) } }) });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Workplace assessment link{name ? ` for ${name}` : ''}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          The supervisor gets a personal link by SMS and email, rates the student on five points without an account, and the rating becomes the workplace mark. A new link replaces any earlier one.
        </Typography>
        {!send.data ? (
          <Stack spacing={2}>
            <TextField label="Supervisor's mobile number" value={p} onChange={(x) => setP(x.target.value)} />
            <TextField label="Supervisor's email (optional)" value={e} onChange={(x) => setE(x.target.value)} />
          </Stack>
        ) : (
          <Alert severity="success">
            Sent{send.data.sentToPhone ? ` by SMS to ${send.data.sentToPhone}` : ''}{send.data.sentToEmail ? ` and by email to ${send.data.sentToEmail}` : ''}. The link works until {formatDate(send.data.expiresAt)}.
            <Box sx={{ mt: 1, fontFamily: 'monospace', fontSize: 13, wordBreak: 'break-all' }}>{send.data.url}</Box>
          </Alert>
        )}
        <ErrorAlert error={send.error} />
      </DialogContent>
      <DialogActions>
        {send.data && <Button onClick={() => navigator.clipboard?.writeText(send.data!.url)}>Copy link</Button>}
        <Button onClick={onClose}>Close</Button>
        {!send.data && <Button variant="contained" disabled={(!p.trim() && !e.trim()) || send.isPending} onClick={() => send.mutate()}>Send</Button>}
      </DialogActions>
    </Dialog>
  );
}

// ============================================================ organisations and periods

function OrganisationsTab({ coord }: { coord: boolean }) {
  const qc = useQueryClient();
  const q = useOrganisations();
  const [editing, setEditing] = useState<IptOrganisation | 'new' | null>(null);
  return (
    <>
      {coord && <Stack direction="row" justifyContent="flex-end" sx={{ mb: 1.5 }}><Button startIcon={<AddIcon />} onClick={() => setEditing('new')}>New organisation</Button></Stack>}
      <ErrorAlert error={q.error} />
      <TableContainer component={Card}>
        <Table size="small">
          <TableHead><TableRow><TableCell>Organisation</TableCell><TableCell>Contact</TableCell><TableCell align="right">Students</TableCell><TableCell align="right">Capacity</TableCell>{coord && <TableCell />}</TableRow></TableHead>
          <TableBody>
            {q.data?.map((o) => (
              <TableRow key={o.id} sx={{ opacity: o.active ? 1 : 0.5 }}>
                <TableCell>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{o.name} {o.proposedByStudent && <Chip size="small" color="warning" variant="outlined" label="proposed by a student" sx={{ ml: 0.5 }} />}</Typography>
                  <Typography variant="caption" color="text.secondary">{[o.sector, o.region, o.district].filter(Boolean).join(' · ')}</Typography>
                </TableCell>
                <TableCell><Typography variant="body2">{o.contactName ?? '—'}{o.contactTitle ? `, ${o.contactTitle}` : ''}</Typography><Typography variant="caption" color="text.secondary">{[o.contactPhone, o.contactEmail].filter(Boolean).join(' · ')}</Typography></TableCell>
                <TableCell align="right">{o.placements}</TableCell>
                <TableCell align="right">{o.capacity ?? '—'}</TableCell>
                {coord && <TableCell align="right"><IconButton size="small" onClick={() => setEditing(o)}><EditIcon fontSize="small" /></IconButton></TableCell>}
              </TableRow>
            ))}
            {q.data && !q.data.length && <TableRow><TableCell colSpan={5}><Typography color="text.secondary" sx={{ py: 1 }}>No organisations yet.</Typography></TableCell></TableRow>}
          </TableBody>
        </Table>
      </TableContainer>
      {editing && <OrganisationForm o={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); qc.invalidateQueries({ queryKey: ['staff', 'ipt-orgs'] }); }} />}
    </>
  );
}

function OrganisationForm({ o, onClose, onDone }: { o: IptOrganisation | null; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({
    name: o?.name ?? '', sector: o?.sector ?? '', region: o?.region ?? '', district: o?.district ?? '', address: o?.address ?? '', contactName: o?.contactName ?? '',
    contactTitle: o?.contactTitle ?? '', contactPhone: o?.contactPhone ?? '', contactEmail: o?.contactEmail ?? '', capacity: o?.capacity == null ? '' : String(o.capacity),
  });
  const m = useMutation({
    mutationFn: () => api(o ? `/api/ipt/organisations/${o.id}` : '/api/ipt/organisations', { ...staff, method: o ? 'PUT' : 'POST', body: {
      ...Object.fromEntries(Object.entries(f).map(([k, x]) => [k, k === 'capacity' ? (x === '' ? null : Number(x)) : blank(x)])), name: f.name.trim(), active: true } }),
    onSuccess: onDone,
  });
  const field = (k: keyof typeof f, label: string, size = 6) => (
    <Grid size={{ xs: 12, sm: size }}><TextField fullWidth size="small" label={label} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} type={k === 'capacity' ? 'number' : 'text'} /></Grid>
  );
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{o ? o.name : 'New placement organisation'}</DialogTitle>
      <DialogContent>
        <Grid container spacing={1.5} sx={{ mt: 0.5 }}>
          {field('name', 'Name', 12)}{field('sector', 'Sector')}{field('capacity', 'Students per period')}{field('region', 'Region')}{field('district', 'District')}
          {field('address', 'Address', 12)}{field('contactName', 'Contact person')}{field('contactTitle', 'Their title')}{field('contactPhone', 'Phone')}{field('contactEmail', 'Email')}
        </Grid>
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!f.name.trim() || m.isPending} onClick={() => m.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

function PeriodsTab({ coord }: { coord: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'ipt-periods'], queryFn: () => api<IptPeriod[]>('/api/ipt/periods', staff) });
  const [editing, setEditing] = useState<IptPeriod | 'new' | null>(null);
  return (
    <>
      {coord && <Stack direction="row" justifyContent="flex-end" sx={{ mb: 1.5 }}><Button startIcon={<AddIcon />} onClick={() => setEditing('new')}>New period</Button></Stack>}
      <ErrorAlert error={q.error} />
      <TableContainer component={Card}>
        <Table size="small">
          <TableHead><TableRow><TableCell>Period</TableCell><TableCell>IPT module</TableCell><TableCell>Dates</TableCell><TableCell align="right">Students</TableCell>
            <TableCell align="right">Placed</TableCell><TableCell align="right">Completed</TableCell>{coord && <TableCell />}</TableRow></TableHead>
          <TableBody>
            {q.data?.map((p) => (
              <TableRow key={p.id}>
                <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{p.name}</Typography><Typography variant="caption" color="text.secondary">{p.semester}</Typography></TableCell>
                <TableCell>{p.moduleCode ?? '—'}</TableCell>
                <TableCell>{formatDate(p.startsOn)} – {formatDate(p.endsOn)}</TableCell>
                <TableCell align="right">{p.eligible}</TableCell><TableCell align="right">{p.confirmed + p.completed}</TableCell><TableCell align="right">{p.completed}</TableCell>
                {coord && <TableCell align="right"><IconButton size="small" onClick={() => setEditing(p)}><EditIcon fontSize="small" /></IconButton></TableCell>}
              </TableRow>
            ))}
            {q.data && !q.data.length && <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 1 }}>No IPT periods yet.</Typography></TableCell></TableRow>}
          </TableBody>
        </Table>
      </TableContainer>
      {editing && <PeriodForm p={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); qc.invalidateQueries({ queryKey: ['staff', 'ipt-periods'] }); }} />}
    </>
  );
}

function PeriodForm({ p, onClose, onDone }: { p: IptPeriod | null; onClose: () => void; onDone: () => void }) {
  const semesters = useQuery({ queryKey: ['staff', 'semesters'], queryFn: () => api<Semester[]>('/api/academic/semesters', staff) });
  const modules = useQuery({ queryKey: ['staff', 'modules'], queryFn: () => api<{ id: number; code: string; title: string }[]>('/api/academic/modules', staff) });
  const [f, setF] = useState({
    name: p?.name ?? '', semesterId: p?.semesterId ?? 0, moduleId: p?.moduleId ?? 0, startsOn: p?.startsOn ?? '', endsOn: p?.endsOn ?? '', requestsClose: p?.requestsClose ?? '',
    workplaceWeight: String(p?.workplaceWeight ?? 40), institutionWeight: String(p?.institutionWeight ?? 30), logbookWeight: String(p?.logbookWeight ?? 30), minLogbookWeeks: String(p?.minLogbookWeeks ?? 4),
  });
  useEffect(() => { if (!f.semesterId && semesters.data?.length) setF((x) => ({ ...x, semesterId: semesters.data![0].id })); }, [semesters.data, f.semesterId]);
  const total = Number(f.workplaceWeight) + Number(f.institutionWeight) + Number(f.logbookWeight);
  const m = useMutation({
    mutationFn: () => api(p ? `/api/ipt/periods/${p.id}` : '/api/ipt/periods', { ...staff, method: p ? 'PUT' : 'POST', body: {
      ...f, moduleId: f.moduleId || null, requestsClose: f.requestsClose || null, workplaceWeight: Number(f.workplaceWeight), institutionWeight: Number(f.institutionWeight),
      logbookWeight: Number(f.logbookWeight), minLogbookWeeks: Number(f.minLogbookWeeks) } }),
    onSuccess: onDone,
  });
  const date = (k: 'startsOn' | 'endsOn' | 'requestsClose', label: string) => (
    <TextField fullWidth size="small" type="date" label={label} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
  );
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{p ? p.name : 'New IPT period'}</DialogTitle>
      <DialogContent>
        <Grid container spacing={1.5} sx={{ mt: 0.5 }}>
          <Grid size={12}><TextField fullWidth size="small" label="Name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="e.g. IPT 2028 (second years)" /></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><TextField fullWidth size="small" select label="Semester" value={f.semesterId || ''} onChange={(e) => setF({ ...f, semesterId: Number(e.target.value) })}>
            {(semesters.data ?? []).map((s) => <MenuItem key={s.id} value={s.id}>{s.name} · {s.academicYear}</MenuItem>)}</TextField></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><TextField fullWidth size="small" select label="IPT module" value={f.moduleId || ''} onChange={(e) => setF({ ...f, moduleId: Number(e.target.value) })}
            helperText="Marks are sent to this module's results">
            <MenuItem value="">None</MenuItem>{(modules.data ?? []).map((x) => <MenuItem key={x.id} value={x.id}>{x.code} {x.title}</MenuItem>)}</TextField></Grid>
          <Grid size={{ xs: 6, sm: 4 }}>{date('startsOn', 'Starts')}</Grid>
          <Grid size={{ xs: 6, sm: 4 }}>{date('endsOn', 'Ends')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{date('requestsClose', 'Student requests until')}</Grid>
          <Grid size={{ xs: 4 }}><TextField fullWidth size="small" type="number" label="Workplace %" value={f.workplaceWeight} onChange={(e) => setF({ ...f, workplaceWeight: e.target.value })} /></Grid>
          <Grid size={{ xs: 4 }}><TextField fullWidth size="small" type="number" label="Institution %" value={f.institutionWeight} onChange={(e) => setF({ ...f, institutionWeight: e.target.value })} /></Grid>
          <Grid size={{ xs: 4 }}><TextField fullWidth size="small" type="number" label="Logbook %" value={f.logbookWeight} onChange={(e) => setF({ ...f, logbookWeight: e.target.value })} error={total !== 100} /></Grid>
          <Grid size={12}><TextField fullWidth size="small" type="number" label="Logbook weeks approved before the logbook can be marked" value={f.minLogbookWeeks} onChange={(e) => setF({ ...f, minLogbookWeeks: e.target.value })} /></Grid>
        </Grid>
        {total !== 100 && <Typography variant="caption" color="error">The weights add up to {total}%, not 100%.</Typography>}
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!f.name || !f.startsOn || !f.endsOn || total !== 100 || m.isPending} onClick={() => m.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}
