import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, InputAdornment, MenuItem, Stack, Switch,
  Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, formatDateTime, uploadForm } from '../../api/client';
import { ErrorAlert, Loading } from '../../components/common';
import { platform } from './shared';

interface SmsPricing { pricePerSms: number; minUnits: number; vatPercent: number }
interface Field { name: string; label: string; secret: boolean; value?: string; set: boolean }
interface ProviderView { code: string; label: string; fields: { name: string; label: string; secret: boolean }[]; sandboxUrl: string; liveUrl: string; needsNetwork: boolean }
interface CollectionView {
  provider?: string; providerLabel?: string; environment: string; active: boolean; fields: Field[]; baseUrl?: string; authUrl?: string; notifyUrl?: string;
  lastTestAt?: string; lastTestOk?: boolean; lastTestNote?: string; providers: ProviderView[]; rehearsalAllowed: boolean; billPayNamba?: string | null; c2bUrl?: string | null; c2bToken?: string | null;
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
  const [f, setF] = useState({ provider: '', environment: 'SANDBOX', active: false, baseUrl: '', authUrl: '', billPayNamba: '', fields: {} as Record<string, string> });
  const [confirm, setConfirm] = useState(false);
  useEffect(() => {
    if (q.data) setF({ provider: q.data.provider ?? '', environment: q.data.environment, active: q.data.active, baseUrl: q.data.baseUrl ?? '', authUrl: q.data.authUrl ?? '', billPayNamba: q.data.billPayNamba ?? '',
      fields: Object.fromEntries(q.data.fields.filter((x) => !x.secret).map((x) => [x.name, x.value ?? ''])) });
  }, [q.data]);
  const done = (v: CollectionView) => qc.setQueryData(key, v);
  const save = useMutation({
    mutationFn: () => api<CollectionView>('/api/platform/billing/collection', { ...platform, method: 'PUT', body: { ...f, baseUrl: f.baseUrl || null, authUrl: f.authUrl || null, billPayNamba: f.billPayNamba || null } }),
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
          {(p?.code === 'CLICKPESA' || p?.code === 'SIMULATED' || p?.code === 'SELCOM') && (
            <Grid size={{ xs: 12, md: 6 }}>
              <TextField fullWidth label={p.code === 'SELCOM' ? 'Selcom Pay merchant number (optional)' : 'BillPay-Namba (optional)'} value={f.billPayNamba}
                inputProps={{ inputMode: 'numeric', maxLength: p.code === 'SELCOM' ? 12 : 4 }}
                onChange={(e) => setF({ ...f, billPayNamba: e.target.value.replace(/\D/g, '').slice(0, p.code === 'SELCOM' ? 12 : 4) })}
                helperText={p.code === 'SELCOM'
                  ? 'The merchant number institutions enter under Lipa kwa Selcom Pay (any mobile wallet, bank app or Selcom agent), followed by the invoice\'s payment reference. Selcom checks the reference with CIMS and reports each payment; CIMS records it.'
                  : 'ClickPesa\'s 4-digit merchant BillPay number. With it every Msingi invoice gets a BillPay control number (the namba and its reference) that institutions pay from M-Pesa, Mixx by Yas, Airtel Money, HaloPesa, CRDB SimBanking or a CRDB agent; ClickPesa reports each payment and CIMS records it.'} />
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

      {d.c2bUrl && d.c2bToken && (
        <Card><CardContent>
          <Typography variant="subtitle2" gutterBottom>Selcom Pay by reference (C2B): to give Selcom</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            Ask Selcom to send the lookup, validation and notification calls of Msingi's merchant number to these addresses, with the header
            Authorization: Bearer and the secret below. Share the secret only with Selcom; Make a new notification address changes it.
          </Typography>
          <Box sx={{ fontFamily: 'monospace', fontSize: 14, wordBreak: 'break-all', p: 1, bgcolor: 'action.hover', borderRadius: 1 }}>
            {d.c2bUrl}/lookup<br />{d.c2bUrl}/validation<br />{d.c2bUrl}/notification<br />Authorization: Bearer {d.c2bToken}
          </Box>
        </CardContent></Card>
      )}

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

interface StatementLine { row: number; date?: string; amount: number; text: string; transactionId: string; outcome: string; reference?: string; receiptNo?: string; invoices?: string }
interface StatementImport { id: number; fileName: string; provider: string; total: number; recorded: number; already: number; unmatched: number; lines: StatementLine[]; importedByName?: string; importedAt: string }
const OUTCOME: Record<string, [string, 'success' | 'default' | 'warning' | 'error']> = {
  RECORDED: ['Recorded', 'success'], ALREADY_RECORDED: ['Already recorded', 'default'], UNMATCHED: ['No reference', 'warning'], UNKNOWN_REFERENCE: ['Unknown reference', 'error'],
};
const BANKS = ['CRDB', 'NMB', 'NBC', 'STANBIC', 'EXIM', 'ABSA', 'AZANIA', 'DTB', 'EQUITY', 'KCB', 'TCB', 'PBZ', 'AMANA', 'MKOMBOZI'];

/**
 * Msingi's bank statement (CSV or Excel export from the bank): every credit quoting an invoice's payment reference, or its BillPay
 * control number, is recorded at once (and what it pays for delivered); importing the same statement again changes nothing.
 */
export function StatementImportDialog({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [bank, setBank] = useState('CRDB');
  const [file, setFile] = useState<File | null>(null);
  const run = useMutation({
    mutationFn: () => { const f = new FormData(); f.append('file', file as File); f.append('provider', bank);
      return uploadForm<StatementImport>('/api/platform/billing/statements', 'platform', f, 'POST'); },
    onSuccess: onDone,
  });
  const r = run.data;
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>Import a bank statement</DialogTitle>
      <DialogContent>
        {!r ? (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Typography variant="body2" color="text.secondary">
              Export the statement of Msingi's account from the bank (CSV or Excel, headers in the first row: a date, the narration and the credit).
              Every credit that quotes an invoice's payment reference is recorded and receipted; the institution's SMS bundle or access follows by itself.
              Importing the same statement again records nothing twice.
            </Typography>
            <TextField select label="Bank" value={bank} onChange={(e) => setBank(e.target.value)} sx={{ maxWidth: 240 }}>
              {BANKS.map((b) => <MenuItem key={b} value={b}>{b}</MenuItem>)}
            </TextField>
            <Button variant="outlined" component="label" sx={{ alignSelf: 'flex-start' }}>
              {file ? file.name : 'Choose the statement file'}
              <input hidden type="file" accept=".csv,.xlsx" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            </Button>
            <ErrorAlert error={run.error} />
          </Stack>
        ) : (
          <Stack spacing={1.5} sx={{ mt: 1 }}>
            <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">
              <Chip color="success" label={`Recorded: ${r.recorded}`} />
              <Chip label={`Already recorded: ${r.already}`} />
              <Chip color={r.unmatched ? 'warning' : 'default'} label={`Not matched: ${r.unmatched}`} />
            </Stack>
            <Box sx={{ overflowX: 'auto' }}>
              <Table size="small">
                <TableHead><TableRow><TableCell>Row</TableCell><TableCell>Date</TableCell><TableCell align="right">Amount</TableCell><TableCell>Narration</TableCell><TableCell>Outcome</TableCell></TableRow></TableHead>
                <TableBody>
                  {r.lines.map((l) => (
                    <TableRow key={l.row}>
                      <TableCell>{l.row}</TableCell>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{l.date ?? '—'}</TableCell>
                      <TableCell align="right">{l.amount.toLocaleString()}</TableCell>
                      <TableCell sx={{ maxWidth: 320 }}>{l.text}</TableCell>
                      <TableCell>
                        <Chip size="small" color={OUTCOME[l.outcome]?.[1] ?? 'default'} label={OUTCOME[l.outcome]?.[0] ?? l.outcome} />
                        {l.receiptNo && <Typography variant="caption" display="block" color="text.secondary">{l.receiptNo}{l.invoices ? ` · ${l.invoices}` : ''}</Typography>}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Box>
            {r.unmatched > 0 && <Alert severity="info" icon={false}>Lines without a valid reference are not Msingi invoice payments, or the payer did not quote the reference: record those by hand with Record payment if they are.</Alert>}
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        {!r && <Button variant="contained" disabled={!file || run.isPending} onClick={() => run.mutate()}>{run.isPending ? 'Importing…' : 'Import'}</Button>}
      </DialogActions>
    </Dialog>
  );
}

interface ServiceView {
  id: number; code: string; name: string; description?: string; unitLabel: string; unitPrice: number; minQuantity: number; maxQuantity: number;
  vatPercent: number; payMethods: string; delivery: string; smsPerUnit?: number; active: boolean; updatedByName?: string; updatedAt?: string;
}
interface OrderView {
  id: number; institution: string; service: string; unitLabel: string; quantity: number; invoiceNo: string; paymentReference: string; total: number;
  status: string; delivery: string; note?: string; requestedByName?: string; requestedAt: string; paidAt?: string; deliveredAt?: string; deliveredByName?: string; deliveryNote?: string;
}
const PAY_LABEL: Record<string, string> = { BOTH: 'Mobile money or bank', MOBILE: 'Mobile money only', BANK: 'Bank only' };
const ORDER_CHIP: Record<string, ['default' | 'warning' | 'success' | 'info', string]> = {
  REQUESTED: ['default', 'Waiting for payment'], PAID: ['warning', 'Paid: to deliver'], DELIVERED: ['success', 'Delivered'], CANCELLED: ['default', 'Lapsed'],
};
const tzsAmount = (n: number) => `TZS ${Math.round(n).toLocaleString()}`;

/** Console › Billing › Services: what Msingi sells besides the subscription, and the paid requests to deliver. */
export function ServicesTab() {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['platform', 'billing', 'services'], queryFn: () => api<ServiceView[]>('/api/platform/billing/services', platform) });
  const [view, setView] = useState('PAID');
  const orders = useQuery({ queryKey: ['platform', 'billing', 'service-orders', view], queryFn: () => api<OrderView[]>(`/api/platform/billing/service-orders?status=${view}`, platform) });
  const [editing, setEditing] = useState<ServiceView | 'new' | null>(null);
  const [delivering, setDelivering] = useState<OrderView | null>(null);
  return (
    <Stack spacing={2.5}>
      <Card><CardContent>
        <Stack direction="row" alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6" sx={{ flex: 1 }}>Services</Typography>
          <Button variant="contained" onClick={() => setEditing('new')}>New service</Button>
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          What institutions can request from Msingi under Administration › Msingi subscription › Services. The invoice is issued at once with its payment
          reference and the payment method chosen here; when it is paid, an SMS pack is added to the institution's bundle by itself, and any other service
          comes to you below to deliver.
        </Typography>
        {!list.data ? <Loading /> : list.data.length === 0 ? <Typography color="text.secondary">No services yet.</Typography> : (
          <Box sx={{ overflowX: 'auto' }}><Table size="small">
            <TableHead><TableRow><TableCell>Service</TableCell><TableCell align="right">Price</TableCell><TableCell>Quantity</TableCell><TableCell>Paid by</TableCell><TableCell>Delivery</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>{list.data.map((s) => (
              <TableRow key={s.id} sx={{ opacity: s.active ? 1 : 0.5 }}>
                <TableCell>{s.name}<Typography variant="caption" display="block" color="text.secondary">{s.code}{s.active ? '' : ' · not offered'}</Typography></TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{tzsAmount(s.unitPrice)} / {s.unitLabel}<Typography variant="caption" display="block" color="text.secondary">+ VAT {s.vatPercent}%</Typography></TableCell>
                <TableCell>{s.minQuantity === s.maxQuantity ? s.minQuantity : `${s.minQuantity}–${s.maxQuantity}`}</TableCell>
                <TableCell>{PAY_LABEL[s.payMethods]}</TableCell>
                <TableCell>{s.delivery === 'SMS_CREDIT' ? `Automatic: ${s.smsPerUnit?.toLocaleString()} SMS per ${s.unitLabel}` : 'Msingi delivers'}</TableCell>
                <TableCell align="right"><Button size="small" onClick={() => setEditing(s)}>Edit</Button></TableCell>
              </TableRow>
            ))}</TableBody>
          </Table></Box>
        )}
        <ErrorAlert error={list.error} />
      </CardContent></Card>
      <Card><CardContent>
        <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1 }} flexWrap="wrap" useFlexGap>
          <Typography variant="h6" sx={{ flex: 1 }}>Requests</Typography>
          <TextField select size="small" value={view} onChange={(e) => setView(e.target.value)} sx={{ minWidth: 200 }}>
            <MenuItem value="PAID">Paid: to deliver</MenuItem><MenuItem value="REQUESTED">Waiting for payment</MenuItem>
            <MenuItem value="DELIVERED">Delivered</MenuItem><MenuItem value="all">All</MenuItem>
          </TextField>
        </Stack>
        {!orders.data ? <Loading /> : orders.data.length === 0 ? <Typography color="text.secondary">None.</Typography> : (
          <Box sx={{ overflowX: 'auto' }}><Table size="small">
            <TableHead><TableRow><TableCell>Institution</TableCell><TableCell>Service</TableCell><TableCell>Invoice</TableCell><TableCell align="right">Total</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>{orders.data.map((o) => (
              <TableRow key={o.id}>
                <TableCell>{o.institution}<Typography variant="caption" display="block" color="text.secondary">{o.requestedByName} · {formatDateTime(o.requestedAt)}</Typography></TableCell>
                <TableCell>{o.quantity} × {o.service}{o.note && <Typography variant="caption" display="block" color="text.secondary">“{o.note}”</Typography>}</TableCell>
                <TableCell sx={{ fontFamily: 'monospace' }}>{o.invoiceNo}</TableCell>
                <TableCell align="right">{tzsAmount(o.total)}</TableCell>
                <TableCell><Chip size="small" color={ORDER_CHIP[o.status]?.[0]} label={ORDER_CHIP[o.status]?.[1] ?? o.status} />
                  {o.deliveredAt && <Typography variant="caption" display="block" color="text.secondary">{o.deliveredByName} · {formatDateTime(o.deliveredAt)}</Typography>}</TableCell>
                <TableCell align="right">{o.status === 'PAID' && <Button size="small" variant="contained" onClick={() => setDelivering(o)}>Mark delivered</Button>}</TableCell>
              </TableRow>
            ))}</TableBody>
          </Table></Box>
        )}
      </CardContent></Card>
      {editing && <ServiceDialog s={editing === 'new' ? null : editing} onClose={() => setEditing(null)} onDone={() => { setEditing(null); qc.invalidateQueries({ queryKey: ['platform', 'billing', 'services'] }); }} />}
      {delivering && <DeliveredDialog o={delivering} onClose={() => setDelivering(null)} onDone={() => { setDelivering(null); qc.invalidateQueries({ queryKey: ['platform', 'billing', 'service-orders'] }); }} />}
    </Stack>
  );
}

function ServiceDialog({ s, onClose, onDone }: { s: ServiceView | null; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ code: s?.code ?? '', name: s?.name ?? '', description: s?.description ?? '', unitLabel: s?.unitLabel ?? 'unit',
    unitPrice: s ? String(s.unitPrice) : '', minQuantity: String(s?.minQuantity ?? 1), maxQuantity: String(s?.maxQuantity ?? 1), vatPercent: String(s?.vatPercent ?? 18),
    payMethods: s?.payMethods ?? 'BOTH', delivery: s?.delivery ?? 'MSINGI_DELIVERS', smsPerUnit: s?.smsPerUnit ? String(s.smsPerUnit) : '', active: s?.active ?? true });
  const save = useMutation({
    mutationFn: () => api<ServiceView>(s ? `/api/platform/billing/services/${s.id}` : '/api/platform/billing/services', { ...platform, method: s ? 'PUT' : 'POST',
      body: { ...f, unitPrice: Number(f.unitPrice), minQuantity: Number(f.minQuantity), maxQuantity: Number(f.maxQuantity), vatPercent: Number(f.vatPercent),
        smsPerUnit: f.delivery === 'SMS_CREDIT' ? Number(f.smsPerUnit) : null } }),
    onSuccess: onDone,
  });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{s ? `Edit ${s.name}` : 'New service'}</DialogTitle>
      <DialogContent>
        <Grid container spacing={2} sx={{ mt: 0.5 }}>
          <Grid size={{ xs: 12, sm: 8 }}><TextField fullWidth label="Name" value={f.name} onChange={set('name')} /></Grid>
          <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="Code" value={f.code} onChange={set('code')} /></Grid>
          <Grid size={12}><TextField fullWidth multiline minRows={2} label="Description (shown to institutions)" value={f.description} onChange={set('description')} /></Grid>
          <Grid size={{ xs: 6, sm: 4 }}><TextField fullWidth label="One is a…" value={f.unitLabel} onChange={set('unitLabel')} helperText="e.g. day, pack" /></Grid>
          <Grid size={{ xs: 6, sm: 4 }}><TextField fullWidth label="Price of one (TZS)" type="number" value={f.unitPrice} onChange={set('unitPrice')} /></Grid>
          <Grid size={{ xs: 6, sm: 4 }}><TextField fullWidth label="VAT %" type="number" value={f.vatPercent} onChange={set('vatPercent')} /></Grid>
          <Grid size={{ xs: 6, sm: 4 }}><TextField fullWidth label="Least at a time" type="number" value={f.minQuantity} onChange={set('minQuantity')} /></Grid>
          <Grid size={{ xs: 6, sm: 4 }}><TextField fullWidth label="Most at a time" type="number" value={f.maxQuantity} onChange={set('maxQuantity')} /></Grid>
          <Grid size={{ xs: 12, sm: 4 }}>
            <TextField select fullWidth label="Paid by" value={f.payMethods} onChange={set('payMethods')}>
              {Object.entries(PAY_LABEL).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid size={{ xs: 12, sm: 8 }}>
            <TextField select fullWidth label="Delivered" value={f.delivery} onChange={set('delivery')}>
              <MenuItem value="SMS_CREDIT">Automatically: SMS added to the bundle</MenuItem>
              <MenuItem value="MSINGI_DELIVERS">By Msingi (staff told when paid)</MenuItem>
            </TextField>
          </Grid>
          {f.delivery === 'SMS_CREDIT' && <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="SMS per one" type="number" value={f.smsPerUnit} onChange={set('smsPerUnit')} /></Grid>}
          <Grid size={12}><FormControlLabel control={<Switch checked={f.active} onChange={(e) => setF({ ...f, active: e.target.checked })} />} label="Offered to institutions" /></Grid>
        </Grid>
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || !f.name || !f.code} onClick={() => save.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

function DeliveredDialog({ o, onClose, onDone }: { o: OrderView; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('');
  const m = useMutation({ mutationFn: () => api(`/api/platform/billing/service-orders/${o.id}/delivered`, { ...platform, method: 'POST', body: { note } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Delivered to {o.institution}?</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 2 }}>{o.quantity} × {o.service} (invoice {o.invoiceNo}).</Typography>
        <TextField fullWidth multiline minRows={2} label="Note (optional)" value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. Training held on 20 November" />
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={m.isPending} onClick={() => m.mutate()}>Mark delivered</Button></DialogActions>
    </Dialog>
  );
}
