import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Stack, Switch, Table,
  TableBody, TableCell, TableHead, TableRow, TextField, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, formatDateTime } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { platform } from './shared';

interface Field { name: string; label: string; secret: boolean }
interface FieldView extends Field { value?: string | null; set: boolean }
interface ProviderView { code: string; label: string; fields: Field[]; baseUrl: string; reportUrl?: string | null }
interface SmsView {
  mode: 'live' | 'simulated'; provider?: string | null; providerLabel?: string | null; environment: string; active: boolean; fields: FieldView[];
  baseUrl?: string | null; reportUrl?: string | null; defaultSender: string; notifyUrl?: string | null; lastTestAt?: string | null; lastTestOk?: boolean | null;
  lastTestNote?: string | null; updatedByName?: string | null; updatedAt?: string | null; providers: ProviderView[];
}
interface SenderName {
  id: number; tenantId: number; institution: string; institutionCode: string; senderId: string; status: string; note?: string | null;
  requestedByName: string; requestedAt: string; decidedByName?: string | null; decidedAt?: string | null;
}

const STATUS_LABEL: Record<string, string> = { REQUESTED: 'Asked for', SUBMITTED: 'Submitted to provider', APPROVED: 'Approved', REJECTED: 'Rejected', WITHDRAWN: 'Withdrawn' };
const STATUS_COLOUR: Record<string, 'default' | 'info' | 'warning' | 'success' | 'error'> = {
  REQUESTED: 'warning', SUBMITTED: 'info', APPROVED: 'success', REJECTED: 'error', WITHDRAWN: 'default',
};
const SENDER = /^(?=.*[A-Za-z])[A-Za-z0-9]{3,11}$/;

/** Msingi's SMS provider account, and the institutions' sender names (asked for, submitted, approved). */
export function SmsPage() {
  return (
    <>
      <PageTitle title="SMS" subtitle="The SMS provider Msingi sends every institution's messages through, and each institution's sender name." />
      <Stack spacing={3}>
        <ProviderCard />
        <SenderNamesCard />
      </Stack>
    </>
  );
}

