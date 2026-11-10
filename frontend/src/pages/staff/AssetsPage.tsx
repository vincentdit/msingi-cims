import AddIcon from '@mui/icons-material/Add';
import DownloadIcon from '@mui/icons-material/Download';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, LinearProgress, MenuItem,
  Stack, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, download, formatDate, formatDateTime, formatTzs } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';

const staff = { realm: 'staff' as const };

interface Asset {
  id: number; tagNo: string; name: string; category: string; description?: string; serialNo?: string; campusId?: number; campus?: string; venueId?: number; venue?: string;
  location?: string; departmentId?: number; department?: string; custodianName?: string; acquiredOn?: string; cost?: number; usefulLifeYears?: number; bookValue?: number;
  funding?: string; supplier?: string; condition: string; status: string; disposedOn?: string; disposalNote?: string; lastVerifiedAt?: string;
}
interface AssetDetail { asset: Asset; history: { kind: string; details: string; byName: string; at: string }[] }
interface Overview { assets: number; cost: number; bookValue: number; poorOrWorse: number; underRepair: number; lost: number; notVerifiedThisYear: number }
interface Verification { id: number; name: string; campus?: string; startedByName: string; startedAt: string; closedByName?: string; closedAt?: string; total: number; found: number; missing: number; unchecked: number }
interface VLine { id: number; assetId: number; tagNo: string; name: string; location?: string; department?: string; expectedCondition: string; found?: boolean; condition?: string; note?: string; checkedByName?: string }
interface StoreItem { id: number; code: string; name: string; unit: string; reorderLevel: number; onHand: number; averageCost: number; value: number; low: boolean; active: boolean }
interface Movement { id: number; itemCode: string; itemName: string; unit: string; kind: string; quantity: number; unitCost?: number; balanceAfter: number; department?: string; party?: string; reference?: string; note?: string; byName: string; at: string }
interface Option { id: number; name: string }

const CATEGORY: Record<string, string> = { FURNITURE: 'Furniture', ICT: 'ICT', LAB_EQUIPMENT: 'Laboratory equipment', WORKSHOP: 'Workshop tools and machines',
  VEHICLE: 'Vehicle', BUILDING: 'Building', LAND: 'Land', OFFICE_EQUIPMENT: 'Office equipment', OTHER: 'Other' };
const CONDITION: Record<string, string> = { NEW: 'New', GOOD: 'Good', FAIR: 'Fair', POOR: 'Poor', UNSERVICEABLE: 'Unserviceable' };
const STATUS: Record<string, string> = { IN_USE: 'In use', IN_STORE: 'In store', UNDER_REPAIR: 'Under repair', LOST: 'Lost', DISPOSED: 'Disposed of' };
const FUNDING: Record<string, string> = { GOVERNMENT: 'Government', OWN: 'Own funds', DONOR: 'Donor', OTHER: 'Other' };

function useOptions() {
  const departments = useQuery({ queryKey: ['staff', 'departments'], queryFn: () => api<Option[]>('/api/academic/departments', staff) });
  const campuses = useQuery({ queryKey: ['staff', 'campuses'], queryFn: () => api<Option[]>('/api/academic/campuses', staff).catch(() => [] as Option[]) });
  const venues = useQuery({ queryKey: ['staff', 'venues'], queryFn: () => api<Option[]>('/api/timetable/venues', staff).catch(() => [] as Option[]) });
  return { departments: departments.data ?? [], campuses: campuses.data ?? [], venues: venues.data ?? [] };
}

/** Assets and stores (FR-AST-01..04). */
export default function AssetsPage() {
  const [tab, setTab] = useState('register');
  const canWrite = useRoles()(['INSTITUTION_ADMIN', 'STORES_OFFICER']);
  const ov = useQuery({ queryKey: ['staff', 'assets', 'overview'], queryFn: () => api<Overview>('/api/assets/overview', staff) });
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Assets and stores" subtitle="The asset register, yearly verification, and the stores." />
      {ov.data && (
        <Grid container spacing={1.5} sx={{ mb: 2 }}>
          {[['Assets', ov.data.assets.toLocaleString()], ['Cost', formatTzs(ov.data.cost)], ['Book value', formatTzs(ov.data.bookValue)],
            ['Poor or unserviceable', ov.data.poorOrWorse], ['Not verified this year', ov.data.notVerifiedThisYear]].map(([l, v]) => (
            <Grid key={l as string} size={{ xs: 6, sm: 4, md: 2.4 }}><Card><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
              <Typography variant="caption" color="text.secondary">{l}</Typography>
              <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 20 }}>{v}</Typography>
            </CardContent></Card></Grid>
          ))}
        </Grid>
      )}
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
        <Tab value="register" label="Asset register" />
        <Tab value="verify" label="Verification" />
        <Tab value="stores" label="Stores" />
      </Tabs>
      {tab === 'register' && <Register canWrite={canWrite} />}
      {tab === 'verify' && <Verifications canWrite={canWrite} />}
      {tab === 'stores' && <Stores canWrite={canWrite} />}
    </Container>
  );
}

