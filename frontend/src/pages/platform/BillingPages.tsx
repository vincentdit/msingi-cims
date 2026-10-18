import AddIcon from '@mui/icons-material/Add';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import DownloadIcon from '@mui/icons-material/DownloadOutlined';
import EditIcon from '@mui/icons-material/Edit';
import PlayIcon from '@mui/icons-material/PlayArrowOutlined';
import SearchIcon from '@mui/icons-material/Search';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Divider, FormControlLabel, IconButton, InputAdornment,
  Link, MenuItem, Radio, RadioGroup, Stack, Switch, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow, Tabs,
  TextField, ToggleButton, ToggleButtonGroup, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, download, formatDateTime, privateImageUrl, uploadForm } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import {
  CYCLE_LABEL, KIND_LABEL, LINE_KIND, METHOD_LABEL, PROVIDERS, day, period, tzs,
  type Aging, type BillingSettings, type Channel, type Contract, type Cycle, type InvoiceRow, type InvoiceView,
  type Line, type PaymentRow, type Statement, type Usage,
} from '../../components/billing/billing';
import { tr } from '../../locale/ui';
import { AgingBar, InvoiceStatusChip, PaymentTable, StatementDialog } from '../../components/billing/BillingParts';
import { platform, type InstitutionRow } from './shared';

const today = () => new Date().toLocaleDateString('en-CA', { timeZone: 'Africa/Dar_es_Salaam' });

/** Msingi billing: what institutions owe Msingi, invoices, payments and Msingi's details on invoices. */
export function BillingPage() {
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'overview';
  const setTab = (v: string) => { const p = new URLSearchParams(params); p.set('tab', v); setParams(p, { replace: true }); };
  return (
    <>
      <PageTitle title="Billing" subtitle="What institutions pay Msingi for CIMS: contracts, invoices, payments and reminders." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2.5 }} variant="scrollable" allowScrollButtonsMobile>
        <Tab value="overview" label="Overview" />
        <Tab value="invoices" label="Invoices" />
        <Tab value="payments" label="Payments" />
        <Tab value="settings" label="Invoice details" />
      </Tabs>
      {tab === 'overview' && <OverviewTab />}
      {tab === 'invoices' && <InvoicesTab />}
      {tab === 'payments' && <PaymentsTab />}
      {tab === 'settings' && <SettingsTab />}
    </>
  );
}

// ------------------------------------------------------------------ overview

interface Overview {
  year: number; invoiced: number; collected: number; outstanding: number; overdue: number; drafts: number; contracts: number; aging: Aging;
  institutions: { tenantId: number; code: string; name: string; tenantStatus: string; contractStatus?: string; annualFee?: number; outstanding: number;
    overdue: number; oldestOverdueDays?: number; credit: number; lastPaymentAt?: string; drafts: number }[];
}

function Figure({ label, value, sub, tone }: { label: string; value: string; sub?: ReactNode; tone?: 'error' | 'success' }) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
        <Typography sx={{ fontFamily: 'Sora', fontSize: 26, fontWeight: 700, color: tone ? `${tone}.main` : 'text.primary' }}>{value}</Typography>
        {sub && <Typography variant="caption" color="text.secondary">{sub}</Typography>}
      </CardContent>
    </Card>
  );
}

