import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import DownloadIcon from '@mui/icons-material/Download';
import PhonelinkRingIcon from '@mui/icons-material/PhonelinkRing';
import {
  Alert, Box, Button, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, Divider, InputAdornment, MenuItem, Stack,
  Table, TableBody, TableCell, TableRow, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, download, formatDate, formatTzs } from '../../api/client';
import { ErrorAlert, Loading } from '../../components/common';
import { NETWORKS, guessNetwork } from '../../components/HowToPay';

const staff = { realm: 'staff' as const };

interface Channel { provider: string; accountName?: string; accountNumber?: string; branch?: string }
interface InvoiceRow {
  id: number; invoiceNo: string; status: string; total: number; paidAmount: number; balance: number; issueDate: string; dueDate: string; paymentReference: string;
}
interface InvoiceView { invoice: InvoiceRow & { kind?: string }; lines: { description: string; quantity: number }[]; payMethods?: string; payTo?: Channel[] }
interface PayOptions { phonePrompt: boolean; phoneNetwork: boolean; phoneProvider?: string; canSimulate: boolean }
interface SmsOffer {
  pricePerSms: number; minUnits: number; vatPercent: number; payTo: Channel[]; phonePrompt: boolean; phoneNetwork: boolean; phoneProvider?: string;
  waiting: InvoiceRow[]; canSimulate: boolean;
}
interface PhonePayment { id: number; status: 'PENDING' | 'PAID' | 'FAILED' | 'EXPIRED'; phone: string; amount: number; message?: string; invoiceStatus: string }

const PROVIDER: Record<string, string> = {
  CRDB: 'CRDB Bank', NMB: 'NMB Bank', NBC: 'NBC Bank', EXIM: 'Exim Bank', STANBIC: 'Stanbic Bank', AZANIA: 'Azania Bank', ABSA: 'Absa Bank',
  MPESA: 'M-Pesa', MIXX_BY_YAS: 'Mixx by Yas', TIGOPESA: 'Mixx by Yas', AIRTEL_MONEY: 'Airtel Money', AIRTELMONEY: 'Airtel Money', HALOPESA: 'HaloPesa',
};
const group = (ref: string) => ref.replace(/(\d{4})(?=\d)/g, '$1 ');
const units = (v: InvoiceView) => Number(v.lines[0]?.quantity ?? 0);

/**
 * Buying an SMS bundle from Msingi: the invoice is issued at once with Msingi's payment reference; it is paid by bank or mobile money
 * quoting the reference, or by a prompt on a phone, and the SMS are added to the bundle the moment the payment reaches Msingi.
 */
