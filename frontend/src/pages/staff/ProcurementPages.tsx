import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import DownloadIcon from '@mui/icons-material/Download';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, MenuItem, Stack, Switch,
  Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime, formatTzs, uploadForm } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';

const staff = { realm: 'staff' as const };
const READ_ALL = ['INSTITUTION_ADMIN', 'PROCUREMENT_OFFICER', 'BURSAR', 'ACCOUNTANT', 'STORES_OFFICER', 'MANAGEMENT_VIEWER', 'AUDITOR'];
const PROCURE = ['INSTITUTION_ADMIN', 'PROCUREMENT_OFFICER'];
const RECEIVE = ['INSTITUTION_ADMIN', 'STORES_OFFICER'];

interface Supplier { id: number; name: string; tin?: string; phone?: string; email?: string; address?: string; supplies?: string; active: boolean; orders: number; ordered: number }
interface Line { id: number; description: string; quantity: number; unit: string; estimatedCost: number; amount: number; storeItemId?: number; storeItem?: string; assetCategory?: string }
interface Quotation { id: number; supplierId: number; supplier: string; amount: number; deliveryDays?: number; reference?: string; fileName?: string; chosen: boolean; choiceNote?: string; createdByName: string; lowest: boolean }
interface Req {
  id: number; reqNo: string; departmentId?: number; department?: string; title: string; justification: string; neededBy?: string; estimatedTotal: number; status: string;
  requestedByName: string; requestedAt: string; endorsedByName?: string; endorsedAt?: string; approvedByName?: string; approvedAt?: string; decisionNote?: string;
  orderId?: number; lpoNo?: string; canEndorse: boolean; canApprove: boolean; mine: boolean;
}
interface ReqDetail { requisition: Req; lines: Line[]; quotations: Quotation[] }
interface OrderLine { id: number; description: string; quantity: number; unit: string; unitPrice: number; amount: number; received: number; storeItem?: string; assetCategory?: string }
interface Receipt { id: number; grnNo: string; deliveryNote?: string; invoiceRef?: string; note?: string; value: number; receivedByName: string; receivedAt: string; lines: { description: string; quantity: number; into: string; assetTags?: string[] }[] }
interface Order { id: number; lpoNo: string; requisitionId: number; reqNo: string; department?: string; supplierId: number; supplier: string; total: number; receivedValue: number; deliverBy?: string; terms?: string; status: string; issuedByName: string; issuedAt: string; cancelNote?: string }
interface OrderDetail { order: Order; lines: OrderLine[]; receipts: Receipt[] }
interface Overview { toEndorse: number; toApprove: number; approvedWithoutOrder: number; openOrders: number; orderedThisYear: number; receivedThisYear: number }
interface Option { id: number; name: string }
interface StoreItem { id: number; code: string; name: string; unit: string; active: boolean }

const REQ_STATUS: Record<string, { label: string; color: 'default' | 'warning' | 'info' | 'success' | 'error' }> = {
  SUBMITTED: { label: 'With the Head of Department', color: 'warning' }, ENDORSED: { label: 'With the Bursar', color: 'warning' }, APPROVED: { label: 'Approved · to order', color: 'info' },
  ORDERED: { label: 'Ordered', color: 'success' }, REJECTED: { label: 'Not approved', color: 'error' }, CANCELLED: { label: 'Cancelled', color: 'default' },
};
const ORDER_STATUS: Record<string, { label: string; color: 'default' | 'warning' | 'info' | 'success' }> = {
  ISSUED: { label: 'Issued', color: 'warning' }, PART_RECEIVED: { label: 'Partly received', color: 'info' }, RECEIVED: { label: 'Received', color: 'success' }, CANCELLED: { label: 'Cancelled', color: 'default' },
};
const CATEGORY: Record<string, string> = { FURNITURE: 'Furniture', ICT: 'ICT', LAB_EQUIPMENT: 'Laboratory equipment', WORKSHOP: 'Workshop tools and machines',
  VEHICLE: 'Vehicle', BUILDING: 'Building', LAND: 'Land', OFFICE_EQUIPMENT: 'Office equipment', OTHER: 'Other' };

/** Procurement (FR-PRC-01..05). */
export default function ProcurementPage() {
  const can = useRoles();
  const all = can(READ_ALL);
  const [tab, setTab] = useState('requisitions');
  const ov = useQuery({ queryKey: ['staff', 'procurement', 'overview'], enabled: all, queryFn: () => api<Overview>('/api/procurement/overview', staff) });
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Procurement" subtitle="Ask to buy; the Head of Department endorses, the Bursar approves, Procurement orders, Stores receive."
        action={all ? <Button startIcon={<DownloadIcon />} onClick={() => download('/api/procurement/register', 'staff', `procurement-${new Date().getFullYear()}.xlsx`)}>Register (Excel)</Button> : undefined} />
      {ov.data && (
        <Grid container spacing={1.5} sx={{ mb: 2 }}>
          {[['With Heads of Department', ov.data.toEndorse], ['With the Bursar', ov.data.toApprove], ['Approved, not ordered', ov.data.approvedWithoutOrder],
            ['Orders not fully received', ov.data.openOrders], ['Ordered this year', formatTzs(ov.data.orderedThisYear)], ['Received this year', formatTzs(ov.data.receivedThisYear)]].map(([l, v]) => (
            <Grid key={l as string} size={{ xs: 6, sm: 4, md: 2 }}><Card><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
              <Typography variant="caption" color="text.secondary">{l}</Typography>
              <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 18 }}>{v}</Typography>
            </CardContent></Card></Grid>
          ))}
        </Grid>
      )}
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
        <Tab value="requisitions" label="Requisitions" />
        {all && <Tab value="orders" label="Purchase orders" />}
        {all && <Tab value="suppliers" label="Suppliers" />}
      </Tabs>
      {tab === 'requisitions' && <Requisitions all={all} />}
      {tab === 'orders' && all && <Orders />}
      {tab === 'suppliers' && all && <Suppliers canEdit={can(PROCURE)} />}
    </Container>
  );
}

