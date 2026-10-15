import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import SendIcon from '@mui/icons-material/Send';
import {
  Alert, Autocomplete, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, MenuItem,
  Stack, Switch, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, formatDateTime } from '../../api/client';
import type { ReportCatalogueEntry } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading } from '../../components/common';

const staff = { realm: 'staff' as const };

interface OutboxRow { id: number; to: string; recipientName?: string; kind: string; templateCode?: string; subject: string; attachmentName?: string;
  attachmentSize?: number; status: 'QUEUED' | 'SENT' | 'FAILED'; attempts: number; error?: string; queuedAt: string; sentAt?: string }

/** The email outbox and the institution's switch (Communication › Messages › Email). */
export function EmailTab() {
  const qc = useQueryClient();
  const { user } = useSession();
  const isAdmin = !!user('staff')?.roles.includes('INSTITUTION_ADMIN');
  const q = useQuery({ queryKey: ['staff', 'emails'], queryFn: () => api<{ enabled: boolean; rows: OutboxRow[] }>('/api/messaging/emails?size=100', staff) });
  const toggle = useMutation({
    mutationFn: (enabled: boolean) => api('/api/messaging/emails/switch', { ...staff, method: 'PUT', body: { enabled } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staff', 'emails'] }),
  });
  if (q.isLoading) return <Loading />;
  const d = q.data;
  return (
    <>
      <Card sx={{ mb: 2 }}>
        <CardContent>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }} justifyContent="space-between">
            <Box>
              <Typography variant="h6">Email</Typography>
              <Typography variant="body2" color="text.secondary">
                Event messages also go by email to applicants and students who have confirmed their address; admission letters go with the PDF attached; staff
                receive scheduled reports. Verification codes are masked here and attachments are not kept once sent.
              </Typography>
            </Box>
            <FormControlLabel control={<Switch checked={!!d?.enabled} disabled={!isAdmin || toggle.isPending} onChange={(e) => toggle.mutate(e.target.checked)} />}
              label={d?.enabled ? 'Email on' : 'Email off'} sx={{ flexShrink: 0 }} />
          </Stack>
          <ErrorAlert error={toggle.error} />
        </CardContent>
      </Card>
      <ErrorAlert error={q.error} />
      <TableContainer component={Card}><Table size="small">
        <TableHead><TableRow><TableCell>Queued</TableCell><TableCell>To</TableCell><TableCell>Subject</TableCell><TableCell>Kind</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
        <TableBody>
          {d?.rows.map((m) => (
            <TableRow key={m.id}>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(m.queuedAt)}</TableCell>
              <TableCell>{m.to}{m.recipientName && <Typography variant="caption" display="block" color="text.secondary">{m.recipientName}</Typography>}</TableCell>
              <TableCell>{m.subject}{m.attachmentName && <Typography variant="caption" display="block" color="text.secondary">📎 {m.attachmentName}</Typography>}</TableCell>
              <TableCell>{m.kind.charAt(0) + m.kind.slice(1).toLowerCase()}{m.templateCode && <Typography variant="caption" display="block" color="text.secondary">{m.templateCode}</Typography>}</TableCell>
              <TableCell>
                <Chip size="small" color={m.status === 'SENT' ? 'success' : m.status === 'FAILED' ? 'error' : 'warning'} label={m.status === 'SENT' ? 'Sent' : m.status === 'FAILED' ? 'Failed' : `Queued (${m.attempts} tries)`} />
                {m.error && <Typography variant="caption" display="block" color="error">{m.error}</Typography>}
              </TableCell>
            </TableRow>
          ))}
          {d?.rows.length === 0 && <TableRow><TableCell colSpan={5}><Typography color="text.secondary">No emails yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table></TableContainer>
    </>
  );
}

interface Schedule { id: number; reportCode: string; title: string; filters: Record<string, unknown>; format: 'PDF' | 'XLSX'; frequency: 'DAILY' | 'WEEKLY' | 'MONTHLY';
  dayOfWeek?: number; dayOfMonth?: number; sendHour: number; recipients: string[]; ownerName: string; mine: boolean; active: boolean;
  nextRunAt: string; lastRunAt?: string; lastStatus?: string }