export function BuySmsDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const offer = useQuery({ queryKey: ['staff', 'sms-offer'], queryFn: () => api<SmsOffer>('/api/msingi-billing/sms-offer', staff) });
  const [count, setCount] = useState('');
  const [invoiceId, setInvoiceId] = useState<number | null>(null);
  const o = offer.data;
  useEffect(() => { if (o && !count) setCount(String(Math.max(o.minUnits, 5000))); }, [o]); // eslint-disable-line react-hooks/exhaustive-deps
  const buy = useMutation({
    mutationFn: () => api<InvoiceView>('/api/msingi-billing/sms-bundles', { ...staff, method: 'POST', body: { units: Number(count) } }),
    onSuccess: (v) => { setInvoiceId(v.invoice.id); qc.invalidateQueries({ queryKey: ['staff', 'sms-offer'] }); },
  });

  const n = Number(count) || 0;
  const subtotal = o ? Math.round(n * o.pricePerSms) : 0;
  const vat = o ? Math.round((subtotal * o.vatPercent) / 100) : 0;
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Buy SMS</DialogTitle>
      <DialogContent>
        {offer.isLoading ? <Loading /> : offer.error ? <ErrorAlert error={offer.error} /> : !o ? null : invoiceId != null ? (
          <PayInvoice id={invoiceId} />
        ) : (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Typography variant="body2" color="text.secondary">
              Your SMS go out through Msingi's SMS account. Choose the bundle: Msingi's invoice is issued at once with its payment reference,
              and the SMS are added to your balance automatically as soon as the payment reaches Msingi.
            </Typography>
            <TextField label="Number of SMS" type="number" value={count} onChange={(e) => setCount(e.target.value)}
              error={n > 0 && n < o.minUnits}
              helperText={`The smallest bundle is ${o.minUnits.toLocaleString()} SMS.`}
              slotProps={{ input: { endAdornment: <InputAdornment position="end">SMS</InputAdornment> } }} />
            <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">
              {[2000, 5000, 10000, 20000, 50000].filter((x) => x >= o.minUnits).map((x) => (
                <Chip key={x} label={x.toLocaleString()} variant={n === x ? 'filled' : 'outlined'} color={n === x ? 'primary' : 'default'} onClick={() => setCount(String(x))} />
              ))}
            </Stack>
            <Table size="small"><TableBody>
              <TableRow><TableCell>Price per SMS</TableCell><TableCell align="right">{formatTzs(o.pricePerSms)}</TableCell></TableRow>
              <TableRow><TableCell>{n.toLocaleString()} SMS</TableCell><TableCell align="right">{formatTzs(subtotal)}</TableCell></TableRow>
              <TableRow><TableCell>VAT {o.vatPercent}%</TableCell><TableCell align="right">{formatTzs(vat)}</TableCell></TableRow>
              <TableRow><TableCell sx={{ fontWeight: 700 }}>Total</TableCell><TableCell align="right" sx={{ fontWeight: 700 }}>{formatTzs(subtotal + vat)}</TableCell></TableRow>
            </TableBody></Table>
            <ErrorAlert error={buy.error} />
            {o.waiting.length > 0 && (
              <Box>
                <Divider sx={{ mb: 1.5 }} />
                <Typography variant="subtitle2" sx={{ mb: 0.5 }}>Waiting for payment</Typography>
                <Table size="small"><TableBody>
                  {o.waiting.map((w) => (
                    <TableRow key={w.id} hover sx={{ cursor: 'pointer' }} onClick={() => setInvoiceId(w.id)}>
                      <TableCell>{w.invoiceNo}</TableCell>
                      <TableCell>{group(w.paymentReference)}</TableCell>
                      <TableCell align="right">{formatTzs(w.balance)}</TableCell>
                      <TableCell align="right"><Button size="small">Pay</Button></TableCell>
                    </TableRow>
                  ))}
                </TableBody></Table>
              </Box>
            )}
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        {invoiceId != null && o && <Button onClick={() => { setInvoiceId(null); buy.reset(); }} sx={{ mr: 'auto' }}>Back</Button>}
        <Button onClick={onClose}>Close</Button>
        {invoiceId == null && o && (
          <Button variant="contained" disabled={n < o.minUnits || buy.isPending} onClick={() => buy.mutate()}>
            {buy.isPending ? 'Issuing the invoice…' : 'Get the invoice'}
          </Button>
        )}
      </DialogActions>
    </Dialog>
  );
}

/**
 * A Msingi invoice to pay: its payment reference (control number), the accounts it may be paid into, a prompt on a phone where
 * mobile money is allowed, and what was delivered the moment it is paid. Used by Buy SMS and the Msingi subscription page.
 */
