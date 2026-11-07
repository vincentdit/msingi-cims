import DownloadIcon from '@mui/icons-material/DownloadOutlined';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, download } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { CYCLE_LABEL, KIND_LABEL, day, period, providerLabel, tzs, type Account, type Contract, type Statement } from '../../components/billing/billing';
import { InvoiceStatusChip, PaymentTable, StatementDialog } from '../../components/billing/BillingParts';
import { tr } from '../../locale/ui';
import { PayInvoice } from './BuySmsDialog';
import MenuItem from '@mui/material/MenuItem';
import TextField from '@mui/material/TextField';

interface ServiceView { id: number; name: string; description?: string; unitLabel: string; unitPrice: number; minQuantity: number; maxQuantity: number; vatPercent: number; delivery: string; smsPerUnit?: number }
interface OrderView { id: number; service: string; quantity: number; invoiceId: number; invoiceNo: string; total: number; status: string; requestedAt: string; deliveredAt?: string; deliveryNote?: string }
const ORDER_LABEL: Record<string, ['default' | 'warning' | 'success', string]> = {
  REQUESTED: ['warning', 'Waiting for payment'], PAID: ['default', 'Paid: Msingi will deliver'], DELIVERED: ['success', 'Delivered'], CANCELLED: ['default', 'Lapsed'],
};

/** Services from Msingi besides the subscription: request one, pay its invoice, and it is delivered when paid. */
function ServicesCard({ onPay }: { onPay: (invoiceId: number) => void }) {
  const qc = useQueryClient();
  const services = useQuery({ queryKey: ['msingi-services'], queryFn: () => api<ServiceView[]>('/api/msingi-billing/services', staff) });
  const orders = useQuery({ queryKey: ['msingi-service-orders'], queryFn: () => api<OrderView[]>('/api/msingi-billing/service-orders', staff) });
  const [asking, setAsking] = useState<ServiceView | null>(null);
  const [qty, setQty] = useState('1');
  const [note, setNote] = useState('');
  const request = useMutation({
    mutationFn: () => api<{ invoice: { id: number } }>('/api/msingi-billing/service-orders', { ...staff, method: 'POST', body: { serviceId: asking?.id, quantity: Number(qty), note } }),
    onSuccess: (v) => { setAsking(null); qc.invalidateQueries({ queryKey: ['msingi-service-orders'] }); qc.invalidateQueries({ queryKey: ['msingi-billing'] }); onPay(v.invoice.id); },
  });
  if (!services.data?.length && !orders.data?.length) return null;
  const n = Number(qty) || 0;
  return (
    <Card>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 0.5 }}>Services</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          Request a service from Msingi: the invoice is issued at once with its payment reference, and the service is delivered as soon as it is paid.
        </Typography>
        <Stack spacing={1}>
          {services.data?.map((sv) => (
            <Stack key={sv.id} direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }} sx={{ p: 1.25, border: 1, borderColor: 'divider', borderRadius: 1.5 }}>
              <Box sx={{ flex: 1 }}>
                <Typography fontWeight={600}>{sv.name}</Typography>
                {sv.description && <Typography variant="body2" color="text.secondary">{sv.description}</Typography>}
                <Typography variant="caption" color="text.secondary">{`TZS ${tzs(sv.unitPrice)} per ${sv.unitLabel} + VAT ${sv.vatPercent}%`}</Typography>
              </Box>
              <Button variant="outlined" onClick={() => { setAsking(sv); setQty(String(sv.minQuantity)); setNote(''); request.reset(); }}>Request</Button>
            </Stack>
          ))}
        </Stack>
        {!!orders.data?.length && (
          <Table size="small" sx={{ mt: 2 }}>
            <TableHead><TableRow><TableCell>Requested</TableCell><TableCell>Service</TableCell><TableCell align="right">Total</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>{orders.data.map((o) => (
              <TableRow key={o.id}>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>{day(o.requestedAt.slice(0, 10))}</TableCell>
                <TableCell>{o.quantity} × {o.service}{o.deliveryNote && <Typography variant="caption" display="block" color="text.secondary">{o.deliveryNote}</Typography>}</TableCell>
                <TableCell align="right">{tzs(o.total)}</TableCell>
                <TableCell><Chip size="small" color={ORDER_LABEL[o.status]?.[0]} label={ORDER_LABEL[o.status]?.[1] ?? o.status} /></TableCell>
                <TableCell align="right">{o.status === 'REQUESTED' && <Button size="small" variant="contained" onClick={() => onPay(o.invoiceId)}>Pay</Button>}</TableCell>
              </TableRow>
            ))}</TableBody>
          </Table>
        )}
      </CardContent>
      {asking && (
        <Dialog open onClose={() => setAsking(null)} maxWidth="xs" fullWidth>
          <DialogTitle>{asking.name}</DialogTitle>
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              {asking.minQuantity !== asking.maxQuantity && (
                <TextField select label={`How many (${asking.unitLabel})`} value={qty} onChange={(e) => setQty(e.target.value)}>
                  {Array.from({ length: Math.min(asking.maxQuantity - asking.minQuantity + 1, 50) }, (_, i) => asking.minQuantity + i).map((q) => <MenuItem key={q} value={String(q)}>{q}</MenuItem>)}
                </TextField>
              )}
              <TextField label="Note to Msingi (optional)" multiline minRows={2} value={note} onChange={(e) => setNote(e.target.value)} inputProps={{ maxLength: 500 }} />
              <Typography>{`Total: TZS ${tzs(Math.round(n * asking.unitPrice * (1 + asking.vatPercent / 100)))} with VAT`}</Typography>
              <ErrorAlert error={request.error} />
            </Stack>
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setAsking(null)}>Cancel</Button>
            <Button variant="contained" disabled={request.isPending} onClick={() => request.mutate()}>Get the invoice</Button>
          </DialogActions>
        </Dialog>
      )}
    </Card>
  );
}