function ProviderCard() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['platform', 'sms'], queryFn: () => api<SmsView>('/api/platform/sms/provider', platform) });
  const [f, setF] = useState({ provider: '', environment: 'SANDBOX', active: false, baseUrl: '', reportUrl: '', defaultSender: 'MSINGI', fields: {} as Record<string, string> });
  const [phone, setPhone] = useState('');
  const [confirm, setConfirm] = useState(false);
  useEffect(() => {
    if (q.data) setF({ provider: q.data.provider ?? '', environment: q.data.environment, active: q.data.active, baseUrl: q.data.baseUrl ?? '',
      reportUrl: q.data.reportUrl ?? '', defaultSender: q.data.defaultSender, fields: Object.fromEntries(q.data.fields.filter((x) => !x.secret).map((x) => [x.name, x.value ?? ''])) });
  }, [q.data]);
  const done = (v: SmsView) => qc.setQueryData(['platform', 'sms'], v);
  const save = useMutation({
    mutationFn: () => api<SmsView>('/api/platform/sms/provider', { ...platform, method: 'PUT', body: { ...f, baseUrl: f.baseUrl || null, reportUrl: f.reportUrl || null } }),
    onSuccess: (v) => { done(v); setF((x) => ({ ...x, fields: Object.fromEntries(Object.entries(x.fields).filter(([k]) => !v.fields.find((y) => y.name === k)?.secret)) })); },
  });
  const test = useMutation({ mutationFn: () => api<SmsView>('/api/platform/sms/provider/test', { ...platform, method: 'POST' }), onSuccess: done });
  const testSms = useMutation({ mutationFn: () => api<SmsView>('/api/platform/sms/provider/test-sms', { ...platform, method: 'POST', body: { phone } }), onSuccess: done });
  const renew = useMutation({ mutationFn: () => api<SmsView>('/api/platform/sms/provider/notify-address', { ...platform, method: 'POST' }),
    onSuccess: (v) => { done(v); setConfirm(false); } });
  if (q.isLoading || !q.data) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  const d = q.data;
  const p = d.providers.find((x) => x.code === f.provider);
  const senderOk = SENDER.test(f.defaultSender);
  return (
    <>
      <Card><CardContent>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }} flexWrap="wrap" useFlexGap>
          <Typography variant="h6" sx={{ flex: 1 }}>SMS provider</Typography>
          {d.provider && <Chip size="small" color={d.active ? 'success' : 'default'} label={d.active ? `${d.providerLabel} on` : `${d.providerLabel} off`} />}
          <Chip size="small" variant="outlined" color={d.mode === 'live' ? 'success' : 'warning'} label={d.mode === 'live' ? 'Server sends real SMS' : 'Server in simulated mode'} />
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Msingi holds one account with a Tanzanian SMS provider and every institution's messages go through it, paid from the SMS bundles the
          institutions buy in CIMS. Each institution's own sender name (e.g. BAHARI) must first be registered with TCRA and the networks through
          the provider; until then its messages go out under Msingi's sender name.
        </Typography>
        {d.mode !== 'live' && (
          <Alert severity="info" sx={{ mb: 2 }}>
            The server is in simulated mode (CIMS_SMS_MODE=simulated): messages are written to the log, nothing is sent. Set up and test the provider
            here, then set CIMS_SMS_MODE=live on the server and restart.
          </Alert>
        )}
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <TextField select fullWidth label="Provider" value={f.provider} onChange={(e) => setF({ ...f, provider: e.target.value, fields: {}, baseUrl: '', reportUrl: '' })}>
              {d.providers.map((x) => <MenuItem key={x.code} value={x.code}>{x.label}</MenuItem>)}
            </TextField>
          </Grid>
          {p && (
            <Grid size={{ xs: 12, sm: 6, md: 4 }}>
              <TextField select fullWidth label="Mode" value={f.environment} onChange={(e) => setF({ ...f, environment: e.target.value })}
                helperText={p.code === 'BEEM' ? 'Beem has no test mode: every message is sent' : f.environment === 'LIVE' ? 'Messages are really sent' : 'NextSMS accepts and answers but sends nothing'}>
                <MenuItem value="SANDBOX">Test</MenuItem>
                <MenuItem value="LIVE">Live</MenuItem>
              </TextField>
            </Grid>
          )}
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <TextField fullWidth label="Msingi's sender name" value={f.defaultSender} onChange={(e) => setF({ ...f, defaultSender: e.target.value.slice(0, 11) })}
              error={!senderOk} helperText={senderOk ? 'Used while an institution\'s own name is not approved' : '3 to 11 letters or digits, no spaces'} />
          </Grid>
          {p?.fields.map((x) => {
            const old = d.fields.find((y) => y.name === x.name && d.provider === p.code);
            return (
              <Grid key={x.name} size={{ xs: 12, sm: 6, md: 4 }}>
                <TextField fullWidth label={x.label} type={x.secret ? 'password' : 'text'} autoComplete={x.secret ? 'new-password' : 'off'}
                  value={f.fields[x.name] ?? ''} onChange={(e) => setF({ ...f, fields: { ...f.fields, [x.name]: e.target.value } })}
                  helperText={x.secret ? (old?.set ? 'Saved (never shown). Leave blank to keep it.' : 'Not saved yet') : undefined} />
              </Grid>
            );
          })}
          {p && (
            <>
              <Grid size={{ xs: 12, md: 6 }}>
                <TextField fullWidth label="API address (optional)" value={f.baseUrl} onChange={(e) => setF({ ...f, baseUrl: e.target.value })} helperText={`Leave blank for ${p.baseUrl}`} />
              </Grid>
              {p.reportUrl && (
                <Grid size={{ xs: 12, md: 6 }}>
                  <TextField fullWidth label="Delivery-report API address (optional)" value={f.reportUrl} onChange={(e) => setF({ ...f, reportUrl: e.target.value })}
                    helperText={`Leave blank for ${p.reportUrl}`} />
                </Grid>
              )}
            </>
          )}
        </Grid>
        <FormControlLabel sx={{ mt: 1 }} control={<Switch checked={f.active} disabled={!f.provider} onChange={(e) => setF({ ...f, active: e.target.checked })} />} label="Switched on" />
        <ErrorAlert error={save.error ?? test.error ?? testSms.error} />
        {save.isSuccess && <Alert severity="success" sx={{ mt: 1 }}>Saved.</Alert>}
        {d.lastTestAt && <Alert severity={d.lastTestOk ? 'success' : 'error'} sx={{ mt: 1 }}>{`${d.lastTestNote ?? ''} (${formatDateTime(d.lastTestAt)})`}</Alert>}
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 2, '& .MuiButton-root': { whiteSpace: 'nowrap', flexShrink: 0 } }} alignItems={{ sm: 'center' }}>
          <Button variant="contained" disabled={save.isPending || !f.provider || !senderOk} onClick={() => save.mutate()}>Save</Button>
          <Button variant="outlined" disabled={test.isPending || !d.provider} onClick={() => test.mutate()}>Test the credentials</Button>
          <Box sx={{ flex: 1 }} />
          <TextField size="small" label="Phone for a test SMS" placeholder="0712 345 678" value={phone} onChange={(e) => setPhone(e.target.value)} />
          <Button variant="outlined" disabled={testSms.isPending || !d.provider || !phone.trim()} onClick={() => testSms.mutate()}>Send a test SMS</Button>
        </Stack>
        {d.updatedByName && <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1.5 }}>Last changed by {d.updatedByName} on {formatDateTime(d.updatedAt ?? undefined)}.</Typography>}
      </CardContent></Card>

      {d.notifyUrl && (
        <Card><CardContent>
          <Typography variant="subtitle2" gutterBottom>Delivery-report address (to give the provider)</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            Enter this as the delivery report (DLR) callback address in the provider's dashboard (NextSMS: Customer Profile › Customization ›
            Webhooks, Delivery Callback URL, with any Verify Token), so CIMS hears at once whether each message
            reached the phone. CIMS also asks the provider every few minutes about messages it has heard nothing of. It contains a secret part:
            share it only with the provider.
          </Typography>
          <Typography variant="body2" sx={{ fontFamily: 'monospace', wordBreak: 'break-all', p: 1, bgcolor: 'action.hover', borderRadius: 1 }}>{d.notifyUrl}</Typography>
          <Button sx={{ mt: 1.5 }} variant="outlined" onClick={() => setConfirm(true)}>Make a new address</Button>
        </CardContent></Card>
      )}

      <Dialog open={confirm} onClose={() => setConfirm(false)} maxWidth="xs" fullWidth>
        <DialogContent><Typography>The current address stops working at once: reports are only asked for until the provider has the new one. Make a new address?</Typography></DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(false)}>Cancel</Button>
          <Button variant="contained" color="warning" disabled={renew.isPending} onClick={() => renew.mutate()}>Make a new address</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