function OverviewTab() {
  const qc = useQueryClient();
  const year = new Date().getFullYear();
  const [y, setY] = useState(year);
  const q = useQuery({ queryKey: ['platform', 'billing', 'overview', y], queryFn: () => api<Overview>(`/api/platform/billing/overview?year=${y}`, platform) });
  const run = useMutation({
    mutationFn: () => api<{ prepared: number; issued: number; reminders: number; suspended: number }>('/api/platform/billing/run', { ...platform, method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['platform', 'billing'] }),
  });
  if (!q.data) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  const o = q.data;
  return (
    <Stack spacing={2.5}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} justifyContent="space-between" alignItems={{ sm: 'center' }}>
        <TextField select size="small" label="Year" value={y} onChange={(e) => setY(Number(e.target.value))} sx={{ width: 140 }}>
          {[year, year - 1, year - 2].map((v) => <MenuItem key={v} value={v}>{v}</MenuItem>)}
        </TextField>
        <Tooltip title={tr('The job runs every morning by itself: it prepares the invoices that fell due and sends reminders.')}>
          <span><Button variant="outlined" startIcon={<PlayIcon />} disabled={run.isPending} onClick={() => run.mutate()}>Run billing now</Button></span>
        </Tooltip>
      </Stack>
      {run.data && (
        <Alert severity="success" onClose={() => run.reset()}>
          {`Prepared ${run.data.prepared} invoices, issued ${run.data.issued}, sent ${run.data.reminders} reminders.`}
          {run.data.suspended > 0 && ` ${run.data.suspended} suspended for non-payment.`}
        </Alert>
      )}
      <ErrorAlert error={run.error} />
      {o.drafts > 0 && (
        <Alert severity="info" action={<Button color="inherit" component={RouterLink} to="/platform/billing?tab=invoices&status=draft">Review</Button>}>
          {`${o.drafts} draft invoices are waiting to be checked and issued.`}
        </Alert>
      )}
      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}><Figure label={`Invoiced in ${o.year}`} value={`TZS ${tzs(o.invoiced)}`} sub={`${o.contracts} contracts in force`} /></Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}><Figure label={`Received in ${o.year}`} value={`TZS ${tzs(o.collected)}`} tone="success" /></Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}><Figure label="Still to be paid" value={`TZS ${tzs(o.outstanding)}`} /></Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}><Figure label="Overdue" value={`TZS ${tzs(o.overdue)}`} tone={o.overdue > 0 ? 'error' : undefined} /></Grid>
      </Grid>
      <Card>
        <CardContent>
          <Typography variant="h6" sx={{ mb: 1.5 }}>Unpaid, by age</Typography>
          <AgingBar aging={o.aging} />
        </CardContent>
      </Card>
      <Card>
        <CardContent>
          <Typography variant="h6" sx={{ mb: 1 }}>Institutions</Typography>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Institution</TableCell><TableCell>Contract</TableCell><TableCell align="right">To be paid</TableCell>
                  <TableCell align="right">Overdue</TableCell><TableCell align="right">Credit</TableCell><TableCell>Last payment</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {o.institutions.map((r) => (
                  <TableRow key={r.tenantId} hover>
                    <TableCell>
                      <Link component={RouterLink} to={`/platform/institutions/${r.tenantId}`} fontWeight={600}>{r.name}</Link>
                      <Typography variant="caption" color="text.secondary" component="div">
                        <Box component="span" sx={{ fontFamily: 'monospace' }}>{r.code}</Box>
                        {r.tenantStatus === 'SUSPENDED' && <Chip size="small" color="error" label="Suspended" sx={{ ml: 1, height: 18 }} />}
                        {r.drafts > 0 && <Chip size="small" variant="outlined" label={`${r.drafts} drafts`} sx={{ ml: 1, height: 18 }} />}
                      </Typography>
                    </TableCell>
                    <TableCell>{r.contractStatus ? (r.annualFee ? `TZS ${tzs(r.annualFee)} a year` : 'In force') : <Typography variant="body2" color="text.secondary">No contract</Typography>}</TableCell>
                    <TableCell align="right">{tzs(r.outstanding)}</TableCell>
                    <TableCell align="right" sx={r.overdue > 0 ? { color: 'error.main', fontWeight: 700 } : undefined}>
                      {tzs(r.overdue)}{r.oldestOverdueDays ? <Typography variant="caption" component="div">{`${r.oldestOverdueDays} days`}</Typography> : null}
                    </TableCell>
                    <TableCell align="right">{tzs(r.credit)}</TableCell>
                    <TableCell>{r.lastPaymentAt ? formatDateTime(r.lastPaymentAt) : '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        </CardContent>
      </Card>
    </Stack>
  );
}

// ------------------------------------------------------------------ invoices

const VIEWS = [{ value: 'all', label: 'All' }, { value: 'draft', label: 'Drafts' }, { value: 'open', label: 'Unpaid' }, { value: 'overdue', label: 'Overdue' },
  { value: 'paid', label: 'Paid' }, { value: 'cancelled', label: 'Cancelled' }];

function InvoicesTab() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const status = params.get('status') ?? 'all';
  const tenantId = params.get('tenantId') ?? '';
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [preparing, setPreparing] = useState(false);
  useEffect(() => { const h = setTimeout(() => { setQuery(q); setPage(0); }, 300); return () => clearTimeout(h); }, [q]);
  const list = useQuery({
    queryKey: ['platform', 'billing', 'invoices', status, tenantId, query, page],
    queryFn: () => api<{ rows: InvoiceRow[]; total: number }>(`/api/platform/billing/invoices?status=${status}&page=${page}&size=25`
      + (tenantId ? `&tenantId=${tenantId}` : '') + (query ? `&q=${encodeURIComponent(query)}` : ''), platform),
  });
  const setParam = (k: string, v: string) => { const p = new URLSearchParams(params); if (v) p.set(k, v); else p.delete(k); setParams(p, { replace: true }); setPage(0); };
  return (
    <Card>
      <CardContent>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} justifyContent="space-between" sx={{ mb: 2 }}>
          <Stack spacing={1.5}>
            <ToggleButtonGroup size="small" exclusive value={status} onChange={(_, v) => v && setParam('status', v)} sx={{ flexWrap: 'wrap' }}>
              {VIEWS.map((v) => <ToggleButton key={v.value} value={v.value} sx={{ textTransform: 'none' }}>{v.label}</ToggleButton>)}
            </ToggleButtonGroup>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
              <TextField size="small" placeholder="Invoice number, institution or reference" value={q} onChange={(e) => setQ(e.target.value)} sx={{ minWidth: 320 }}
                InputProps={{ startAdornment: <InputAdornment position="start"><SearchIcon /></InputAdornment> }} inputProps={{ 'aria-label': tr('Search invoices') }} />
              {tenantId && <Chip label="One institution" onDelete={() => setParam('tenantId', '')} sx={{ alignSelf: 'center' }} />}
            </Stack>
          </Stack>
          <Box><Button variant="contained" startIcon={<AddIcon />} onClick={() => setPreparing(true)}>New invoice</Button></Box>
        </Stack>
        {list.isLoading ? <Loading /> : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Invoice</TableCell><TableCell>Institution</TableCell><TableCell>For</TableCell><TableCell align="right">Total</TableCell>
                  <TableCell align="right">To pay</TableCell><TableCell>Due</TableCell><TableCell>Status</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {list.data?.rows.map((r) => (
                  <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/platform/billing/invoices/${r.id}`)}>
                    <TableCell sx={{ fontFamily: 'monospace', whiteSpace: 'nowrap' }}>{r.invoiceNo ?? <Typography variant="body2" color="text.secondary">Draft</Typography>}</TableCell>
                    <TableCell>{r.institutionName}</TableCell>
                    <TableCell>
                      {KIND_LABEL[r.kind]}
                      <Typography variant="caption" color="text.secondary" component="div">{period(r.periodFrom, r.periodTo)}</Typography>
                    </TableCell>
                    <TableCell align="right">{tzs(r.total)}</TableCell>
                    <TableCell align="right">{r.status === 'CANCELLED' || r.status === 'DRAFT' ? '—' : tzs(r.balance)}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{day(r.dueDate)}</TableCell>
                    <TableCell><InvoiceStatusChip status={r.status} overdue={r.daysOverdue} /></TableCell>
                  </TableRow>
                ))}
                {list.data?.rows.length === 0 && <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 2 }}>No invoices here.</Typography></TableCell></TableRow>}
              </TableBody>
            </Table>
          </TableContainer>
        )}
        {(list.data?.total ?? 0) > 25 && (
          <TablePagination component="div" count={list.data?.total ?? 0} page={page} rowsPerPage={25} rowsPerPageOptions={[25]} onPageChange={(_, p) => setPage(p)} />
        )}
        <ErrorAlert error={list.error} />
      </CardContent>
      {preparing && <PrepareDialog tenantId={tenantId ? Number(tenantId) : undefined} onClose={() => setPreparing(false)}
        onDone={(v) => navigate(`/platform/billing/invoices/${v.invoice.id}`)} />}
    </Card>
  );
}

function InstitutionPicker({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const list = useQuery({ queryKey: ['platform', 'institutions'], queryFn: () => api<InstitutionRow[]>('/api/platform/institutions', platform) });
  return (
    <TextField select label="Institution" required value={value} onChange={(e) => onChange(e.target.value)} fullWidth>
      {list.data?.map((i) => <MenuItem key={i.id} value={String(i.id)}>{i.name} · {i.code}</MenuItem>)}
    </TextField>
  );
}

/** Prepares a draft: the licence year, a usage period (with what was used), or an empty invoice for other work. */
export function PrepareDialog({ tenantId, onClose, onDone }: { tenantId?: number; onClose: () => void; onDone: (v: InvoiceView) => void }) {
  const [tenant, setTenant] = useState(tenantId ? String(tenantId) : '');
  const [kind, setKind] = useState<'LICENCE' | 'USAGE' | 'OTHER'>('USAGE');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const usage = useQuery({
    queryKey: ['platform', 'billing', 'usage', tenant, from, to], enabled: kind === 'USAGE' && !!tenant && !!from && !!to && to >= from,
    queryFn: () => api<Usage>(`/api/platform/billing/usage/${tenant}?from=${from}&to=${to}`, platform),
  });
  const prepare = useMutation({
    mutationFn: () => api<InvoiceView>('/api/platform/billing/invoices', { ...platform, method: 'POST',
      body: { tenantId: Number(tenant), kind, from: from || null, to: to || null } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>New invoice</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          {!tenantId && <InstitutionPicker value={tenant} onChange={setTenant} />}
          <RadioGroup value={kind} onChange={(e) => setKind(e.target.value as typeof kind)}>
            <FormControlLabel value="USAGE" control={<Radio />} label="Usage for a period (students and applications)" />
            <FormControlLabel value="LICENCE" control={<Radio />} label="The next licence year (with the set-up fee if not yet invoiced)" />
            <FormControlLabel value="OTHER" control={<Radio />} label="Other work, typed in by hand" />
          </RadioGroup>
          {kind !== 'LICENCE' && (
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField label="From" type="date" value={from} onChange={(e) => setFrom(e.target.value)} InputLabelProps={{ shrink: true }} fullWidth required={kind === 'USAGE'} />
              <TextField label="To" type="date" value={to} onChange={(e) => setTo(e.target.value)} InputLabelProps={{ shrink: true }} fullWidth required={kind === 'USAGE'} />
            </Stack>
          )}
          {usage.data && (
            <Alert severity="info" icon={false}>
              <Typography fontWeight={600}>{`Used in ${period(usage.data.from, usage.data.to)}`}</Typography>
              <Typography variant="body2">{`${usage.data.activeStudents} active students · ${usage.data.paidApplications} applications with the fee paid · TZS ${tzs(usage.data.applicationFeesCollected)} application fees received`}</Typography>
            </Alert>
          )}
          <Typography variant="body2" color="text.secondary">The invoice is saved as a draft: check it, change the lines if needed, then issue it.</Typography>
          <ErrorAlert error={prepare.error ?? usage.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!tenant || prepare.isPending || (kind === 'USAGE' && (!from || !to))} onClick={() => prepare.mutate()}>Prepare draft</Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ one invoice

export function BillingInvoicePage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const key = ['platform', 'billing', 'invoice', id];
  const q = useQuery({ queryKey: key, queryFn: () => api<InvoiceView>(`/api/platform/billing/invoices/${id}`, platform) });
  const [lines, setLines] = useState<Line[] | null>(null);
  const [notes, setNotes] = useState('');
  const [vat, setVat] = useState('');
  const [dialog, setDialog] = useState<null | 'cancel' | 'pay'>(null);
  useEffect(() => {
    if (q.data) { setLines(q.data.lines.map((l) => ({ ...l }))); setNotes(q.data.notes ?? ''); setVat(String(q.data.vatPercent)); }
  }, [q.data]);
  const done = (v: InvoiceView) => { qc.setQueryData(key, v); qc.invalidateQueries({ queryKey: ['platform', 'billing'] }); setDialog(null); };
  const save = useMutation({ mutationFn: () => api<InvoiceView>(`/api/platform/billing/invoices/${id}`, { ...platform, method: 'PUT',
    body: { lines, notes, vatPercent: vat === '' ? null : Number(vat) } }), onSuccess: done });
  const act = useMutation({ mutationFn: (path: string) => api<InvoiceView>(`/api/platform/billing/invoices/${id}/${path}`, { ...platform, method: 'POST' }), onSuccess: done });
  if (!q.data || !lines) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  const v = q.data;
  const r = v.invoice;
  const draft = r.status === 'DRAFT';
  const open = r.status === 'ISSUED' || r.status === 'PARTLY_PAID';
  const changed = draft && (JSON.stringify(lines) !== JSON.stringify(v.lines) || notes !== (v.notes ?? '') || vat !== String(v.vatPercent));
  const subtotal = lines.reduce((s, l) => s + Math.round((l.kind === 'DISCOUNT' ? -Math.abs(l.unitPrice) : l.unitPrice) * l.quantity), 0);
  const vatAmount = Math.round((subtotal * Number(vat || 0)) / 100);
  return (
    <>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/platform/billing?tab=invoices')} sx={{ mb: 1 }}>Invoices</Button>
      <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" alignItems={{ md: 'flex-start' }} spacing={1.5} sx={{ mb: 2.5 }}>
        <Box>
          <Typography variant="h5" component="h1">{r.invoiceNo ? `Invoice ${r.invoiceNo}` : 'Draft invoice'}</Typography>
          <Typography color="text.secondary">
            <Link component={RouterLink} to={`/platform/institutions/${r.tenantId}`}>{r.institutionName}</Link>
            {' · '}{KIND_LABEL[r.kind]}{r.periodFrom ? ` · ${period(r.periodFrom, r.periodTo)}` : ''}{r.generated ? ` · ${tr('prepared by CIMS')}` : ''}
          </Typography>
        </Box>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          <InvoiceStatusChip status={r.status} overdue={r.daysOverdue} />
          <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/platform/billing/invoices/${r.id}/pdf`, 'platform', `invoice-${r.invoiceNo ?? r.id}.pdf`)}>PDF</Button>
          {open && <Button variant="outlined" onClick={() => setDialog('pay')}>Record payment</Button>}
          {open && <Button onClick={() => act.mutate('remind')} disabled={act.isPending}>Send reminder</Button>}
          {draft && <Button variant="contained" disabled={changed || act.isPending || subtotal <= 0} onClick={() => act.mutate('issue')}>Issue and send</Button>}
          {r.status !== 'CANCELLED' && r.status !== 'PAID' && <Button color="error" onClick={() => setDialog('cancel')}>{draft ? 'Discard' : 'Cancel invoice'}</Button>}
        </Stack>
      </Stack>
      {draft && changed && <Alert severity="info" sx={{ mb: 2 }}>Save your changes before issuing.</Alert>}
      {r.status === 'CANCELLED' && <Alert severity="warning" sx={{ mb: 2 }}>{`Cancelled: ${v.cancelledReason ?? ''}`}</Alert>}
      <ErrorAlert error={act.error ?? save.error} />
      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, md: 8 }}>
          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>Charges</Typography>
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      {draft && <TableCell sx={{ width: 140 }}>Kind</TableCell>}
                      <TableCell>Description</TableCell><TableCell align="right" sx={{ width: 100 }}>Quantity</TableCell>
                      <TableCell align="right" sx={{ width: 140 }}>Unit (TZS)</TableCell><TableCell align="right" sx={{ width: 120 }}>Amount</TableCell>
                      {draft && <TableCell sx={{ width: 40 }} />}
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {lines.map((l, i) => {
                      const set = (patch: Partial<Line>) => setLines(lines.map((x, j) => (j === i ? { ...x, ...patch } : x)));
                      const amount = Math.round((l.kind === 'DISCOUNT' ? -Math.abs(l.unitPrice) : l.unitPrice) * l.quantity);
                      return draft ? (
                        <TableRow key={i}>
                          <TableCell>
                            <TextField select size="small" value={l.kind} onChange={(e) => set({ kind: e.target.value })} fullWidth inputProps={{ 'aria-label': tr('Kind') }}>
                              {Object.entries(LINE_KIND).map(([k, label]) => <MenuItem key={k} value={k}>{label}</MenuItem>)}
                            </TextField>
                          </TableCell>
                          <TableCell><TextField size="small" value={l.description} onChange={(e) => set({ description: e.target.value })} fullWidth multiline inputProps={{ 'aria-label': tr('Description'), maxLength: 300 }} /></TableCell>
                          <TableCell><TextField size="small" type="number" value={l.quantity} onChange={(e) => set({ quantity: Number(e.target.value) })} inputProps={{ 'aria-label': tr('Quantity'), min: 0 }} /></TableCell>
                          <TableCell><TextField size="small" type="number" value={l.unitPrice} onChange={(e) => set({ unitPrice: Number(e.target.value) })} inputProps={{ 'aria-label': tr('Unit price'), min: 0 }} /></TableCell>
                          <TableCell align="right" sx={amount < 0 ? { color: 'error.main' } : undefined}>{tzs(amount)}</TableCell>
                          <TableCell><IconButton size="small" onClick={() => setLines(lines.filter((_, j) => j !== i))} aria-label={tr('Remove the line')}><DeleteIcon fontSize="small" /></IconButton></TableCell>
                        </TableRow>
                      ) : (
                        <TableRow key={i}>
                          <TableCell>{l.description}</TableCell><TableCell align="right">{l.quantity}</TableCell>
                          <TableCell align="right">{tzs(l.unitPrice)}</TableCell><TableCell align="right">{tzs(l.amount)}</TableCell>
                        </TableRow>
                      );
                    })}
                    {lines.length === 0 && <TableRow><TableCell colSpan={6}><Typography color="text.secondary">No charges yet.</Typography></TableCell></TableRow>}
                  </TableBody>
                </Table>
              </TableContainer>
              {draft && <Button size="small" startIcon={<AddIcon />} sx={{ mt: 1 }} onClick={() => setLines([...lines, { kind: 'OTHER', description: '', quantity: 1, unitPrice: 0 }])}>Add a line</Button>}
              <Divider sx={{ my: 1.5 }} />
              <Stack spacing={0.5} sx={{ ml: 'auto', maxWidth: 320 }}>
                <Total label="Subtotal" value={draft ? subtotal : v.subtotal} />
                <Stack direction="row" justifyContent="space-between" alignItems="center">
                  {draft ? <TextField size="small" label="VAT %" type="number" value={vat} onChange={(e) => setVat(e.target.value)} sx={{ width: 100 }} />
                    : <Typography color="text.secondary">{`VAT ${v.vatPercent}%`}</Typography>}
                  <Typography>{tzs(draft ? vatAmount : v.vatAmount)}</Typography>
                </Stack>
                <Total label="Total" value={draft ? subtotal + vatAmount : r.total} strong />
                {!draft && r.paidAmount > 0 && <Total label="Paid" value={r.paidAmount} />}
                {!draft && r.status !== 'CANCELLED' && <Total label="To pay" value={r.balance} strong />}
              </Stack>
              {draft ? (
                <TextField label="Note on the invoice" value={notes} onChange={(e) => setNotes(e.target.value)} multiline minRows={2} fullWidth sx={{ mt: 2 }} inputProps={{ maxLength: 1000 }} />
              ) : v.notes && <Typography sx={{ mt: 2, whiteSpace: 'pre-wrap' }} color="text.secondary">{v.notes}</Typography>}
              {draft && <Stack direction="row" justifyContent="flex-end" sx={{ mt: 2 }}><Button variant="outlined" disabled={!changed || save.isPending} onClick={() => save.mutate()}>Save draft</Button></Stack>}
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 4 }}>
          <Card sx={{ mb: 2.5 }}>
            <CardContent>
              <Detail label="Issued" value={r.issueDate ? `${day(r.issueDate)}${v.issuedByName ? ` · ${v.issuedByName}` : ''}` : '—'} />
              <Detail label="Due" value={day(r.dueDate)} />
              <Detail label="Payment reference" value={r.paymentReference ?? 'Given when issued'} mono />
              <Detail label="Prepared by" value={v.createdByName ?? '—'} />
            </CardContent>
          </Card>
          <Card sx={{ mb: 2.5 }}>
            <CardContent>
              <Typography variant="subtitle1" fontWeight={600} sx={{ mb: 1 }}>Payments</Typography>
              {v.payments.length === 0 && <Typography variant="body2" color="text.secondary">None yet.</Typography>}
              {v.payments.map((p) => (
                <Stack key={p.paymentId} direction="row" justifyContent="space-between" sx={{ py: 0.5, borderTop: 1, borderColor: 'divider' }}>
                  <Box>
                    <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>{p.receiptNo}</Typography>
                    <Typography variant="caption" color="text.secondary">{formatDateTime(p.paidAt)} · {METHOD_LABEL[p.method]}</Typography>
                  </Box>
                  <Typography variant="body2" fontWeight={600}>{tzs(p.amount)}</Typography>
                </Stack>
              ))}
            </CardContent>
          </Card>
          {v.reminders.length > 0 && (
            <Card>
              <CardContent>
                <Typography variant="subtitle1" fontWeight={600} sx={{ mb: 1 }}>Reminders sent</Typography>
                {v.reminders.map((m, i) => (
                  <Box key={i} sx={{ py: 0.5, borderTop: 1, borderColor: 'divider' }}>
                    <Typography variant="body2">{REMINDER_LABEL[m.kind] ?? m.kind}</Typography>
                    <Typography variant="caption" color="text.secondary">{formatDateTime(m.createdAt)}{m.sentTo ? ` · ${m.sentTo}` : ''}</Typography>
                  </Box>
                ))}
              </CardContent>
            </Card>
          )}
        </Grid>
      </Grid>
      {dialog === 'cancel' && <ReasonDialog title={draft ? 'Discard this draft?' : 'Cancel this invoice?'} required={!draft} busy={act.isPending}
        text={draft ? 'The draft is removed from the list of invoices to issue.' : 'The institution is no longer asked to pay it. Payments already applied to it are kept as credit for the next invoice.'}
        action={draft ? 'Discard' : 'Cancel invoice'} onClose={() => setDialog(null)}
        onConfirm={(reason) => api<InvoiceView>(`/api/platform/billing/invoices/${id}/cancel`, { ...platform, method: 'POST', body: { reason } }).then(done)} />}
      {dialog === 'pay' && <RecordPaymentDialog tenantId={r.tenantId} invoiceId={r.id} amount={r.balance} onClose={() => setDialog(null)}
        onDone={() => { setDialog(null); q.refetch(); qc.invalidateQueries({ queryKey: ['platform', 'billing'] }); }} />}
    </>
  );
}