function Requisitions({ all }: { all: boolean }) {
  const navigate = useNavigate();
  const [status, setStatus] = useState('');
  const [mine, setMine] = useState(!all);
  const [adding, setAdding] = useState(false);
  const list = useQuery({ queryKey: ['staff', 'procurement', 'reqs', status, mine], queryFn: () => api<Req[]>(`/api/procurement/requisitions?status=${status}&mine=${mine}`, staff) });
  return (
    <Card><CardContent>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mb: 2 }} alignItems={{ sm: 'center' }}>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setAdding(true)}>Ask to buy</Button>
        <TextField select size="small" label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 200 }}>
          <MenuItem value="">All</MenuItem>{Object.entries(REQ_STATUS).map(([k, v]) => <MenuItem key={k} value={k}>{v.label}</MenuItem>)}</TextField>
        <FormControlLabel control={<Switch checked={mine} onChange={(e) => setMine(e.target.checked)} />} label="Only mine" />
      </Stack>
      <ErrorAlert error={list.error} />
      {list.isLoading && <Loading />}
      {list.data?.length === 0 && <Typography color="text.secondary">No requisitions.</Typography>}
      {!!list.data?.length && (
        <TableContainer>
          <Table size="small" sx={{ minWidth: 760 }}>
            <TableHead><TableRow><TableCell>Requisition</TableCell><TableCell>For</TableCell><TableCell>Asked by</TableCell><TableCell align="right">Estimate</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
            <TableBody>
              {list.data.map((r) => (
                <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/procurement/requisitions/${r.id}`)}>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}><Typography variant="body2" sx={{ fontFamily: 'monospace' }}>{r.reqNo}</Typography><Typography variant="caption" color="text.secondary">{formatDate(r.requestedAt)}</Typography></TableCell>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{r.title}</Typography><Typography variant="caption" color="text.secondary">{r.department ?? ''}</Typography></TableCell>
                  <TableCell>{r.requestedByName}</TableCell>
                  <TableCell align="right">{formatTzs(r.estimatedTotal)}</TableCell>
                  <TableCell><Chip size="small" color={REQ_STATUS[r.status].color} label={REQ_STATUS[r.status].label} />{(r.canEndorse || r.canApprove) && <Chip size="small" sx={{ ml: 0.5 }} color="primary" label="Your decision" />}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {adding && <NewRequisition onClose={() => setAdding(false)} />}
    </CardContent></Card>
  );
}

type NewLine = { description: string; quantity: string; unit: string; estimatedCost: string; target: 'none' | 'store' | 'asset'; storeItemId?: number; assetCategory?: string };

function NewRequisition({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate();
  const departments = useQuery({ queryKey: ['staff', 'departments'], queryFn: () => api<Option[]>('/api/academic/departments', staff) });
  const items = useQuery({ queryKey: ['staff', 'stores', 'items-all'], queryFn: () => api<StoreItem[]>('/api/stores/items', staff).catch(() => [] as StoreItem[]) });
  const [f, setF] = useState({ departmentId: '' as number | '', title: '', justification: '', neededBy: '' });
  const [lines, setLines] = useState<NewLine[]>([{ description: '', quantity: '1', unit: 'piece', estimatedCost: '', target: 'none' }]);
  const setLine = (i: number, l: Partial<NewLine>) => setLines(lines.map((x, j) => (j === i ? { ...x, ...l } : x)));
  const total = lines.reduce((t, l) => t + (Number(l.quantity) || 0) * (Number(l.estimatedCost) || 0), 0);
  const save = useMutation({
    mutationFn: () => api<ReqDetail>('/api/procurement/requisitions', { ...staff, method: 'POST', body: {
      departmentId: f.departmentId || undefined, title: f.title, justification: f.justification, neededBy: f.neededBy || undefined,
      lines: lines.map((l) => ({ description: l.description, quantity: Number(l.quantity), unit: l.unit, estimatedCost: Number(l.estimatedCost),
        storeItemId: l.target === 'store' ? l.storeItemId : undefined, assetCategory: l.target === 'asset' ? l.assetCategory : undefined })) } }),
    onSuccess: (d) => { onClose(); navigate(`/staff/procurement/requisitions/${d.requisition.id}`); },
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Ask to buy</DialogTitle>
      <DialogContent>
        <Grid container spacing={2} sx={{ mt: 0.5 }}>
          <Grid size={{ xs: 12, sm: 8 }}><TextField fullWidth label="What is to be bought" value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} placeholder="Laboratory consumables for Semester 2" /></Grid>
          <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth type="date" label="Needed by" value={f.neededBy} onChange={(e) => setF({ ...f, neededBy: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} /></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><TextField select fullWidth label="Department" value={f.departmentId} onChange={(e) => setF({ ...f, departmentId: e.target.value === '' ? '' : Number(e.target.value) })}
            helperText="Its Head endorses the request"><MenuItem value="">None (office)</MenuItem>{(departments.data ?? []).map((d) => <MenuItem key={d.id} value={d.id}>{d.name}</MenuItem>)}</TextField></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><TextField fullWidth multiline label="Why it is needed" value={f.justification} onChange={(e) => setF({ ...f, justification: e.target.value })} /></Grid>
        </Grid>
        <Typography variant="subtitle1" sx={{ mt: 2 }}>Items</Typography>
        {lines.map((l, i) => (
          <Grid container spacing={1} key={i} sx={{ mt: 0.5, pb: 1, borderBottom: '1px solid', borderColor: 'divider' }} alignItems="center">
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth size="small" label="Description" value={l.description} onChange={(e) => setLine(i, { description: e.target.value })} /></Grid>
            <Grid size={{ xs: 4, sm: 1.5 }}><TextField fullWidth size="small" type="number" label="Quantity" value={l.quantity} onChange={(e) => setLine(i, { quantity: e.target.value })} /></Grid>
            <Grid size={{ xs: 4, sm: 1.5 }}><TextField fullWidth size="small" label="Unit" value={l.unit} disabled={l.target === 'store'} onChange={(e) => setLine(i, { unit: e.target.value })} /></Grid>
            <Grid size={{ xs: 4, sm: 2 }}><TextField fullWidth size="small" type="number" label="Unit cost (est.)" value={l.estimatedCost} onChange={(e) => setLine(i, { estimatedCost: e.target.value })} /></Grid>
            <Grid size={{ xs: 10, sm: 2.5 }}>
              <TextField select fullWidth size="small" label="Goes to" value={l.target === 'store' ? `s${l.storeItemId}` : l.target === 'asset' ? `a${l.assetCategory}` : 'none'}
                onChange={(e) => {
                  const v = e.target.value;
                  if (v === 'none') setLine(i, { target: 'none', storeItemId: undefined, assetCategory: undefined });
                  else if (v.startsWith('s')) { const it = items.data?.find((x) => x.id === Number(v.slice(1))); setLine(i, { target: 'store', storeItemId: Number(v.slice(1)), assetCategory: undefined, unit: it?.unit ?? l.unit, description: l.description || it?.name || '' }); }
                  else setLine(i, { target: 'asset', assetCategory: v.slice(1), storeItemId: undefined });
                }}>
                <MenuItem value="none">The department directly</MenuItem>
                {(items.data ?? []).filter((x) => x.active).map((x) => <MenuItem key={`s${x.id}`} value={`s${x.id}`}>Stores: {x.code} {x.name}</MenuItem>)}
                {Object.entries(CATEGORY).map(([k, v]) => <MenuItem key={`a${k}`} value={`a${k}`}>Asset register: {v}</MenuItem>)}
              </TextField>
            </Grid>
            <Grid size={{ xs: 2, sm: 0.5 }}>{lines.length > 1 && <IconButton size="small" aria-label="Remove item" onClick={() => setLines(lines.filter((_, j) => j !== i))}><DeleteIcon fontSize="small" /></IconButton>}</Grid>
          </Grid>
        ))}
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mt: 1 }}>
          <Button size="small" startIcon={<AddIcon />} onClick={() => setLines([...lines, { description: '', quantity: '1', unit: 'piece', estimatedCost: '', target: 'none' }])}>Add an item</Button>
          <Typography sx={{ fontWeight: 700 }}>Estimate {formatTzs(total)}</Typography>
        </Stack>
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={save.isPending || !f.title || !f.justification || lines.some((l) => !l.description || !l.quantity || l.estimatedCost === '')} onClick={() => save.mutate()}>Send</Button></DialogActions>
    </Dialog>
  );
}

export function RequisitionPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const navigate = useNavigate();
  const can = useRoles();
  const q = useQuery({ queryKey: ['staff', 'procurement', 'req', id], queryFn: () => api<ReqDetail>(`/api/procurement/requisitions/${id}`, staff) });
  const [decide, setDecide] = useState<'endorse' | 'approve' | null>(null);
  const [cancelling, setCancelling] = useState(false);
  const [quoting, setQuoting] = useState(false);
  const [choosing, setChoosing] = useState<Quotation | null>(null);
  const [ordering, setOrdering] = useState(false);
  const done = (d: ReqDetail) => { qc.setQueryData(['staff', 'procurement', 'req', id], d); qc.invalidateQueries({ queryKey: ['staff', 'procurement', 'reqs'] }); };
  const act = useMutation({
    mutationFn: (x: { path: string; body: unknown }) => api<ReqDetail>(x.path, { ...staff, method: 'POST', body: x.body }),
    onSuccess: (d) => { done(d); setDecide(null); setCancelling(false); setChoosing(null); },
  });
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} />;
  const { requisition: r, lines, quotations } = q.data;
  const procure = can(PROCURE);
  return (
    <Container maxWidth="md" disableGutters>
      <PageTitle title={r.title} subtitle={`${r.reqNo}${r.department ? ` · ${r.department}` : ''} · asked by ${r.requestedByName}, ${formatDateTime(r.requestedAt)}`}
        action={<Chip color={REQ_STATUS[r.status].color} label={REQ_STATUS[r.status].label} />} />
      <Card sx={{ mb: 2 }}><CardContent>
        <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{r.justification}</Typography>
        {r.neededBy && <Typography variant="caption" color="text.secondary">Needed by {formatDate(r.neededBy)}</Typography>}
        <Table size="small" sx={{ mt: 1 }}>
          <TableHead><TableRow><TableCell>Item</TableCell><TableCell align="right">Quantity</TableCell><TableCell align="right">Unit cost</TableCell><TableCell align="right">Amount</TableCell><TableCell>Goes to</TableCell></TableRow></TableHead>
          <TableBody>
            {lines.map((l) => (
              <TableRow key={l.id}><TableCell>{l.description}</TableCell><TableCell align="right">{l.quantity} {l.unit}</TableCell><TableCell align="right">{formatTzs(l.estimatedCost)}</TableCell>
                <TableCell align="right">{formatTzs(l.amount)}</TableCell><TableCell>{l.storeItem ? `Stores · ${l.storeItem}` : l.assetCategory ? `Asset register · ${CATEGORY[l.assetCategory]}` : 'Department'}</TableCell></TableRow>
            ))}
            <TableRow><TableCell colSpan={3} sx={{ fontWeight: 700 }}>Estimate</TableCell><TableCell align="right" sx={{ fontWeight: 700 }}>{formatTzs(r.estimatedTotal)}</TableCell><TableCell /></TableRow>
          </TableBody>
        </Table>
        <Stack spacing={0.5} sx={{ mt: 1.5 }}>
          {r.endorsedByName && <Typography variant="caption" color="text.secondary">Endorsed: {r.endorsedByName}{r.endorsedAt ? `, ${formatDateTime(r.endorsedAt)}` : ''}</Typography>}
          {r.approvedByName && <Typography variant="caption" color="text.secondary">{r.status === 'REJECTED' ? 'Turned down' : 'Approved'}: {r.approvedByName}{r.approvedAt ? `, ${formatDateTime(r.approvedAt)}` : ''}</Typography>}
          {r.decisionNote && <Alert severity={r.status === 'REJECTED' || r.status === 'CANCELLED' ? 'warning' : 'info'}>{r.decisionNote}</Alert>}
          {r.lpoNo && <Button size="small" sx={{ alignSelf: 'flex-start' }} onClick={() => navigate(`/staff/procurement/orders/${r.orderId}`)}>Purchase order {r.lpoNo} ›</Button>}
        </Stack>
      </CardContent></Card>
      <Stack direction="row" spacing={1} sx={{ mb: 2 }} flexWrap="wrap" useFlexGap>
        {r.canEndorse && <Button variant="contained" onClick={() => setDecide('endorse')}>Endorse or turn down</Button>}
        {r.canApprove && <Button variant="contained" onClick={() => setDecide('approve')}>Approve or turn down</Button>}
        {['SUBMITTED', 'ENDORSED', 'APPROVED'].includes(r.status) && (r.mine || procure) && <Button color="error" onClick={() => setCancelling(true)}>Cancel the requisition</Button>}
      </Stack>
      <ErrorAlert error={act.error} />
      {(r.status === 'APPROVED' || quotations.length > 0) && can(READ_ALL) && (
        <Card><CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="center"><Typography variant="subtitle1">Quotations</Typography>
            {procure && r.status === 'APPROVED' && <Button size="small" startIcon={<AddIcon />} onClick={() => setQuoting(true)}>Record a quotation</Button>}</Stack>
          {quotations.length === 0 && <Typography variant="body2" color="text.secondary">None yet. Ask suppliers for quotations and record each one here.</Typography>}
          <Table size="small">
            <TableBody>
              {quotations.map((x) => (
                <TableRow key={x.id} selected={x.chosen}>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{x.supplier}</Typography>{x.reference && <Typography variant="caption" color="text.secondary">{x.reference}</Typography>}
                    {x.choiceNote && <Typography variant="caption" component="div">Chosen because: {x.choiceNote}</Typography>}</TableCell>
                  <TableCell align="right">{formatTzs(x.amount)}{x.lowest && <Chip size="small" sx={{ ml: 1 }} label="Lowest" />}</TableCell>
                  <TableCell>{x.deliveryDays != null ? `${x.deliveryDays} days` : ''}</TableCell>
                  <TableCell>{x.fileName && <IconButton size="small" aria-label="Quotation file" onClick={() => download(`/api/procurement/quotations/${x.id}/file`, 'staff', x.fileName!)}><DownloadIcon fontSize="small" /></IconButton>}</TableCell>
                  <TableCell>{x.chosen ? <Chip size="small" color="success" label="Chosen" /> : procure && r.status === 'APPROVED' && <Button size="small" onClick={() => setChoosing(x)}>Choose</Button>}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {procure && r.status === 'APPROVED' && quotations.some((x) => x.chosen) && <Button variant="contained" sx={{ mt: 2 }} onClick={() => setOrdering(true)}>Issue the purchase order</Button>}
        </CardContent></Card>
      )}
      {decide && <DecideDialog kind={decide} busy={act.isPending} onClose={() => setDecide(null)} onSave={(approve, note) => act.mutate({ path: `/api/procurement/requisitions/${id}/${decide}`, body: { approve, note } })} />}
      {cancelling && <NoteDialog title="Cancel the requisition" label="Why it is cancelled" busy={act.isPending} onClose={() => setCancelling(false)} onSave={(note) => act.mutate({ path: `/api/procurement/requisitions/${id}/cancel`, body: { note } })} />}
      {choosing && <NoteDialog title={`Choose ${choosing.supplier}`} label={choosing.lowest ? 'Note (optional)' : 'Why this one and not the lowest (quality, delivery time, after-sales service)'}
        optional={choosing.lowest} busy={act.isPending} onClose={() => setChoosing(null)} onSave={(note) => act.mutate({ path: `/api/procurement/quotations/${choosing.id}/choose`, body: { note } })} />}
      {quoting && <QuotationDialog reqId={r.id} onClose={() => setQuoting(false)} onDone={(d) => { done(d); setQuoting(false); }} />}
      {ordering && <OrderDialog detail={q.data} onClose={() => setOrdering(false)} />}
    </Container>
  );
}

function DecideDialog({ kind, busy, onClose, onSave }: { kind: 'endorse' | 'approve'; busy: boolean; onClose: () => void; onSave: (approve: boolean, note: string) => void }) {
  const [note, setNote] = useState('');
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{kind === 'endorse' ? 'Endorse the requisition' : 'Approve the requisition'}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>{kind === 'endorse' ? 'You confirm the department needs this.' : 'You confirm the funds are available in the budget.'}</Typography>
        <TextField fullWidth multiline minRows={2} label="Note (needed to turn it down)" value={note} onChange={(e) => setNote(e.target.value)} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button color="error" disabled={busy || !note.trim()} onClick={() => onSave(false, note)}>Turn down</Button>
        <Button variant="contained" disabled={busy} onClick={() => onSave(true, note)}>{kind === 'endorse' ? 'Endorse' : 'Approve'}</Button>
      </DialogActions>
    </Dialog>
  );
}

function NoteDialog({ title, label, optional, busy, onClose, onSave }: { title: string; label: string; optional?: boolean; busy: boolean; onClose: () => void; onSave: (note: string) => void }) {
  const [note, setNote] = useState('');
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent><TextField fullWidth multiline minRows={2} label={label} value={note} onChange={(e) => setNote(e.target.value)} sx={{ mt: 1 }} /></DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={busy || (!optional && !note.trim())} onClick={() => onSave(note)}>Save</Button></DialogActions>
    </Dialog>
  );
}

function QuotationDialog({ reqId, onClose, onDone }: { reqId: number; onClose: () => void; onDone: (d: ReqDetail) => void }) {
  const suppliers = useQuery({ queryKey: ['staff', 'procurement', 'suppliers', 'active'], queryFn: () => api<Supplier[]>('/api/procurement/suppliers?activeOnly=true', staff) });
  const [f, setF] = useState({ supplierId: '' as number | '', amount: '', deliveryDays: '', reference: '' });
  const [file, setFile] = useState<File | null>(null);
  const save = useMutation({
    mutationFn: () => {
      const form = new FormData();
      form.append('supplierId', String(f.supplierId));
      form.append('amount', f.amount);
      if (f.deliveryDays) form.append('deliveryDays', f.deliveryDays);
      if (f.reference) form.append('reference', f.reference);
      if (file) form.append('file', file);
      return uploadForm<ReqDetail>(`/api/procurement/requisitions/${reqId}/quotations`, 'staff', form, 'POST');
    },
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Record a quotation</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select label="Supplier" value={f.supplierId} onChange={(e) => setF({ ...f, supplierId: Number(e.target.value) })}
            helperText={suppliers.data?.length === 0 ? 'Add suppliers under the Suppliers tab first' : undefined}>
            {(suppliers.data ?? []).map((s) => <MenuItem key={s.id} value={s.id}>{s.name}</MenuItem>)}</TextField>
          <Stack direction="row" spacing={1}>
            <TextField fullWidth type="number" label="Quoted total (TZS)" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />
            <TextField fullWidth type="number" label="Delivery (days)" value={f.deliveryDays} onChange={(e) => setF({ ...f, deliveryDays: e.target.value })} />
          </Stack>
          <TextField label="Quotation number" value={f.reference} onChange={(e) => setF({ ...f, reference: e.target.value })} />
          <Button component="label" variant="outlined">{file ? file.name : 'Attach the quotation (PDF or picture, optional)'}
            <input hidden type="file" accept="application/pdf,image/jpeg,image/png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></Button>
        </Stack>
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!f.supplierId || !f.amount || save.isPending} onClick={() => save.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

function OrderDialog({ detail, onClose }: { detail: ReqDetail; onClose: () => void }) {
  const navigate = useNavigate();
  const chosen = detail.quotations.find((x) => x.chosen)!;
  const [prices, setPrices] = useState<Record<number, string>>(Object.fromEntries(detail.lines.map((l) => [l.id, String(l.estimatedCost)])));
  const [f, setF] = useState({ deliverBy: '', terms: '' });
  const total = detail.lines.reduce((t, l) => t + l.quantity * (Number(prices[l.id]) || 0), 0);
  const save = useMutation({
    mutationFn: () => api<OrderDetail>('/api/procurement/orders', { ...staff, method: 'POST', body: { requisitionId: detail.requisition.id, deliverBy: f.deliverBy || undefined, terms: f.terms,
      lines: detail.lines.map((l) => ({ requisitionLineId: l.id, unitPrice: Number(prices[l.id]) })) } }),
    onSuccess: (d) => { onClose(); navigate(`/staff/procurement/orders/${d.order.id}`); },
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Purchase order to {chosen.supplier}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary">Enter each item's unit price from the quotation ({formatTzs(chosen.amount)}). The order cannot be more than the quotation.</Typography>
        <Table size="small" sx={{ mt: 1 }}>
          <TableBody>
            {detail.lines.map((l) => (
              <TableRow key={l.id}><TableCell>{l.description}</TableCell><TableCell align="right">{l.quantity} {l.unit}</TableCell>
                <TableCell align="right"><TextField size="small" type="number" label="Unit price" value={prices[l.id]} onChange={(e) => setPrices({ ...prices, [l.id]: e.target.value })} /></TableCell>
                <TableCell align="right">{formatTzs(l.quantity * (Number(prices[l.id]) || 0))}</TableCell></TableRow>
            ))}
            <TableRow><TableCell colSpan={3} sx={{ fontWeight: 700 }}>Total</TableCell><TableCell align="right" sx={{ fontWeight: 700, color: total > chosen.amount ? 'error.main' : undefined }}>{formatTzs(total)}</TableCell></TableRow>
          </TableBody>
        </Table>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 2 }}>
          <TextField type="date" label="Deliver by" value={f.deliverBy} onChange={(e) => setF({ ...f, deliverBy: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField fullWidth label="Terms (delivery place, payment)" value={f.terms} onChange={(e) => setF({ ...f, terms: e.target.value })} />
        </Stack>
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending || total > chosen.amount} onClick={() => save.mutate()}>Issue</Button></DialogActions>
    </Dialog>
  );
}

function Orders() {
  const navigate = useNavigate();
  const [status, setStatus] = useState('OPEN');
  const list = useQuery({ queryKey: ['staff', 'procurement', 'orders', status], queryFn: () => api<Order[]>(`/api/procurement/orders?status=${status}`, staff) });
  return (
    <Card><CardContent>
      <TextField select size="small" label="Show" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ mb: 2, minWidth: 200 }}>
        <MenuItem value="OPEN">Not fully received</MenuItem>{Object.entries(ORDER_STATUS).map(([k, v]) => <MenuItem key={k} value={k}>{v.label}</MenuItem>)}<MenuItem value="">All</MenuItem></TextField>
      <ErrorAlert error={list.error} />
      {list.data?.length === 0 && <Typography color="text.secondary">No purchase orders.</Typography>}
      {!!list.data?.length && (
        <TableContainer>
          <Table size="small" sx={{ minWidth: 700 }}>
            <TableHead><TableRow><TableCell>Order</TableCell><TableCell>Supplier</TableCell><TableCell align="right">Total</TableCell><TableCell align="right">Received</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
            <TableBody>
              {list.data.map((o) => (
                <TableRow key={o.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/procurement/orders/${o.id}`)}>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}><Typography variant="body2" sx={{ fontFamily: 'monospace' }}>{o.lpoNo}</Typography><Typography variant="caption" color="text.secondary">{formatDate(o.issuedAt)} · {o.reqNo}</Typography></TableCell>
                  <TableCell>{o.supplier}</TableCell>
                  <TableCell align="right">{formatTzs(o.total)}</TableCell>
                  <TableCell align="right">{formatTzs(o.receivedValue)}</TableCell>
                  <TableCell><Chip size="small" color={ORDER_STATUS[o.status].color} label={ORDER_STATUS[o.status].label} /></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </CardContent></Card>
  );
}

