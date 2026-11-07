import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, FormControlLabel, InputAdornment, MenuItem, Stack, Switch,
  TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, formatDateTime } from '../../api/client';
import { ErrorAlert, Loading } from '../../components/common';
import { platform } from './shared';

interface SmsPricing { pricePerSms: number; minUnits: number; vatPercent: number }
interface Field { name: string; label: string; secret: boolean; value?: string; set: boolean }
interface ProviderView { code: string; label: string; fields: { name: string; label: string; secret: boolean }[]; sandboxUrl: string; liveUrl: string; needsNetwork: boolean }
interface CollectionView {
  provider?: string; providerLabel?: string; environment: string; active: boolean; fields: Field[]; baseUrl?: string; authUrl?: string; notifyUrl?: string;
  lastTestAt?: string; lastTestOk?: boolean; lastTestNote?: string; providers: ProviderView[]; rehearsalAllowed: boolean;
}

/** Console › Billing › SMS bundles: what institutions pay for SMS, and Msingi's own mobile money account for prompts on their phones. */
export function SmsBundlesTab() {
  return (
    <Stack spacing={2.5}>
      <Alert severity="info" icon={false}>
        Institutions buy SMS themselves under Communication › Messages › Buy SMS. The invoice is issued at once with its payment reference; when
        Msingi's collector or aggregator reports the payment, the bundle is added to the institution's balance automatically. Unpaid bundle
        invoices lapse after 14 days and are left out of reminders and suspension.
      </Alert>
      <PricingCard />
      <CollectionCard />
    </Stack>
  );
}

function PricingCard() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['platform', 'billing', 'sms-pricing'], queryFn: () => api<SmsPricing>('/api/platform/billing/sms-pricing', platform) });
  const [f, setF] = useState({ pricePerSms: '', minUnits: '', vatPercent: '' });
  useEffect(() => { if (q.data) setF({ pricePerSms: String(q.data.pricePerSms), minUnits: String(q.data.minUnits), vatPercent: String(q.data.vatPercent) }); }, [q.data]);
  const save = useMutation({
    mutationFn: () => api<SmsPricing>('/api/platform/billing/sms-pricing', { ...platform, method: 'PUT',
      body: { pricePerSms: Number(f.pricePerSms), minUnits: Number(f.minUnits), vatPercent: Number(f.vatPercent) } }),
    onSuccess: (v) => qc.setQueryData(['platform', 'billing', 'sms-pricing'], v),
  });
  if (!q.data) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  return (
    <Card><CardContent>
      <Typography variant="h6">SMS price</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>The standard price institutions pay per SMS, the smallest bundle they can buy, and the VAT on bundle invoices.</Typography>
      <Grid container spacing={2}>
        <Grid size={{ xs: 12, sm: 4 }}>
          <TextField fullWidth label="Price per SMS" type="number" value={f.pricePerSms} onChange={(e) => setF({ ...f, pricePerSms: e.target.value })}
            slotProps={{ input: { startAdornment: <InputAdornment position="start">TZS</InputAdornment> } }} />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <TextField fullWidth label="Smallest bundle" type="number" value={f.minUnits} onChange={(e) => setF({ ...f, minUnits: e.target.value })}
            slotProps={{ input: { endAdornment: <InputAdornment position="end">SMS</InputAdornment> } }} />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <TextField fullWidth label="VAT" type="number" value={f.vatPercent} onChange={(e) => setF({ ...f, vatPercent: e.target.value })}
            slotProps={{ input: { endAdornment: <InputAdornment position="end">%</InputAdornment> } }} />
        </Grid>
      </Grid>
      <ErrorAlert error={save.error} />
      {save.isSuccess && <Alert severity="success" sx={{ mt: 1.5 }}>Saved.</Alert>}
      <Stack direction="row" justifyContent="flex-end" sx={{ mt: 2 }}>
        <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Save</Button>
      </Stack>
    </CardContent></Card>
  );
}

