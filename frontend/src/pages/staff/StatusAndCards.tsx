import BadgeIcon from '@mui/icons-material/Badge';
import CheckIcon from '@mui/icons-material/Check';
import CloseIcon from '@mui/icons-material/Close';
import PhotoCameraIcon from '@mui/icons-material/PhotoCamera';
import PrintIcon from '@mui/icons-material/Print';
import SwapHorizIcon from '@mui/icons-material/SwapHoriz';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Link, MenuItem, Stack, Tab, Table,
  TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { api, download, formatDate, formatDateTime, privateImageUrl, uploadForm } from '../../api/client';
import type { Programme } from '../../api/types';
import { ErrorAlert, Loading, PageTitle, humanise } from '../../components/common';
import { useRoles } from './MarksPages';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };
const REGISTRY = ['INSTITUTION_ADMIN', 'REGISTRAR'];

// ============================================================ types

export type ChangeType = 'POSTPONEMENT' | 'RESUMPTION' | 'SUSPENSION' | 'DISCONTINUATION' | 'WITHDRAWAL' | 'DEATH';
type ChangeState = 'REQUESTED' | 'RECOMMENDED' | 'APPROVED' | 'APPLIED' | 'REJECTED' | 'CANCELLED';

export interface StatusChange {
  id: number; studentId: number; registrationNo: string; studentName: string; programmeCode: string; departmentName?: string;
  type: ChangeType; fromStatus: string; toStatus: string; currentStatus: string; reason: string; reference?: string;
  effectiveDate: string; untilDate?: string; state: ChangeState; requestedByName: string; requestedAt: string;
  recommendedByName?: string; recommendedAt?: string; recommendationNote?: string; decidedByName?: string; decidedAt?: string;
  decisionNote?: string; appliedAt?: string; canRecommend: boolean; canDecide: boolean; canCancel: boolean;
}

interface StatusOptions { currentStatus: string; allowed: ChangeType[]; openRequest: boolean }

interface CardView {
  id: number; cardNo: string; verificationCode: string; validUntil: string; status: 'VALID' | 'REPLACED' | 'REVOKED';
  issuedByName: string; issuedAt: string; endedReason?: string; endedAt?: string;
}

interface IdCardSummary { hasPhoto: boolean; photoUpdatedAt?: string; canIssue: boolean; issueBlocker?: string; current?: CardView; history: CardView[] }

interface BatchPreview { ready: number; withoutPhoto: number; alreadyCarded: number; missingPhoto: string[] }

export const CHANGE_LABEL: Record<ChangeType, string> = {
  POSTPONEMENT: 'Postponement of studies', RESUMPTION: 'Resumption of studies', SUSPENSION: 'Suspension',
  DISCONTINUATION: 'Discontinuation', WITHDRAWAL: 'Withdrawal', DEATH: 'Death',
};

const CHANGE_HELP: Record<ChangeType, string> = {
  POSTPONEMENT: 'The student pauses their studies (illness, family or financial reasons) and is expected back on the end date.',
  RESUMPTION: 'A postponed or suspended student returns to their studies.',
  SUSPENSION: 'The student is barred from studies for a period, usually after a disciplinary decision.',
  DISCONTINUATION: 'The institution ends the student\'s studies (academic or disciplinary grounds). Their ID card is revoked.',
  WITHDRAWAL: 'The student leaves the programme of their own choice. Their ID card is revoked.',
  DEATH: 'Record the death of a student. Their ID card is revoked; no SMS is sent if the phone is not on record.',
};

const STATE_LABEL: Record<ChangeState, string> = {
  REQUESTED: 'Waiting for the Head of Department', RECOMMENDED: 'Waiting for the Deputy Principal', APPROVED: 'Approved — applies on the date',
  APPLIED: 'Applied', REJECTED: 'Rejected', CANCELLED: 'Cancelled',
};

const STATE_COLOUR: Record<ChangeState, 'default' | 'warning' | 'info' | 'success' | 'error'> = {
  REQUESTED: 'warning', RECOMMENDED: 'info', APPROVED: 'info', APPLIED: 'success', REJECTED: 'error', CANCELLED: 'default',
};