const staff = { realm: 'staff' as const };

/** The institution's subscription to CIMS: what it pays Msingi, its invoices and receipts, and how to pay (administrators). */
export default function MsingiSubscriptionPage() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['msingi-billing'], queryFn: () => api<Account>('/api/msingi-billing', staff) });
  const [statement, setStatement] = useState<Statement | null>(null);
  const openStatement = useMutation({ mutationFn: () => api<Statement>('/api/msingi-billing/statement', staff), onSuccess: setStatement });
  const [paying, setPaying] = useState<number | null>(null);
  if (!q.data) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  const a = q.data;
  const unpaid = a.invoices.filter((i) => i.status === 'ISSUED' || i.status === 'PARTLY_PAID');
  return (
    <>
      <PageTitle title="Msingi subscription" subtitle="What your institution pays Msingi Technologies for CIMS, your invoices and receipts, and how to pay."
        action={<Button variant="outlined" onClick={() => openStatement.mutate()} disabled={openStatement.isPending}>Statement</Button>} />
      {a.overdue > 0 && (
        <Alert severity="error" sx={{ mb: 2.5 }}>
          {`TZS ${tzs(a.overdue)} is overdue. Please pay it to avoid interruption of your CIMS service, quoting the payment reference of each invoice.`}
        </Alert>
      )}
      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, sm: 4 }}><Figure label="To be paid" value={`TZS ${tzs(a.balance)}`} /></Grid>
        <Grid size={{ xs: 12, sm: 4 }}><Figure label="Overdue" value={`TZS ${tzs(a.overdue)}`} tone={a.overdue > 0 ? 'error.main' : undefined} /></Grid>
        <Grid size={{ xs: 12, sm: 4 }}><Figure label="Credit" value={`TZS ${tzs(a.credit)}`} tone={a.credit > 0 ? 'success.main' : undefined} sub="Paid ahead; used for your next invoice" /></Grid>

        <Grid size={12}>
          <Card sx={{ mb: 2.5 }}>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>Invoices</Typography>
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Invoice</TableCell><TableCell>For</TableCell><TableCell align="right">Total</TableCell><TableCell align="right">To pay</TableCell>
                      <TableCell>Due</TableCell><TableCell>Status</TableCell><TableCell />
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {a.invoices.map((i) => (
                      <TableRow key={i.id}>
                        <TableCell sx={{ whiteSpace: 'nowrap' }}>
                          <Box sx={{ fontFamily: 'monospace' }}>{i.invoiceNo}</Box>
                          {i.paymentReference && i.status !== 'PAID' && i.status !== 'CANCELLED' && (
                            <Typography variant="caption" color="text.secondary">{`Reference ${i.paymentReference}`}</Typography>
                          )}
                        </TableCell>
                        <TableCell>{KIND_LABEL[i.kind]}<Typography variant="caption" color="text.secondary" component="div">{period(i.periodFrom, i.periodTo)}</Typography></TableCell>
                        <TableCell align="right">{tzs(i.total)}</TableCell>
                        <TableCell align="right">{i.status === 'CANCELLED' ? '—' : tzs(i.balance)}</TableCell>
                        <TableCell sx={{ whiteSpace: 'nowrap' }}>{day(i.dueDate)}</TableCell>
                        <TableCell><InvoiceStatusChip status={i.status} overdue={i.daysOverdue} /></TableCell>
                        <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                          <Tooltip title={tr('Invoice (PDF)')}>
                            <IconButton size="small" aria-label={tr('Invoice (PDF)')} onClick={() => download(`/api/msingi-billing/invoices/${i.id}/pdf`, 'staff', `invoice-${i.invoiceNo}.pdf`)}>
                              <DownloadIcon fontSize="small" />
                            </IconButton>
                          </Tooltip>
                          {unpaid.includes(i) && <Button size="small" variant="contained" sx={{ ml: 0.5 }} onClick={() => setPaying(i.id)}>Pay</Button>}
                        </TableCell>
                      </TableRow>
                    ))}
                    {a.invoices.length === 0 && <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 2 }}>No invoices yet.</Typography></TableCell></TableRow>}
                  </TableBody>
                </Table>
              </TableContainer>
              <ErrorAlert error={openStatement.error} />
            </CardContent>
          </Card>
          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>Payments</Typography>
              <PaymentTable rows={a.payments} realm="staff" />
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 12, md: 6 }}>
          <Card sx={{ height: '100%' }}>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>How to pay</Typography>
              {a.payTo.channels.length === 0 ? (
                <Typography variant="body2" color="text.secondary">Msingi will tell you where to pay.</Typography>
              ) : (
                <Stack spacing={1}>
                  {a.payTo.channels.map((c, i) => (
                    <Box key={i} sx={{ p: 1.25, border: 1, borderColor: 'divider', borderRadius: 1.5 }}>
                      <Typography variant="body2" color="text.secondary">{providerLabel(c.provider)}</Typography>
                      <Typography fontWeight={700} sx={{ fontFamily: 'monospace', fontSize: 16 }}>{c.accountNumber}</Typography>
                      <Typography variant="caption" color="text.secondary">{[c.accountName, c.branch].filter(Boolean).join(' · ')}</Typography>
                    </Box>
                  ))}
                </Stack>
              )}
              <Alert severity="info" icon={false} sx={{ mt: 1.5 }}>Quote the invoice's payment reference so the payment is matched to it. Your receipt comes by email.</Alert>
              <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>
                {[a.payTo.companyName, a.payTo.tin ? `TIN ${a.payTo.tin}` : null, a.payTo.phone, a.payTo.email].filter(Boolean).join(' · ')}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 6 }}><ContractCard c={a.contract} /></Grid>
        <Grid size={12}><ServicesCard onPay={setPaying} /></Grid>
      </Grid>
      {paying != null && (
        <Dialog open onClose={() => { setPaying(null); qc.invalidateQueries({ queryKey: ['msingi-billing'] }); }} maxWidth="sm" fullWidth>
          <DialogTitle>Pay Msingi</DialogTitle>
          <DialogContent><PayInvoice id={paying} /></DialogContent>
          <DialogActions><Button onClick={() => { setPaying(null); qc.invalidateQueries({ queryKey: ['msingi-billing'] }); }}>Close</Button></DialogActions>
        </Dialog>
      )}
      {statement && <StatementDialog st={statement} realm="staff" onClose={() => setStatement(null)} />}
    </>
  );
}