interface Recipient { userId: number; name: string; email: string }
const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

const when = (s: Schedule) => `${s.frequency === 'DAILY' ? 'Every day' : s.frequency === 'WEEKLY' ? `Every ${DAYS[(s.dayOfWeek ?? 1) - 1]}` : `Monthly on day ${s.dayOfMonth}`} at ${String(s.sendHour).padStart(2, '0')}:00`;

/** Reports emailed on a schedule to staff (FR-RPT-03). */
export function ScheduledReports({ catalogue }: { catalogue: ReportCatalogueEntry[] }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'report-schedules'], queryFn: () => api<Schedule[]>('/api/reports/schedules', staff) });
  const recipients = useQuery({ queryKey: ['staff', 'report-recipients'], queryFn: () => api<Recipient[]>('/api/reports/schedules/recipients', staff) });
  const empty = { id: 0, reportCode: catalogue[0]?.code ?? '', format: 'PDF' as Schedule['format'], frequency: 'WEEKLY' as Schedule['frequency'], dayOfWeek: 1, dayOfMonth: 1,
    sendHour: 7, recipients: [] as string[], active: true, filters: {} as Record<string, unknown> };
  const [f, setF] = useState<typeof empty | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff', 'report-schedules'] });
  const save = useMutation({
    mutationFn: (x: typeof empty) => api(x.id ? `/api/reports/schedules/${x.id}` : '/api/reports/schedules', { ...staff, method: x.id ? 'PUT' : 'POST', body: { ...x, id: undefined } }),
    onSuccess: () => { setF(null); refresh(); },
  });
  const send = useMutation({ mutationFn: (id: number) => api(`/api/reports/schedules/${id}/send`, { ...staff, method: 'POST' }), onSuccess: refresh });
  const del = useMutation({ mutationFn: (id: number) => api(`/api/reports/schedules/${id}`, { ...staff, method: 'DELETE' }), onSuccess: refresh });
  if (q.isLoading) return <Loading />;
  return (
    <Card>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }}>
          <Box>
            <Typography variant="h6">Scheduled reports</Typography>
            <Typography variant="body2" color="text.secondary">Emailed to staff with a confirmed email, as PDF or Excel. Each run uses your roles, so it shows only what you may see.</Typography>
          </Box>
          <Button variant="contained" startIcon={<AddIcon />} sx={{ flexShrink: 0, ml: 2 }} onClick={() => setF(empty)}>Schedule a report</Button>
        </Stack>
        {recipients.data?.length === 0 && <Alert severity="info" sx={{ mb: 1.5 }}>No staff member has confirmed an email yet. Confirm yours on Change password › Email.</Alert>}
        <ErrorAlert error={q.error ?? send.error ?? del.error} />
        <TableContainer><Table size="small">
          <TableHead><TableRow><TableCell>Report</TableCell><TableCell>When</TableCell><TableCell>To</TableCell><TableCell>Last run</TableCell><TableCell /></TableRow></TableHead>
          <TableBody>
            {q.data?.map((s) => (
              <TableRow key={s.id} hover={s.mine} sx={{ cursor: s.mine ? 'pointer' : 'default', opacity: s.active ? 1 : 0.6 }}
                onClick={() => s.mine && setF({ id: s.id, reportCode: s.reportCode, format: s.format, frequency: s.frequency, dayOfWeek: s.dayOfWeek ?? 1, dayOfMonth: s.dayOfMonth ?? 1,
                  sendHour: s.sendHour, recipients: s.recipients, active: s.active, filters: s.filters })}>
                <TableCell>{s.title}<Typography variant="caption" display="block" color="text.secondary">{s.format} · by {s.ownerName}{s.active ? '' : ' · paused'}</Typography></TableCell>
                <TableCell>{when(s)}<Typography variant="caption" display="block" color="text.secondary">next {formatDateTime(s.nextRunAt)}</Typography></TableCell>
                <TableCell>{s.recipients.join(', ')}</TableCell>
                <TableCell>{s.lastStatus ?? '—'}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }} onClick={(e) => e.stopPropagation()}>
                  <Tooltip title="Send now"><span><IconButton size="small" disabled={send.isPending} onClick={() => send.mutate(s.id)}><SendIcon fontSize="small" /></IconButton></span></Tooltip>
                  {s.mine && <Tooltip title="Delete"><IconButton size="small" onClick={() => del.mutate(s.id)}><DeleteIcon fontSize="small" /></IconButton></Tooltip>}
                </TableCell>
              </TableRow>
            ))}
            {q.data?.length === 0 && <TableRow><TableCell colSpan={5}><Typography color="text.secondary">No scheduled reports.</Typography></TableCell></TableRow>}
          </TableBody>
        </Table></TableContainer>
      </CardContent>
      <Dialog open={!!f} onClose={() => setF(null)} fullWidth maxWidth="sm">
        <DialogTitle>{f?.id ? 'Scheduled report' : 'Schedule a report'}</DialogTitle>
        {f && (
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              <TextField select size="small" label="Report" value={f.reportCode} onChange={(e) => setF({ ...f, reportCode: e.target.value })}>
                {catalogue.map((c) => <MenuItem key={c.code} value={c.code}>{c.code} — {c.title}</MenuItem>)}
              </TextField>
              <Stack direction="row" spacing={1}>
                <TextField select size="small" fullWidth label="Format" value={f.format} onChange={(e) => setF({ ...f, format: e.target.value as Schedule['format'] })}>
                  <MenuItem value="PDF">PDF</MenuItem><MenuItem value="XLSX">Excel</MenuItem>
                </TextField>
                <TextField select size="small" fullWidth label="How often" value={f.frequency} onChange={(e) => setF({ ...f, frequency: e.target.value as Schedule['frequency'] })}>
                  <MenuItem value="DAILY">Daily</MenuItem><MenuItem value="WEEKLY">Weekly</MenuItem><MenuItem value="MONTHLY">Monthly</MenuItem>
                </TextField>
                {f.frequency === 'WEEKLY' && <TextField select size="small" fullWidth label="Day" value={f.dayOfWeek} onChange={(e) => setF({ ...f, dayOfWeek: Number(e.target.value) })}>
                  {DAYS.map((d, i) => <MenuItem key={d} value={i + 1}>{d}</MenuItem>)}</TextField>}
                {f.frequency === 'MONTHLY' && <TextField select size="small" fullWidth label="Day of month" value={f.dayOfMonth} onChange={(e) => setF({ ...f, dayOfMonth: Number(e.target.value) })}>
                  {Array.from({ length: 28 }, (_, i) => <MenuItem key={i} value={i + 1}>{i + 1}</MenuItem>)}</TextField>}
                <TextField select size="small" fullWidth label="Hour" value={f.sendHour} onChange={(e) => setF({ ...f, sendHour: Number(e.target.value) })}>
                  {Array.from({ length: 24 }, (_, i) => <MenuItem key={i} value={i}>{String(i).padStart(2, '0')}:00</MenuItem>)}
                </TextField>
              </Stack>
              <Autocomplete multiple size="small" options={(recipients.data ?? []).map((r) => r.email)} value={f.recipients}
                getOptionLabel={(e) => { const r = recipients.data?.find((x) => x.email === e); return r ? `${r.name} <${e}>` : e; }}
                onChange={(_, v) => setF({ ...f, recipients: v })} renderInput={(p) => <TextField {...p} label="Send to (staff with a confirmed email)" />} />
              <FormControlLabel control={<Switch checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} />} label="Active" />
              <Typography variant="caption" color="text.secondary">The report runs with its standard filters (current period). Filters can be set when the report is run on screen.</Typography>
              <ErrorAlert error={save.error} />
            </Stack>
          </DialogContent>
        )}
        <DialogActions><Button onClick={() => setF(null)}>Cancel</Button>
          <Button variant="contained" disabled={!f?.reportCode || !f?.recipients.length || save.isPending} onClick={() => f && save.mutate(f)}>Save</Button></DialogActions>
      </Dialog>
    </Card>
  );
}
