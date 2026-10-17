import CheckIcon from '@mui/icons-material/Check';
import CloseIcon from '@mui/icons-material/Close';
import MoveUpIcon from '@mui/icons-material/MoveUp';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Link, MenuItem, Stack, Tab, Table,
  TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, formatDate, formatDateTime } from '../../api/client';
import type { StudentDetail, Transfer, TransferOptions } from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';
import { useCategories } from './StructurePages';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };
const REGISTRY = ['INSTITUTION_ADMIN', 'REGISTRAR'];

export const TRANSFER_STATE: Record<Transfer['state'], { label: string; colour: 'default' | 'warning' | 'info' | 'success' | 'error' }> = {
  REQUESTED: { label: 'Waiting', colour: 'warning' }, RECOMMENDED: { label: 'Recommended — waiting for the Deputy Principal', colour: 'info' },
  APPROVED: { label: 'Approved', colour: 'success' }, REJECTED: { label: 'Rejected', colour: 'error' }, CANCELLED: { label: 'Cancelled', colour: 'default' },
};
export const KIND_LABEL: Record<Transfer['kind'], string> = { PROGRAMME: 'Programme transfer', CAMPUS: 'Campus transfer', BOTH: 'Programme and campus transfer' };

function Trail({ t }: { t: Transfer }) {
  return (
    <Box sx={{ fontSize: 13, color: 'text.secondary', lineHeight: 1.6 }}>
      <div>Asked by {t.requestedByName} on {formatDateTime(t.requestedAt)}</div>
      {t.recommendedByName && <div>Recommended by {t.recommendedByName}{t.recommendationNote ? ` — “${t.recommendationNote}”` : ''}</div>}
      {t.decidedByName && <div>{t.state === 'CANCELLED' ? 'Cancelled' : t.state === 'REJECTED' ? 'Rejected' : 'Approved'} by {t.decidedByName} on {formatDateTime(t.decidedAt)}{t.decisionNote ? ` — “${t.decisionNote}”` : ''}</div>}
    </Box>
  );
}

function Move({ t }: { t: Transfer }) {
  const programme = t.fromProgramme !== t.toProgramme;
  const campus = t.fromCampus !== t.toCampus;
  return (
    <Box sx={{ fontSize: 14 }}>
      {programme && <div>{t.fromProgramme} (year {t.fromYear}) → <strong>{t.toProgramme}</strong> (year {t.toYear})</div>}
      {campus && <div>{t.fromCampus ?? '—'} → <strong>{t.toCampus ?? '—'}</strong></div>}
    </Box>
  );
}

