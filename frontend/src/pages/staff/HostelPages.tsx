import AddIcon from '@mui/icons-material/Add';
import EditIcon from '@mui/icons-material/EditOutlined';
import BuildIcon from '@mui/icons-material/BuildOutlined';
import {
  Alert, Box, Button, Card, CardActionArea, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel,
  IconButton, LinearProgress, MenuItem, Stack, Switch, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField,
  Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, formatDate, formatTzs } from '../../api/client';
import type {
  AcademicYear, FreeBed, HostelAllocationView, HostelApplicationView, HostelDetail, HostelFeeCategory, HostelRoomView, HostelSummary, HostelWindow,
  RoomType,
} from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';

const staff = { realm: 'staff' as const };
const WARDENS = ['INSTITUTION_ADMIN', 'HOSTEL_WARDEN'];
const ROOM_TYPES: RoomType[] = ['SINGLE', 'DOUBLE', 'TRIPLE', 'QUAD', 'DORMITORY'];
const title = (s: string) => s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, ' ');
const APP_COLOUR = { SUBMITTED: 'default', ALLOCATED: 'success', WAITLISTED: 'warning', RELEASED: 'error', CANCELLED: 'default' } as const;
const ALLOC_COLOUR = { ALLOCATED: 'info', CHECKED_IN: 'success', CHECKED_OUT: 'default', RELEASED: 'error', CANCELLED: 'default' } as const;

/** Hostels and accommodation (FR-HST-01..05): allocation by window, the bed inventory, windows and fee categories. */
export function HostelPage() {
  const can = useRoles();
  const warden = can(WARDENS);
  const [tab, setTab] = useState<'allocation' | 'inventory' | 'windows' | 'fees'>('allocation');
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Hostels" subtitle="Applications and bed allocation by gender and priority, hostel invoices, check-in and check-out, and the bed inventory." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2, borderBottom: 1, borderColor: 'divider' }} variant="scrollable">
        <Tab value="allocation" label="Allocation" />
        <Tab value="inventory" label="Hostels and rooms" />
        <Tab value="windows" label="Application windows" />
        <Tab value="fees" label="Fee categories" />
      </Tabs>
      {tab === 'allocation' && <AllocationTab warden={warden} />}
      {tab === 'inventory' && <InventoryTab warden={warden} />}
      {tab === 'windows' && <WindowsTab warden={warden} />}
      {tab === 'fees' && <FeesTab warden={warden} />}
    </Container>
  );
}

// ============================================================ allocation