const REMINDER_LABEL: Record<string, string> = {
  DUE_SOON: 'Due soon', OVERDUE: 'Overdue', SUSPENSION_DUE: 'Final notice', SUSPENDED: 'Suspended for non-payment', MANUAL: 'Reminder sent by hand',
};

function Total({ label, value, strong }: { label: string; value: number; strong?: boolean }) {
  return (
    <Stack direction="row" justifyContent="space-between">
      <Typography color={strong ? 'text.primary' : 'text.secondary'} fontWeight={strong ? 700 : 400}>{label}</Typography>
      <Typography fontWeight={strong ? 700 : 400}>{strong ? `TZS ${tzs(value)}` : tzs(value)}</Typography>
    </Stack>
  );
}

function Detail({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <Stack direction="row" justifyContent="space-between" spacing={2} sx={{ py: 0.4 }}>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography variant="body2" sx={{ textAlign: 'right', ...(mono ? { fontFamily: 'monospace' } : {}) }}>{value}</Typography>
    </Stack>
  );
}

function ReasonDialog({ title, text, action, required, busy, onClose, onConfirm }: {
  title: string; text: string; action: string; required?: boolean; busy?: boolean; onClose: () => void; onConfirm: (reason: string) => Promise<unknown>;
}) {
  const [reason, setReason] = useState('');
  const [error, setError] = useState<unknown>(null);
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Typography sx={{ mb: 2 }}>{text}</Typography>
        <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} fullWidth required={required} multiline inputProps={{ maxLength: 300 }} />
        <ErrorAlert error={error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Back</Button>
        <Button color="error" variant="contained" disabled={busy || (required && !reason.trim())} onClick={() => onConfirm(reason).catch(setError)}>{action}</Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ payments

function PaymentsTab() {
  const qc = useQueryClient();
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [recording, setRecording] = useState(false);
  const [reversing, setReversing] = useState<PaymentRow | null>(null);
  useEffect(() => { const h = setTimeout(() => setQuery(q), 300); return () => clearTimeout(h); }, [q]);
  const list = useQuery({ queryKey: ['platform', 'billing', 'payments', query], queryFn: () => api<PaymentRow[]>(`/api/platform/billing/payments?q=${encodeURIComponent(query)}`, platform) });
  return (
    <Card>
      <CardContent>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} justifyContent="space-between" sx={{ mb: 2 }}>
          <TextField size="small" placeholder="Receipt, reference or institution" value={q} onChange={(e) => setQ(e.target.value)} sx={{ minWidth: 300 }}
            InputProps={{ startAdornment: <InputAdornment position="start"><SearchIcon /></InputAdornment> }} inputProps={{ 'aria-label': tr('Search payments') }} />
          <Box><Button variant="contained" startIcon={<AddIcon />} onClick={() => setRecording(true)}>Record payment</Button></Box>
        </Stack>
        <PaymentTable rows={list.data} loading={list.isLoading} realm="platform" onReverse={setReversing} showInstitution />
        <ErrorAlert error={list.error} />
      </CardContent>
      {recording && <RecordPaymentDialog onClose={() => setRecording(false)} onDone={() => { setRecording(false); qc.invalidateQueries({ queryKey: ['platform', 'billing'] }); }} />}
      {reversing && <ReasonDialog title={`Reverse ${reversing.receiptNo}?`} required action="Reverse payment"
        text="Use this for a payment recorded by mistake or a cheque that bounced. Its amount comes off the invoices it paid."
        onClose={() => setReversing(null)}
        onConfirm={(reason) => api(`/api/platform/billing/payments/${reversing.id}/reverse`, { ...platform, method: 'POST', body: { reason } })
          .then(() => { setReversing(null); qc.invalidateQueries({ queryKey: ['platform', 'billing'] }); })} />}
    </Card>
  );
}