function Figure({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: string }) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
        <Typography sx={{ fontFamily: 'Sora', fontSize: 24, fontWeight: 700, color: tone ?? 'text.primary' }}>{value}</Typography>
        {sub && <Typography variant="caption" color="text.secondary">{sub}</Typography>}
      </CardContent>
    </Card>
  );
}

function ContractCard({ c }: { c?: Contract }) {
  const rows: [string, string][] = !c ? [] : [
    ['Contract', c.reference ?? '—'], ['From', day(c.startDate)], ...(c.endDate ? [['To', day(c.endDate)] as [string, string]] : []),
    ...(c.setupFee > 0 ? [['Set-up (once)', `TZS ${tzs(c.setupFee)}`] as [string, string]] : []),
    ...(c.annualFee > 0 ? [['Annual licence', `TZS ${tzs(c.annualFee)}`] as [string, string]] : []),
    ...(c.studentFee > 0 ? [['Per active student a year', `TZS ${tzs(c.studentFee)}`] as [string, string]] : []),
    ...(c.applicationFee > 0 ? [['Per paid application', `TZS ${tzs(c.applicationFee)}`] as [string, string]] : []),
    ...(c.applicationPercent > 0 ? [['Share of application fees', `${c.applicationPercent}%`] as [string, string]] : []),
    ['Usage invoiced', CYCLE_LABEL[c.usageCycle]], ['VAT', `${c.vatPercent}%`], ['Pay within', `${c.paymentTermsDays} days`],
  ];
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 1 }}>Your contract</Typography>
        {!c ? <Typography variant="body2" color="text.secondary">No contract is recorded yet.</Typography> : rows.map(([l, v]) => (
          <Stack key={l} direction="row" justifyContent="space-between" spacing={2} sx={{ py: 0.4 }}>
            <Typography variant="body2" color="text.secondary">{l}</Typography>
            <Typography variant="body2" sx={{ textAlign: 'right' }}>{v}</Typography>
          </Stack>
        ))}
        {c?.billingEmail && <Chip size="small" variant="outlined" sx={{ mt: 1 }} label={`Invoices go to ${c.billingEmail}`} />}
      </CardContent>
    </Card>
  );
}