function AllocationTab({ warden }: { warden: boolean }) {
  const qc = useQueryClient();
  const windows = useQuery({ queryKey: ['staff', 'hostel-windows'], queryFn: () => api<HostelWindow[]>('/api/hostel/windows', staff) });
  const [windowId, setWindowId] = useState<number>();
  useEffect(() => { if (!windowId && windows.data?.length) setWindowId((windows.data.find((w) => w.open) ?? windows.data[0]).id); }, [windows.data, windowId]);
  const [filter, setFilter] = useState<string>('ALL');
  const [notice, setNotice] = useState<string | null>(null);
  const [manual, setManual] = useState<HostelApplicationView | null>(null);
  const [action, setAction] = useState<{ kind: 'in' | 'out' | 'cancel'; a: HostelAllocationView } | null>(null);
  const apps = useQuery({
    queryKey: ['staff', 'hostel-applications', windowId], enabled: !!windowId,
    queryFn: () => api<HostelApplicationView[]>(`/api/hostel/windows/${windowId}/applications`, staff),
  });
  const refresh = () => { qc.invalidateQueries({ queryKey: ['staff', 'hostel-applications'] }); qc.invalidateQueries({ queryKey: ['staff', 'hostel-windows'] }); };
  const allocate = useMutation({
    mutationFn: () => api<{ allocated: number; waitlisted: number; notes: string[] }>(`/api/hostel/windows/${windowId}/allocate`, { ...staff, method: 'POST' }),
    onSuccess: (r) => { setNotice(`${r.allocated} bed${r.allocated === 1 ? '' : 's'} allocated, ${r.waitlisted} on the waiting list. Students were told by SMS.${r.notes.length ? ' ' + r.notes.join('; ') : ''}`); refresh(); },
  });
  const release = useMutation({
    mutationFn: () => api<{ released: number; reallocated: number }>('/api/hostel/allocations/release-unpaid', { ...staff, method: 'POST' }),
    onSuccess: (r) => { setNotice(r.released ? `${r.released} unpaid allocation${r.released === 1 ? '' : 's'} released; ${r.reallocated} bed${r.reallocated === 1 ? '' : 's'} offered to the waiting list.` : 'No allocation is past its payment date.'); refresh(); },
  });
  const w = windows.data?.find((x) => x.id === windowId);
  const rows = (apps.data ?? []).filter((a) => filter === 'ALL' || a.status === filter || a.allocation?.status === filter);
  const count = (st: string) => (apps.data ?? []).filter((a) => a.status === st || a.allocation?.status === st).length;
  if (windows.isLoading) return <Loading />;
  if (!windows.data?.length) return <Alert severity="info">No application windows yet. Set up hostels and rooms, then open a window under Application windows.</Alert>;
  return (
    <>
      <ErrorAlert error={windows.error ?? apps.error ?? allocate.error ?? release.error} />
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} sx={{ mb: 2 }} alignItems={{ md: 'center' }} justifyContent="space-between">
        <TextField select size="small" label="Window" value={windowId ?? ''} onChange={(e) => setWindowId(Number(e.target.value))} sx={{ minWidth: 280 }}>
          {windows.data.map((x) => <MenuItem key={x.id} value={x.id}>{x.name}{x.open ? ' (open)' : ''}</MenuItem>)}
        </TextField>
        {warden && (
          <Stack direction="row" spacing={1}>
            <Tooltip title="Releases allocations still unpaid after their payment date and offers the beds to the waiting list (also runs every morning)">
              <Button onClick={() => release.mutate()} disabled={release.isPending} sx={{ whiteSpace: 'nowrap' }}>Release unpaid</Button>
            </Tooltip>
            <Button variant="contained" onClick={() => allocate.mutate()} disabled={allocate.isPending} sx={{ whiteSpace: 'nowrap' }}>Allocate beds</Button>
          </Stack>
        )}
      </Stack>
      {w && (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          {w.academicYear} · applications {formatDate(w.opensOn)} – {formatDate(w.closesOn)} · pay within {w.paymentDays} days ·
          priority: {[w.specialNeedsFirst && 'special needs', w.firstYearsFirst && 'first years', w.paidFeesFirst && 'fees paid'].filter(Boolean).join(', ') || 'none'}, then who applied first
        </Typography>
      )}
      {notice && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>{notice}</Alert>}
      <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" sx={{ mb: 1.5 }}>
        {['ALL', 'SUBMITTED', 'WAITLISTED', 'ALLOCATED', 'CHECKED_IN', 'CHECKED_OUT', 'RELEASED'].map((k) => (
          <Chip key={k} clickable label={k === 'ALL' ? `All (${apps.data?.length ?? 0})` : `${title(k)} (${count(k)})`} color={filter === k ? 'primary' : 'default'}
            variant={filter === k ? 'filled' : 'outlined'} onClick={() => setFilter(k)} />
        ))}
      </Stack>
      {apps.isLoading && <Loading />}
      {apps.data && (
        <TableContainer component={Card}>
          <Table size="small">
            <TableHead><TableRow>
              <TableCell>Student</TableCell><TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Preferences</TableCell>
              <TableCell>Status</TableCell><TableCell>Bed</TableCell><TableCell align="right" />
            </TableRow></TableHead>
            <TableBody>
              {rows.map((a) => {
                const al = a.allocation;
                return (
                  <TableRow key={a.id} sx={{ verticalAlign: 'top' }}>
                    <TableCell>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{a.registrationNo}</Typography>
                      <Typography variant="caption" color="text.secondary">{a.studentName} · {a.sex === 'F' ? 'Female' : 'Male'} · {a.programmeCode} year {a.yearOfStudy}</Typography>
                      <Stack direction="row" spacing={0.5} sx={{ mt: 0.5 }}>
                        {a.specialNeeds && <Tooltip title={a.specialNeedsNote ?? ''}><Chip size="small" color="secondary" variant="outlined" label="Special needs" /></Tooltip>}
                        {a.feesCleared && <Chip size="small" variant="outlined" label="Fees cleared" />}
                      </Stack>
                    </TableCell>
                    <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>
                      <Typography variant="body2">{a.preferences.join(' → ') || 'Any'}</Typography>
                      {a.roomType && <Typography variant="caption" color="text.secondary">{title(a.roomType)} room</Typography>}
                    </TableCell>
                    <TableCell>
                      <Chip size="small" color={al && al.status !== 'RELEASED' && al.status !== 'CANCELLED' ? ALLOC_COLOUR[al.status] : APP_COLOUR[a.status]}
                        label={al && a.status === 'ALLOCATED' ? title(al.status) : title(a.status)} />
                      {al?.releaseReason && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{al.releaseReason}</Typography>}
                    </TableCell>
                    <TableCell>
                      {al ? <>
                        <Typography variant="body2">{al.hostel}, {al.room}, bed {al.bed}</Typography>
                        <Typography variant="caption" color={al.balance > 0 && al.status === 'ALLOCATED' ? 'warning.main' : 'text.secondary'}>
                          {al.fee > 0 ? (al.invoiceStatus === 'PAID' ? `Paid ${formatTzs(al.fee)}` : al.invoiceStatus === 'CANCELLED' ? 'Invoice cancelled'
                            : `${formatTzs(al.balance)} to pay by ${formatDate(al.payBy)}`) : 'No fee'}
                        </Typography>
                        {al.damageCharge != null && <Typography variant="caption" color="error.main" sx={{ display: 'block' }}>Damages {formatTzs(al.damageCharge)}: {al.damages}</Typography>}
                      </> : '—'}
                    </TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                      {warden && (a.status === 'SUBMITTED' || a.status === 'WAITLISTED') && <Button size="small" onClick={() => setManual(a)}>Allocate</Button>}
                      {warden && al?.status === 'ALLOCATED' && a.status === 'ALLOCATED' && <>
                        <Button size="small" color="warning" onClick={() => setAction({ kind: 'cancel', a: al })}>Cancel</Button>
                        <Button size="small" variant="contained" onClick={() => setAction({ kind: 'in', a: al })} sx={{ ml: 0.5 }}>Check in</Button>
                      </>}
                      {warden && al?.status === 'CHECKED_IN' && <Button size="small" variant="outlined" onClick={() => setAction({ kind: 'out', a: al })}>Check out</Button>}
                    </TableCell>
                  </TableRow>
                );
              })}
              {!rows.length && <TableRow><TableCell colSpan={5}><Typography color="text.secondary" sx={{ py: 1 }}>No applications here.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {manual && <ManualDialog app={manual} onClose={() => setManual(null)} onDone={() => { setManual(null); setNotice('Bed allocated and invoiced.'); refresh(); }} />}
      {action && <ActionDialog kind={action.kind} a={action.a} onClose={() => setAction(null)} onDone={(m) => { setAction(null); setNotice(m); refresh(); }} />}
    </>
  );
}

function ManualDialog({ app, onClose, onDone }: { app: HostelApplicationView; onClose: () => void; onDone: () => void }) {
  const beds = useQuery({ queryKey: ['staff', 'free-beds', app.id], queryFn: () => api<FreeBed[]>(`/api/hostel/applications/${app.id}/free-beds`, staff) });
  const [bedId, setBedId] = useState<number>();
  const m = useMutation({ mutationFn: () => api('/api/hostel/allocations', { ...staff, method: 'POST', body: { applicationId: app.id, bedId } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Allocate a bed · {app.registrationNo} {app.studentName}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>Free beds for {app.sex === 'F' ? 'female' : 'male'} students, preferred hostels first.</Typography>
        {beds.isLoading && <Loading />}
        {beds.data && !beds.data.length && <Alert severity="warning">No free bed for this student. Free a bed or put a room back in service.</Alert>}
        <Stack spacing={0.5}>
          {beds.data?.map((b) => (
            <Card key={b.bedId} variant="outlined" sx={{ borderColor: bedId === b.bedId ? 'primary.main' : undefined, borderWidth: bedId === b.bedId ? 2 : 1 }}>
              <CardActionArea onClick={() => setBedId(b.bedId)} sx={{ px: 1.5, py: 1 }}>
                <Stack direction="row" justifyContent="space-between">
                  <Box>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{b.hostel}, {b.room}, bed {b.bed}</Typography>
                    <Typography variant="caption" color="text.secondary">{title(b.roomType)}{b.accessible ? ' · accessible' : ''}</Typography>
                  </Box>
                  <Typography variant="body2">{formatTzs(b.fee)}</Typography>
                </Stack>
              </CardActionArea>
            </Card>
          ))}
        </Stack>
        <ErrorAlert error={beds.error ?? m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!bedId || m.isPending} onClick={() => m.mutate()}>Allocate and invoice</Button></DialogActions>
    </Dialog>
  );
}

function ActionDialog({ kind, a, onClose, onDone }: { kind: 'in' | 'out' | 'cancel'; a: HostelAllocationView; onClose: () => void; onDone: (m: string) => void }) {
  const [note, setNote] = useState('');
  const [damages, setDamages] = useState('');
  const [charge, setCharge] = useState('');
  const m = useMutation({
    mutationFn: () => kind === 'in' ? api(`/api/hostel/allocations/${a.id}/check-in`, { ...staff, method: 'POST', body: { note: note || null } })
      : kind === 'out' ? api(`/api/hostel/allocations/${a.id}/check-out`, { ...staff, method: 'POST', body: { condition: note || null, damages: damages || null, damageCharge: charge ? Number(charge) : null } })
        : api(`/api/hostel/allocations/${a.id}/cancel`, { ...staff, method: 'POST', body: { reason: note } }),
    onSuccess: () => onDone(kind === 'in' ? `${a.studentName} checked in.` : kind === 'out' ? `${a.studentName} checked out${charge ? '; damages invoiced' : ''}.` : 'Allocation cancelled; the student was told by SMS.'),
  });
  const heading = kind === 'in' ? 'Check in' : kind === 'out' ? 'Check out' : 'Cancel allocation';
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{heading} · {a.registrationNo} {a.studentName}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{a.hostel}, {a.room}, bed {a.bed}</Typography>
        {kind === 'in' && a.balance > 0 && <Alert severity="warning" sx={{ mb: 2 }}>The hostel fee is not fully paid ({formatTzs(a.balance)} outstanding).</Alert>}
        {kind === 'out' && a.checkInCondition && <Typography variant="body2" sx={{ mb: 2 }}>At check-in: {a.checkInCondition}</Typography>}
        <Stack spacing={2}>
          <TextField label={kind === 'cancel' ? 'Reason' : 'Condition of the room and inventory'} value={note} onChange={(e) => setNote(e.target.value)} multiline minRows={2}
            required={kind === 'cancel'} slotProps={{ htmlInput: { maxLength: kind === 'cancel' ? 300 : 500 } }} />
          {kind === 'out' && <>
            <TextField label="Damages (if any)" value={damages} onChange={(e) => setDamages(e.target.value)} slotProps={{ htmlInput: { maxLength: 500 } }} />
            <TextField label="Damage charge (TZS)" type="number" value={charge} onChange={(e) => setCharge(e.target.value)} helperText="Invoiced to the student with a control number" />
          </>}
          <ErrorAlert error={m.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" color={kind === 'cancel' ? 'warning' : 'primary'} disabled={m.isPending || (kind === 'cancel' && !note.trim())} onClick={() => m.mutate()}>{heading}</Button>
      </DialogActions>
    </Dialog>
  );
}

// ============================================================ inventory

function InventoryTab({ warden }: { warden: boolean }) {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['staff', 'hostels'], queryFn: () => api<HostelSummary[]>('/api/hostel/hostels', staff) });
  const [open, setOpen] = useState<number | null>(null);
  const [editing, setEditing] = useState<HostelSummary | 'new' | null>(null);
  return (
    <>
      {warden && <Stack direction="row" justifyContent="flex-end" sx={{ mb: 1.5 }}><Button startIcon={<AddIcon />} onClick={() => setEditing('new')}>New hostel</Button></Stack>}
      <ErrorAlert error={list.error} />
      {list.isLoading && <Loading />}
      <Grid container spacing={2}>
        {list.data?.map((h) => {
          const taken = h.occupied + h.allocatedUnpaid;
          return (
            <Grid key={h.id} size={{ xs: 12, sm: 6, md: 4 }}>
              <Card sx={{ height: '100%', opacity: h.active ? 1 : 0.6 }}>
                <CardActionArea onClick={() => setOpen(h.id)} sx={{ height: '100%' }}>
                  <CardContent>
                    <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
                      <Box>
                        <Typography variant="h6">{h.name}</Typography>
                        <Typography variant="caption" color="text.secondary">{title(h.gender)}{h.location ? ` · ${h.location}` : ''}{!h.active ? ' · closed' : ''}</Typography>
                      </Box>
                      <Chip size="small" label={`${h.rooms} rooms`} variant="outlined" />
                    </Stack>
                    <LinearProgress variant="determinate" value={h.beds ? (taken / h.beds) * 100 : 0} sx={{ my: 1.5, height: 8, borderRadius: 4 }} />
                    <Typography variant="body2">{taken} of {h.beds} beds taken</Typography>
                    <Typography variant="caption" color="text.secondary">{h.occupied} checked in · {h.allocatedUnpaid} awaiting · {h.available} free{h.outOfService ? ` · ${h.outOfService} out of service` : ''}</Typography>
                  </CardContent>
                </CardActionArea>
              </Card>
            </Grid>
          );
        })}
        {list.data && !list.data.length && <Grid size={12}><Typography color="text.secondary">No hostels yet.</Typography></Grid>}
      </Grid>
      {open != null && <HostelDialog id={open} onClose={() => setOpen(null)} onEditHostel={(h) => { setOpen(null); setEditing(h); }} />}
      {editing && <HostelForm hostel={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); qc.invalidateQueries({ queryKey: ['staff', 'hostels'] }); }} />}
    </>
  );
}

function HostelForm({ hostel, onClose, onDone }: { hostel: HostelSummary | null; onClose: () => void; onDone: () => void }) {
  const [code, setCode] = useState(hostel?.code ?? '');
  const [name, setName] = useState(hostel?.name ?? '');
  const [gender, setGender] = useState(hostel?.gender ?? 'FEMALE');
  const [location, setLocation] = useState(hostel?.location ?? '');
  const [active, setActive] = useState(hostel?.active ?? true);
  const m = useMutation({
    mutationFn: () => api(hostel ? `/api/hostel/hostels/${hostel.id}` : '/api/hostel/hostels', { ...staff, method: hostel ? 'PUT' : 'POST', body: { code, name, gender, location: location || null, active } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>{hostel ? hostel.name : 'New hostel'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField label="Code" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} disabled={!!hostel} slotProps={{ htmlInput: { maxLength: 20 } }} />
          <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} slotProps={{ htmlInput: { maxLength: 120 } }} />
          <TextField select label="For" value={gender} onChange={(e) => setGender(e.target.value as typeof gender)}>
            <MenuItem value="FEMALE">Female students</MenuItem><MenuItem value="MALE">Male students</MenuItem><MenuItem value="MIXED">Mixed (each room female or male)</MenuItem>
          </TextField>
          <TextField label="Location" value={location} onChange={(e) => setLocation(e.target.value)} />
          <FormControlLabel control={<Switch checked={active} onChange={(e) => setActive(e.target.checked)} />} label="Open for allocation" />
          <ErrorAlert error={m.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!code || !name || m.isPending} onClick={() => m.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

function HostelDialog({ id, onClose, onEditHostel }: { id: number; onClose: () => void; onEditHostel: (h: HostelSummary) => void }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'hostel', id], queryFn: () => api<HostelDetail>(`/api/hostel/hostels/${id}`, staff) });
  const [room, setRoom] = useState<HostelRoomView | 'new' | null>(null);
  const bed = useMutation({
    mutationFn: (p: { id: number; status: string }) => api(`/api/hostel/beds/${p.id}/status`, { ...staff, method: 'PUT', body: { status: p.status } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staff'] }),
  });
  const d = q.data;
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>
        <Stack direction="row" justifyContent="space-between" alignItems="center">
          <span>{d?.hostel.name ?? 'Hostel'}</span>
          {d?.canEdit && <Stack direction="row" spacing={1}><Button size="small" onClick={() => onEditHostel(d.hostel)}>Details</Button><Button size="small" startIcon={<AddIcon />} onClick={() => setRoom('new')}>Room</Button></Stack>}
        </Stack>
      </DialogTitle>
      <DialogContent dividers>
        <ErrorAlert error={q.error ?? bed.error} />
        {q.isLoading && <Loading />}
        {d && !d.rooms.length && <Typography color="text.secondary">No rooms yet.</Typography>}
        <Stack spacing={1}>
          {d?.rooms.map((r) => (
            <Card key={r.id} variant="outlined" sx={{ opacity: r.status === 'AVAILABLE' ? 1 : 0.7 }}>
              <CardContent sx={{ py: 1.25, '&:last-child': { pb: 1.25 } }}>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between">
                  <Box>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{r.block ? `${r.block} · ` : ''}Room {r.roomNo} <Typography component="span" variant="caption" color="text.secondary">floor {r.floor}</Typography></Typography>
                    <Typography variant="caption" color="text.secondary">{title(r.roomType)} · {title(r.gender)} · {r.feeCategory} {formatTzs(r.fee)}{r.accessible ? ' · accessible' : ''}{r.status !== 'AVAILABLE' ? ` · ${title(r.status)}` : ''}</Typography>
                  </Box>
                  {d.canEdit && <IconButton size="small" onClick={() => setRoom(r)} aria-label="Edit room"><EditIcon fontSize="small" /></IconButton>}
                </Stack>
                <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" sx={{ mt: 1 }}>
                  {r.beds.map((b) => (
                    <Chip key={b.id} size="small" variant={b.occupantRegNo ? 'filled' : 'outlined'}
                      color={b.allocationStatus === 'CHECKED_IN' ? 'success' : b.allocationStatus === 'ALLOCATED' ? 'info' : b.status === 'MAINTENANCE' ? 'warning' : 'default'}
                      label={`${b.label}: ${b.occupantName ?? (b.status === 'MAINTENANCE' ? 'out of service' : 'free')}`}
                      onDelete={d.canEdit && !b.occupantRegNo ? () => bed.mutate({ id: b.id, status: b.status === 'MAINTENANCE' ? 'AVAILABLE' : 'MAINTENANCE' }) : undefined}
                      deleteIcon={<Tooltip title={b.status === 'MAINTENANCE' ? 'Back in service' : 'Out of service'}><BuildIcon /></Tooltip>} />
                  ))}
                </Stack>
              </CardContent>
            </Card>
          ))}
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
      {room && d && <RoomForm hostel={d.hostel} room={room === 'new' ? null : room} onClose={() => setRoom(null)}
        onDone={() => { setRoom(null); qc.invalidateQueries({ queryKey: ['staff'] }); }} />}
    </Dialog>
  );
}

function RoomForm({ hostel, room, onClose, onDone }: { hostel: HostelSummary; room: HostelRoomView | null; onClose: () => void; onDone: () => void }) {
  const fees = useQuery({ queryKey: ['staff', 'hostel-fees'], queryFn: () => api<HostelFeeCategory[]>('/api/hostel/fee-categories', staff) });
  const [f, setF] = useState({
    block: room?.block ?? '', floor: room?.floor ?? 0, roomNo: room?.roomNo ?? '', roomType: room?.roomType ?? ('DOUBLE' as RoomType),
    gender: room?.gender ?? (hostel.gender === 'MIXED' ? 'FEMALE' : hostel.gender), feeCategoryId: room?.feeCategoryId ?? 0, accessible: room?.accessible ?? false,
    status: room?.status ?? 'AVAILABLE', beds: room?.beds.length ?? 2,
  });
  useEffect(() => { if (!f.feeCategoryId && fees.data?.length) setF((x) => ({ ...x, feeCategoryId: fees.data![0].id })); }, [fees.data, f.feeCategoryId]);
  const m = useMutation({
    mutationFn: () => api(room ? `/api/hostel/rooms/${room.id}` : '/api/hostel/rooms', { ...staff, method: room ? 'PUT' : 'POST', body: { ...f, hostelId: hostel.id } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{room ? `Room ${room.roomNo}` : `New room in ${hostel.name}`}</DialogTitle>
      <DialogContent>
        {fees.data && !fees.data.length && <Alert severity="warning" sx={{ mb: 2 }}>Add a fee category first.</Alert>}
        <Grid container spacing={2} sx={{ mt: 0.5 }}>
          <Grid size={{ xs: 4 }}><TextField fullWidth label="Block" value={f.block} onChange={(e) => setF({ ...f, block: e.target.value })} /></Grid>
          <Grid size={{ xs: 4 }}><TextField fullWidth type="number" label="Floor" value={f.floor} onChange={(e) => setF({ ...f, floor: Number(e.target.value) })} /></Grid>
          <Grid size={{ xs: 4 }}><TextField fullWidth label="Room no." value={f.roomNo} onChange={(e) => setF({ ...f, roomNo: e.target.value })} /></Grid>
          <Grid size={{ xs: 6 }}><TextField fullWidth select label="Type" value={f.roomType} onChange={(e) => setF({ ...f, roomType: e.target.value as RoomType })}>
            {ROOM_TYPES.map((t) => <MenuItem key={t} value={t}>{title(t)}</MenuItem>)}</TextField></Grid>
          <Grid size={{ xs: 6 }}><TextField fullWidth type="number" label="Beds" value={f.beds} onChange={(e) => setF({ ...f, beds: Number(e.target.value) })} slotProps={{ htmlInput: { min: 1, max: 40 } }} /></Grid>
          <Grid size={{ xs: 6 }}><TextField fullWidth select label="Gender" value={f.gender} disabled={hostel.gender !== 'MIXED'} onChange={(e) => setF({ ...f, gender: e.target.value as 'FEMALE' | 'MALE' })}>
            <MenuItem value="FEMALE">Female</MenuItem><MenuItem value="MALE">Male</MenuItem></TextField></Grid>
          <Grid size={{ xs: 6 }}><TextField fullWidth select label="Fee category" value={f.feeCategoryId || ''} onChange={(e) => setF({ ...f, feeCategoryId: Number(e.target.value) })}>
            {(fees.data ?? []).map((c) => <MenuItem key={c.id} value={c.id}>{c.name} · {formatTzs(c.amount)}</MenuItem>)}</TextField></Grid>
          <Grid size={{ xs: 6 }}><TextField fullWidth select label="Status" value={f.status} onChange={(e) => setF({ ...f, status: e.target.value as typeof f.status })}>
            <MenuItem value="AVAILABLE">Available</MenuItem><MenuItem value="MAINTENANCE">Under maintenance</MenuItem><MenuItem value="CLOSED">Closed</MenuItem></TextField></Grid>
          <Grid size={{ xs: 6 }}><FormControlLabel control={<Switch checked={f.accessible} onChange={(e) => setF({ ...f, accessible: e.target.checked })} />} label="Accessible" /></Grid>
        </Grid>
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!f.roomNo || !f.feeCategoryId || m.isPending} onClick={() => m.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

// ============================================================ windows and fee categories

function WindowsTab({ warden }: { warden: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'hostel-windows'], queryFn: () => api<HostelWindow[]>('/api/hostel/windows', staff) });
  const [editing, setEditing] = useState<HostelWindow | 'new' | null>(null);
  return (
    <>
      {warden && <Stack direction="row" justifyContent="flex-end" sx={{ mb: 1.5 }}><Button startIcon={<AddIcon />} onClick={() => setEditing('new')}>New window</Button></Stack>}
      <ErrorAlert error={q.error} />
      <TableContainer component={Card}>
        <Table size="small">
          <TableHead><TableRow><TableCell>Window</TableCell><TableCell>Applications open</TableCell><TableCell align="right">Applied</TableCell>
            <TableCell align="right">Allocated</TableCell><TableCell align="right">Waiting</TableCell><TableCell align="right">Checked in</TableCell>{warden && <TableCell />}</TableRow></TableHead>
          <TableBody>
            {q.data?.map((w) => (
              <TableRow key={w.id}>
                <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{w.name}</Typography><Typography variant="caption" color="text.secondary">{w.academicYear} · pay within {w.paymentDays} days</Typography></TableCell>
                <TableCell>{formatDate(w.opensOn)} – {formatDate(w.closesOn)} {w.open && <Chip size="small" color="success" label="Open" sx={{ ml: 0.5 }} />}</TableCell>
                <TableCell align="right">{w.applications}</TableCell><TableCell align="right">{w.allocated}</TableCell>
                <TableCell align="right">{w.waitlisted}</TableCell><TableCell align="right">{w.checkedIn}</TableCell>
                {warden && <TableCell align="right"><IconButton size="small" onClick={() => setEditing(w)}><EditIcon fontSize="small" /></IconButton></TableCell>}
              </TableRow>
            ))}
            {q.data && !q.data.length && <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 1 }}>No windows yet.</Typography></TableCell></TableRow>}
          </TableBody>
        </Table>
      </TableContainer>
      {editing && <WindowForm w={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); qc.invalidateQueries({ queryKey: ['staff', 'hostel-windows'] }); }} />}
    </>
  );
}

function WindowForm({ w, onClose, onDone }: { w: HostelWindow | null; onClose: () => void; onDone: () => void }) {
  const years = useQuery({ queryKey: ['staff', 'academic-years'], queryFn: () => api<AcademicYear[]>('/api/academic/years', staff) });
  const [f, setF] = useState({
    academicYearId: w?.academicYearId ?? 0, name: w?.name ?? '', opensOn: w?.opensOn ?? '', closesOn: w?.closesOn ?? '', occupancyStarts: w?.occupancyStarts ?? '',
    occupancyEnds: w?.occupancyEnds ?? '', paymentDays: w?.paymentDays ?? 14, firstYearsFirst: w?.firstYearsFirst ?? true, specialNeedsFirst: w?.specialNeedsFirst ?? true,
    paidFeesFirst: w?.paidFeesFirst ?? true,
  });
  useEffect(() => { if (!f.academicYearId && years.data?.length) setF((x) => ({ ...x, academicYearId: years.data![0].id })); }, [years.data, f.academicYearId]);
  const m = useMutation({
    mutationFn: () => api(w ? `/api/hostel/windows/${w.id}` : '/api/hostel/windows', { ...staff, method: w ? 'PUT' : 'POST',
      body: { ...f, occupancyStarts: f.occupancyStarts || null, occupancyEnds: f.occupancyEnds || null } }),
    onSuccess: onDone,
  });
  const date = (k: 'opensOn' | 'closesOn' | 'occupancyStarts' | 'occupancyEnds', label: string) => (
    <TextField fullWidth type="date" label={label} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
  );
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{w ? w.name : 'New application window'}</DialogTitle>
      <DialogContent>
        <Grid container spacing={2} sx={{ mt: 0.5 }}>
          <Grid size={{ xs: 12, sm: 8 }}><TextField fullWidth label="Name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth select label="Academic year" value={f.academicYearId || ''} onChange={(e) => setF({ ...f, academicYearId: Number(e.target.value) })}>
            {(years.data ?? []).map((y) => <MenuItem key={y.id} value={y.id}>{y.code}</MenuItem>)}</TextField></Grid>
          <Grid size={{ xs: 6 }}>{date('opensOn', 'Applications open')}</Grid>
          <Grid size={{ xs: 6 }}>{date('closesOn', 'Applications close')}</Grid>
          <Grid size={{ xs: 6 }}>{date('occupancyStarts', 'Occupancy from')}</Grid>
          <Grid size={{ xs: 6 }}>{date('occupancyEnds', 'Occupancy until')}</Grid>
          <Grid size={{ xs: 12 }}><TextField fullWidth type="number" label="Days to pay after allocation" value={f.paymentDays} onChange={(e) => setF({ ...f, paymentDays: Number(e.target.value) })}
            helperText="Unpaid allocations are released after this and the bed offered to the waiting list" /></Grid>
          <Grid size={{ xs: 12 }}>
            <Typography variant="subtitle2">Priority when allocating</Typography>
            <FormControlLabel control={<Switch checked={f.specialNeedsFirst} onChange={(e) => setF({ ...f, specialNeedsFirst: e.target.checked })} />} label="Special needs first" />
            <FormControlLabel control={<Switch checked={f.firstYearsFirst} onChange={(e) => setF({ ...f, firstYearsFirst: e.target.checked })} />} label="First years first" />
            <FormControlLabel control={<Switch checked={f.paidFeesFirst} onChange={(e) => setF({ ...f, paidFeesFirst: e.target.checked })} />} label="Students meeting the hostel fees gate first" />
          </Grid>
        </Grid>
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!f.name || !f.opensOn || !f.closesOn || m.isPending} onClick={() => m.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

function FeesTab({ warden }: { warden: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'hostel-fees'], queryFn: () => api<HostelFeeCategory[]>('/api/hostel/fee-categories', staff) });
  const [editing, setEditing] = useState<HostelFeeCategory | 'new' | null>(null);
  return (
    <>
      {warden && <Stack direction="row" justifyContent="flex-end" sx={{ mb: 1.5 }}><Button startIcon={<AddIcon />} onClick={() => setEditing('new')}>New fee category</Button></Stack>}
      <ErrorAlert error={q.error} />
      <TableContainer component={Card}>
        <Table size="small">
          <TableHead><TableRow><TableCell>Code</TableCell><TableCell>Name</TableCell><TableCell align="right">Per bed per academic year</TableCell>{warden && <TableCell />}</TableRow></TableHead>
          <TableBody>
            {q.data?.map((c) => (
              <TableRow key={c.id} sx={{ opacity: c.active ? 1 : 0.5 }}>
                <TableCell>{c.code}</TableCell><TableCell>{c.name}</TableCell><TableCell align="right">{formatTzs(c.amount)}</TableCell>
                {warden && <TableCell align="right"><IconButton size="small" onClick={() => setEditing(c)}><EditIcon fontSize="small" /></IconButton></TableCell>}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {editing && <FeeForm c={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); qc.invalidateQueries({ queryKey: ['staff', 'hostel-fees'] }); }} />}
    </>
  );
}

function FeeForm({ c, onClose, onDone }: { c: HostelFeeCategory | null; onClose: () => void; onDone: () => void }) {
  const [code, setCode] = useState(c?.code ?? '');
  const [name, setName] = useState(c?.name ?? '');
  const [amount, setAmount] = useState(String(c?.amount ?? ''));
  const [active, setActive] = useState(c?.active ?? true);
  const m = useMutation({
    mutationFn: () => api(c ? `/api/hostel/fee-categories/${c.id}` : '/api/hostel/fee-categories', { ...staff, method: c ? 'PUT' : 'POST', body: { code, name, amount: Number(amount), active } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>{c ? c.name : 'New fee category'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField label="Code" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} disabled={!!c} />
          <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} />
          <TextField label="Amount (TZS per academic year)" type="number" value={amount} onChange={(e) => setAmount(e.target.value)} />
          <FormControlLabel control={<Switch checked={active} onChange={(e) => setActive(e.target.checked)} />} label="Active" />
          <ErrorAlert error={m.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!code || !name || amount === '' || m.isPending} onClick={() => m.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}