function useTransferActions(onDone: () => void) {
  const [dialog, setDialog] = useState<{ t: Transfer; action: 'recommend' | 'approve' | 'reject' | 'cancel' } | null>(null);
  const [note, setNote] = useState('');
  const act = useMutation({
    mutationFn: () => {
      const { t, action } = dialog!;
      if (action === 'recommend') return api<Transfer>(`/api/registry/transfers/${t.id}/recommend`, { method: 'POST', ...staff, body: { note } });
      if (action === 'cancel') return api<Transfer>(`/api/registry/transfers/${t.id}/cancel`, { method: 'POST', ...staff, body: { note } });
      return api<Transfer>(`/api/registry/transfers/${t.id}/decide`, { method: 'POST', ...staff, body: { approve: action === 'approve', note } });
    },
    onSuccess: () => { setDialog(null); setNote(''); onDone(); },
  });
  const open = (t: Transfer, action: 'recommend' | 'approve' | 'reject' | 'cancel') => { act.reset(); setNote(''); setDialog({ t, action }); };
  const buttons = (t: Transfer) => (
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
      {t.canRecommend && <Button size="small" variant="contained" startIcon={<CheckIcon />} onClick={() => open(t, 'recommend')}>Recommend</Button>}
      {t.canDecide && <Button size="small" variant="contained" color="success" startIcon={<CheckIcon />} onClick={() => open(t, 'approve')}>Approve</Button>}
      {t.canDecide && <Button size="small" variant="outlined" color="error" startIcon={<CloseIcon />} onClick={() => open(t, 'reject')}>Reject</Button>}
      {t.canCancel && <Button size="small" onClick={() => open(t, 'cancel')}>Cancel</Button>}
    </Stack>
  );
  const title = { recommend: 'Recommend this transfer', approve: 'Approve this transfer', reject: 'Reject this transfer', cancel: 'Cancel this transfer' };
  const element = dialog && (
    <Dialog open onClose={() => setDialog(null)} maxWidth="sm" fullWidth>
      <DialogTitle>{title[dialog.action]}</DialogTitle>
      <DialogContent>
        <Typography><strong>{KIND_LABEL[dialog.t.kind]}</strong> for {dialog.t.studentName} ({dialog.t.registrationNo})</Typography>
        <Box sx={{ my: 1 }}><Move t={dialog.t} /></Box>
        <Typography variant="body2" color="text.secondary">Reason: {dialog.t.reason}</Typography>
        {dialog.action === 'approve' && (
          <Alert severity="info" sx={{ mt: 2 }}>
            The student moves now and is told by SMS. The registration number stays the same.
            {dialog.t.kind !== 'CAMPUS' && ' Their ID card is withdrawn so a new one shows the new programme, and they choose modules of the new curriculum at the next semester registration. Check their fees invoice with the Bursar.'}
          </Alert>
        )}
        <TextField label={dialog.action === 'reject' ? 'Reason for rejecting' : 'Note (optional)'} required={dialog.action === 'reject'}
          fullWidth multiline minRows={2} sx={{ mt: 2 }} value={note} onChange={(e) => setNote(e.target.value)} inputProps={{ maxLength: 1000 }} />
        <ErrorAlert error={act.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={() => setDialog(null)}>Close</Button>
        <Button variant="contained" color={dialog.action === 'reject' ? 'error' : 'primary'} disabled={act.isPending || (dialog.action === 'reject' && !note.trim())}
          onClick={() => act.mutate()}>{title[dialog.action].split(' ')[0]}</Button>
      </DialogActions>
    </Dialog>
  );
  return { buttons, element };
}

/** On the student record: campus and fee category, transfer history, and a new transfer request (Registry). */
export function StudentTransferCard({ student }: { student: StudentDetail }) {
  const qc = useQueryClient();
  const roles = useRoles();
  const isRegistry = roles(REGISTRY);
  const canCategory = roles(['INSTITUTION_ADMIN', 'REGISTRAR', 'BURSAR']);
  const id = student.summary.id;
  const list = useQuery({ queryKey: ['staff', 'student', String(id), 'transfers'], queryFn: () => api<Transfer[]>(`/api/registry/students/${id}/transfers`, staff) });
  const options = useQuery({ queryKey: ['staff', 'student', String(id), 'transfer-options'], enabled: isRegistry,
    queryFn: () => api<TransferOptions>(`/api/registry/students/${id}/transfer-options`, staff) });
  const categories = useCategories();
  const [open, setOpen] = useState(false);
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['staff', 'student', String(id)] });
    qc.invalidateQueries({ queryKey: ['staff', 'transfers'] });
    qc.invalidateQueries({ queryKey: ['staff', 'approvals'] });
    qc.invalidateQueries({ queryKey: ['staff', 'students'] });
  };
  const actions = useTransferActions(refresh);
  const setCategory = useMutation({
    mutationFn: (v: string) => api(`/api/registry/students/${id}/category`, { method: 'PUT', ...staff, body: { studentCategoryId: v ? Number(v) : null } }),
    onSuccess: refresh,
  });
  const rows = list.data ?? [];
  return (
    <Card sx={{ mb: 2.5 }}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6">Campus, category and transfers</Typography>
          {isRegistry && options.data && ['ADMITTED', 'ACTIVE', 'POSTPONED'].includes(options.data.status) && (
            <Button size="small" variant="outlined" startIcon={<MoveUpIcon />} disabled={options.data.openTransfer} onClick={() => setOpen(true)}>Request a transfer</Button>
          )}
        </Stack>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} sx={{ mb: 1.5 }} alignItems={{ sm: 'center' }}>
          <Box sx={{ minWidth: 180 }}><Typography variant="caption" color="text.secondary">Campus</Typography><Typography>{student.campus ?? '—'}</Typography></Box>
          {(categories.data?.length ?? 0) > 0 && (
            <TextField select size="small" label="Fee category" sx={{ width: 240 }} value={student.studentCategoryId ? String(student.studentCategoryId) : ''}
              disabled={!canCategory || setCategory.isPending} onChange={(e) => setCategory.mutate(e.target.value)}
              slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
              helperText="Used for invoices from now on">
              <MenuItem value="">General</MenuItem>
              {categories.data?.filter((c) => c.active || c.id === student.studentCategoryId).map((c) => <MenuItem key={c.id} value={String(c.id)}>{c.name}</MenuItem>)}
            </TextField>
          )}
        </Stack>
        <ErrorAlert error={list.error ?? setCategory.error} />
        {rows.length === 0 && !list.isLoading && <Typography variant="body2" color="text.secondary">No transfer has been asked for.</Typography>}
        <Stack spacing={1.5}>
          {rows.map((t) => (
            <Box key={t.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1.5 }}>
              <Stack direction="row" justifyContent="space-between" spacing={1}>
                <Typography fontWeight={600}>{KIND_LABEL[t.kind]}</Typography>
                <Chip size="small" color={TRANSFER_STATE[t.state].colour} label={TRANSFER_STATE[t.state].label} />
              </Stack>
              <Move t={t} />
              <Typography variant="body2" sx={{ mt: 0.5 }}>{t.reason}</Typography>
              <Box sx={{ mt: 0.5 }}><Trail t={t} /></Box>
              {(t.canRecommend || t.canDecide || t.canCancel) && <Box sx={{ mt: 1 }}>{actions.buttons(t)}</Box>}
            </Box>
          ))}
        </Stack>
      </CardContent>
      {open && options.data && <TransferDialog studentId={id} options={options.data} onClose={() => setOpen(false)} onDone={() => { setOpen(false); refresh(); }} />}
      {actions.element}
    </Card>
  );
}