export function PayInvoice({ id }: { id: number }) {
  const qc = useQueryClient();
  const opts = useQuery({ queryKey: ['staff', 'msingi-pay-options'], queryFn: () => api<PayOptions>('/api/msingi-billing/pay-options', staff) });
  const inv = useQuery({
    queryKey: ['staff', 'msingi-invoice', id],
    queryFn: () => api<InvoiceView>(`/api/msingi-billing/invoices/${id}`, staff),
    refetchInterval: (q) => (q.state.data?.invoice.status === 'PAID' || q.state.data?.invoice.status === 'CANCELLED' ? false : 8000),
  });
  const paid = inv.data?.invoice.status === 'PAID';
  useEffect(() => {
    if (paid) {
      qc.invalidateQueries({ queryKey: ['staff', 'messaging', 'overview'] });
      qc.invalidateQueries({ queryKey: ['staff', 'sms-offer'] });
      qc.invalidateQueries({ queryKey: ['msingi-billing'] });
    }
  }, [paid]); // eslint-disable-line react-hooks/exhaustive-deps
  const simulate = useMutation({
    mutationFn: () => api(`/api/msingi-billing/invoices/${id}/simulate-payment`, { ...staff, method: 'POST' }),
    onSuccess: () => inv.refetch(),
  });
  if (inv.isLoading || opts.isLoading) return <Loading />;
  if (inv.error || !inv.data) return <ErrorAlert error={inv.error} />;
  const i = inv.data.invoice;
  const sms = i.kind === 'SMS';
  const payTo = inv.data.payTo ?? [];
  const o = opts.data;
  const prompt = !!o?.phonePrompt && inv.data.payMethods !== 'BANK';
  if (paid) {
    return (
      <Stack spacing={1.5} alignItems="center" sx={{ py: 3, textAlign: 'center' }}>
        <CheckCircleIcon color="success" sx={{ fontSize: 56 }} />
        <Typography variant="h6">{sms ? `${units(inv.data).toLocaleString()} SMS added to your bundle` : `Invoice ${i.invoiceNo} is paid`}</Typography>
        <Typography color="text.secondary">{sms ? `Invoice ${i.invoiceNo} is paid. ` : ''}Msingi's receipt has been emailed to your billing contact.</Typography>
      </Stack>
    );
  }
  if (i.status === 'CANCELLED') return <Alert severity="info" sx={{ mt: 1 }}>{sms ? 'This invoice lapsed unpaid. Buy a new bundle instead.' : 'This invoice was cancelled.'}</Alert>;
  return (
    <Stack spacing={2} sx={{ mt: 1 }}>
      <Box sx={{ p: 2, borderRadius: 2, bgcolor: 'action.hover' }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between">
          <Box>
            <Typography variant="body2" color="text.secondary">Payment reference (control number)</Typography>
            <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 26, letterSpacing: 1 }}>{group(i.paymentReference)}</Typography>
          </Box>
          <Box sx={{ textAlign: { sm: 'right' } }}>
            <Typography variant="body2" color="text.secondary">Amount to pay</Typography>
            <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 22 }}>{formatTzs(i.balance)}</Typography>
          </Box>
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          {sms ? `Invoice ${i.invoiceNo} · ${units(inv.data).toLocaleString()} SMS · due ${formatDate(i.dueDate)}` : `Invoice ${i.invoiceNo} · due ${formatDate(i.dueDate)}`}
        </Typography>
        <Button size="small" startIcon={<DownloadIcon />} sx={{ mt: 0.5, ml: -0.5 }}
          onClick={() => download(`/api/msingi-billing/invoices/${id}/pdf`, 'staff', `${i.invoiceNo}.pdf`)}>Invoice (PDF)</Button>
      </Box>

      {prompt && o && <PhonePrompt invoiceId={id} amount={i.balance} needsNetwork={o.phoneNetwork} provider={o.phoneProvider} onPaid={() => inv.refetch()} />}

      {payTo.length > 0 && (
        <Box>
          <Typography variant="subtitle2" sx={{ mb: 0.5 }}>{prompt ? 'Or pay to Msingi, quoting the payment reference' : 'Pay to Msingi, quoting the payment reference'}</Typography>
          <Table size="small"><TableBody>
            {payTo.map((c) => c.provider === 'BILLPAY' ? (
              <TableRow key={c.provider + c.accountNumber}>
                <TableCell>BillPay control number<Typography variant="caption" display="block" color="text.secondary">{c.branch}</Typography></TableCell>
                <TableCell>{c.accountName}</TableCell>
                <TableCell sx={{ fontWeight: 700, fontFamily: 'monospace', fontSize: 15 }}>{group(c.accountNumber ?? '')}</TableCell>
              </TableRow>
            ) : (
              <TableRow key={c.provider + c.accountNumber}>
                <TableCell>{PROVIDER[c.provider] ?? c.provider}{c.branch ? `, ${c.branch}` : ''}</TableCell>
                <TableCell>{c.accountName}</TableCell>
                <TableCell sx={{ fontWeight: 600 }}>{c.accountNumber}</TableCell>
              </TableRow>
            ))}
          </TableBody></Table>
        </Box>
      )}
      <Alert severity="info" icon={false}>
        {sms ? `Quote the reference ${group(i.paymentReference)} exactly. The SMS are added here automatically when the payment reaches Msingi: you can close this window.`
          : `Quote the reference ${group(i.paymentReference)} exactly. The payment is recorded automatically when it reaches Msingi and your receipt comes by email: you can close this window.`}
      </Alert>
      {o?.canSimulate && (
        <Box>
          <Button size="small" variant="outlined" disabled={simulate.isPending} onClick={() => simulate.mutate()}>Pay now (testing only)</Button>
          <ErrorAlert error={simulate.error} />
        </Box>
      )}
    </Stack>
  );
}