const today = () => new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);

// ============================================================ the actions on a request (recommend, decide, cancel)

function useChangeActions(onDone: (c: StatusChange) => void) {
  const [dialog, setDialog] = useState<{ change: StatusChange; action: 'recommend' | 'approve' | 'reject' | 'cancel' } | null>(null);
  const [note, setNote] = useState('');
  const act = useMutation({
    mutationFn: () => {
      const { change, action } = dialog!;
      if (action === 'recommend') return api<StatusChange>(`/api/registry/status-changes/${change.id}/recommend`, { method: 'POST', ...staff, body: { note } });
      if (action === 'cancel') return api<StatusChange>(`/api/registry/status-changes/${change.id}/cancel`, { method: 'POST', ...staff, body: { note } });
      return api<StatusChange>(`/api/registry/status-changes/${change.id}/decision`, { method: 'POST', ...staff, body: { approve: action === 'approve', note } });
    },
    onSuccess: (c) => { setDialog(null); setNote(''); onDone(c); },
  });
  const open = (change: StatusChange, action: 'recommend' | 'approve' | 'reject' | 'cancel') => { act.reset(); setNote(''); setDialog({ change, action }); };
  const buttons = (c: StatusChange) => (
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
      {c.canRecommend && <Button size="small" variant="contained" startIcon={<CheckIcon />} onClick={() => open(c, 'recommend')}>Recommend</Button>}
      {c.canDecide && <Button size="small" variant="contained" color="success" startIcon={<CheckIcon />} onClick={() => open(c, 'approve')}>Approve</Button>}
      {c.canDecide && <Button size="small" variant="outlined" color="error" startIcon={<CloseIcon />} onClick={() => open(c, 'reject')}>Reject</Button>}
      {c.canCancel && <Button size="small" onClick={() => open(c, 'cancel')}>Cancel request</Button>}
    </Stack>
  );
  const title = { recommend: 'Recommend this change', approve: 'Approve this change', reject: 'Reject this change', cancel: 'Cancel this request' };
  const element = dialog && (
    <Dialog open onClose={() => setDialog(null)} maxWidth="sm" fullWidth>
      <DialogTitle>{title[dialog.action]}</DialogTitle>
      <DialogContent>
        <Typography><strong>{CHANGE_LABEL[dialog.change.type]}</strong> for {dialog.change.studentName} ({dialog.change.registrationNo}),
          effective {formatDate(dialog.change.effectiveDate)}{dialog.change.untilDate ? ` until ${formatDate(dialog.change.untilDate)}` : ''}.</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Reason: {dialog.change.reason}{dialog.change.reference ? ` (ref. ${dialog.change.reference})` : ''}</Typography>
        {dialog.action === 'approve' && (
          <Alert severity="info" sx={{ mt: 2 }}>
            {dialog.change.effectiveDate <= today()
              ? <>The student's status becomes <strong>{humanise(dialog.change.toStatus)}</strong> now and they are told by SMS.</>
              : <>It is applied on {formatDate(dialog.change.effectiveDate)}: the student's status becomes <strong>{humanise(dialog.change.toStatus)}</strong> and they are told by SMS.</>}
            {dialog.change.state === 'REQUESTED' && <> The Head of Department has not recommended it yet.</>}
          </Alert>
        )}
        <TextField label={dialog.action === 'reject' ? 'Reason for rejecting' : 'Note (optional)'} required={dialog.action === 'reject'}
          fullWidth multiline minRows={2} sx={{ mt: 2 }} value={note} onChange={(e) => setNote(e.target.value)} inputProps={{ maxLength: 500 }} />
        <ErrorAlert error={act.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={() => setDialog(null)}>Close</Button>
        <Button variant="contained" color={dialog.action === 'reject' ? 'error' : 'primary'}
          disabled={act.isPending || (dialog.action === 'reject' && !note.trim())} onClick={() => act.mutate()}>
          {title[dialog.action].split(' ')[0]}
        </Button>
      </DialogActions>
    </Dialog>
  );
  return { buttons, element };
}

function Trail({ c }: { c: StatusChange }) {
  return (
    <Box sx={{ fontSize: 13, color: 'text.secondary', lineHeight: 1.6 }}>
      <div>Requested by {c.requestedByName} on {formatDateTime(c.requestedAt)}</div>
      {c.recommendedByName && <div>Recommended by {c.recommendedByName} on {formatDateTime(c.recommendedAt)}{c.recommendationNote ? ` — “${c.recommendationNote}”` : ''}</div>}
      {c.decidedByName && <div>{c.state === 'CANCELLED' ? 'Cancelled' : c.state === 'REJECTED' ? 'Rejected' : 'Approved'} by {c.decidedByName} on {formatDateTime(c.decidedAt)}{c.decisionNote ? ` — “${c.decisionNote}”` : ''}</div>}
      {c.appliedAt && <div>Applied {formatDateTime(c.appliedAt)}</div>}
    </Box>
  );
}

// ============================================================ on the student record

/** Status changes on the student record: the history, and (for Registry) a request for a new change. */
export function StudentStatusCard({ studentId }: { studentId: number }) {
  const qc = useQueryClient();
  const isRegistry = useRoles()(REGISTRY);
  const list = useQuery({ queryKey: ['staff', 'student', String(studentId), 'status-changes'], queryFn: () => api<StatusChange[]>(`/api/registry/students/${studentId}/status-changes`, staff) });
  const options = useQuery({
    queryKey: ['staff', 'student', String(studentId), 'status-options'], enabled: isRegistry,
    queryFn: () => api<StatusOptions>(`/api/registry/students/${studentId}/status-options`, staff),
  });
  const [open, setOpen] = useState(false);
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['staff', 'student', String(studentId)] });
    qc.invalidateQueries({ queryKey: ['staff', 'students'] });
    qc.invalidateQueries({ queryKey: ['staff', 'status-changes'] });
    qc.invalidateQueries({ queryKey: ['staff', 'approvals'] });
  };
  const actions = useChangeActions(refresh);
  const rows = list.data ?? [];
  const o = options.data;

  return (
    <Card sx={{ mb: 2.5 }}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6">Status changes</Typography>
          {isRegistry && o && o.allowed.length > 0 && (
            <Button size="small" variant="outlined" startIcon={<SwapHorizIcon />} disabled={o.openRequest} onClick={() => setOpen(true)}>Request a change</Button>
          )}
        </Stack>
        <ErrorAlert error={list.error} />
        {isRegistry && o?.openRequest && <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>A request is in progress; it must be decided or cancelled before another.</Typography>}
        {rows.length === 0 && !list.isLoading && (
          <Typography variant="body2" color="text.secondary">No postponement, suspension or other change has been requested for this student.</Typography>
        )}
        <Stack spacing={1.5}>
          {rows.map((c) => (
            <Box key={c.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 1, p: 1.5 }}>
              <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
                <Box>
                  <Typography fontWeight={600}>{CHANGE_LABEL[c.type]}</Typography>
                  <Typography variant="body2">
                    {humanise(c.fromStatus)} → {humanise(c.toStatus)} · from {formatDate(c.effectiveDate)}{c.untilDate ? ` until ${formatDate(c.untilDate)}` : ''}
                  </Typography>
                </Box>
                <Chip size="small" color={STATE_COLOUR[c.state]} label={STATE_LABEL[c.state]} />
              </Stack>
              <Typography variant="body2" sx={{ mt: 0.5 }}>{c.reason}{c.reference ? ` · ref. ${c.reference}` : ''}</Typography>
              <Box sx={{ mt: 0.5 }}><Trail c={c} /></Box>
              {(c.canRecommend || c.canDecide || c.canCancel) && <Box sx={{ mt: 1 }}>{actions.buttons(c)}</Box>}
            </Box>
          ))}
        </Stack>
      </CardContent>
      {open && o && <RequestDialog studentId={studentId} options={o} onClose={() => setOpen(false)} onDone={() => { setOpen(false); refresh(); }} />}
      {actions.element}
    </Card>
  );
}