function TransferDialog({ studentId, options, onClose, onDone }: { studentId: number; options: TransferOptions; onClose: () => void; onDone: () => void }) {
  const [programmeId, setProgrammeId] = useState(String(options.programmeId));
  const [campusId, setCampusId] = useState(options.campusId ? String(options.campusId) : '');
  const [year, setYear] = useState('');
  const [reason, setReason] = useState('');
  const p = options.programmes.find((x) => String(x.id) === programmeId);
  const allowedCampuses = options.campuses.filter((c) => !p || p.campusIds.length === 0 || p.campusIds.includes(c.id));
  const changesProgramme = programmeId !== String(options.programmeId);
  const submit = useMutation({
    mutationFn: () => api<Transfer>(`/api/registry/students/${studentId}/transfers`, { method: 'POST', ...staff, body: {
      toProgrammeId: Number(programmeId), toCampusId: campusId ? Number(campusId) : null, toYearOfStudy: year ? Number(year) : null, reason } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Request a transfer</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Now: <strong>{options.programme}</strong>, year {options.yearOfStudy}{options.campus ? `, ${options.campus}` : ''}. A programme transfer goes to the Head of the
          receiving department to recommend; the Deputy Principal (Academic) approves. The registration number never changes.
        </Typography>
        <Stack spacing={2}>
          <TextField select label="Programme" value={programmeId} onChange={(e) => { setProgrammeId(e.target.value); setYear(''); }}>
            {options.programmes.map((x) => <MenuItem key={x.id} value={String(x.id)}>{x.code} · {x.name}</MenuItem>)}
          </TextField>
          {options.campuses.length > 1 && (
            <TextField select label="Campus" value={allowedCampuses.some((c) => String(c.id) === campusId) ? campusId : ''} onChange={(e) => setCampusId(e.target.value)}
              helperText={p && p.campusIds.length ? `${p.code} is taught at ${allowedCampuses.map((c) => c.name).join(', ')}` : undefined}
              slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
              <MenuItem value="">Programme's usual campus</MenuItem>
              {allowedCampuses.map((c) => <MenuItem key={c.id} value={String(c.id)}>{c.name}</MenuItem>)}
            </TextField>
          )}
          <TextField select label="Year of study in the new programme" value={year} onChange={(e) => setYear(e.target.value)}
            slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
            helperText={changesProgramme ? 'Usually year 1; a later year when credits are transferred' : 'Unchanged for a campus transfer'}>
            <MenuItem value="">{changesProgramme ? 'Year 1' : `Year ${options.yearOfStudy}`}</MenuItem>
            {Array.from({ length: p?.years ?? 1 }, (_, i) => i + 1).map((y) => <MenuItem key={y} value={String(y)}>Year {y}</MenuItem>)}
          </TextField>
          <TextField label="Reason" required multiline minRows={2} value={reason} onChange={(e) => setReason(e.target.value)} inputProps={{ maxLength: 1000 }} />
        </Stack>
        <ErrorAlert error={submit.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!reason.trim() || submit.isPending} onClick={() => submit.mutate()}>Submit request</Button>
      </DialogActions>
    </Dialog>
  );
}

/** Transfers across the institution (or the Head's department): what is waiting and recent decisions. */
export function TransfersPage() {
  const qc = useQueryClient();
  const [tab, setTab] = useState<'open' | 'all'>('open');
  const list = useQuery({ queryKey: ['staff', 'transfers', tab], queryFn: () => api<Transfer[]>(`/api/registry/transfers?open=${tab === 'open'}`, staff) });
  const actions = useTransferActions(() => {
    qc.invalidateQueries({ queryKey: ['staff', 'transfers'] });
    qc.invalidateQueries({ queryKey: ['staff', 'approvals'] });
    qc.invalidateQueries({ queryKey: ['staff', 'student'] });
  });
  const rows = list.data ?? [];
  return (
    <>
      <PageTitle title="Transfers" subtitle="Programme and campus transfers: asked by Registry or by the student in the portal, recommended by the Head of the receiving department, approved by the Deputy Principal (Academic)." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
        <Tab value="open" label="In progress" />
        <Tab value="all" label="All recent" />
      </Tabs>
      <ErrorAlert error={list.error} />
      {list.isLoading ? <Loading /> : rows.length === 0 ? (
        <Card><CardContent><Typography color="text.secondary">
          {tab === 'open' ? 'No transfer is waiting. Registry asks for one from the student\'s record; students can ask in the portal.' : 'No transfer has been asked for yet.'}
        </Typography></CardContent></Card>
      ) : (
        <Card><TableContainer><Table size="small">
          <TableHead><TableRow><TableCell>Student</TableCell><TableCell>Transfer</TableCell><TableCell>Reason</TableCell><TableCell>Progress</TableCell><TableCell align="right" /></TableRow></TableHead>
          <TableBody>
            {rows.map((t) => (
              <TableRow key={t.id} hover sx={{ verticalAlign: 'top' }}>
                <TableCell>
                  <Link component={StaffLink} to={`/staff/students/${t.studentId}`} fontWeight={600} underline="hover">{t.studentName}</Link>
                  <Box sx={{ fontFamily: 'monospace', fontSize: 13 }}>{t.registrationNo}</Box>
                </TableCell>
                <TableCell sx={{ minWidth: 260 }}>{KIND_LABEL[t.kind]}<Move t={t} /><Box sx={{ fontSize: 13, color: 'text.secondary' }}>effective {formatDate(t.effectiveDate)}</Box></TableCell>
                <TableCell sx={{ maxWidth: 240 }}>{t.reason}{t.requestedByStudent && <Box><Chip size="small" label="Asked by the student" sx={{ mt: 0.5 }} /></Box>}</TableCell>
                <TableCell sx={{ minWidth: 220 }}><Chip size="small" color={TRANSFER_STATE[t.state].colour} label={TRANSFER_STATE[t.state].label} sx={{ mb: 0.5 }} /><Trail t={t} /></TableCell>
                <TableCell align="right">{actions.buttons(t)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table></TableContainer></Card>
      )}
      {actions.element}
    </>
  );
}