/** A payment Msingi received (bank transfer, mobile money, cheque or cash), applied to an invoice or to the oldest. */
export function RecordPaymentDialog({ tenantId, invoiceId, amount, onClose, onDone }: {
  tenantId?: number; invoiceId?: number; amount?: number; onClose: () => void; onDone: () => void;
}) {
  const [f, setF] = useState({ tenant: tenantId ? String(tenantId) : '', invoice: invoiceId ? String(invoiceId) : '', amount: amount ? String(amount) : '',
    paidOn: today(), method: 'BANK', provider: '', reference: '', note: '' });
  const open = useQuery({ queryKey: ['platform', 'billing', 'invoices', 'open', f.tenant], enabled: !!f.tenant,
    queryFn: () => api<{ rows: InvoiceRow[] }>(`/api/platform/billing/invoices?status=open&tenantId=${f.tenant}&size=100`, platform) });
  const save = useMutation({
    mutationFn: () => api<PaymentRow>('/api/platform/billing/payments', { ...platform, method: 'POST', body: {
      tenantId: Number(f.tenant), invoiceId: f.invoice ? Number(f.invoice) : null, amount: Number(f.amount), paidOn: f.paidOn, method: f.method,
      provider: f.provider || null, reference: f.reference || null, note: f.note || null } }),
    onSuccess: onDone,
  });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  const providers = PROVIDERS.filter((p) => (f.method === 'MOBILE_MONEY' ? p.kind === 'MOBILE_MONEY' : p.kind === 'BANK'));
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Record a payment</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          {!tenantId && <InstitutionPicker value={f.tenant} onChange={(v) => setF({ ...f, tenant: v, invoice: '' })} />}
          <TextField select label="Apply to" value={f.invoice} onChange={set('invoice')} helperText="What is left over pays the oldest unpaid invoices, then is kept as credit">
            <MenuItem value="">The oldest unpaid invoices</MenuItem>
            {open.data?.rows.map((i) => <MenuItem key={i.id} value={String(i.id)}>{`${i.invoiceNo} · TZS ${tzs(i.balance)} to pay`}</MenuItem>)}
          </TextField>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField label="Amount (TZS)" type="number" required value={f.amount} onChange={set('amount')} fullWidth inputProps={{ min: 1 }} />
            <TextField label="Paid on" type="date" required value={f.paidOn} onChange={set('paidOn')} fullWidth InputLabelProps={{ shrink: true }} inputProps={{ max: today() }} />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField select label="How" value={f.method} onChange={(e) => setF({ ...f, method: e.target.value, provider: '' })} fullWidth>
              {Object.entries(METHOD_LABEL).map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
            </TextField>
            {(f.method === 'BANK' || f.method === 'MOBILE_MONEY' || f.method === 'CHEQUE') && (
              <TextField select label={f.method === 'MOBILE_MONEY' ? 'Service' : 'Bank'} value={f.provider} onChange={set('provider')} fullWidth>
                <MenuItem value="">—</MenuItem>
                {providers.map((p) => <MenuItem key={p.code} value={p.code}>{p.label}</MenuItem>)}
              </TextField>
            )}
          </Stack>
          <TextField label="Reference" value={f.reference} onChange={set('reference')} placeholder="Bank or mobile money transaction reference" inputProps={{ maxLength: 80 }} />
          <TextField label="Note" value={f.note} onChange={set('note')} multiline inputProps={{ maxLength: 500 }} />
          <Typography variant="body2" color="text.secondary">The institution gets a receipt by email.</Typography>
          <ErrorAlert error={save.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!f.tenant || !(Number(f.amount) > 0) || save.isPending} onClick={() => save.mutate()}>Record payment</Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ Msingi's details on invoices

function SettingsTab() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['platform', 'billing', 'settings'], queryFn: () => api<BillingSettings>('/api/platform/billing/settings', platform) });
  const [f, setF] = useState<BillingSettings | null>(null);
  useEffect(() => { if (q.data) setF(q.data); }, [q.data]);
  const save = useMutation({ mutationFn: () => api<BillingSettings>('/api/platform/billing/settings', { ...platform, method: 'PUT', body: f }),
    onSuccess: (s) => { qc.setQueryData(['platform', 'billing', 'settings'], s); setF(s); } });
  if (!f) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  const set = (k: keyof BillingSettings) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  const setChannel = (i: number, patch: Partial<Channel>) => setF({ ...f, channels: f.channels.map((c, j) => (j === i ? { ...c, ...patch } : c)) });
  return (
    <Card>
      <CardContent>
        <Typography variant="h6">Msingi's details on invoices</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Printed on every invoice, receipt and statement, and in the payment instructions sent to institutions.</Typography>
        <LogoPicker custom={!!f.customLogo} onChanged={(s2) => { qc.setQueryData(['platform', 'billing', 'settings'], s2); setF({ ...f, customLogo: s2.customLogo }); }} />
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, md: 6 }}><TextField label="Company name" required value={f.companyName} onChange={set('companyName')} fullWidth /></Grid>
          <Grid size={{ xs: 6, md: 3 }}><TextField label="TIN" value={f.tin ?? ''} onChange={set('tin')} fullWidth /></Grid>
          <Grid size={{ xs: 6, md: 3 }}><TextField label="VRN" value={f.vrn ?? ''} onChange={set('vrn')} fullWidth /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><TextField label="Address" value={f.address ?? ''} onChange={set('address')} fullWidth /></Grid>
          <Grid size={{ xs: 12, md: 3 }}><TextField label="Phone" value={f.phone ?? ''} onChange={set('phone')} fullWidth /></Grid>
          <Grid size={{ xs: 12, md: 3 }}><TextField label="Email" value={f.email ?? ''} onChange={set('email')} fullWidth /></Grid>
          <Grid size={{ xs: 12, md: 6 }}><TextField label="Website" value={f.website ?? ''} onChange={set('website')} fullWidth /></Grid>
          <Grid size={12}><TextField label="Note at the foot of invoices" value={f.invoiceNote ?? ''} onChange={set('invoiceNote')} fullWidth multiline inputProps={{ maxLength: 1000 }} /></Grid>
        </Grid>
        <Typography variant="subtitle1" fontWeight={600} sx={{ mt: 3 }}>Accounts institutions pay into</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>Payers quote the invoice's payment reference. Payments reported by Msingi's collector are matched by it automatically.</Typography>
        <Stack spacing={1.5}>
          {f.channels.map((c, i) => (
            <Stack key={i} direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }}>
              <TextField select size="small" label="Bank or service" value={c.provider} onChange={(e) => setChannel(i, { provider: e.target.value })} sx={{ minWidth: 200 }}>
                {PROVIDERS.map((p) => <MenuItem key={p.code} value={p.code}>{p.label}</MenuItem>)}
              </TextField>
              <TextField size="small" label="Account or business number" value={c.accountNumber} onChange={(e) => setChannel(i, { accountNumber: e.target.value })} sx={{ minWidth: 220 }} />
              <TextField size="small" label="Account name" value={c.accountName ?? ''} onChange={(e) => setChannel(i, { accountName: e.target.value })} fullWidth />
              <TextField size="small" label="Branch" value={c.branch ?? ''} onChange={(e) => setChannel(i, { branch: e.target.value })} sx={{ minWidth: 140 }} />
              <IconButton onClick={() => setF({ ...f, channels: f.channels.filter((_, j) => j !== i) })} aria-label={tr('Remove the account')}><DeleteIcon /></IconButton>
            </Stack>
          ))}
          <Box><Button size="small" startIcon={<AddIcon />} onClick={() => setF({ ...f, channels: [...f.channels, { provider: 'CRDB', accountNumber: '', accountName: f.companyName }] })}>Add an account</Button></Box>
        </Stack>
        <ErrorAlert error={save.error} />
        <Stack direction="row" spacing={2} justifyContent="flex-end" alignItems="center" sx={{ mt: 2 }}>
          {f.updatedByName && <Typography variant="caption" color="text.secondary">{`Last changed by ${f.updatedByName}, ${formatDateTime(f.updatedAt)}`}</Typography>}
          <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Save</Button>
        </Stack>
        {save.isSuccess && <Alert severity="success" sx={{ mt: 1.5 }}>Saved.</Alert>}
      </CardContent>
    </Card>
  );
}