function PhonePrompt({ invoiceId, amount, needsNetwork, provider, onPaid }: {
  invoiceId: number; amount: number; needsNetwork: boolean; provider?: string; onPaid: () => void;
}) {
  const [number, setNumber] = useState('');
  const [network, setNetwork] = useState('');
  const [requestId, setRequestId] = useState<number | null>(null);
  const send = useMutation({
    mutationFn: () => api<PhonePayment>(`/api/msingi-billing/invoices/${invoiceId}/phone-payment`, { ...staff, method: 'POST', body: { phone: number, network: needsNetwork ? network : null } }),
    onSuccess: (r) => setRequestId(r.id),
  });
  const status = useQuery({
    queryKey: ['staff', 'msingi-phone-payment', requestId], enabled: requestId != null,
    queryFn: () => api<PhonePayment>(`/api/msingi-billing/phone-payments/${requestId}`, staff),
    refetchInterval: (q) => (q.state.data?.status === 'PENDING' || !q.state.data ? 3000 : false),
  });
  const r = status.data ?? send.data;
  useEffect(() => { if (r?.status === 'PAID') onPaid(); }, [r?.status]); // eslint-disable-line react-hooks/exhaustive-deps
  return (
    <Box sx={{ p: 1.5, borderRadius: 2, border: 1, borderColor: 'primary.light' }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
        <PhonelinkRingIcon color="primary" fontSize="small" />
        <Typography sx={{ fontWeight: 600, flexGrow: 1 }}>Pay by a prompt on your phone</Typography>
        {provider && <Typography variant="caption" color="text.secondary">through {provider}</Typography>}
      </Stack>
      {r?.status === 'PENDING' ? (
        <Stack direction="row" spacing={1.5} alignItems="center">
          <CircularProgress size={20} />
          <Typography variant="body2">Enter your PIN on the phone to pay {formatTzs(amount)}. This window follows the payment.</Typography>
        </Stack>
      ) : r?.status === 'PAID' ? (
        <Alert severity="success">Paid. Adding the SMS…</Alert>
      ) : (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.25 }}>
            Enter the mobile money number that will pay: it receives a prompt for {formatTzs(amount)}.
          </Typography>
          {r?.status === 'FAILED' && <Alert severity="warning" sx={{ mb: 1 }}>Not paid: {r.message ?? ''}</Alert>}
          {r?.status === 'EXPIRED' && <Alert severity="info" sx={{ mb: 1 }}>The prompt was not answered. Send it again.</Alert>}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
            <TextField size="small" label="Phone number" placeholder="0712 345 678" sx={{ flex: 1 }} value={number} inputProps={{ inputMode: 'tel' }}
              onChange={(e) => { setNumber(e.target.value); if (needsNetwork && !network) setNetwork(guessNetwork(e.target.value)); }} />
            {needsNetwork && (
              <TextField select size="small" label="Mobile money" value={network} onChange={(e) => setNetwork(e.target.value)} sx={{ minWidth: 170 }}>
                {NETWORKS.map((x) => <MenuItem key={x.code} value={x.code}>{x.label}</MenuItem>)}
              </TextField>
            )}
            <Button variant="contained" sx={{ whiteSpace: 'nowrap', flexShrink: 0 }} disabled={send.isPending || !number || (needsNetwork && !network)}
              onClick={() => { setRequestId(null); send.mutate(); }}>
              {send.isPending ? 'Sending…' : r ? 'Send again' : 'Send the prompt'}
            </Button>
          </Stack>
          <ErrorAlert error={send.error} />
        </>
      )}
    </Box>
  );
}
