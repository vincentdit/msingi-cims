import AddIcon from '@mui/icons-material/Add';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, Tab, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, ApiError, formatDate } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';

const staff = { realm: 'staff' as const };

export interface LeaveType { id: number; code: string; name: string; daysPerYear?: number; onlyGender?: 'M' | 'F'; calendarDays: boolean; active: boolean }
export interface Balance { leaveTypeId: number; code: string; name: string; entitlement?: number; adjustments: number; taken: number; pending: number; remaining?: number }
export interface LeaveRequest {
  id: number; staffId: number; staffName: string; staffNo: string; department?: string; leaveTypeId: number; leaveType: string; startOn: string; endOn: string;
  days: number; reason?: string; reliefName?: string; status: 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'CANCELLED'; submittedByName: string; submittedAt: string;
  decidedByName?: string; decidedAt?: string; decisionNote?: string; canDecide: boolean;
}
interface MyLeave { staffId: number; name: string; year: number; balances: Balance[]; requests: LeaveRequest[]; types: LeaveType[] }

const STATUS: Record<string, { label: string; color: 'default' | 'warning' | 'success' | 'error' }> = {
  SUBMITTED: { label: 'Waiting for approval', color: 'warning' }, APPROVED: { label: 'Approved', color: 'success' },
  REJECTED: { label: 'Not approved', color: 'error' }, CANCELLED: { label: 'Cancelled', color: 'default' },
};

/** Leave for every member of staff (FR-HR-03): one's own balances and requests, and for Heads of Department the requests to decide. */
export default function LeavePage() {
  const isApprover = useRoles()(['HEAD_OF_DEPARTMENT', 'INSTITUTION_ADMIN', 'HR_OFFICER']);
  const [tab, setTab] = useState('mine');
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Leave" subtitle="Your leave days and requests." />
      {isApprover && (
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
          <Tab value="mine" label="My leave" />
          <Tab value="decide" label="To decide" />
          <Tab value="away" label="Who is away" />
        </Tabs>
      )}
      {tab === 'mine' && <MyLeaveView />}
      {tab === 'decide' && <LeaveRequestsCard />}
      {tab === 'away' && <AwayCard />}
    </Container>
  );
}