function CollectionCard() {
  const qc = useQueryClient();
  const key = ['platform', 'billing', 'collection'];
  const q = useQuery({ queryKey: key, queryFn: () => api<CollectionView>('/api/platform/billing/collection', platform) });
  const [f, setF] = useState({ provider: '', environment: 'SANDBOX', active: false, baseUrl: '', authUrl: '', fields: {} as Record<string, string> });
  const [confirm, setConfirm] = useState(false);
  useEffect(() => {
    if (q.data) setF({ provider: q.data.provider ?? '', environment: q.data.environment, active: q.data.active, baseUrl: q.data.baseUrl ?? '', authUrl: q.data.authUrl ?? '',
      fields: Object.fromEntries(q.data.fields.filter((x) => !x.secret).map((x) => [x.name, x.value ?? ''])) });
  }, [q.data]);
  const done = (v: CollectionView) => qc.setQueryData(key, v);
  const save = useMutation({
    mutationFn: () => api<CollectionView>('/api/platform/billing/collection', { ...platform, method: 'PUT', body: { ...f, baseUrl: f.baseUrl || null, authUrl: f.authUrl || null } }),
    onSuccess: done,
  });
  const test = useMutation({ mutationFn: () => api<CollectionView>('/api/platform/billing/collection/test', { ...platform, method: 'POST' }), onSuccess: done });
  const renew = useMutation({ mutationFn: () => api<CollectionView>('/api/platform/billing/collection/notify-address', { ...platform, method: 'POST' }),
    onSuccess: (v) => { done(v); setConfirm(false); } });
  if (!q.data) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  const d = q.data;
  const p = d.providers.find((x) => x.code === f.provider);
  const providers = d.providers.filter((x) => x.code !== 'SIMULATED' || d.rehearsalAllowed || d.provider === 'SIMULATED');
  return (
    <>
      <Card><CardContent>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }} flexWrap="wrap" useFlexGap>
          <Typography variant="h6" sx={{ flex: 1 }}>Msingi's mobile money account</Typography>
          {d.provider && <Chip size="small" color={d.active ? 'success' : 'default'} label={d.active ? `${d.providerLabel} on` : `${d.providerLabel} off`} />}
          {d.provider && d.provider !== 'SIMULATED' && <Chip size="small" variant="outlined" label={d.environment === 'LIVE' ? 'Live' : 'Sandbox'} />}
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Msingi's merchant account with a payment aggregator. With it switched on, institutions can pay Msingi invoices (SMS bundles and
          others) by a prompt on a phone, and the payment is recorded the moment the aggregator confirms it. Bank transfers and mobile money
          quoting the payment reference keep coming through Msingi's collector.
        </Typography>
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <TextField select fullWidth label="Aggregator" value={f.provider} onChange={(e) => setF({ ...f, provider: e.target.value, fields: {}, baseUrl: '', authUrl: '' })}>
              {providers.map((x) => <MenuItem key={x.code} value={x.code}>{x.label}</MenuItem>)}
            </TextField>
          </Grid>
          {p && p.code !== 'SIMULATED' && (
            <Grid size={{ xs: 12, sm: 6, md: 4 }}>
              <TextField select fullWidth label="Environment" value={f.environment} onChange={(e) => setF({ ...f, environment: e.target.value })}
                helperText="Sandbox until the aggregator gives live credentials">
                <MenuItem value="SANDBOX">Sandbox (test)</MenuItem>
                <MenuItem value="LIVE">Live (real money)</MenuItem>
              </TextField>
            </Grid>
          )}
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
          {p && p.code !== 'SIMULATED' && (
            <Grid size={{ xs: 12, md: 6 }}>
              <TextField fullWidth label="API address (optional)" value={f.baseUrl} onChange={(e) => setF({ ...f, baseUrl: e.target.value })}
                helperText={`Leave blank for ${f.environment === 'LIVE' ? p.liveUrl : p.sandboxUrl}`} />
            </Grid>
          )}
          {p?.code === 'AZAMPAY' && (
            <Grid size={{ xs: 12, md: 6 }}>
              <TextField fullWidth label="Authenticator address (optional)" value={f.authUrl} onChange={(e) => setF({ ...f, authUrl: e.target.value })}
                helperText="Leave blank for AzamPay's own" />
            </Grid>
          )}
          {p?.code === 'SIMULATED' && (
            <Grid size={12}><Alert severity="warning">The rehearsal records payments without money moving: a prompt is paid a few seconds later, except to a phone ending in 9. Only for testing.</Alert></Grid>
          )}
        </Grid>
        <FormControlLabel sx={{ mt: 1.5 }} control={<Switch checked={f.active} disabled={!f.provider} onChange={(e) => setF({ ...f, active: e.target.checked })} />} label="Switched on" />
        <ErrorAlert error={save.error ?? test.error} />
        {save.isSuccess && <Alert severity="success" sx={{ mt: 1 }}>Saved.</Alert>}
        {d.lastTestAt && <Alert severity={d.lastTestOk ? 'success' : 'error'} sx={{ mt: 1 }}>{`${d.lastTestNote ?? ''} (${formatDateTime(d.lastTestAt)})`}</Alert>}
        <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
          <Button variant="contained" disabled={save.isPending || !f.provider} onClick={() => save.mutate()}>Save</Button>
          <Button variant="outlined" disabled={test.isPending || !d.provider} onClick={() => test.mutate()}>Test the credentials</Button>
        </Stack>
      </CardContent></Card>

      {d.notifyUrl && d.provider !== 'SIMULATED' && (
        <Card><CardContent>
          <Typography variant="subtitle2" gutterBottom>Notification address (to give the aggregator)</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            {d.provider === 'SELCOM' ? 'CIMS sends this address with every payment; Selcom also signs each notification.' : 'Register this address in the aggregator\'s merchant portal as the callback (webhook) address.'}
            {' '}It contains a secret part: share it only with the aggregator.
          </Typography>
          <Box sx={{ fontFamily: 'monospace', fontSize: 14, wordBreak: 'break-all', p: 1, bgcolor: 'action.hover', borderRadius: 1 }}>{d.notifyUrl}</Box>
          <Button sx={{ mt: 1.5 }} variant="outlined" onClick={() => setConfirm(true)}>Make a new notification address</Button>
        </CardContent></Card>
      )}

      <Dialog open={confirm} onClose={() => setConfirm(false)} maxWidth="xs" fullWidth>
        <DialogContent><Typography>The current address stops working at once: notifications fail until the aggregator has the new one. Make a new address?</Typography></DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(false)}>Cancel</Button>
          <Button variant="contained" color="warning" disabled={renew.isPending} onClick={() => renew.mutate()}>Make a new address</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

