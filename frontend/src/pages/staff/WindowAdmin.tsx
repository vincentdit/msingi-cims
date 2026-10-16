import EditIcon from '@mui/icons-material/EditOutlined';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Stack,
  TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, formatDateTime } from '../../api/client';
import type { AcademicYear, AdmissionWindow, WindowEvent, WindowState, WindowStats } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert } from '../../components/common';

const staff = { realm: 'staff' as const };
/** Who may publish, open, close and reopen admission windows. */
export const OPEN_CLOSE_ROLES = ['INSTITUTION_ADMIN', 'ADMISSIONS_OFFICER', 'DEPUTY_PRINCIPAL_ACADEMIC'];
/** Who may create and edit admission windows. */
export const EDIT_ROLES = ['INSTITUTION_ADMIN', 'ADMISSIONS_OFFICER'];

export function useWindowRoles() {
  const { user } = useSession();
  const roles = user('staff')?.roles ?? [];
  return { canOpenClose: roles.some((r) => OPEN_CLOSE_ROLES.includes(r)), canEdit: roles.some((r) => EDIT_ROLES.includes(r)) };
}

const STATE = {
  DRAFT: { label: 'Draft', color: 'default' },
  SCHEDULED: { label: 'Opens later', color: 'info' },
  OPEN: { label: 'Open', color: 'success' },
  CLOSED: { label: 'Closed', color: 'error' },
} as const;

export function WindowStateChip({ state, size }: { state: WindowState; size?: 'small' | 'medium' }) {
  const s = STATE[state] ?? STATE.DRAFT;
  return <Chip size={size} color={s.color} label={s.label} variant={state === 'DRAFT' ? 'outlined' : 'filled'} />;
}

/** ISO instant → value for a datetime-local input, in the browser's time zone. */
const toLocal = (iso?: string) => {
  if (!iso) return '';
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
};
const fromLocal = (v: string) => new Date(v).toISOString();
const inDays = (n: number, hour = 23, minute = 59) => { const d = new Date(); d.setDate(d.getDate() + n); d.setHours(hour, minute, 0, 0); return d.toISOString(); };

// ================================================================== create / edit