function RequestDialog({ studentId, options, onClose, onDone }: { studentId: number; options: StatusOptions; onClose: () => void; onDone: () => void }) {
  const [type, setType] = useState<ChangeType>(options.allowed[0]);
  const [reason, setReason] = useState('');
  const [reference, setReference] = useState('');
  const [effectiveDate, setEffectiveDate] = useState(today());
  const [untilDate, setUntilDate] = useState('');
  const takesUntil = type === 'POSTPONEMENT' || type === 'SUSPENSION';
  const submit = useMutation({
    mutationFn: () => api<StatusChange>(`/api/registry/students/${studentId}/status-changes`, {
      method: 'POST', ...staff,
      body: { type, reason, reference: reference || null, effectiveDate, untilDate: takesUntil && untilDate ? untilDate : null },
    }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Request a status change</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          The student is now <strong>{humanise(options.currentStatus)}</strong>. The request goes to the Head of Department to recommend and the
          Deputy Principal (Academic) to approve; it takes effect on the effective date.
        </Typography>
        <Stack spacing={2}>
          <TextField select label="Change" value={type} onChange={(e) => setType(e.target.value as ChangeType)} helperText={CHANGE_HELP[type]}>
            {options.allowed.map((t) => <MenuItem key={t} value={t}>{CHANGE_LABEL[t]}</MenuItem>)}
          </TextField>
          <TextField label="Reason" required multiline minRows={2} value={reason} onChange={(e) => setReason(e.target.value)} inputProps={{ maxLength: 1000 }} />
          <TextField label="Reference (letter, minute or case number)" value={reference} onChange={(e) => setReference(e.target.value)} inputProps={{ maxLength: 120 }} />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField type="date" label="Effective date" required value={effectiveDate} onChange={(e) => setEffectiveDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} fullWidth />
            {takesUntil && (
              <TextField type="date" label={type === 'POSTPONEMENT' ? 'Expected back on' : 'Until (optional)'} required={type === 'POSTPONEMENT'}
                value={untilDate} onChange={(e) => setUntilDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} fullWidth />
            )}
          </Stack>
        </Stack>
        <ErrorAlert error={submit.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={submit.isPending || !reason.trim() || !effectiveDate || (type === 'POSTPONEMENT' && !untilDate)} onClick={() => submit.mutate()}>
          Submit request
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ============================================================ the Status changes page

/** Status change requests across the institution (or the Head's department): what is waiting, and the latest decisions. */
export function StatusChangesPage() {
  const qc = useQueryClient();
  const [tab, setTab] = useState<'open' | 'all'>('open');
  const list = useQuery({ queryKey: ['staff', 'status-changes', tab], queryFn: () => api<StatusChange[]>(`/api/registry/status-changes?open=${tab === 'open'}`, staff) });
  const actions = useChangeActions(() => {
    qc.invalidateQueries({ queryKey: ['staff', 'status-changes'] });
    qc.invalidateQueries({ queryKey: ['staff', 'approvals'] });
    qc.invalidateQueries({ queryKey: ['staff', 'student'] });
  });
  const rows = list.data ?? [];
  return (
    <>
      <PageTitle title="Status changes" subtitle="Postponement, resumption, suspension, discontinuation, withdrawal and death: requested by Registry, recommended by the Head of Department, approved by the Deputy Principal (Academic)." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
        <Tab value="open" label="In progress" />
        <Tab value="all" label="All recent" />
      </Tabs>
      <ErrorAlert error={list.error} />
      {list.isLoading ? <Loading /> : rows.length === 0 ? (
        <Card><CardContent><Typography color="text.secondary">
          {tab === 'open' ? 'No status change is waiting. Registry requests one from the student\'s record.' : 'No status change has been requested yet.'}
        </Typography></CardContent></Card>
      ) : (
        <Card>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Student</TableCell><TableCell>Change</TableCell><TableCell>Effective</TableCell><TableCell>Reason</TableCell>
                  <TableCell>Progress</TableCell><TableCell align="right" />
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((c) => (
                  <TableRow key={c.id} hover sx={{ verticalAlign: 'top' }}>
                    <TableCell>
                      <Link component={StaffLink} to={`/staff/students/${c.studentId}`} fontWeight={600} underline="hover">{c.studentName}</Link>
                      <Box sx={{ fontFamily: 'monospace', fontSize: 13 }}>{c.registrationNo}</Box>
                      <Box sx={{ fontSize: 13, color: 'text.secondary' }}>{c.programmeCode}{c.departmentName ? ` · ${c.departmentName}` : ''}</Box>
                    </TableCell>
                    <TableCell>
                      {CHANGE_LABEL[c.type]}
                      <Box sx={{ fontSize: 13, color: 'text.secondary' }}>{humanise(c.fromStatus)} → {humanise(c.toStatus)}</Box>
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDate(c.effectiveDate)}{c.untilDate && <Box sx={{ fontSize: 13, color: 'text.secondary' }}>until {formatDate(c.untilDate)}</Box>}</TableCell>
                    <TableCell sx={{ maxWidth: 260 }}>{c.reason}{c.reference && <Box sx={{ fontSize: 13, color: 'text.secondary' }}>ref. {c.reference}</Box>}</TableCell>
                    <TableCell sx={{ minWidth: 220 }}><Chip size="small" color={STATE_COLOUR[c.state]} label={STATE_LABEL[c.state]} sx={{ mb: 0.5 }} /><Trail c={c} /></TableCell>
                    <TableCell align="right">{actions.buttons(c)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </Card>
      )}
      {actions.element}
    </>
  );
}

// ============================================================ photo and ID card on the student record

/** The student's photo and ID card: upload a photo, issue, print, replace or revoke the card (FR-REG-06). */
export function StudentIdCard({ studentId, regNo }: { studentId: number; regNo: string }) {
  const qc = useQueryClient();
  const isRegistry = useRoles()(REGISTRY);
  const key = ['staff', 'student', String(studentId), 'id-card'];
  const q = useQuery({ queryKey: key, queryFn: () => api<IdCardSummary>(`/api/registry/students/${studentId}/id-card`, staff) });
  const [photoUrl, setPhotoUrl] = useState<string | null>(null);
  const file = useRef<HTMLInputElement>(null);
  const [dialog, setDialog] = useState<'reissue' | 'revoke' | null>(null);
  const [reason, setReason] = useState('');
  const d = q.data;

  useEffect(() => {
    let url: string | null = null;
    if (d?.hasPhoto) {
      privateImageUrl(`/api/registry/students/${studentId}/photo?v=${d.photoUpdatedAt ?? ''}`, 'staff').then((u) => { url = u; setPhotoUrl(u); }).catch(() => setPhotoUrl(null));
    } else setPhotoUrl(null);
    return () => { if (url) URL.revokeObjectURL(url); };
  }, [studentId, d?.hasPhoto, d?.photoUpdatedAt]);

  const done = (s: IdCardSummary) => { qc.setQueryData(key, s); setDialog(null); setReason(''); };
  const upload = useMutation({
    mutationFn: (f: File) => { const form = new FormData(); form.append('file', f); return uploadForm<IdCardSummary>(`/api/registry/students/${studentId}/photo`, 'staff', form); },
    onSuccess: done,
  });
  const issue = useMutation({
    mutationFn: (why?: string) => api<IdCardSummary>(`/api/registry/students/${studentId}/id-card`, { method: 'POST', ...staff, body: { reason: why ?? null } }),
    onSuccess: done,
  });
  const revoke = useMutation({
    mutationFn: () => api<IdCardSummary>(`/api/registry/students/${studentId}/id-cards/${d!.current!.id}/revoke`, { method: 'POST', ...staff, body: { reason } }),
    onSuccess: done,
  });
  const print = useMutation({ mutationFn: () => download(`/api/registry/students/${studentId}/id-card.pdf`, 'staff', `id-card-${regNo.replace(/[^A-Za-z0-9]+/g, '-')}.pdf`) });
  const c = d?.current;

  return (
    <Card sx={{ mb: 2.5 }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 1.5 }}>Photo and ID card</Typography>
        <ErrorAlert error={q.error ?? upload.error ?? issue.error ?? print.error} />
        {d && (
          <Stack direction="row" spacing={2} alignItems="flex-start">
            <Box sx={{ width: 96, height: 120, flexShrink: 0, border: 1, borderColor: 'divider', borderRadius: 1, overflow: 'hidden', bgcolor: 'action.hover',
              display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              {photoUrl ? <img src={photoUrl} alt="Student" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                : <Typography variant="caption" color="text.secondary" sx={{ textAlign: 'center', px: 1 }}>No photo</Typography>}
            </Box>
            <Box sx={{ flex: 1, minWidth: 0 }}>
              {c ? (
                <>
                  <Stack direction="row" spacing={1} alignItems="center"><Chip size="small" color="success" label="Valid card" />
                    <Typography sx={{ fontFamily: 'monospace', fontWeight: 600 }}>{c.cardNo}</Typography></Stack>
                  <Typography variant="body2" sx={{ mt: 0.5 }}>Valid until {formatDate(c.validUntil)}</Typography>
                  <Typography variant="body2" color="text.secondary">Issued {formatDateTime(c.issuedAt)} by {c.issuedByName}</Typography>
                  <Typography variant="body2" color="text.secondary">Verification code <span style={{ fontFamily: 'monospace' }}>{c.verificationCode}</span></Typography>
                </>
              ) : (
                <Typography variant="body2" color="text.secondary">{d.issueBlocker ?? 'No card has been issued yet.'}</Typography>
              )}
              {isRegistry && (
                <Stack direction="row" spacing={1} sx={{ mt: 1.5 }} flexWrap="wrap" useFlexGap>
                  <input ref={file} type="file" accept="image/jpeg,image/png" hidden
                    onChange={(e) => { const f = e.target.files?.[0]; if (f) upload.mutate(f); e.target.value = ''; }} />
                  <Button size="small" variant="outlined" startIcon={<PhotoCameraIcon />} disabled={upload.isPending} onClick={() => file.current?.click()}>
                    {d.hasPhoto ? 'Replace photo' : 'Upload photo'}
                  </Button>
                  {!c && <Button size="small" variant="contained" startIcon={<BadgeIcon />} disabled={!d.canIssue || issue.isPending} onClick={() => issue.mutate(undefined)}>Issue card</Button>}
                  {c && <Button size="small" variant="contained" startIcon={<PrintIcon />} disabled={print.isPending} onClick={() => print.mutate()}>Print card</Button>}
                  {c && <Button size="small" disabled={!d.canIssue} onClick={() => { issue.reset(); setDialog('reissue'); }}>Replace</Button>}
                  {c && <Button size="small" color="error" onClick={() => { revoke.reset(); setDialog('revoke'); }}>Revoke</Button>}
                </Stack>
              )}
              {isRegistry && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>Passport-style photo, JPEG or PNG, upright, at least 200 pixels wide, up to 2 MB.</Typography>}
            </Box>
          </Stack>
        )}
        {d && d.history.filter((h) => h.status !== 'VALID').length > 0 && (
          <Box sx={{ mt: 2 }}>
            <Typography variant="subtitle2" sx={{ mb: 0.5 }}>Earlier cards</Typography>
            {d.history.filter((h) => h.status !== 'VALID').map((h) => (
              <Typography key={h.id} variant="body2" color="text.secondary">
                <span style={{ fontFamily: 'monospace' }}>{h.cardNo}</span> · {h.status === 'REPLACED' ? 'Replaced' : 'Revoked'} {formatDate(h.endedAt)}{h.endedReason ? ` — ${h.endedReason}` : ''}
              </Typography>
            ))}
          </Box>
        )}
      </CardContent>
      <Dialog open={dialog !== null} onClose={() => setDialog(null)} maxWidth="xs" fullWidth>
        <DialogTitle>{dialog === 'reissue' ? 'Replace the ID card' : 'Revoke the ID card'}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {dialog === 'reissue'
              ? 'A new card with a new number and QR code is issued; the old card shows as superseded when scanned.'
              : 'The card shows as revoked when scanned. A new card can be issued later.'}
          </Typography>
          <TextField label="Reason" required fullWidth value={reason} onChange={(e) => setReason(e.target.value)} inputProps={{ maxLength: 300 }}
            placeholder={dialog === 'reissue' ? 'Lost, damaged, name corrected…' : 'Confiscated, misuse…'} />
          <ErrorAlert error={dialog === 'reissue' ? issue.error : revoke.error} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDialog(null)}>Cancel</Button>
          <Button variant="contained" color={dialog === 'revoke' ? 'error' : 'primary'} disabled={!reason.trim() || issue.isPending || revoke.isPending}
            onClick={() => (dialog === 'reissue' ? issue.mutate(reason) : revoke.mutate())}>
            {dialog === 'reissue' ? 'Issue replacement' : 'Revoke'}
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}

// ============================================================ batch printing

/** Issues and prints ID cards for a whole class: active students with a photo and no valid card. */
export function IdCardBatchDialog({ open, onClose, programmes }: { open: boolean; onClose: () => void; programmes: Programme[] }) {
  const qc = useQueryClient();
  const [programmeId, setProgrammeId] = useState<number | ''>('');
  const [year, setYear] = useState<number | ''>('');
  useEffect(() => { if (open && programmeId === '' && programmes.length) setProgrammeId(programmes[0].id); }, [open, programmes, programmeId]);
  const params = `programmeId=${programmeId}${year ? `&yearOfStudy=${year}` : ''}`;
  const preview = useQuery({
    queryKey: ['staff', 'id-card-batch', params], enabled: open && programmeId !== '',
    queryFn: () => api<BatchPreview>(`/api/registry/id-cards/batch?${params}`, staff),
  });
  const p = preview.data;
  const code = programmes.find((x) => x.id === programmeId)?.code ?? 'class';
  const issue = useMutation({
    mutationFn: () => download('/api/registry/id-cards/batch', 'staff', `id-cards-${code}${year ? `-year-${year}` : ''}.pdf`, 'POST',
      { programmeId, yearOfStudy: year || null }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['staff', 'id-card-batch'] }); qc.invalidateQueries({ queryKey: ['staff', 'student'] }); },
  });
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Print ID cards for a class</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Cards are issued to registered (active) students who have a photo and no valid card, and downloaded as one PDF at card size (85.6 × 54 mm,
          front and back) for the card printer.
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <TextField select label="Programme" value={programmeId} onChange={(e) => setProgrammeId(Number(e.target.value))} fullWidth>
            {programmes.map((x) => <MenuItem key={x.id} value={x.id}>{x.code} · {x.name}</MenuItem>)}
          </TextField>
          <TextField select label="Year of study" value={year} onChange={(e) => setYear(e.target.value === '' ? '' : Number(e.target.value))} sx={{ minWidth: 150 }}
            slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
            <MenuItem value="">All years</MenuItem>
            {[1, 2, 3].map((y) => <MenuItem key={y} value={y}>Year {y}</MenuItem>)}
          </TextField>
        </Stack>
        <ErrorAlert error={preview.error ?? issue.error} />
        {p && (
          <Box sx={{ mt: 2 }}>
            <Typography><strong>{p.ready}</strong> {p.ready === 1 ? 'card' : 'cards'} ready to issue · {p.alreadyCarded} already have a valid card</Typography>
            {p.withoutPhoto > 0 && (
              <Alert severity="warning" sx={{ mt: 1.5 }}>
                {p.withoutPhoto} {p.withoutPhoto === 1 ? 'student needs' : 'students need'} a photo first:
                <Box component="ul" sx={{ m: 0, pl: 2.5 }}>{p.missingPhoto.slice(0, 8).map((m) => <li key={m}>{m}</li>)}</Box>
                {p.missingPhoto.length > 8 && <>and {p.withoutPhoto - 8} more</>}
              </Alert>
            )}
            {issue.isSuccess && <Alert severity="success" sx={{ mt: 1.5 }}>Cards issued and downloaded.</Alert>}
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" startIcon={<PrintIcon />} disabled={!p || p.ready === 0 || issue.isPending} onClick={() => issue.mutate()}>
          Issue and download {p && p.ready > 0 ? p.ready : ''}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