const PAY_KINDS: [string, string][] = [['LICENCE', 'Set-up and licence'], ['USAGE', 'Usage (students and applications)'], ['SMS', 'SMS bundles'], ['OTHER', 'Other invoices']];

/** How institutions may pay each kind of Msingi invoice: mobile money, bank or both (only those accounts are shown on the invoice). */
export function PayMethodsCard() {
  const qc = useQueryClient();
  const key = ['platform', 'billing', 'pay-methods'];
  const q = useQuery({ queryKey: key, queryFn: () => api<Record<string, string>>('/api/platform/billing/pay-methods', platform) });
  const [f, setF] = useState<Record<string, string> | null>(null);
  useEffect(() => { if (q.data) setF(q.data); }, [q.data]);
  const save = useMutation({ mutationFn: () => api<Record<string, string>>('/api/platform/billing/pay-methods', { ...platform, method: 'PUT', body: f }),
    onSuccess: (v) => { qc.setQueryData(key, v); setF(v); } });
  if (!f) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  return (
    <Card sx={{ mt: 2.5 }}><CardContent>
      <Typography variant="h6">How institutions may pay</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Every Msingi invoice is issued with its payment reference (control number) the moment it is due or requested, and is recorded and
        delivered automatically when the payment reaches Msingi. Choose for each kind of invoice whether it is paid by mobile money, by bank
        or either: the invoice, its email and the Pay window show only those accounts, and a prompt on a phone only where mobile money is allowed.
      </Typography>
      <Grid container spacing={2}>
        {PAY_KINDS.map(([k, label]) => (
          <Grid key={k} size={{ xs: 12, sm: 6, md: 3 }}>
            <TextField select fullWidth label={label} value={f[k] ?? 'BOTH'} onChange={(e) => setF({ ...f, [k]: e.target.value })}>
              <MenuItem value="BOTH">Mobile money or bank</MenuItem>
              <MenuItem value="MOBILE">Mobile money only</MenuItem>
              <MenuItem value="BANK">Bank only</MenuItem>
            </TextField>
          </Grid>
        ))}
      </Grid>
      <ErrorAlert error={save.error} />
      {save.isSuccess && <Alert severity="success" sx={{ mt: 1.5 }}>Saved.</Alert>}
      <Stack direction="row" justifyContent="flex-end" sx={{ mt: 2 }}>
        <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Save</Button>
      </Stack>
    </CardContent></Card>
  );
}