export function PurchaseOrderPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const can = useRoles();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ['staff', 'procurement', 'order', id], queryFn: () => api<OrderDetail>(`/api/procurement/orders/${id}`, staff) });
  const [receiving, setReceiving] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const cancel = useMutation({
    mutationFn: (note: string) => api<OrderDetail>(`/api/procurement/orders/${id}/cancel`, { ...staff, method: 'POST', body: { note } }),
    onSuccess: (d) => { qc.setQueryData(['staff', 'procurement', 'order', id], d); setCancelling(false); },
  });
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} />;
  const { order: o, lines, receipts } = q.data;
  const open = ['ISSUED', 'PART_RECEIVED'].includes(o.status);
  return (
    <Container maxWidth="md" disableGutters>
      <PageTitle title={`Purchase order ${o.lpoNo}`} subtitle={`${o.supplier} · issued by ${o.issuedByName}, ${formatDateTime(o.issuedAt)}${o.deliverBy ? ` · deliver by ${formatDate(o.deliverBy)}` : ''}`}
        action={<Chip color={ORDER_STATUS[o.status].color} label={ORDER_STATUS[o.status].label} />} />
      <Stack direction="row" spacing={1} sx={{ mb: 2 }} flexWrap="wrap" useFlexGap>
        <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/procurement/orders/${id}/pdf`, 'staff', `${o.lpoNo.replace(/\//g, '-')}.pdf`)}>Print the order (PDF)</Button>
        <Button onClick={() => navigate(`/staff/procurement/requisitions/${o.requisitionId}`)}>Requisition {o.reqNo}</Button>
        {open && can(RECEIVE) && <Button variant="contained" onClick={() => setReceiving(true)}>Receive goods</Button>}
        {o.status === 'ISSUED' && can(PROCURE) && <Button color="error" onClick={() => setCancelling(true)}>Cancel the order</Button>}
      </Stack>
      {o.cancelNote && <Alert severity="warning" sx={{ mb: 2 }}>Cancelled: {o.cancelNote}</Alert>}
      <ErrorAlert error={cancel.error} />
      <Card sx={{ mb: 2 }}><CardContent>
        <Table size="small">
          <TableHead><TableRow><TableCell>Item</TableCell><TableCell align="right">Ordered</TableCell><TableCell align="right">Unit price</TableCell><TableCell align="right">Amount</TableCell><TableCell align="right">Received</TableCell></TableRow></TableHead>
          <TableBody>
            {lines.map((l) => (
              <TableRow key={l.id}><TableCell>{l.description}<Typography variant="caption" color="text.secondary" component="div">{l.storeItem ? `Stores · ${l.storeItem}` : l.assetCategory ? `Asset register · ${CATEGORY[l.assetCategory]}` : 'Department'}</Typography></TableCell>
                <TableCell align="right">{l.quantity} {l.unit}</TableCell><TableCell align="right">{formatTzs(l.unitPrice)}</TableCell><TableCell align="right">{formatTzs(l.amount)}</TableCell>
                <TableCell align="right">{l.received >= l.quantity ? <Chip size="small" color="success" label="All" /> : `${l.received} of ${l.quantity}`}</TableCell></TableRow>
            ))}
            <TableRow><TableCell colSpan={3} sx={{ fontWeight: 700 }}>Total</TableCell><TableCell align="right" sx={{ fontWeight: 700 }}>{formatTzs(o.total)}</TableCell><TableCell /></TableRow>
          </TableBody>
        </Table>
      </CardContent></Card>
      {receipts.length > 0 && (
        <Card><CardContent>
          <Typography variant="subtitle1">Goods received</Typography>
          {receipts.map((g) => (
            <Box key={g.id} sx={{ py: 1, borderBottom: '1px solid', borderColor: 'divider' }}>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>{g.grnNo} · {formatTzs(g.value)}</Typography>
              <Typography variant="caption" color="text.secondary">{formatDateTime(g.receivedAt)} · {g.receivedByName}{g.deliveryNote ? ` · delivery note ${g.deliveryNote}` : ''}{g.invoiceRef ? ` · invoice ${g.invoiceRef}` : ''}</Typography>
              {g.lines.map((l, i) => <Typography key={i} variant="body2">{l.quantity} × {l.description} → {l.into}{l.assetTags?.length ? ` (${l.assetTags.join(', ')})` : ''}</Typography>)}
            </Box>
          ))}
        </CardContent></Card>
      )}
      {receiving && <ReceiveDialog detail={q.data} onClose={() => setReceiving(false)} onDone={(d) => { qc.setQueryData(['staff', 'procurement', 'order', id], d); setReceiving(false); }} />}
      {cancelling && <NoteDialog title="Cancel the order" label="Why it is cancelled" busy={cancel.isPending} onClose={() => setCancelling(false)} onSave={(note) => cancel.mutate(note)} />}
    </Container>
  );
}

function ReceiveDialog({ detail, onClose, onDone }: { detail: OrderDetail; onClose: () => void; onDone: (d: OrderDetail) => void }) {
  const [qty, setQty] = useState<Record<number, string>>(Object.fromEntries(detail.lines.map((l) => [l.id, String(Math.max(0, l.quantity - l.received))])));
  const [f, setF] = useState({ deliveryNote: '', invoiceRef: '', note: '' });
  const save = useMutation({
    mutationFn: () => api<OrderDetail>(`/api/procurement/orders/${detail.order.id}/receipts`, { ...staff, method: 'POST', body: { ...f,
      lines: detail.lines.map((l) => ({ orderLineId: l.id, quantity: Number(qty[l.id]) || 0 })).filter((l) => l.quantity > 0) } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Receive goods</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary">Count what arrived. Stores items go into stores at the order price; assets go into the register, one tag per item (status In store).</Typography>
        <Table size="small" sx={{ mt: 1 }}>
          <TableBody>
            {detail.lines.map((l) => (
              <TableRow key={l.id}><TableCell>{l.description}</TableCell><TableCell align="right">still due {l.quantity - l.received} {l.unit}</TableCell>
                <TableCell align="right"><TextField size="small" type="number" label="Received" value={qty[l.id]} onChange={(e) => setQty({ ...qty, [l.id]: e.target.value })} /></TableCell></TableRow>
            ))}
          </TableBody>
        </Table>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 2 }}>
          <TextField fullWidth label="Delivery note number" value={f.deliveryNote} onChange={(e) => setF({ ...f, deliveryNote: e.target.value })} />
          <TextField fullWidth label="Supplier's invoice number" value={f.invoiceRef} onChange={(e) => setF({ ...f, invoiceRef: e.target.value })} />
        </Stack>
        <TextField fullWidth sx={{ mt: 1 }} label="Note (damaged or short items)" value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} />
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Record the goods received note</Button></DialogActions>
    </Dialog>
  );
}

function Suppliers({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const [q, setQ] = useState('');
  const [edit, setEdit] = useState<Partial<Supplier> | null>(null);
  const list = useQuery({ queryKey: ['staff', 'procurement', 'suppliers', q], queryFn: () => api<Supplier[]>(`/api/procurement/suppliers?q=${encodeURIComponent(q)}`, staff) });
  const save = useMutation({
    mutationFn: () => api<Supplier>(edit?.id ? `/api/procurement/suppliers/${edit.id}` : '/api/procurement/suppliers', { ...staff, method: edit?.id ? 'PUT' : 'POST', body: edit }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['staff', 'procurement', 'suppliers'] }); setEdit(null); },
  });
  return (
    <Card><CardContent>
      <Stack direction="row" spacing={1} sx={{ mb: 2 }}>
        <TextField size="small" fullWidth label="Name or what they supply" value={q} onChange={(e) => setQ(e.target.value)} />
        {canEdit && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setEdit({ active: true })} sx={{ whiteSpace: 'nowrap' }}>Add</Button>}
      </Stack>
      <ErrorAlert error={list.error} />
      {!!list.data?.length && (
        <Table size="small">
          <TableHead><TableRow><TableCell>Supplier</TableCell><TableCell>Supplies</TableCell><TableCell>Contact</TableCell><TableCell align="right">Ordered</TableCell></TableRow></TableHead>
          <TableBody>
            {list.data.map((s) => (
              <TableRow key={s.id} hover sx={{ cursor: canEdit ? 'pointer' : 'default', opacity: s.active ? 1 : 0.5 }} onClick={() => canEdit && setEdit(s)}>
                <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{s.name}</Typography>{s.tin && <Typography variant="caption" color="text.secondary">TIN {s.tin}</Typography>}</TableCell>
                <TableCell>{s.supplies ?? '—'}</TableCell>
                <TableCell>{[s.phone, s.email].filter(Boolean).join(' · ') || '—'}</TableCell>
                <TableCell align="right">{s.orders > 0 ? `${s.orders} · ${formatTzs(s.ordered)}` : '—'}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {list.data?.length === 0 && <Typography color="text.secondary">No suppliers yet.</Typography>}
      {edit && (
        <Dialog open onClose={() => setEdit(null)} fullWidth maxWidth="sm">
          <DialogTitle>{edit.id ? edit.name : 'Add a supplier'}</DialogTitle>
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              <TextField label="Name" value={edit.name ?? ''} onChange={(e) => setEdit({ ...edit, name: e.target.value })} />
              <Stack direction="row" spacing={1}>
                <TextField fullWidth label="TIN" value={edit.tin ?? ''} onChange={(e) => setEdit({ ...edit, tin: e.target.value })} placeholder="123-456-789" />
                <TextField fullWidth label="Phone" value={edit.phone ?? ''} onChange={(e) => setEdit({ ...edit, phone: e.target.value })} />
              </Stack>
              <TextField label="Email" value={edit.email ?? ''} onChange={(e) => setEdit({ ...edit, email: e.target.value })} />
              <TextField label="Address" multiline value={edit.address ?? ''} onChange={(e) => setEdit({ ...edit, address: e.target.value })} />
              <TextField label="What they supply" value={edit.supplies ?? ''} onChange={(e) => setEdit({ ...edit, supplies: e.target.value })} placeholder="Stationery, ICT equipment" />
              {edit.id && <FormControlLabel control={<Switch checked={edit.active ?? true} onChange={(e) => setEdit({ ...edit, active: e.target.checked })} />} label="Still used" />}
            </Stack>
            <ErrorAlert error={save.error} />
          </DialogContent>
          <DialogActions><Button onClick={() => setEdit(null)}>Cancel</Button><Button variant="contained" disabled={!edit.name || save.isPending} onClick={() => save.mutate()}>Save</Button></DialogActions>
        </Dialog>
      )}
    </CardContent></Card>
  );
}