function Register({ canWrite }: { canWrite: boolean }) {
  const qc = useQueryClient();
  const [q, setQ] = useState('');
  const [category, setCategory] = useState('');
  const [status, setStatus] = useState('');
  const [open, setOpen] = useState<number | 'new' | null>(null);
  const list = useQuery({ queryKey: ['staff', 'assets', 'list', q, category, status],
    queryFn: () => api<Asset[]>(`/api/assets?q=${encodeURIComponent(q)}${category ? `&category=${category}` : ''}${status ? `&status=${status}` : ''}`, staff) });
  return (
    <Card><CardContent>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ mb: 2 }}>
        <TextField size="small" fullWidth label="Tag, name, serial number, place or custodian" value={q} onChange={(e) => setQ(e.target.value)} />
        <TextField select size="small" label="Category" value={category} onChange={(e) => setCategory(e.target.value)} sx={{ minWidth: 190 }}>
          <MenuItem value="">All</MenuItem>{Object.entries(CATEGORY).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField>
        <TextField select size="small" label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 160 }}>
          <MenuItem value="">Not disposed of</MenuItem>{Object.entries(STATUS).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}<MenuItem value="ALL">All</MenuItem></TextField>
        <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/assets/register${status ? `?status=${status}` : ''}`, 'staff', 'asset-register.xlsx')} sx={{ whiteSpace: 'nowrap' }}>Excel</Button>
        {canWrite && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setOpen('new')} sx={{ whiteSpace: 'nowrap' }}>Add</Button>}
      </Stack>
      <ErrorAlert error={list.error} />
      {list.isLoading && <Loading />}
      {list.data?.length === 0 && <Typography color="text.secondary">No assets.</Typography>}
      {!!list.data?.length && (
        <TableContainer>
          <Table size="small" sx={{ minWidth: 820 }}>
            <TableHead><TableRow><TableCell>Tag</TableCell><TableCell>Asset</TableCell><TableCell>Where</TableCell><TableCell>Condition</TableCell><TableCell align="right">Cost</TableCell><TableCell align="right">Book value</TableCell></TableRow></TableHead>
            <TableBody>
              {list.data.map((a) => (
                <TableRow key={a.id} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(a.id)}>
                  <TableCell sx={{ fontFamily: 'monospace', whiteSpace: 'nowrap' }}>{a.tagNo}</TableCell>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{a.name}</Typography><Typography variant="caption" color="text.secondary">{CATEGORY[a.category]}{a.serialNo ? ` · ${a.serialNo}` : ''}</Typography></TableCell>
                  <TableCell>{[a.campus, a.venue ?? a.location, a.department].filter(Boolean).join(', ') || '—'}{a.custodianName && <Typography variant="caption" color="text.secondary" component="div">{a.custodianName}</Typography>}</TableCell>
                  <TableCell>{CONDITION[a.condition]}{a.status !== 'IN_USE' && <Chip size="small" sx={{ ml: 1 }} color={a.status === 'LOST' ? 'error' : 'default'} label={STATUS[a.status]} />}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{a.cost != null ? formatTzs(a.cost) : '—'}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{a.bookValue != null ? formatTzs(a.bookValue) : '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {open !== null && <AssetDialog id={open === 'new' ? null : open} canWrite={canWrite} onClose={() => { setOpen(null); qc.invalidateQueries({ queryKey: ['staff', 'assets'] }); }} />}
    </CardContent></Card>
  );
}

function AssetDialog({ id, canWrite, onClose }: { id: number | null; canWrite: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const { departments, campuses, venues } = useOptions();
  const [assetId, setAssetId] = useState(id);
  const q = useQuery({ queryKey: ['staff', 'assets', 'one', assetId], enabled: assetId !== null, queryFn: () => api<AssetDetail>(`/api/assets/${assetId}`, staff) });
  const [form, setForm] = useState<Partial<Asset> | null>(id === null ? { category: 'FURNITURE', condition: 'GOOD' } : null);
  const [action, setAction] = useState<'move' | 'condition' | 'dispose' | null>(null);
  const a = q.data?.asset;
  const save = useMutation({
    mutationFn: () => api<AssetDetail>(assetId === null ? '/api/assets' : `/api/assets/${assetId}`, { ...staff, method: assetId === null ? 'POST' : 'PUT', body: form }),
    onSuccess: (d) => { setAssetId(d.asset.id); setForm(null); qc.invalidateQueries({ queryKey: ['staff', 'assets'] }); },
  });
  const set = (k: keyof Asset, v: unknown) => setForm({ ...(form ?? {}), [k]: v === '' ? undefined : v });
  const f = form;
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{assetId === null ? 'Add an asset' : a ? `${a.tagNo} · ${a.name}` : 'Asset'}</DialogTitle>
      <DialogContent>
        {f ? (
          <Grid container spacing={2} sx={{ mt: 0.5 }}>
            {assetId === null && <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="Tag (empty: the next number)" value={f.tagNo ?? ''} onChange={(e) => set('tagNo', e.target.value)} /></Grid>}
            <Grid size={{ xs: 12, sm: assetId === null ? 8 : 12 }}><TextField fullWidth label="Name" value={f.name ?? ''} onChange={(e) => set('name', e.target.value)} required /></Grid>
            <Grid size={{ xs: 12, sm: 6 }}><TextField select fullWidth label="Category" value={f.category ?? ''} onChange={(e) => set('category', e.target.value)}>
              {Object.entries(CATEGORY).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField></Grid>
            <Grid size={{ xs: 12, sm: 6 }}><TextField fullWidth label="Serial number" value={f.serialNo ?? ''} onChange={(e) => set('serialNo', e.target.value)} /></Grid>
            <Grid size={12}><TextField fullWidth label="Description" value={f.description ?? ''} onChange={(e) => set('description', e.target.value)} /></Grid>
            {assetId === null && (
              <>
                <Grid size={{ xs: 12, sm: 4 }}><TextField select fullWidth label="Campus" value={f.campusId ?? ''} onChange={(e) => set('campusId', e.target.value === '' ? '' : Number(e.target.value))}>
                  <MenuItem value="">—</MenuItem>{campuses.map((c) => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}</TextField></Grid>
                <Grid size={{ xs: 12, sm: 4 }}><TextField select fullWidth label="Room" value={f.venueId ?? ''} onChange={(e) => set('venueId', e.target.value === '' ? '' : Number(e.target.value))}>
                  <MenuItem value="">—</MenuItem>{venues.map((c) => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}</TextField></Grid>
                <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="Or the place" value={f.location ?? ''} onChange={(e) => set('location', e.target.value)} /></Grid>
                <Grid size={{ xs: 12, sm: 6 }}><TextField select fullWidth label="Department" value={f.departmentId ?? ''} onChange={(e) => set('departmentId', e.target.value === '' ? '' : Number(e.target.value))}>
                  <MenuItem value="">—</MenuItem>{departments.map((c) => <MenuItem key={c.id} value={c.id}>{c.name}</MenuItem>)}</TextField></Grid>
                <Grid size={{ xs: 12, sm: 6 }}><TextField fullWidth label="Looked after by" value={f.custodianName ?? ''} onChange={(e) => set('custodianName', e.target.value)} /></Grid>
                <Grid size={{ xs: 12, sm: 4 }}><TextField select fullWidth label="Condition" value={f.condition ?? 'GOOD'} onChange={(e) => set('condition', e.target.value)}>
                  {Object.entries(CONDITION).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField></Grid>
              </>
            )}
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth type="date" label="Acquired" value={f.acquiredOn ?? ''} onChange={(e) => set('acquiredOn', e.target.value)} slotProps={{ inputLabel: { shrink: true } }} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth type="number" label="Cost (TZS)" value={f.cost ?? ''} onChange={(e) => set('cost', e.target.value === '' ? '' : Number(e.target.value))} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth type="number" label="Useful life (years)" value={f.usefulLifeYears ?? ''} onChange={(e) => set('usefulLifeYears', e.target.value === '' ? '' : Number(e.target.value))} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField select fullWidth label="Paid for by" value={f.funding ?? ''} onChange={(e) => set('funding', e.target.value)}>
              <MenuItem value="">—</MenuItem>{Object.entries(FUNDING).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField></Grid>
            <Grid size={{ xs: 12, sm: 8 }}><TextField fullWidth label="Supplier" value={f.supplier ?? ''} onChange={(e) => set('supplier', e.target.value)} /></Grid>
          </Grid>
        ) : a ? (
          <Box>
            <Grid container spacing={1.5}>
              {[['Category', CATEGORY[a.category]], ['Serial number', a.serialNo], ['Where', [a.campus, a.venue ?? a.location, a.department].filter(Boolean).join(', ')],
                ['Looked after by', a.custodianName], ['Condition', `${CONDITION[a.condition]} · ${STATUS[a.status]}`], ['Acquired', a.acquiredOn && formatDate(a.acquiredOn)],
                ['Cost', a.cost != null ? formatTzs(a.cost) : null], ['Book value', a.bookValue != null ? `${formatTzs(a.bookValue)}${a.usefulLifeYears ? ` (life ${a.usefulLifeYears} years)` : ''}` : null],
                ['Paid for by', a.funding && FUNDING[a.funding]], ['Supplier', a.supplier], ['Last verified', a.lastVerifiedAt && formatDate(a.lastVerifiedAt)],
                ['Disposed of', a.disposedOn && `${formatDate(a.disposedOn)}: ${a.disposalNote}`]].map(([l, v]) => (
                <Grid key={l as string} size={{ xs: 12, sm: 6, md: 4 }}><Typography variant="caption" color="text.secondary">{l}</Typography><Typography variant="body2">{v || '—'}</Typography></Grid>
              ))}
            </Grid>
            {a.description && <Typography variant="body2" sx={{ mt: 1.5 }}>{a.description}</Typography>}
            {canWrite && a.status !== 'DISPOSED' && (
              <Stack direction="row" spacing={1} sx={{ mt: 2 }} flexWrap="wrap" useFlexGap>
                <Button size="small" variant="outlined" onClick={() => setAction('move')}>Move or hand over</Button>
                <Button size="small" variant="outlined" onClick={() => setAction('condition')}>Condition or status</Button>
                <Button size="small" onClick={() => setForm(a)}>Edit details</Button>
                <Button size="small" color="error" onClick={() => setAction('dispose')}>Dispose of</Button>
              </Stack>
            )}
            <Typography variant="subtitle1" sx={{ mt: 2.5 }}>History</Typography>
            <Stack spacing={0.75} sx={{ mt: 0.5 }}>
              {q.data!.history.map((h, i) => (
                <Typography key={i} variant="body2"><Box component="span" sx={{ color: 'text.secondary' }}>{`${formatDateTime(h.at)} · ${h.byName}: `}</Box>{h.details}</Typography>
              ))}
            </Stack>
          </Box>
        ) : <Loading />}
        <ErrorAlert error={save.error ?? q.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        {f && <Button variant="contained" disabled={save.isPending || !f.name} onClick={() => save.mutate()}>Save</Button>}
      </DialogActions>
      {action && a && <AssetActionDialog asset={a} action={action} onClose={() => setAction(null)} onDone={() => { setAction(null); qc.invalidateQueries({ queryKey: ['staff', 'assets'] }); }} />}
    </Dialog>
  );
}

function AssetActionDialog({ asset, action, onClose, onDone }: { asset: Asset; action: 'move' | 'condition' | 'dispose'; onClose: () => void; onDone: () => void }) {
  const { departments, campuses, venues } = useOptions();
  const [f, setF] = useState<Record<string, string | number | undefined>>(action === 'move'
    ? { campusId: asset.campusId, venueId: asset.venueId, location: asset.location, departmentId: asset.departmentId, custodianName: asset.custodianName }
    : action === 'condition' ? { condition: asset.condition, status: asset.status === 'DISPOSED' ? 'IN_USE' : asset.status } : { disposedOn: new Date().toISOString().slice(0, 10) });
  const go = useMutation({ mutationFn: () => api(`/api/assets/${asset.id}/${action}`, { ...staff, method: 'POST', body: f }), onSuccess: onDone });
  const sel = (k: string, label: string, opts: [string | number, string][], blank = true) => (
    <TextField select fullWidth label={label} value={f[k] ?? ''} onChange={(e) => setF({ ...f, [k]: e.target.value === '' ? undefined : (typeof opts[0]?.[0] === 'number' ? Number(e.target.value) : e.target.value) })}>
      {blank && <MenuItem value="">—</MenuItem>}{opts.map(([v, l]) => <MenuItem key={v} value={v}>{l}</MenuItem>)}
    </TextField>
  );
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{action === 'move' ? 'Move or hand over' : action === 'condition' ? 'Condition or status' : 'Dispose of the asset'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {action === 'move' && (
            <>
              {sel('campusId', 'Campus', campuses.map((c) => [c.id, c.name]))}
              {sel('venueId', 'Room', venues.map((c) => [c.id, c.name]))}
              <TextField label="Or the place" value={f.location ?? ''} onChange={(e) => setF({ ...f, location: e.target.value })} />
              {sel('departmentId', 'Department', departments.map((c) => [c.id, c.name]))}
              <TextField label="Looked after by" value={f.custodianName ?? ''} onChange={(e) => setF({ ...f, custodianName: e.target.value })} />
            </>
          )}
          {action === 'condition' && (
            <>
              {sel('condition', 'Condition', Object.entries(CONDITION), false)}
              {sel('status', 'Status', Object.entries(STATUS).filter(([k]) => k !== 'DISPOSED'), false)}
            </>
          )}
          {action === 'dispose' && (
            <>
              <TextField type="date" label="Disposed of on" value={f.disposedOn ?? ''} onChange={(e) => setF({ ...f, disposedOn: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
              <Alert severity="warning">Disposed assets leave the register; record the approval (Board of Survey, auction, write-off) below.</Alert>
            </>
          )}
          <TextField label={action === 'dispose' ? 'How and with whose approval' : 'Note (optional)'} value={f.note ?? ''} onChange={(e) => setF({ ...f, note: e.target.value })} multiline minRows={2} />
          <ErrorAlert error={go.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" color={action === 'dispose' ? 'error' : 'primary'} disabled={go.isPending} onClick={() => go.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

function Verifications({ canWrite }: { canWrite: boolean }) {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['staff', 'assets', 'verifications'], queryFn: () => api<Verification[]>('/api/asset-verifications', staff) });
  const [open, setOpen] = useState<number | null>(null);
  const start = useMutation({ mutationFn: () => api<{ verification: Verification }>('/api/asset-verifications', { ...staff, method: 'POST', body: {} }),
    onSuccess: (d) => { qc.invalidateQueries({ queryKey: ['staff', 'assets'] }); setOpen(d.verification.id); } });
  const running = list.data?.find((v) => !v.closedAt);
  return (
    <Card><CardContent>
      <Stack direction="row" alignItems="center" sx={{ mb: 1.5 }}>
        <Typography variant="body2" color="text.secondary" sx={{ flexGrow: 1 }}>Each asset is looked for and its condition noted; the missing ones are listed for the Board of Survey.</Typography>
        {canWrite && !running && <Button variant="contained" disabled={start.isPending} onClick={() => start.mutate()}>Start a verification</Button>}
      </Stack>
      <ErrorAlert error={list.error ?? start.error} />
      <Stack spacing={1}>
        {list.data?.map((v) => (
          <Box key={v.id} sx={{ p: 1.5, border: 1, borderColor: 'divider', borderRadius: 1, cursor: 'pointer' }} onClick={() => setOpen(v.id)}>
            <Stack direction="row" spacing={1} alignItems="center">
              <Typography sx={{ fontWeight: 600, flexGrow: 1 }}>{v.name}</Typography>
              <Chip size="small" color={v.closedAt ? 'default' : 'warning'} label={v.closedAt ? `Closed ${formatDate(v.closedAt)}` : 'Under way'} />
            </Stack>
            <Typography variant="caption" color="text.secondary">{`${v.found} found, ${v.missing} missing, ${v.unchecked} not checked of ${v.total} · started by ${v.startedByName}, ${formatDate(v.startedAt)}`}</Typography>
            <LinearProgress variant="determinate" value={v.total ? ((v.total - v.unchecked) / v.total) * 100 : 0} sx={{ mt: 0.75, height: 6, borderRadius: 3 }} />
          </Box>
        ))}
      </Stack>
      {open !== null && <VerificationDialog id={open} canWrite={canWrite} onClose={() => { setOpen(null); qc.invalidateQueries({ queryKey: ['staff', 'assets'] }); }} />}
    </CardContent></Card>
  );
}

function VerificationDialog({ id, canWrite, onClose }: { id: number; canWrite: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const key = ['staff', 'assets', 'verification', id];
  const q = useQuery({ queryKey: key, queryFn: () => api<{ verification: Verification; lines: VLine[] }>(`/api/asset-verifications/${id}`, staff) });
  const [tag, setTag] = useState('');
  const [show, setShow] = useState<'todo' | 'all' | 'missing'>('todo');
  const [lost, setLost] = useState(false);
  const scan = useMutation({ mutationFn: () => api(`/api/asset-verifications/${id}/scan`, { ...staff, method: 'POST', body: { tagNo: tag } }),
    onSuccess: () => { setTag(''); qc.invalidateQueries({ queryKey: key }); } });
  const check = useMutation({ mutationFn: ({ l, found, condition }: { l: VLine; found: boolean; condition?: string }) =>
    api(`/api/asset-verification-lines/${l.id}`, { ...staff, method: 'PUT', body: { found, condition } }), onSuccess: () => qc.invalidateQueries({ queryKey: key }) });
  const close = useMutation({ mutationFn: () => api(`/api/asset-verifications/${id}/close`, { ...staff, method: 'POST', body: { markMissingLost: lost } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: key }) });
  const v = q.data?.verification;
  const open = !!v && !v.closedAt && canWrite;
  const lines = (q.data?.lines ?? []).filter((l) => show === 'all' || (show === 'todo' ? l.found == null : l.found === false));
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{v?.name ?? 'Verification'}</DialogTitle>
      <DialogContent>
        {q.isLoading && <Loading />}
        {v && (
          <>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>{`${v.found} found, ${v.missing} missing, ${v.unchecked} not checked of ${v.total}.`}</Typography>
            {open && (
              <form onSubmit={(e) => { e.preventDefault(); scan.mutate(); }}>
                <Stack direction="row" spacing={1} sx={{ mb: 1.5 }}>
                  <TextField size="small" fullWidth label="Scan or type a tag: found where it is" value={tag} onChange={(e) => setTag(e.target.value)} autoFocus />
                  <Button type="submit" variant="contained" disabled={!tag.trim() || scan.isPending}>Found</Button>
                </Stack>
              </form>
            )}
            <ErrorAlert error={scan.error ?? check.error ?? close.error} />
            <ToggleButtonGroup size="small" exclusive value={show} onChange={(_, x) => x && setShow(x)} sx={{ mb: 1 }}>
              <ToggleButton value="todo">Not checked</ToggleButton><ToggleButton value="missing">Missing</ToggleButton><ToggleButton value="all">All</ToggleButton>
            </ToggleButtonGroup>
            <TableContainer sx={{ maxHeight: 420 }}>
              <Table size="small" stickyHeader>
                <TableHead><TableRow><TableCell>Tag</TableCell><TableCell>Asset</TableCell><TableCell>Expected at</TableCell><TableCell>Result</TableCell><TableCell /></TableRow></TableHead>
                <TableBody>
                  {lines.map((l) => (
                    <TableRow key={l.id}>
                      <TableCell sx={{ fontFamily: 'monospace' }}>{l.tagNo}</TableCell>
                      <TableCell>{l.name}</TableCell>
                      <TableCell>{[l.location, l.department].filter(Boolean).join(', ') || '—'}</TableCell>
                      <TableCell>{l.found == null ? '—' : l.found ? `Found${l.condition ? `, ${CONDITION[l.condition]}` : ''}` : 'Missing'}</TableCell>
                      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                        {open && <>
                          <TextField select size="small" value="" label="Found" sx={{ width: 120 }} onChange={(e) => check.mutate({ l, found: true, condition: e.target.value })}>
                            {Object.entries(CONDITION).map(([k, x]) => <MenuItem key={k} value={k}>{x}</MenuItem>)}
                          </TextField>
                          <Button size="small" color="error" onClick={() => check.mutate({ l, found: false })}>Missing</Button>
                        </>}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            {open && (
              <Box sx={{ mt: 2 }}>
                <FormControlLabel control={<Checkbox checked={lost} onChange={(e) => setLost(e.target.checked)} />} label="Mark the missing assets as lost when closing" />
              </Box>
            )}
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/asset-verifications/${id}/report`, 'staff', `asset-verification-${id}.xlsx`)}>Report</Button>
        <Box sx={{ flexGrow: 1 }} />
        <Button onClick={onClose}>Close</Button>
        {open && <Button variant="contained" disabled={close.isPending} onClick={() => { if (window.confirm('Close the verification? Found assets get today\'s date and the condition seen.')) close.mutate(); }}>Finish the verification</Button>}
      </DialogActions>
    </Dialog>
  );
}