function SenderNamesCard() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['platform', 'sms-senders'], queryFn: () => api<SenderName[]>('/api/platform/sms/sender-names', platform) });
  const [show, setShow] = useState<'open' | 'all'>('open');
  const [open, setOpen] = useState<SenderName | null>(null);
  const [status, setStatus] = useState('SUBMITTED');
  const [note, setNote] = useState('');
  const decide = useMutation({
    mutationFn: () => api<SenderName>(`/api/platform/sms/sender-names/${open!.id}`, { ...platform, method: 'PUT', body: { status, note: note.trim() || null } }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['platform', 'sms-senders'] }); setOpen(null); },
  });
  const rows = (q.data ?? []).filter((r) => show === 'all' || ['REQUESTED', 'SUBMITTED', 'REJECTED'].includes(r.status));
  return (
    <Card><CardContent>
      <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1 }}>
        <Typography variant="h6" sx={{ flex: 1 }}>Sender names</Typography>
        <ToggleButtonGroup size="small" exclusive value={show} onChange={(_, v) => v && setShow(v)}>
          <ToggleButton value="open">Waiting</ToggleButton>
          <ToggleButton value="all">All</ToggleButton>
        </ToggleButtonGroup>
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        An institution asks for its sender name on its Messaging page. Submit it to the provider with the institution's request letter on its
        letterhead, its registration certificate, TIN certificate and the signatory's ID, plus a sample message; approval through TCRA and the
        networks usually takes one to four weeks. Record the outcome here: the institution's messages carry its name from the moment it is approved.
      </Typography>
      <ErrorAlert error={q.error} />
      {q.isLoading ? <Loading /> : !rows.length ? <Typography variant="body2" color="text.secondary">{show === 'open' ? 'Nothing waiting.' : 'No sender names asked for yet.'}</Typography> : (
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small">
            <TableHead><TableRow><TableCell>Institution</TableCell><TableCell>Sender name</TableCell><TableCell>Status</TableCell><TableCell>Asked for</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {rows.map((r) => (
                <TableRow key={r.id}>
                  <TableCell>{r.institution}<Typography variant="caption" display="block" color="text.secondary">{r.institutionCode}</Typography></TableCell>
                  <TableCell sx={{ fontFamily: 'monospace', fontWeight: 600 }}>{r.senderId}</TableCell>
                  <TableCell>
                    <Chip size="small" color={STATUS_COLOUR[r.status] ?? 'default'} label={STATUS_LABEL[r.status] ?? r.status} />
                    {(r.note || r.decidedByName) && (
                      <Typography variant="caption" display="block" color="text.secondary">
                        {[r.note, r.decidedByName && r.decidedAt ? `${r.decidedByName}, ${formatDateTime(r.decidedAt)}` : null].filter(Boolean).join(' · ')}
                      </Typography>
                    )}
                  </TableCell>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(r.requestedAt)}<Typography variant="caption" display="block" color="text.secondary">{r.requestedByName}</Typography></TableCell>
                  <TableCell align="right">
                    {r.status !== 'WITHDRAWN' && (
                      <Button size="small" onClick={() => { setOpen(r); setStatus(r.status === 'REQUESTED' ? 'SUBMITTED' : 'APPROVED'); setNote(r.note ?? ''); }}>Update</Button>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}
      <Dialog open={!!open} onClose={() => setOpen(null)} maxWidth="xs" fullWidth>
        <DialogTitle>{open?.senderId} · {open?.institution}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField select label="Status" value={status} onChange={(e) => setStatus(e.target.value)}>
              <MenuItem value="SUBMITTED">Submitted to the provider</MenuItem>
              <MenuItem value="APPROVED">Approved: messages now carry this name</MenuItem>
              <MenuItem value="REJECTED">Rejected</MenuItem>
            </TextField>
            <TextField label={status === 'REJECTED' ? 'Why it was rejected (the institution sees this)' : 'Note (optional)'} value={note} multiline minRows={2}
              onChange={(e) => setNote(e.target.value)} inputProps={{ maxLength: 300 }} required={status === 'REJECTED'} />
          </Stack>
          <ErrorAlert error={decide.error} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(null)}>Cancel</Button>
          <Button variant="contained" disabled={decide.isPending || (status === 'REJECTED' && !note.trim())} onClick={() => decide.mutate()}>Save</Button>
        </DialogActions>
      </Dialog>
    </CardContent></Card>
  );
}