/** Msingi's logo on its documents: the built-in one, or one uploaded here (PNG or JPEG). */
function LogoPicker({ custom, onChanged }: { custom: boolean; onChanged: (s: BillingSettings) => void }) {
  const [url, setUrl] = useState<string>();
  const [n, setN] = useState(0);
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    let live = true;
    privateImageUrl('/api/platform/billing/settings/logo', 'platform').then((u) => live && setUrl(u)).catch(() => undefined);
    return () => { live = false; };
  }, [n]);
  const pick = (file?: File) => {
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    uploadForm<BillingSettings>('/api/platform/billing/settings/logo', 'platform', form, 'PUT').then((s) => { setError(null); onChanged(s); setN(n + 1); }).catch(setError);
  };
  const reset = () => api<BillingSettings>('/api/platform/billing/settings/logo', { ...platform, method: 'DELETE' }).then((s) => { onChanged(s); setN(n + 1); }).catch(setError);
  return (
    <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 2.5 }}>
      <Box sx={{ width: 84, height: 84, border: 1, borderColor: 'divider', borderRadius: 2, display: 'flex', alignItems: 'center', justifyContent: 'center', bgcolor: '#fff' }}>
        {url && <Box component="img" src={url} alt={tr('Logo')} sx={{ maxWidth: 76, maxHeight: 76 }} />}
      </Box>
      <Box>
        <Typography variant="subtitle2">Logo</Typography>
        <Typography variant="body2" color="text.secondary">{custom ? tr('Your uploaded logo.') : tr('The Msingi Technologies logo built into CIMS.')}</Typography>
        <Stack direction="row" spacing={1} sx={{ mt: 0.5 }}>
          <Button size="small" component="label">Upload a logo<input hidden type="file" accept="image/png,image/jpeg" onChange={(e) => pick(e.target.files?.[0])} /></Button>
          {custom && <Button size="small" color="inherit" onClick={reset}>Use the built-in logo</Button>}
        </Stack>
        <ErrorAlert error={error} />
      </Box>
    </Stack>
  );
}