function MyLeaveView() {
  const qc = useQueryClient();
  const [year, setYear] = useState(new Date().getFullYear());
  const q = useQuery({ queryKey: ['staff', 'my-leave', year], queryFn: () => api<MyLeave>(`/api/my/leave?year=${year}`, staff), retry: false });
  const [applying, setApplying] = useState(false);
  const cancel = useMutation({ mutationFn: (id: number) => api(`/api/leave/requests/${id}/cancel`, { ...staff, method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staff', 'my-leave'] }) });
  if (q.isLoading) return <Loading />;
  if (q.error instanceof ApiError && q.error.problem.code === 'NO_STAFF_RECORD') {
    return <Alert severity="info">{q.error.problem.detail}</Alert>;
  }
  if (!q.data) return <ErrorAlert error={q.error} />;
  const d = q.data;
  const today = new Date().toISOString().slice(0, 10);
  return (
    <Stack spacing={2}>
      <Card><CardContent>
        <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1 }}>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>Days</Typography>
          <TextField select size="small" value={year} onChange={(e) => setYear(Number(e.target.value))}>
            {[year - 1, new Date().getFullYear(), new Date().getFullYear() + 1].filter((v, i, a) => a.indexOf(v) === i).map((v) => <MenuItem key={v} value={v}>{v}</MenuItem>)}
          </TextField>
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setApplying(true)}>Ask for leave</Button>
        </Stack>
        <BalancesTable balances={d.balances} />
      </CardContent></Card>
      <Card><CardContent>
        <Typography variant="h6" sx={{ mb: 1 }}>Requests</Typography>
        <ErrorAlert error={cancel.error} />
        {d.requests.length === 0 && <Typography variant="body2" color="text.secondary">No leave requested yet.</Typography>}
        <Stack spacing={1}>
          {d.requests.map((r) => (
            <Box key={r.id} sx={{ display: 'flex', gap: 1, alignItems: 'center', p: 1.25, border: 1, borderColor: 'divider', borderRadius: 1, flexWrap: 'wrap' }}>
              <Box sx={{ flexGrow: 1, minWidth: 220 }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>{`${r.leaveType}: ${formatDate(r.startOn)} – ${formatDate(r.endOn)} (${r.days} days)`}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {r.decidedByName ? `${r.decidedByName}, ${formatDate(r.decidedAt)}${r.decisionNote ? `: ${r.decisionNote}` : ''}` : `Asked ${formatDate(r.submittedAt)}`}
                </Typography>
              </Box>
              <Chip size="small" color={STATUS[r.status].color} label={STATUS[r.status].label} />
              {(r.status === 'SUBMITTED' || (r.status === 'APPROVED' && r.startOn > today)) && (
                <Button size="small" disabled={cancel.isPending} onClick={() => { if (window.confirm('Cancel this leave?')) cancel.mutate(r.id); }}>Cancel</Button>
              )}
            </Box>
          ))}
        </Stack>
      </CardContent></Card>
      {applying && <NewLeaveDialog types={d.types} path="/api/my/leave" title="Ask for leave" onClose={() => setApplying(false)}
        onDone={() => { setApplying(false); qc.invalidateQueries({ queryKey: ['staff', 'my-leave'] }); }} />}
    </Stack>
  );
}

/** A new request (the person's own, or recorded by HR, which approves it at once). Shows how many days the dates count as. */
export function NewLeaveDialog({ types, path, title, onClose, onDone }: { types: LeaveType[]; path: string; title: string; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ leaveTypeId: types[0]?.id, startOn: '', endOn: '', reason: '', reliefName: '' });
  const ready = !!f.leaveTypeId && !!f.startOn && !!f.endOn && f.endOn >= f.startOn;
  const count = useQuery({ queryKey: ['staff', 'leave-count', f.leaveTypeId, f.startOn, f.endOn], enabled: ready,
    queryFn: () => api<{ days: number; skipped: string[] }>(`/api/leave/count?leaveTypeId=${f.leaveTypeId}&from=${f.startOn}&to=${f.endOn}`, staff) });
  const go = useMutation({ mutationFn: () => api(path, { ...staff, method: 'POST', body: f }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select label="Leave" value={f.leaveTypeId ?? ''} onChange={(e) => setF({ ...f, leaveTypeId: Number(e.target.value) })}>
            {types.map((t) => <MenuItem key={t.id} value={t.id}>{`${t.name}${t.daysPerYear != null ? ` (${t.daysPerYear} days a year)` : ''}`}</MenuItem>)}
          </TextField>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField type="date" label="From" value={f.startOn} onChange={(e) => setF({ ...f, startOn: e.target.value, endOn: f.endOn || e.target.value })} slotProps={{ inputLabel: { shrink: true } }} sx={{ flex: 1 }} />
            <TextField type="date" label="To (the last day away)" value={f.endOn} onChange={(e) => setF({ ...f, endOn: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} sx={{ flex: 1 }} />
          </Stack>
          {count.data && (
            <Alert severity="info">
              {`${count.data.days} ${types.find((t) => t.id === f.leaveTypeId)?.calendarDays ? 'days' : 'working days'}.`}
              {count.data.skipped.length > 0 && ` Public holidays not counted: ${count.data.skipped.join(', ')}.`}
            </Alert>
          )}
          <ErrorAlert error={count.error} />
          <TextField label="Reason" value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} multiline minRows={2} />
          <TextField label="Who covers your work (optional)" value={f.reliefName} onChange={(e) => setF({ ...f, reliefName: e.target.value })} />
          <ErrorAlert error={go.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!ready || go.isPending} onClick={() => go.mutate()}>{path.startsWith('/api/my') ? 'Send the request' : 'Record'}</Button>
      </DialogActions>
    </Dialog>
  );
}

/** Requests waiting for a decision (Heads of Department see their departments' staff; HR sees everyone and may look back). */
export function LeaveRequestsCard({ hr }: { hr?: boolean }) {
  const qc = useQueryClient();
  const [status, setStatus] = useState('SUBMITTED');
  const q = useQuery({ queryKey: ['staff', 'leave-requests', status], queryFn: () => api<LeaveRequest[]>(`/api/leave/requests?status=${status}`, staff) });
  const [deciding, setDeciding] = useState<{ r: LeaveRequest; approve: boolean } | null>(null);
  return (
    <Card><CardContent>
      <Stack direction="row" alignItems="center" sx={{ mb: 1.5 }}>
        <Typography variant="h6" sx={{ flexGrow: 1 }}>Leave requests</Typography>
        {hr && (
          <TextField select size="small" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 200 }}>
            <MenuItem value="SUBMITTED">Waiting for a decision</MenuItem><MenuItem value="APPROVED">Approved this year</MenuItem>
            <MenuItem value="REJECTED">Not approved this year</MenuItem><MenuItem value="ALL">All this year</MenuItem>
          </TextField>
        )}
      </Stack>
      <ErrorAlert error={q.error} />
      {q.isLoading && <Loading />}
      {q.data?.length === 0 && <Typography variant="body2" color="text.secondary">Nothing waiting.</Typography>}
      {!!q.data?.length && (
        <TableContainer>
          <Table size="small" sx={{ minWidth: 760 }}>
            <TableHead><TableRow><TableCell>Staff</TableCell><TableCell>Leave</TableCell><TableCell>Dates</TableCell><TableCell align="right">Days</TableCell><TableCell>Reason</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {q.data.map((r) => (
                <TableRow key={r.id}>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{r.staffName}</Typography><Typography variant="caption" color="text.secondary">{r.department ?? r.staffNo}</Typography></TableCell>
                  <TableCell>{r.leaveType}</TableCell>
                  <TableCell>{`${formatDate(r.startOn)} – ${formatDate(r.endOn)}`}</TableCell>
                  <TableCell align="right">{r.days}</TableCell>
                  <TableCell>{r.reason ?? ''}{r.reliefName ? <Typography variant="caption" color="text.secondary" component="div">{`Covered by ${r.reliefName}`}</Typography> : null}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    {r.canDecide ? (
                      <>
                        <Button size="small" variant="contained" onClick={() => setDeciding({ r, approve: true })}>Approve</Button>
                        <Button size="small" color="error" onClick={() => setDeciding({ r, approve: false })}>Not approve</Button>
                      </>
                    ) : <Chip size="small" color={STATUS[r.status].color} label={STATUS[r.status].label} />}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {deciding && <DecideDialog {...deciding} onClose={() => setDeciding(null)} onDone={() => { setDeciding(null); qc.invalidateQueries({ queryKey: ['staff', 'leave-requests'] }); }} />}
    </CardContent></Card>
  );
}

function DecideDialog({ r, approve, onClose, onDone }: { r: LeaveRequest; approve: boolean; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('');
  const go = useMutation({ mutationFn: () => api(`/api/leave/requests/${r.id}/decision`, { ...staff, method: 'POST', body: { approve, note } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>{approve ? 'Approve the leave' : 'Do not approve'}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 2 }}>{`${r.staffName}: ${r.leaveType}, ${formatDate(r.startOn)} – ${formatDate(r.endOn)} (${r.days} days). ${r.staffName.split(' ')[0]} is told by SMS.`}</Typography>
        <TextField fullWidth label={approve ? 'Note (optional)' : 'Reason'} value={note} onChange={(e) => setNote(e.target.value)} multiline minRows={2} />
        <ErrorAlert error={go.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color={approve ? 'primary' : 'error'} disabled={go.isPending || (!approve && !note.trim())} onClick={() => go.mutate()}>{approve ? 'Approve' : 'Do not approve'}</Button>
      </DialogActions>
    </Dialog>
  );
}

function AwayCard() {
  const [day, setDay] = useState(new Date().toISOString().slice(0, 10));
  const q = useQuery({ queryKey: ['staff', 'leave-away', day], queryFn: () => api<LeaveRequest[]>(`/api/leave/away?day=${day}`, staff) });
  return (
    <Card><CardContent>
      <Stack direction="row" alignItems="center" sx={{ mb: 1.5 }}>
        <Typography variant="h6" sx={{ flexGrow: 1 }}>Away on leave</Typography>
        <TextField type="date" size="small" value={day} onChange={(e) => setDay(e.target.value)} />
      </Stack>
      <ErrorAlert error={q.error} />
      {q.data?.length === 0 && <Typography variant="body2" color="text.secondary">No one is on approved leave that day.</Typography>}
      <Stack spacing={1}>
        {q.data?.map((r) => (
          <Typography key={r.id} variant="body2">{`${r.staffName} (${r.department ?? r.staffNo}): ${r.leaveType}, back after ${formatDate(r.endOn)}${r.reliefName ? `; covered by ${r.reliefName}` : ''}`}</Typography>
        ))}
      </Stack>
    </CardContent></Card>
  );
}

export function BalancesTable({ balances }: { balances: Balance[] }) {
  return (
    <TableContainer>
      <Table size="small">
        <TableHead><TableRow><TableCell>Leave</TableCell><TableCell align="right">Days a year</TableCell><TableCell align="right">Added</TableCell>
          <TableCell align="right">Taken</TableCell><TableCell align="right">Waiting</TableCell><TableCell align="right">Left</TableCell></TableRow></TableHead>
        <TableBody>
          {balances.map((b) => (
            <TableRow key={b.leaveTypeId}>
              <TableCell>{b.name}</TableCell>
              <TableCell align="right">{b.entitlement ?? 'As granted'}</TableCell>
              <TableCell align="right">{b.adjustments || ''}</TableCell>
              <TableCell align="right">{b.taken || ''}</TableCell>
              <TableCell align="right">{b.pending || ''}</TableCell>
              <TableCell align="right" sx={{ fontWeight: 600 }}>{b.remaining ?? '—'}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
