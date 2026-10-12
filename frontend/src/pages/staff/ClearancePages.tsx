import AddIcon from '@mui/icons-material/Add';
import EditIcon from '@mui/icons-material/EditOutlined';
import {
  Alert, Autocomplete, Box, Button, Card, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton,
  LinearProgress, MenuItem, Stack, Switch, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Tooltip, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, formatDate, formatDateTime } from '../../api/client';
import type {
  ClearanceDetail, ClearanceItem, ClearanceItemStatus, ClearanceOffice, ClearanceSummary, Finalist, OfficeKind, QueueLine, StaffOption,
} from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';

const staff = { realm: 'staff' as const };
const REGISTRY = ['INSTITUTION_ADMIN', 'REGISTRAR'];
const VIEWERS = ['INSTITUTION_ADMIN', 'REGISTRAR', 'DEPUTY_PRINCIPAL_ACADEMIC', 'MANAGEMENT_VIEWER'];
export const ITEM_COLOUR = { PENDING: 'default', CLEARED: 'success', HELD: 'error' } as const;
export const ITEM_LABEL: Record<ClearanceItemStatus, string> = { PENDING: 'Waiting', CLEARED: 'Cleared', HELD: 'Held' };
const KIND_LABEL: Record<OfficeKind, string> = {
  DEPARTMENT: "Head of the student's department", FINANCE: 'Automatic from the fees balance', STAFF: 'Assigned officers',
};

/** Clearance of finalists (FR-GRD-01): office queues for officers, finalists and clearances for Registry, office set-up. */
export function ClearancePage() {
  const can = useRoles();
  const viewer = can(VIEWERS);
  const offices = useQuery({ queryKey: ['staff', 'clearance-offices'], queryFn: () => api<ClearanceOffice[]>('/api/clearance/offices', staff) });
  const mineOffices = (offices.data ?? []).filter((o) => o.canDecide);
  const [tab, setTab] = useState<string>();
  useEffect(() => {
    if (tab || !offices.data) return;
    setTab(mineOffices.length ? 'queue' : viewer ? 'finalists' : 'queue');
  }, [offices.data, tab, mineOffices.length, viewer]);
  const [open, setOpen] = useState<number | null>(null);

  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Clearance" subtitle="Finalists are cleared by every office before they are listed for graduation. Finance clears itself once nothing is owed." />
      <ErrorAlert error={offices.error} />
      {offices.isLoading && <Loading />}
      {tab && (
        <>
          <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2, borderBottom: 1, borderColor: 'divider' }} variant="scrollable">
            {(mineOffices.length > 0 || !viewer) && <Tab value="queue" label="My office" />}
            {viewer && <Tab value="finalists" label="Finalists" />}
            {viewer && <Tab value="all" label="All clearances" />}
            {viewer && <Tab value="offices" label="Offices" />}
          </Tabs>
          {tab === 'queue' && <QueueTab offices={mineOffices} onOpen={setOpen} />}
          {tab === 'finalists' && <FinalistsTab onOpen={setOpen} />}
          {tab === 'all' && <AllTab onOpen={setOpen} />}
          {tab === 'offices' && <OfficesTab offices={offices.data ?? []} editable={can(REGISTRY)} />}
        </>
      )}
      {open != null && <ClearanceDialog id={open} onClose={() => setOpen(null)} />}
    </Container>
  );
}

// ============================================================ an office's queue