// ------------------------------------------------------------------ the institution's billing (on its page in the console)

/** The institution's contract and account with Msingi, on its page in the platform console. */
export function BillingCard({ tenantId, name }: { tenantId: number; name: string }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const contracts = useQuery({ queryKey: ['platform', 'billing', 'contracts', tenantId], queryFn: () => api<Contract[]>(`/api/platform/billing/contracts/${tenantId}`, platform) });
  const st = useQuery({ queryKey: ['platform', 'billing', 'statement', tenantId], queryFn: () => api<Statement>(`/api/platform/billing/statements/${tenantId}`, platform) });
  const [dialog, setDialog] = useState<null | 'contract' | 'prepare' | 'pay' | 'statement'>(null);
  const c = contracts.data?.find((x) => x.status === 'ACTIVE');
  const refresh = () => { qc.invalidateQueries({ queryKey: ['platform', 'billing'] }); setDialog(null); };
  return (
    <Card sx={{ mb: 2.5 }}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6">Billing</Typography>
          <Button size="small" startIcon={c ? <EditIcon /> : <AddIcon />} onClick={() => setDialog('contract')}>{c ? 'Contract' : 'Set up billing'}</Button>
        </Stack>
        {contracts.isLoading ? <Loading /> : !c ? (
          <Typography variant="body2" color="text.secondary">No contract yet. Record what the institution pays (set-up, licence, per student, per application) and CIMS prepares its invoices.</Typography>
        ) : (
          <>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
              {`${c.reference ? `${c.reference} · ` : ''}from ${day(c.startDate)}${c.endDate ? ` to ${day(c.endDate)}` : ''} · usage invoiced ${CYCLE_LABEL[c.usageCycle].toLowerCase()}`}
            </Typography>
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 1.5 }}>
              {feeChips(c).map((t) => <Chip key={t} size="small" variant="outlined" label={t} />)}
            </Stack>
          </>
        )}
        {st.data && (st.data.entries.length > 0 || c) && (
          <Stack direction="row" spacing={3} sx={{ py: 1, borderTop: 1, borderColor: 'divider' }} flexWrap="wrap" useFlexGap>
            <Box><Typography variant="caption" color="text.secondary">To be paid</Typography><Typography fontWeight={700}>{`TZS ${tzs(st.data.balance)}`}</Typography></Box>
            <Box><Typography variant="caption" color="text.secondary">Overdue</Typography>
              <Typography fontWeight={700} color={overdue(st.data.aging) > 0 ? 'error.main' : undefined}>{`TZS ${tzs(overdue(st.data.aging))}`}</Typography></Box>
            {st.data.credit > 0 && <Box><Typography variant="caption" color="text.secondary">Credit</Typography><Typography fontWeight={700} color="success.main">{`TZS ${tzs(st.data.credit)}`}</Typography></Box>}
          </Stack>
        )}
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
          <Button size="small" onClick={() => setDialog('prepare')}>New invoice</Button>
          <Button size="small" onClick={() => setDialog('pay')}>Record payment</Button>
          <Button size="small" onClick={() => setDialog('statement')}>Statement</Button>
          <Button size="small" component={RouterLink} to={`/platform/billing?tab=invoices&tenantId=${tenantId}`}>Invoices</Button>
        </Stack>
      </CardContent>
      {dialog === 'contract' && <ContractDialog tenantId={tenantId} name={name} contract={c} onClose={() => setDialog(null)} onDone={refresh} />}
      {dialog === 'prepare' && <PrepareDialog tenantId={tenantId} onClose={() => setDialog(null)} onDone={(v) => navigate(`/platform/billing/invoices/${v.invoice.id}`)} />}
      {dialog === 'pay' && <RecordPaymentDialog tenantId={tenantId} onClose={() => setDialog(null)} onDone={refresh} />}
      {dialog === 'statement' && st.data && <StatementDialog st={st.data} realm="platform" onClose={() => setDialog(null)} />}
    </Card>
  );
}