function Stores({ canWrite }: { canWrite: boolean }) {
  const qc = useQueryClient();
  const [q, setQ] = useState('');
  const [low, setLow] = useState(false);
  const items = useQuery({ queryKey: ['staff', 'stores', q, low], queryFn: () => api<StoreItem[]>(`/api/stores/items?q=${encodeURIComponent(q)}&low=${low}`, staff) });
  const moves = useQuery({ queryKey: ['staff', 'stores', 'moves'], queryFn: () => api<Movement[]>('/api/stores/movements', staff) });
  const [item, setItem] = useState<Partial<StoreItem> | null>(null);
  const [move, setMove] = useState<{ item: StoreItem; kind: 'RECEIPT' | 'ISSUE' | 'ADJUSTMENT' } | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff', 'stores'] });
  return (
    <Stack spacing={2}>
      <Card><CardContent>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ mb: 2 }} alignItems={{ md: 'center' }}>
          <TextField size="small" fullWidth label="Item code or name" value={q} onChange={(e) => setQ(e.target.value)} />
          <FormControlLabel control={<Checkbox checked={low} onChange={(e) => setLow(e.target.checked)} />} label="To reorder" sx={{ whiteSpace: 'nowrap' }} />
          <Button startIcon={<DownloadIcon />} onClick={() => download('/api/stores/report', 'staff', 'stock-report.xlsx')} sx={{ whiteSpace: 'nowrap' }}>Stock report</Button>
          {canWrite && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setItem({})} sx={{ whiteSpace: 'nowrap' }}>New item</Button>}
        </Stack>
        <ErrorAlert error={items.error} />
        {items.data?.length === 0 && <Typography color="text.secondary">No items.</Typography>}
        {!!items.data?.length && (
          <TableContainer>
            <Table size="small" sx={{ minWidth: 720 }}>
              <TableHead><TableRow><TableCell>Item</TableCell><TableCell align="right">In stock</TableCell><TableCell align="right">Reorder at</TableCell><TableCell align="right">Value</TableCell><TableCell /></TableRow></TableHead>
              <TableBody>
                {items.data.map((i) => (
                  <TableRow key={i.id} sx={{ opacity: i.active ? 1 : 0.5 }}>
                    <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{i.name}</Typography><Typography variant="caption" color="text.secondary">{i.code}</Typography></TableCell>
                    <TableCell align="right">{`${i.onHand.toLocaleString()} ${i.unit}`}{i.low && <Chip size="small" color="warning" label="Reorder" sx={{ ml: 1 }} />}</TableCell>
                    <TableCell align="right">{i.reorderLevel.toLocaleString()}</TableCell>
                    <TableCell align="right">{formatTzs(i.value)}</TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                      {canWrite && <>
                        <Button size="small" onClick={() => setMove({ item: i, kind: 'RECEIPT' })}>Receive</Button>
                        <Button size="small" onClick={() => setMove({ item: i, kind: 'ISSUE' })} disabled={i.onHand <= 0}>Issue</Button>
                        <Button size="small" onClick={() => setMove({ item: i, kind: 'ADJUSTMENT' })}>Adjust</Button>
                        <Button size="small" onClick={() => setItem(i)}>Edit</Button>
                      </>}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </CardContent></Card>
      <Card><CardContent>
        <Typography variant="h6" sx={{ mb: 1 }}>Latest receipts and issues</Typography>
        {moves.data?.length === 0 && <Typography variant="body2" color="text.secondary">Nothing yet.</Typography>}
        {!!moves.data?.length && (
          <TableContainer sx={{ maxHeight: 360 }}>
            <Table size="small" stickyHeader sx={{ minWidth: 720 }}>
              <TableHead><TableRow><TableCell>When</TableCell><TableCell>Item</TableCell><TableCell align="right">Quantity</TableCell><TableCell>From / to</TableCell><TableCell>By</TableCell></TableRow></TableHead>
              <TableBody>{moves.data.slice(0, 100).map((m) => (
                <TableRow key={m.id}>
                  <TableCell>{formatDateTime(m.at)}</TableCell>
                  <TableCell>{m.itemName}</TableCell>
                  <TableCell align="right" sx={{ color: m.quantity < 0 ? 'error.main' : 'success.main' }}>{`${m.quantity > 0 ? '+' : ''}${m.quantity.toLocaleString()} ${m.unit}`}</TableCell>
                  <TableCell>{[m.kind === 'ADJUSTMENT' ? 'Adjustment' : null, m.department, m.party, m.reference, m.note].filter(Boolean).join(' · ')}</TableCell>
                  <TableCell>{m.byName}</TableCell>
                </TableRow>
              ))}</TableBody>
            </Table>
          </TableContainer>
        )}
      </CardContent></Card>
      {item && <StoreItemDialog item={item} onClose={() => setItem(null)} onDone={() => { setItem(null); refresh(); }} />}
      {move && <MovementDialog {...move} onClose={() => setMove(null)} onDone={() => { setMove(null); refresh(); }} />}
    </Stack>
  );
}

function StoreItemDialog({ item, onClose, onDone }: { item: Partial<StoreItem>; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState<Partial<StoreItem>>({ active: true, reorderLevel: 0, ...item });
  const go = useMutation({ mutationFn: () => api(item.id ? `/api/stores/items/${item.id}` : '/api/stores/items', { ...staff, method: item.id ? 'PUT' : 'POST', body: f }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>{item.id ? 'Edit item' : 'New stores item'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {!item.id && <TextField label="Code" value={f.code ?? ''} onChange={(e) => setF({ ...f, code: e.target.value })} />}
          <TextField label="Name" value={f.name ?? ''} onChange={(e) => setF({ ...f, name: e.target.value })} />
          <TextField label="Unit (piece, ream, box, litre)" value={f.unit ?? ''} onChange={(e) => setF({ ...f, unit: e.target.value })} />
          <TextField type="number" label="Reorder when stock falls to" value={f.reorderLevel ?? 0} onChange={(e) => setF({ ...f, reorderLevel: Number(e.target.value) })} />
          {item.id && <FormControlLabel control={<Checkbox checked={f.active !== false} onChange={(e) => setF({ ...f, active: e.target.checked })} />} label="Still stocked" />}
          <ErrorAlert error={go.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={go.isPending} onClick={() => go.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

function MovementDialog({ item, kind, onClose, onDone }: { item: StoreItem; kind: 'RECEIPT' | 'ISSUE' | 'ADJUSTMENT'; onClose: () => void; onDone: () => void }) {
  const { departments } = useOptions();
  const [f, setF] = useState<{ quantity: string; unitCost: string; departmentId?: number; party: string; reference: string; note: string }>({ quantity: '', unitCost: '', party: '', reference: '', note: '' });
  const go = useMutation({
    mutationFn: () => api(`/api/stores/items/${item.id}/movements`, { ...staff, method: 'POST', body: { kind, quantity: Number(f.quantity), unitCost: f.unitCost === '' ? null : Number(f.unitCost),
      departmentId: f.departmentId, party: f.party, reference: f.reference, note: f.note } }),
    onSuccess: onDone,
  });
  const title = { RECEIPT: 'Receive', ISSUE: 'Issue', ADJUSTMENT: 'Adjust the stock' }[kind];
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>{`${title}: ${item.name}`}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <Typography variant="body2" color="text.secondary">{`In stock: ${item.onHand.toLocaleString()} ${item.unit}`}</Typography>
          <TextField type="number" label={kind === 'ADJUSTMENT' ? `Quantity (+ to add, - to remove)` : `Quantity (${item.unit})`} value={f.quantity} onChange={(e) => setF({ ...f, quantity: e.target.value })} />
          {kind === 'RECEIPT' && <TextField type="number" label="Unit cost (TZS)" value={f.unitCost} onChange={(e) => setF({ ...f, unitCost: e.target.value })} />}
          {kind === 'ISSUE' && <TextField select label="Department" value={f.departmentId ?? ''} onChange={(e) => setF({ ...f, departmentId: e.target.value === '' ? undefined : Number(e.target.value) })}>
            <MenuItem value="">—</MenuItem>{departments.map((d) => <MenuItem key={d.id} value={d.id}>{d.name}</MenuItem>)}</TextField>}
          {kind !== 'ADJUSTMENT' && <TextField label={kind === 'RECEIPT' ? 'Supplier' : 'Collected by'} value={f.party} onChange={(e) => setF({ ...f, party: e.target.value })} />}
          {kind !== 'ADJUSTMENT' && <TextField label={kind === 'RECEIPT' ? 'Delivery note or LPO number' : 'Requisition number'} value={f.reference} onChange={(e) => setF({ ...f, reference: e.target.value })} />}
          <TextField label={kind === 'ADJUSTMENT' ? 'Reason (stock take, damage, expiry)' : 'Note (optional)'} value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} />
          <ErrorAlert error={go.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={go.isPending || !f.quantity} onClick={() => go.mutate()}>{title}</Button></DialogActions>
    </Dialog>
  );
}