export function WindowFormDialog({ win, onClose, onSaved }: { win: AdmissionWindow | null; onClose: () => void; onSaved: (w: AdmissionWindow) => void }) {
  const years = useQuery({ queryKey: ['staff', 'years'], queryFn: () => api<AcademicYear[]>('/api/academic/years', staff) });
  const intakes = useQuery({ queryKey: ['staff', 'intakes'], queryFn: () => api<{ id: number; name: string }[]>('/api/academic/intakes', staff) });
  const open = win?.state === 'OPEN';
  const [f, setF] = useState({
    name: win?.name ?? '', academicYearId: win?.academicYearId ?? 0, intakeId: win?.intakeId ?? 0,
    opensAt: toLocal(win?.opensAt ?? inDays(1, 8, 0)), closesAt: toLocal(win?.closesAt ?? inDays(45)),
    applicationFee: String(win?.applicationFee ?? 10000), maxChoices: String(win?.maxChoices ?? 3),
    offerAcceptanceDays: String(win?.offerAcceptanceDays ?? 14), instructions: win?.instructions ?? '',
  });
  const [feeReminders, setFeeReminders] = useState(win?.feeReminders ?? true);
  const year = f.academicYearId || years.data?.find((y) => y.current)?.id || years.data?.[0]?.id || 0;
  const intake = f.intakeId || intakes.data?.[0]?.id || 0;
  const valid = f.name.trim() && year && intake && f.opensAt && f.closesAt && new Date(f.closesAt) > new Date(f.opensAt);
  const save = useMutation({
    mutationFn: () => api<AdmissionWindow>(win ? `/api/admission/windows/${win.id}` : '/api/admission/windows', { ...staff, method: win ? 'PUT' : 'POST', body: {
      name: f.name.trim(), academicYearId: year, intakeId: intake, opensAt: open ? win!.opensAt : fromLocal(f.opensAt), closesAt: fromLocal(f.closesAt),
      applicationFee: Number(f.applicationFee), maxChoices: Number(f.maxChoices), offerAcceptanceDays: Number(f.offerAcceptanceDays),
      instructions: f.instructions.trim() || null, feeReminders } }),
    onSuccess: onSaved,
  });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{win ? `Edit ${win.name}` : 'New admission window'}</DialogTitle>
      <DialogContent>
        <Grid container spacing={2} sx={{ mt: 0.5 }}>
          <Grid size={12}><TextField fullWidth label="Name" value={f.name} onChange={set('name')} placeholder="e.g. 2027/2028 September Intake" /></Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <TextField fullWidth select label="Academic year" value={year || ''} onChange={(e) => setF({ ...f, academicYearId: Number(e.target.value) })}>
              {(years.data ?? []).map((y) => <MenuItem key={y.id} value={y.id}>{y.code}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <TextField fullWidth select label="Intake" value={intake || ''} onChange={(e) => setF({ ...f, intakeId: Number(e.target.value) })}>
              {(intakes.data ?? []).map((i) => <MenuItem key={i.id} value={i.id}>{i.name}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <TextField fullWidth type="datetime-local" label="Opens" value={f.opensAt} onChange={set('opensAt')} disabled={open}
              helperText={open ? 'Already open' : 'Applicants can apply from this date and time'} slotProps={{ inputLabel: { shrink: true } }} />
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <TextField fullWidth type="datetime-local" label="Closes" value={f.closesAt} onChange={set('closesAt')}
              error={!!f.closesAt && !!f.opensAt && new Date(f.closesAt) <= new Date(f.opensAt)} helperText="Closes by itself at this time" slotProps={{ inputLabel: { shrink: true } }} />
          </Grid>
          <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth type="number" label="Application fee (TZS)" value={f.applicationFee} onChange={set('applicationFee')} /></Grid>
          <Grid size={{ xs: 6, sm: 4 }}><TextField fullWidth type="number" label="Programme choices" value={f.maxChoices} onChange={set('maxChoices')} slotProps={{ htmlInput: { min: 1, max: 10 } }} /></Grid>
          <Grid size={{ xs: 6, sm: 4 }}><TextField fullWidth type="number" label="Days to accept an offer" value={f.offerAcceptanceDays} onChange={set('offerAcceptanceDays')} /></Grid>
          <Grid size={12}><TextField fullWidth multiline minRows={2} label="Instructions shown to applicants (optional)" value={f.instructions} onChange={set('instructions')} /></Grid>
          <Grid size={12}>
            <FormControlLabel control={<Checkbox checked={feeReminders} onChange={(e) => setFeeReminders(e.target.checked)} />}
              label="Remind applicants by SMS who got a control number but have not paid (after two days, and again three days before closing)" />
          </Grid>
        </Grid>
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!valid || save.isPending} onClick={() => save.mutate()}>{win ? 'Save' : 'Create draft'}</Button></DialogActions>
    </Dialog>
  );
}

// ================================================================== opening and closing

type Action = 'publish' | 'open' | 'close' | 'reopen';

/** The window's state for staff, with the actions the user may take: publish, open now, close, reopen, edit. */
export function WindowLifecycle({ win, onEdit }: { win: AdmissionWindow; onEdit: () => void }) {
  const { canOpenClose, canEdit } = useWindowRoles();
  const [action, setAction] = useState<Action | null>(null);
  const noProgrammes = !win.programmes?.length;
  const text = {
    DRAFT: 'Draft: applicants cannot see this window. Publish it to open on its opening date, or open it now.',
    SCHEDULED: `Published: applicants see that applications open on ${formatDateTime(win.opensAt)} and can apply from then until ${formatDateTime(win.closesAt)}.`,
    OPEN: `Open: applicants can apply until ${formatDateTime(win.closesAt)}, when it closes by itself.`,
    CLOSED: `Closed on ${formatDateTime(win.closesAt)}: no new applications and no submissions.`,
  }[win.state];
  return (
    <>
      <Alert severity={win.state === 'OPEN' ? 'success' : win.state === 'CLOSED' ? 'warning' : 'info'} icon={false} sx={{ mb: 2, alignItems: 'center' }}
        action={
          <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" justifyContent="flex-end">
            {canEdit && win.state !== 'CLOSED' && <Button size="small" startIcon={<EditIcon />} onClick={onEdit}>{win.state === 'OPEN' ? 'Edit or extend' : 'Edit'}</Button>}
            {canOpenClose && win.state === 'DRAFT' && <Button size="small" variant="outlined" disabled={noProgrammes} onClick={() => setAction('publish')}>Publish</Button>}
            {canOpenClose && (win.state === 'DRAFT' || win.state === 'SCHEDULED') && <Button size="small" variant="contained" color="success" disabled={noProgrammes} onClick={() => setAction('open')}>Open now</Button>}
            {canOpenClose && (win.state === 'OPEN' || win.state === 'SCHEDULED') && <Button size="small" variant="contained" color="error" onClick={() => setAction('close')}>{win.state === 'OPEN' ? 'Close now' : 'Cancel window'}</Button>}
            {canOpenClose && win.state === 'CLOSED' && <Button size="small" variant="contained" onClick={() => setAction('reopen')}>Reopen</Button>}
          </Stack>
        }>
        <Stack direction="row" spacing={1.5} alignItems="center"><WindowStateChip state={win.state} size="small" /><span>{text}</span></Stack>
        {noProgrammes && win.state !== 'CLOSED' && canOpenClose && <Typography variant="caption" sx={{ display: 'block', mt: 0.5 }}>Add at least one programme before publishing or opening.</Typography>}
      </Alert>
      {action && <ActionDialog win={win} action={action} onClose={() => setAction(null)} />}
    </>
  );
}

function ActionDialog({ win, action, onClose }: { win: AdmissionWindow; action: Action; onClose: () => void }) {
  const qc = useQueryClient();
  const stats = useQuery({ queryKey: ['staff', 'window-stats', win.id], enabled: action === 'close' || action === 'reopen',
    queryFn: () => api<WindowStats>(`/api/admission/windows/${win.id}/stats`, staff) });
  const pending = (stats.data?.draft ?? 0) + (stats.data?.paymentPending ?? 0);
  const [reason, setReason] = useState('');
  const [notify, setNotify] = useState(true);
  const future = new Date(win.closesAt) > new Date();
  const [closesAt, setClosesAt] = useState(toLocal(future && action === 'open' ? win.closesAt : inDays(14)));
  const [changeClose, setChangeClose] = useState(!future);
  const m = useMutation({
    mutationFn: () => {
      const url = `/api/admission/windows/${win.id}/${action}`;
      if (action === 'publish') return api<AdmissionWindow>(url, { ...staff, method: 'POST' });
      if (action === 'open') return api<AdmissionWindow>(url, { ...staff, method: 'POST', body: { closesAt: changeClose ? fromLocal(closesAt) : null } });
      if (action === 'close') return api<AdmissionWindow>(url, { ...staff, method: 'POST', body: { reason: reason.trim(), notifyApplicants: notify } });
      return api<AdmissionWindow>(url, { ...staff, method: 'POST', body: { closesAt: fromLocal(closesAt), reason: reason.trim(), notifyApplicants: notify } });
    },
    onSuccess: (w) => { qc.setQueryData(['staff', 'window', String(win.id)], (old: AdmissionWindow | undefined) => ({ ...w, programmes: w.programmes ?? old?.programmes })); qc.invalidateQueries({ queryKey: ['staff'] }); qc.invalidateQueries({ queryKey: ['public-windows'] }); onClose(); },
  });
  const futureClose = !!closesAt && new Date(closesAt) > new Date();
  const titles = { publish: 'Publish window', open: 'Open the window now', close: win.state === 'OPEN' ? 'Close the window now' : 'Cancel the window', reopen: 'Reopen the window' };
  const ok = action === 'publish' || (action === 'open' && (!changeClose || futureClose)) || (action === 'close' && reason.trim()) || (action === 'reopen' && reason.trim() && futureClose);
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{titles[action]} · {win.name}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 0.5 }}>
          {action === 'publish' && (
            <Typography>Applicants will see this window now. They can apply from <b>{formatDateTime(win.opensAt)}</b> until <b>{formatDateTime(win.closesAt)}</b>.</Typography>
          )}
          {action === 'open' && (
            <>
              <Typography>Applicants can apply from now{changeClose ? '' : <> until <b>{formatDateTime(win.closesAt)}</b></>}.</Typography>
              {future && <FormControlLabel control={<Checkbox checked={changeClose} onChange={(e) => setChangeClose(e.target.checked)} />} label="Set a different closing date" />}
              {changeClose && <TextField type="datetime-local" label="Closes" value={closesAt} onChange={(e) => setClosesAt(e.target.value)} error={!futureClose} slotProps={{ inputLabel: { shrink: true } }} />}
            </>
          )}
          {action === 'close' && (
            <>
              <Typography>
                {win.state === 'OPEN'
                  ? <>Applications stop now instead of on <b>{formatDateTime(win.closesAt)}</b>. Submitted applications are kept and go to selection.</>
                  : 'The window will not open. Applicants no longer see it.'}
              </Typography>
              {win.state === 'OPEN' && stats.data && (
                <Alert severity={pending ? 'warning' : 'info'}>
                  {stats.data.submitted} submitted. {pending ? `${pending} applicant${pending === 1 ? ' has' : 's have'} started but not submitted (${stats.data.paymentPending} waiting for the fee) and will not be able to submit.` : 'Nobody has an unfinished application.'}
                </Alert>
              )}
              <TextField label="Reason (kept in the window's record)" value={reason} onChange={(e) => setReason(e.target.value)} multiline minRows={2} autoFocus />
              {win.state === 'OPEN' && pending > 0 && (
                <FormControlLabel control={<Checkbox checked={notify} onChange={(e) => setNotify(e.target.checked)} />} label={`Tell the ${pending} by SMS, with the reason`} />
              )}
            </>
          )}
          {action === 'reopen' && (
            <>
              <Typography>Applications will be taken again from now until the new closing date. This is not possible once selection has started.</Typography>
              <TextField type="datetime-local" label="New closing date" value={closesAt} onChange={(e) => setClosesAt(e.target.value)} error={!futureClose}
                helperText={futureClose ? ' ' : 'Choose a time in the future'} slotProps={{ inputLabel: { shrink: true } }} />
              <TextField label="Reason (kept in the window's record)" value={reason} onChange={(e) => setReason(e.target.value)} multiline minRows={2} />
              {pending > 0 && <FormControlLabel control={<Checkbox checked={notify} onChange={(e) => setNotify(e.target.checked)} />} label={`Tell the ${pending} applicant${pending === 1 ? '' : 's'} with unfinished applications by SMS`} />}
            </>
          )}
          <ErrorAlert error={m.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Back</Button>
        <Button variant="contained" color={action === 'close' ? 'error' : action === 'open' ? 'success' : 'primary'} disabled={!ok || m.isPending} onClick={() => m.mutate()}>
          {{ publish: 'Publish', open: 'Open now', close: win.state === 'OPEN' ? 'Close now' : 'Cancel window', reopen: 'Reopen' }[action]}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ================================================================== record

const ACTION_LABEL: Record<WindowEvent['action'], string> = {
  CREATED: 'Created', PUBLISHED: 'Published', OPENED: 'Opened', CLOSED: 'Closed', REOPENED: 'Reopened', EXTENDED: 'Extended', RESCHEDULED: 'Dates changed',
};

export function WindowHistoryCard({ windowId }: { windowId: number }) {
  const q = useQuery({ queryKey: ['staff', 'window-events', windowId], queryFn: () => api<WindowEvent[]>(`/api/admission/windows/${windowId}/events`, staff) });
  if (!q.data?.length) return <ErrorAlert error={q.error} />;
  return (
    <Card sx={{ mt: 2 }}>
      <CardContent>
        <Typography variant="h6" gutterBottom>Opening and closing record</Typography>
        <Stack divider={<Box sx={{ borderTop: 1, borderColor: 'divider' }} />}>
          {q.data.map((e) => (
            <Stack key={e.id} direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 0.25, sm: 2 }} sx={{ py: 1 }}>
              <Typography variant="body2" color="text.secondary" sx={{ minWidth: 150 }}>{formatDateTime(e.at)}</Typography>
              <Box sx={{ flexGrow: 1 }}>
                <Typography variant="body2">
                  <b>{ACTION_LABEL[e.action]}</b>
                  {e.action === 'EXTENDED' || e.action === 'REOPENED' || e.action === 'RESCHEDULED' || e.action === 'OPENED' || e.action === 'PUBLISHED'
                    ? ` · applications ${e.action === 'PUBLISHED' || e.action === 'RESCHEDULED' ? `${formatDateTime(e.opensAt)} → ` : 'until '}${formatDateTime(e.closesAt)}` : ''}
                  {e.previousClosesAt && e.action !== 'CLOSED' ? ` (was ${formatDateTime(e.previousClosesAt)})` : ''}
                  {e.action === 'CLOSED' && e.previousClosesAt ? ` early (was due ${formatDateTime(e.previousClosesAt)})` : ''}
                </Typography>
                {e.reason && <Typography variant="body2" color="text.secondary">{e.reason}</Typography>}
                {e.applicantsNotified > 0 && <Typography variant="caption" color="text.secondary">{e.applicantsNotified} applicant{e.applicantsNotified === 1 ? '' : 's'} told by SMS</Typography>}
              </Box>
              <Typography variant="body2" color="text.secondary">{e.automatic ? 'Automatic' : e.byName}</Typography>
            </Stack>
          ))}
        </Stack>
      </CardContent>
    </Card>
  );
}