const overdue = (a: Aging) => a.days1to30 + a.days31to60 + a.days61to90 + a.over90;

export function feeChips(c: Contract) {
  const out: string[] = [];
  if (c.setupFee > 0) out.push(`Set-up TZS ${tzs(c.setupFee)}`);
  if (c.annualFee > 0) out.push(`Licence TZS ${tzs(c.annualFee)} a year`);
  if (c.studentFee > 0) out.push(`TZS ${tzs(c.studentFee)} per student a year`);
  if (c.applicationFee > 0) out.push(`TZS ${tzs(c.applicationFee)} per paid application`);
  if (c.applicationPercent > 0) out.push(`${c.applicationPercent}% of application fees`);
  out.push(`VAT ${c.vatPercent}%`, `Pay within ${c.paymentTermsDays} days`);
  return out;
}

function ContractDialog({ tenantId, name, contract, onClose, onDone }: {
  tenantId: number; name: string; contract?: Contract; onClose: () => void; onDone: () => void;
}) {
  const [f, setF] = useState(() => ({
    reference: contract?.reference ?? '', startDate: contract?.startDate ?? today(), endDate: contract?.endDate ?? '',
    setupFee: String(contract?.setupFee ?? 0), annualFee: String(contract?.annualFee ?? 0), studentFee: String(contract?.studentFee ?? 0),
    applicationFee: String(contract?.applicationFee ?? 0), applicationPercent: String(contract?.applicationPercent ?? 0),
    usageCycle: (contract?.usageCycle ?? 'QUARTERLY') as Cycle, vatPercent: String(contract?.vatPercent ?? 18), paymentTermsDays: String(contract?.paymentTermsDays ?? 30),
    autoIssue: contract?.autoIssue ?? false, autoSuspend: contract?.autoSuspend ?? false, suspendAfterDays: String(contract?.suspendAfterDays ?? 60),
    billingContactName: contract?.billingContactName ?? '', billingEmail: contract?.billingEmail ?? '', billingPhone: contract?.billingPhone ?? '', notes: contract?.notes ?? '',
  }));
  const [ending, setEnding] = useState(false);
  const save = useMutation({
    mutationFn: () => api<Contract>(`/api/platform/billing/contracts/${tenantId}`, { ...platform, method: 'PUT', body: {
      ...f, endDate: f.endDate || null, setupFee: Number(f.setupFee), annualFee: Number(f.annualFee), studentFee: Number(f.studentFee),
      applicationFee: Number(f.applicationFee), applicationPercent: Number(f.applicationPercent), vatPercent: Number(f.vatPercent),
      paymentTermsDays: Number(f.paymentTermsDays), suspendAfterDays: Number(f.suspendAfterDays) } }),
    onSuccess: onDone,
  });
  const end = useMutation({ mutationFn: () => api<Contract>(`/api/platform/billing/contracts/${tenantId}/end`, { ...platform, method: 'POST', body: { endDate: today() } }), onSuccess: onDone });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  const billed = !!(contract?.licenceBilledUntil || contract?.usageBilledUntil);
  const money = (k: keyof typeof f, label: string, helper?: string) => (
    <TextField label={label} type="number" value={f[k] as string} onChange={set(k)} fullWidth helperText={helper}
      InputProps={{ startAdornment: <InputAdornment position="start">TZS</InputAdornment> }} inputProps={{ min: 0 }} />
  );
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{contract ? `Contract with ${name}` : `Set up billing for ${name}`}</DialogTitle>
      <DialogContent dividers>
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, md: 4 }}><TextField label="Contract number" value={f.reference} onChange={set('reference')} fullWidth /></Grid>
          <Grid size={{ xs: 6, md: 4 }}><TextField label="Starts" type="date" required value={f.startDate} onChange={set('startDate')} fullWidth InputLabelProps={{ shrink: true }} disabled={billed}
            helperText={billed ? tr('Fixed: invoices have been prepared') : tr('Licence years and usage cycles count from this date')} /></Grid>
          <Grid size={{ xs: 6, md: 4 }}><TextField label="Ends (optional)" type="date" value={f.endDate} onChange={set('endDate')} fullWidth InputLabelProps={{ shrink: true }} /></Grid>
          <Grid size={12}><Typography variant="subtitle2">What the institution pays</Typography></Grid>
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>{money('setupFee', 'Set-up fee (once)', 'Installation, data migration, training')}</Grid>
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>{money('annualFee', 'Annual licence', 'Invoiced at the start of each contract year')}</Grid>
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>{money('studentFee', 'Per active student, per year', 'Charged in proportion each cycle')}</Grid>
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>{money('applicationFee', 'Per application with the fee paid')}</Grid>
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <TextField label="Share of application fees" type="number" value={f.applicationPercent} onChange={set('applicationPercent')} fullWidth
              InputProps={{ endAdornment: <InputAdornment position="end">%</InputAdornment> }} inputProps={{ min: 0, max: 100, step: 0.5 }} />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <TextField select label="Usage invoiced" value={f.usageCycle} onChange={set('usageCycle')} fullWidth disabled={billed && !!contract?.usageBilledUntil}>
              {Object.entries(CYCLE_LABEL).map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid size={{ xs: 6, md: 4 }}><TextField label="VAT" type="number" value={f.vatPercent} onChange={set('vatPercent')} fullWidth InputProps={{ endAdornment: <InputAdornment position="end">%</InputAdornment> }} /></Grid>
          <Grid size={{ xs: 6, md: 4 }}><TextField label="Pay within" type="number" value={f.paymentTermsDays} onChange={set('paymentTermsDays')} fullWidth InputProps={{ endAdornment: <InputAdornment position="end">{tr('days')}</InputAdornment> }} /></Grid>
          <Grid size={12}><Typography variant="subtitle2">Who receives the invoices</Typography></Grid>
          <Grid size={{ xs: 12, md: 4 }}><TextField label="Name" value={f.billingContactName} onChange={set('billingContactName')} fullWidth placeholder="e.g. The Bursar" /></Grid>
          <Grid size={{ xs: 12, md: 4 }}><TextField label="Email" type="email" value={f.billingEmail} onChange={set('billingEmail')} fullWidth helperText="Otherwise the institution's contact email" /></Grid>
          <Grid size={{ xs: 12, md: 4 }}><TextField label="Phone" value={f.billingPhone} onChange={set('billingPhone')} fullWidth /></Grid>
          <Grid size={12}>
            <FormControlLabel control={<Switch checked={f.autoIssue} onChange={(e) => setF({ ...f, autoIssue: e.target.checked })} />}
              label="Issue the invoices CIMS prepares without review" />
          </Grid>
          <Grid size={{ xs: 12, md: 8 }}>
            <FormControlLabel control={<Switch checked={f.autoSuspend} onChange={(e) => setF({ ...f, autoSuspend: e.target.checked })} />}
              label="Suspend the institution automatically when an invoice stays unpaid" />
          </Grid>
          <Grid size={{ xs: 12, md: 4 }}>
            <TextField label={f.autoSuspend ? 'Suspend after' : 'Final notice after'} type="number" value={f.suspendAfterDays} onChange={set('suspendAfterDays')} fullWidth
              InputProps={{ endAdornment: <InputAdornment position="end">{tr('days overdue')}</InputAdornment> }} inputProps={{ min: 7, max: 365 }} />
          </Grid>
          <Grid size={12}><TextField label="Notes (Msingi only)" value={f.notes} onChange={set('notes')} fullWidth multiline inputProps={{ maxLength: 2000 }} /></Grid>
        </Grid>
        <ErrorAlert error={save.error ?? end.error} />
        {ending && (
          <Alert severity="warning" sx={{ mt: 2 }} action={<Button color="inherit" onClick={() => end.mutate()} disabled={end.isPending}>End it today</Button>}>
            No more invoices are prepared once the contract ends. Unpaid invoices stay to be paid.
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        {contract && !ending && <Button color="error" onClick={() => setEnding(true)} sx={{ mr: 'auto' }}>End the contract</Button>}
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!f.startDate || save.isPending} onClick={() => save.mutate()}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}


// ------------------------------------------------------------------ billing set up while onboarding

export interface OnboardBillingValue {
  enabled: boolean; reference: string; startDate: string; setupFee: string; annualFee: string; studentFee: string; applicationFee: string;
  applicationPercent: string; usageCycle: Cycle; vatPercent: string; paymentTermsDays: string; billingContactName: string; billingEmail: string;
  firstInvoice: boolean; issueNow: boolean;
}

export const ONBOARD_BILLING: OnboardBillingValue = {
  enabled: true, reference: '', startDate: today(), setupFee: '0', annualFee: '0', studentFee: '0', applicationFee: '0', applicationPercent: '0',
  usageCycle: 'QUARTERLY', vatPercent: '18', paymentTermsDays: '30', billingContactName: '', billingEmail: '', firstInvoice: true, issueNow: false,
};

/** What the institution will pay Msingi, entered while onboarding it (the contract, and the first invoice for the set-up fee and licence). */
export function OnboardBillingCard({ value: f, onChange }: { value: OnboardBillingValue; onChange: (v: OnboardBillingValue) => void }) {
  const set = (k: keyof OnboardBillingValue) => (e: { target: { value: string } }) => onChange({ ...f, [k]: e.target.value });
  const money = (k: keyof OnboardBillingValue, label: string, helper?: string) => (
    <TextField label={label} type="number" size="small" value={f[k] as string} onChange={set(k)} fullWidth helperText={helper}
      InputProps={{ startAdornment: <InputAdornment position="start">TZS</InputAdornment> }} inputProps={{ min: 0 }} />
  );
  const charges = Number(f.setupFee) > 0 || Number(f.annualFee) > 0;
  return (
    <Card sx={{ mb: 2.5 }}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center">
          <Typography variant="h6">What it pays Msingi</Typography>
          <Switch checked={f.enabled} onChange={(e) => onChange({ ...f, enabled: e.target.checked })} inputProps={{ 'aria-label': tr('Bill this institution') }} />
        </Stack>
        {!f.enabled ? (
          <Typography variant="body2" color="text.secondary">No contract now. Set it up later on the institution's page, on the Billing card.</Typography>
        ) : (
          <Grid container spacing={1.5} sx={{ mt: 0.5 }}>
            <Grid size={{ xs: 6 }}><TextField label="Contract number" size="small" value={f.reference} onChange={set('reference')} fullWidth /></Grid>
            <Grid size={{ xs: 6 }}><TextField label="Starts" type="date" size="small" value={f.startDate} onChange={set('startDate')} fullWidth InputLabelProps={{ shrink: true }} /></Grid>
            <Grid size={{ xs: 6 }}>{money('setupFee', 'Set-up fee (once)')}</Grid>
            <Grid size={{ xs: 6 }}>{money('annualFee', 'Annual licence')}</Grid>
            <Grid size={{ xs: 6 }}>{money('studentFee', 'Per active student, per year')}</Grid>
            <Grid size={{ xs: 6 }}>{money('applicationFee', 'Per application with the fee paid')}</Grid>
            <Grid size={{ xs: 6 }}>
              <TextField label="Share of application fees" type="number" size="small" value={f.applicationPercent} onChange={set('applicationPercent')} fullWidth
                InputProps={{ endAdornment: <InputAdornment position="end">%</InputAdornment> }} inputProps={{ min: 0, max: 100, step: 0.5 }} />
            </Grid>
            <Grid size={{ xs: 6 }}>
              <TextField select label="Usage invoiced" size="small" value={f.usageCycle} onChange={set('usageCycle')} fullWidth>
                {Object.entries(CYCLE_LABEL).map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
              </TextField>
            </Grid>
            <Grid size={{ xs: 6 }}><TextField label="VAT" type="number" size="small" value={f.vatPercent} onChange={set('vatPercent')} fullWidth InputProps={{ endAdornment: <InputAdornment position="end">%</InputAdornment> }} /></Grid>
            <Grid size={{ xs: 6 }}><TextField label="Pay within" type="number" size="small" value={f.paymentTermsDays} onChange={set('paymentTermsDays')} fullWidth InputProps={{ endAdornment: <InputAdornment position="end">{tr('days')}</InputAdornment> }} /></Grid>
            <Grid size={{ xs: 6 }}><TextField label="Invoices to (name)" size="small" value={f.billingContactName} onChange={set('billingContactName')} fullWidth placeholder="e.g. The Bursar" /></Grid>
            <Grid size={{ xs: 6 }}><TextField label="Invoices to (email)" type="email" size="small" value={f.billingEmail} onChange={set('billingEmail')} fullWidth helperText="Otherwise the contact email" /></Grid>
            <Grid size={12}>
              <FormControlLabel control={<Switch checked={f.firstInvoice && charges} disabled={!charges} onChange={(e) => onChange({ ...f, firstInvoice: e.target.checked })} />}
                label="Prepare the first invoice now (set-up fee and the first licence year)" />
              {f.firstInvoice && charges && (
                <FormControlLabel control={<Switch checked={f.issueNow} onChange={(e) => onChange({ ...f, issueNow: e.target.checked })} />}
                  label="Issue it and email it to the institution straight away" />
              )}
            </Grid>
          </Grid>
        )}
      </CardContent>
    </Card>
  );
}

/** After onboarding: records the contract, then prepares (and issues) the first invoice. Returns what was done, in words. */
export async function applyOnboardBilling(tenantId: number, f: OnboardBillingValue): Promise<{ text: string; invoiceId?: number }> {
  if (!f.enabled) return { text: '' };
  await api<Contract>(`/api/platform/billing/contracts/${tenantId}`, { ...platform, method: 'PUT', body: {
    reference: f.reference || null, startDate: f.startDate, setupFee: Number(f.setupFee), annualFee: Number(f.annualFee), studentFee: Number(f.studentFee),
    applicationFee: Number(f.applicationFee), applicationPercent: Number(f.applicationPercent), usageCycle: f.usageCycle, vatPercent: Number(f.vatPercent),
    paymentTermsDays: Number(f.paymentTermsDays), billingContactName: f.billingContactName || null, billingEmail: f.billingEmail || null } });
  if (!f.firstInvoice || !(Number(f.setupFee) > 0 || Number(f.annualFee) > 0)) return { text: tr('The contract is recorded. CIMS prepares its invoices as they fall due.') };
  let v = await api<InvoiceView>('/api/platform/billing/invoices', { ...platform, method: 'POST', body: { tenantId, kind: 'LICENCE' } });
  if (f.issueNow) {
    v = await api<InvoiceView>(`/api/platform/billing/invoices/${v.invoice.id}/issue`, { ...platform, method: 'POST' });
    return { text: tr(`Invoice ${v.invoice.invoiceNo} for TZS ${tzs(v.invoice.total)} was issued and emailed to the institution.`), invoiceId: v.invoice.id };
  }
  return { text: tr(`A draft invoice for TZS ${tzs(v.invoice.total)} is ready to check and issue.`), invoiceId: v.invoice.id };
}