function QueueTab({ offices, onOpen }: { offices: ClearanceOffice[]; onOpen: (id: number) => void }) {
  const [officeId, setOfficeId] = useState<number | undefined>(offices[0]?.id);
  const [filter, setFilter] = useState<'open' | 'all'>('open');
  const [deciding, setDeciding] = useState<{ line: QueueLine; status: 'CLEARED' | 'HELD' } | null>(null);
  const qc = useQueryClient();
  const q = useQuery({
    queryKey: ['staff', 'clearance-queue', officeId], enabled: !!officeId,
    queryFn: () => api<QueueLine[]>(`/api/clearance/offices/${officeId}/queue`, staff),
  });
  if (!offices.length) return <Alert severity="info">You are not an officer of any clearance office. Registry assigns officers under Offices.</Alert>;
  const rows = (q.data ?? []).filter((l) => filter === 'all' || l.status !== 'CLEARED');
  return (
    <>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }} alignItems={{ sm: 'center' }}>
        {offices.length > 1 && (
          <TextField select size="small" label="Office" value={officeId ?? ''} onChange={(e) => setOfficeId(Number(e.target.value))} sx={{ minWidth: 220 }}>
            {offices.map((o) => <MenuItem key={o.id} value={o.id}>{o.name}</MenuItem>)}
          </TextField>
        )}
        <Chip clickable label="To decide" color={filter === 'open' ? 'primary' : 'default'} variant={filter === 'open' ? 'filled' : 'outlined'} onClick={() => setFilter('open')} />
        <Chip clickable label="Everyone" color={filter === 'all' ? 'primary' : 'default'} variant={filter === 'all' ? 'filled' : 'outlined'} onClick={() => setFilter('all')} />
      </Stack>
      <ErrorAlert error={q.error} />
      {q.isLoading && <Loading />}
      {q.data && (
        <TableContainer component={Card}>
          <Table size="small">
            <TableHead><TableRow>
              <TableCell>Student</TableCell><TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Started</TableCell>
              <TableCell>Status</TableCell><TableCell align="right">Decision</TableCell>
            </TableRow></TableHead>
            <TableBody>
              {rows.map((l) => (
                <TableRow key={l.itemId} hover>
                  <TableCell sx={{ cursor: 'pointer' }} onClick={() => onOpen(l.clearanceId)}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{l.registrationNo}</Typography>
                    <Typography variant="caption" color="text.secondary">{l.studentName} · {l.programmeCode}</Typography>
                  </TableCell>
                  <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{formatDate(l.startedAt)}</TableCell>
                  <TableCell>
                    <Chip size="small" color={ITEM_COLOUR[l.status]} label={ITEM_LABEL[l.status]} variant={l.status === 'PENDING' ? 'outlined' : 'filled'} />
                    {l.note && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>{l.note}</Typography>}
                    {l.decidedByName && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{l.decidedByName}, {formatDate(l.decidedAt!)}</Typography>}
                  </TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                    {l.canDecide && (
                      <>
                        {l.status !== 'HELD' && <Button size="small" color="error" onClick={() => setDeciding({ line: l, status: 'HELD' })}>Hold</Button>}
                        {l.status !== 'CLEARED' && <Button size="small" variant="contained" color="success" sx={{ ml: 1 }} onClick={() => setDeciding({ line: l, status: 'CLEARED' })}>Clear</Button>}
                      </>
                    )}
                  </TableCell>
                </TableRow>
              ))}
              {!rows.length && <TableRow><TableCell colSpan={4}><Typography color="text.secondary" sx={{ py: 1 }}>{filter === 'open' ? 'Nothing waiting for this office.' : 'No clearances yet.'}</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {deciding && (
        <DecideDialog itemId={deciding.line.itemId} who={`${deciding.line.registrationNo} · ${deciding.line.studentName}`} status={deciding.status}
          onClose={() => setDeciding(null)} onDone={() => { setDeciding(null); qc.invalidateQueries({ queryKey: ['staff', 'clearance-queue'] }); qc.invalidateQueries({ queryKey: ['staff', 'clearance-offices'] }); }} />
      )}
    </>
  );
}

function DecideDialog({ itemId, who, status, onClose, onDone }: { itemId: number; who: string; status: 'CLEARED' | 'HELD'; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('');
  const m = useMutation({
    mutationFn: () => api(`/api/clearance/items/${itemId}`, { ...staff, method: 'PUT', body: { status, note: note.trim() || null } }),
    onSuccess: onDone,
  });
  const hold = status === 'HELD';
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{hold ? 'Hold' : 'Clear'} · {who}</DialogTitle>
      <DialogContent>
        <TextField fullWidth multiline minRows={2} sx={{ mt: 1 }} label={hold ? 'Reason and what the student must do' : 'Remark (optional)'} value={note}
          onChange={(e) => setNote(e.target.value)} required={hold} slotProps={{ htmlInput: { maxLength: 500 } }}
          helperText={hold ? 'The student sees this on the portal and gets it by SMS.' : undefined} />
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color={hold ? 'error' : 'success'} disabled={m.isPending || (hold && !note.trim())} onClick={() => m.mutate()}>{hold ? 'Hold' : 'Clear'}</Button>
      </DialogActions>
    </Dialog>
  );
}

// ============================================================ finalists and all clearances

function FinalistsTab({ onOpen }: { onOpen: (id: number) => void }) {
  const can = useRoles();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'finalists'], queryFn: () => api<Finalist[]>('/api/clearance/finalists', staff) });
  const start = useMutation({
    mutationFn: (studentId: number) => api<ClearanceDetail>('/api/clearance/clearances', { ...staff, method: 'POST', body: { studentId } }),
    onSuccess: (c) => { qc.invalidateQueries({ queryKey: ['staff', 'finalists'] }); onOpen(c.id); },
  });
  return (
    <>
      <ErrorAlert error={q.error ?? start.error} />
      {q.isLoading && <Loading />}
      {q.data && (
        <TableContainer component={Card}>
          <Table size="small">
            <TableHead><TableRow>
              <TableCell>Student</TableCell><TableCell>Award</TableCell><TableCell align="right">GPA</TableCell><TableCell>Clearance</TableCell><TableCell />
            </TableRow></TableHead>
            <TableBody>
              {q.data.map((f) => (
                <TableRow key={f.studentId}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{f.registrationNo}</Typography>
                    <Typography variant="caption" color="text.secondary">{f.studentName} · {f.programmeCode}</Typography>
                  </TableCell>
                  <TableCell>
                    {f.award.qualified ? <>
                      <Typography variant="body2">{f.award.awardName}</Typography>
                      <Typography variant="caption" color="text.secondary">{f.award.classification ?? '—'} · completed {f.award.completedSemester}</Typography>
                    </> : <Typography variant="body2" color="warning.main">{f.award.reason}</Typography>}
                  </TableCell>
                  <TableCell align="right">{f.award.cgpa ?? '—'}</TableCell>
                  <TableCell>
                    {f.graduation ? <Chip size="small" color="primary" variant="outlined" label={f.graduation} />
                      : f.clearanceStatus ? <Chip size="small" clickable onClick={() => onOpen(f.clearanceId!)} color={f.clearanceStatus === 'CLEARED' ? 'success' : 'default'}
                        label={f.clearanceStatus === 'CLEARED' ? 'Cleared' : 'In progress'} />
                        : <Typography variant="body2" color="text.secondary">Not started</Typography>}
                  </TableCell>
                  <TableCell align="right">
                    {can(REGISTRY) && f.award.qualified && !f.clearanceStatus && (
                      <Button size="small" onClick={() => start.mutate(f.studentId)} disabled={start.isPending} sx={{ whiteSpace: 'nowrap' }}>Start clearance</Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
              {!q.data.length && <TableRow><TableCell colSpan={5}><Typography color="text.secondary" sx={{ py: 1 }}>No finalists yet: students appear here once their final semester results are published.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </>
  );
}

function AllTab({ onOpen }: { onOpen: (id: number) => void }) {
  const q = useQuery({ queryKey: ['staff', 'clearances'], queryFn: () => api<ClearanceSummary[]>('/api/clearance/clearances', staff) });
  return (
    <>
      <ErrorAlert error={q.error} />
      {q.isLoading && <Loading />}
      {q.data && (
        <TableContainer component={Card}>
          <Table size="small">
            <TableHead><TableRow><TableCell>Student</TableCell><TableCell>Started</TableCell><TableCell sx={{ minWidth: 160 }}>Offices cleared</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
            <TableBody>
              {q.data.map((c) => (
                <TableRow key={c.id} hover sx={{ cursor: 'pointer' }} onClick={() => onOpen(c.id)}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{c.registrationNo}</Typography>
                    <Typography variant="caption" color="text.secondary">{c.studentName} · {c.programmeCode}</Typography>
                  </TableCell>
                  <TableCell>{formatDate(c.startedAt)}</TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={1} alignItems="center">
                      <LinearProgress variant="determinate" value={(c.cleared / Math.max(1, c.total)) * 100} color={c.status === 'CLEARED' ? 'success' : 'primary'}
                        sx={{ flexGrow: 1, height: 6, borderRadius: 3 }} />
                      <Typography variant="body2">{c.cleared}/{c.total}</Typography>
                    </Stack>
                  </TableCell>
                  <TableCell>
                    <Chip size="small" color={c.status === 'CLEARED' ? 'success' : 'default'} label={c.status === 'CLEARED' ? `Cleared ${formatDate(c.clearedAt!)}` : 'In progress'} />
                    {c.held > 0 && <Chip size="small" color="error" variant="outlined" label={`${c.held} held`} sx={{ ml: 0.5 }} />}
                  </TableCell>
                </TableRow>
              ))}
              {!q.data.length && <TableRow><TableCell colSpan={4}><Typography color="text.secondary" sx={{ py: 1 }}>No clearances started yet.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </>
  );
}

/** One student's clearance across the offices. */
function ClearanceDialog({ id, onClose }: { id: number; onClose: () => void }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'clearance', id], queryFn: () => api<ClearanceDetail>(`/api/clearance/clearances/${id}`, staff) });
  const [deciding, setDeciding] = useState<{ item: ClearanceItem; status: 'CLEARED' | 'HELD' } | null>(null);
  const c = q.data;
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{c ? `${c.registrationNo} · ${c.studentName}` : 'Clearance'}</DialogTitle>
      <DialogContent dividers>
        <ErrorAlert error={q.error} />
        {q.isLoading && <Loading />}
        {c && (
          <>
            <Typography variant="body2" color="text.secondary">{c.programmeName} · started {formatDateTime(c.startedAt)} by {c.startedByName}</Typography>
            {c.status === 'CLEARED' && <Alert severity="success" sx={{ mt: 1.5 }}>Fully cleared {formatDateTime(c.clearedAt!)}.</Alert>}
            <ClearanceItems items={c.items} onDecide={(item, status) => setDeciding({ item, status })} />
          </>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
      {deciding && c && (
        <DecideDialog itemId={deciding.item.id} who={`${c.registrationNo} · ${deciding.item.officeName}`} status={deciding.status} onClose={() => setDeciding(null)}
          onDone={() => { setDeciding(null); qc.invalidateQueries({ queryKey: ['staff'] }); }} />
      )}
    </Dialog>
  );
}

export function ClearanceItems({ items, onDecide }: { items: ClearanceItem[]; onDecide?: (item: ClearanceItem, status: 'CLEARED' | 'HELD') => void }) {
  return (
    <Box sx={{ mt: 1.5 }}>
      {items.map((i) => (
        <Stack key={i.id} direction="row" spacing={1.5} alignItems="flex-start" justifyContent="space-between" sx={{ py: 1.25, borderBottom: 1, borderColor: 'divider' }}>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>{i.officeName}</Typography>
            {i.note && <Typography variant="body2" color={i.status === 'HELD' ? 'error.main' : 'text.secondary'}>{i.note}</Typography>}
            {i.status === 'PENDING' && i.instructions && <Typography variant="caption" color="text.secondary">{i.instructions}</Typography>}
            {i.decidedByName && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{i.decidedByName}{i.decidedAt && `, ${formatDate(i.decidedAt)}`}</Typography>}
          </Box>
          <Stack alignItems="flex-end" spacing={0.5} sx={{ flexShrink: 0 }}>
            <Chip size="small" color={ITEM_COLOUR[i.status]} label={ITEM_LABEL[i.status]} variant={i.status === 'PENDING' ? 'outlined' : 'filled'} />
            {onDecide && i.canDecide && (
              <Stack direction="row" spacing={0.5}>
                {i.status !== 'HELD' && <Button size="small" color="error" onClick={() => onDecide(i, 'HELD')}>Hold</Button>}
                {i.status !== 'CLEARED' && <Button size="small" color="success" onClick={() => onDecide(i, 'CLEARED')}>Clear</Button>}
              </Stack>
            )}
          </Stack>
        </Stack>
      ))}
    </Box>
  );
}

// ============================================================ offices

function OfficesTab({ offices, editable }: { offices: ClearanceOffice[]; editable: boolean }) {
  const [editing, setEditing] = useState<ClearanceOffice | 'new' | null>(null);
  const qc = useQueryClient();
  return (
    <>
      {editable && <Stack direction="row" justifyContent="flex-end" sx={{ mb: 1.5 }}><Button startIcon={<AddIcon />} onClick={() => setEditing('new')}>New office</Button></Stack>}
      <TableContainer component={Card}>
        <Table size="small">
          <TableHead><TableRow><TableCell>Office</TableCell><TableCell>Who clears</TableCell><TableCell align="right">Waiting</TableCell><TableCell align="right">Held</TableCell>{editable && <TableCell />}</TableRow></TableHead>
          <TableBody>
            {offices.map((o) => (
              <TableRow key={o.id} sx={{ opacity: o.active ? 1 : 0.5 }}>
                <TableCell>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{o.name}{!o.active && ' (inactive)'}</Typography>
                  {o.instructions && <Typography variant="caption" color="text.secondary">{o.instructions}</Typography>}
                </TableCell>
                <TableCell>
                  <Typography variant="body2">{o.kind === 'STAFF' ? (o.staff.length ? o.staff.map((p) => p.fullName).join(', ') : <Box component="span" sx={{ color: 'warning.main' }}>No officer assigned</Box>) : KIND_LABEL[o.kind]}</Typography>
                </TableCell>
                <TableCell align="right">{o.pending}</TableCell>
                <TableCell align="right">{o.held}</TableCell>
                {editable && <TableCell align="right"><Tooltip title="Edit"><IconButton size="small" onClick={() => setEditing(o)}><EditIcon fontSize="small" /></IconButton></Tooltip></TableCell>}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {editing && <OfficeDialog office={editing === 'new' ? null : editing} onClose={() => setEditing(null)}
        onDone={() => { setEditing(null); qc.invalidateQueries({ queryKey: ['staff', 'clearance-offices'] }); }} />}
    </>
  );
}

function OfficeDialog({ office, onClose, onDone }: { office: ClearanceOffice | null; onClose: () => void; onDone: () => void }) {
  const people = useQuery({ queryKey: ['staff', 'clearance-staff'], queryFn: () => api<StaffOption[]>('/api/clearance/staff', staff) });
  const [code, setCode] = useState(office?.code ?? '');
  const [name, setName] = useState(office?.name ?? '');
  const [kind, setKind] = useState<OfficeKind>(office?.kind ?? 'STAFF');
  const [instructions, setInstructions] = useState(office?.instructions ?? '');
  const [order, setOrder] = useState(office?.sortOrder ?? 60);
  const [active, setActive] = useState(office?.active ?? true);
  const [chosen, setChosen] = useState<number[]>(office?.staff.map((p) => p.id) ?? []);
  const save = useMutation({
    mutationFn: () => api(office ? `/api/clearance/offices/${office.id}` : '/api/clearance/offices', {
      ...staff, method: office ? 'PUT' : 'POST',
      body: { code, name, kind, instructions: instructions.trim() || null, sortOrder: order, active, staffUserIds: kind === 'STAFF' ? chosen : [] },
    }),
    onSuccess: onDone,
  });
  const options = people.data ?? [];
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{office ? office.name : 'New clearance office'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Stack direction="row" spacing={1.5}>
            <TextField label="Code" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} disabled={!!office} sx={{ width: 140 }} slotProps={{ htmlInput: { maxLength: 20 } }} />
            <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} fullWidth slotProps={{ htmlInput: { maxLength: 120 } }} />
          </Stack>
          <TextField select label="Who clears" value={kind} onChange={(e) => setKind(e.target.value as OfficeKind)}>
            {(Object.keys(KIND_LABEL) as OfficeKind[]).map((k) => <MenuItem key={k} value={k}>{KIND_LABEL[k]}</MenuItem>)}
          </TextField>
          {kind === 'STAFF' && (
            <Autocomplete multiple options={options} value={options.filter((o) => chosen.includes(o.id))} getOptionLabel={(o) => o.fullName}
              onChange={(_, v) => setChosen(v.map((x) => x.id))} renderInput={(p) => <TextField {...p} label="Officers" />}
              renderOption={(p, o) => <li {...p} key={o.id}><Box><Typography>{o.fullName}</Typography>
                <Typography variant="caption" color="text.secondary">{o.roles.map((r) => r.replace(/_/g, ' ').toLowerCase()).join(', ')}</Typography></Box></li>} />
          )}
          <TextField label="What the student must do" value={instructions} onChange={(e) => setInstructions(e.target.value)} multiline minRows={2}
            slotProps={{ htmlInput: { maxLength: 500 } }} />
          <Stack direction="row" spacing={2} alignItems="center">
            <TextField type="number" label="Order" value={order} onChange={(e) => setOrder(Number(e.target.value))} sx={{ width: 110 }} />
            <FormControlLabel control={<Switch checked={active} onChange={(e) => setActive(e.target.checked)} />} label="Active (new clearances include it)" />
          </Stack>
          <ErrorAlert error={save.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!code.trim() || !name.trim() || save.isPending} onClick={() => save.mutate()}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}
