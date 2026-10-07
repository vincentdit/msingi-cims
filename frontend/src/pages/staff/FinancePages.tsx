import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import DownloadIcon from '@mui/icons-material/Download';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle,
  FormControlLabel, IconButton, InputAdornment, LinearProgress, MenuItem, Stack, Switch, Tab, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Tabs, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { api, download, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type {
  AcademicYear, ClearanceGateRule, CollectionsReport, DebtorsReport, FeeItem, FeeStructure, Programme,
} from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle, humanise, statusColour } from '../../components/common';

const staff = { realm: 'staff' as const };
const CATEGORIES = ['TUITION', 'REGISTRATION', 'EXAMINATION', 'ACCOMMODATION', 'CAUTION', 'STUDENT_UNION', 'OTHER'];
type TabKey = 'structures' | 'items' | 'gates' | 'collections' | 'debtors';

function useRoles() {
  const { user } = useSession();
  const roles = user('staff')?.roles ?? [];
  return {
    canEdit: roles.some((r) => ['INSTITUTION_ADMIN', 'BURSAR', 'ACCOUNTANT'].includes(r)),
    isBursar: roles.some((r) => ['INSTITUTION_ADMIN', 'BURSAR'].includes(r)),
  };
}

/** Bursar's office (BP-06): fee structures, items, clearance rules, collections and debtors. */
export default function FinancePage() {
  const [tab, setTab] = useState<TabKey>('structures');
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Fees" subtitle="Fee structures approved by the Bursar, billing through GePG, clearance rules and collection reports." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" sx={{ mb: 2 }}>
        <Tab value="structures" label="Fee structures" />
        <Tab value="items" label="Fee items" />
        <Tab value="gates" label="Clearance rules" />
        <Tab value="collections" label="Collections" />
        <Tab value="debtors" label="Debtors" />
      </Tabs>
      {tab === 'structures' && <StructuresTab />}
      {tab === 'items' && <ItemsTab />}
      {tab === 'gates' && <GatesTab />}
      {tab === 'collections' && <CollectionsTab />}
      {tab === 'debtors' && <DebtorsTab />}
    </Container>
  );
}

// ============================================================ fee structures

type LineForm = { feeItemId: string; amount: string; foreignAmount: string; instalment: string };
type StructureForm = { id?: number; programmeId: string; yearOfStudy: string; notes: string; lines: LineForm[] };

function useYears() {
  const years = useQuery({ queryKey: ['staff', 'years'], queryFn: () => api<AcademicYear[]>('/api/academic/years', staff) });
  const [yearId, setYearId] = useState<number | null>(null);
  useEffect(() => {
    if (years.data?.length && yearId == null) setYearId((years.data.find((y) => y.current) ?? years.data[0]).id);
  }, [years.data, yearId]);
  return { years, yearId, setYearId };
}

function StructuresTab() {
  const qc = useQueryClient();
  const { canEdit, isBursar } = useRoles();
  const { years, yearId, setYearId } = useYears();
  const programmes = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const items = useQuery({ queryKey: ['staff', 'fee-items'], queryFn: () => api<FeeItem[]>('/api/finance/fee-items', staff) });
  const list = useQuery({
    queryKey: ['staff', 'fee-structures', yearId], enabled: yearId != null,
    queryFn: () => api<FeeStructure[]>(`/api/finance/fee-structures?academicYearId=${yearId}`, staff),
  });
  const [form, setForm] = useState<StructureForm | null>(null);
  const [viewing, setViewing] = useState<FeeStructure | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff', 'fee-structures'] });
  const save = useMutation({
    mutationFn: (f: StructureForm) => {
      const lines = f.lines.map((l) => ({ feeItemId: Number(l.feeItemId), amount: Number(l.amount), foreignAmount: l.foreignAmount === '' ? null : Number(l.foreignAmount), instalment: Number(l.instalment) }));
      return f.id
        ? api<FeeStructure>(`/api/finance/fee-structures/${f.id}`, { method: 'PUT', ...staff, body: { notes: f.notes, lines } })
        : api<FeeStructure>('/api/finance/fee-structures', { method: 'POST', ...staff, body: { academicYearId: yearId, programmeId: Number(f.programmeId), yearOfStudy: Number(f.yearOfStudy), notes: f.notes, lines } });
    },
    onSuccess: (s) => { setForm(null); setNotice(`Draft saved: ${s.programmeName}, year ${s.yearOfStudy}, version ${s.structureVersion}. It needs the Bursar's approval before it is used for invoices.`); refresh(); },
  });
  const approve = useMutation({
    mutationFn: (id: number) => api<FeeStructure>(`/api/finance/fee-structures/${id}/approve`, { method: 'POST', ...staff }),
    onSuccess: (s) => { setViewing(null); setNotice(`Approved: ${s.programmeName}, year ${s.yearOfStudy}, version ${s.structureVersion}. New invoices use it; invoices already issued keep their amounts.`); refresh(); },
  });
  const revise = useMutation({
    mutationFn: (id: number) => api<FeeStructure>(`/api/finance/fee-structures/${id}/revise`, { method: 'POST', ...staff }),
    onSuccess: (s) => { setViewing(null); refresh(); setForm(toForm(s)); },
  });
  const run = useMutation({
    mutationFn: () => api<{ billed: number; alreadyBilled: number; noStructure: number; programmesWithoutStructure: string[] }>('/api/finance/billing-runs', { method: 'POST', ...staff, body: { academicYearId: yearId } }),
    onSuccess: (r) => setNotice(`Billing run: ${r.billed} invoice(s) issued with control numbers by SMS; ${r.alreadyBilled} already billed.`
      + (r.noStructure ? ` ${r.noStructure} not billed — no approved structure for ${r.programmesWithoutStructure.join('; ')}.` : '')),
  });

  if (years.isLoading) return <Loading />;
  const busy = save.isPending || approve.isPending || revise.isPending || run.isPending;
  const toForm = (s: FeeStructure): StructureForm => ({
    id: s.id, programmeId: String(s.programmeId), yearOfStudy: String(s.yearOfStudy), notes: s.notes ?? '',
    lines: s.lines.map((l) => ({ feeItemId: String(l.feeItemId), amount: String(l.amount), foreignAmount: l.foreignAmount == null ? '' : String(l.foreignAmount), instalment: String(l.instalment) })),
  });
  const blank: StructureForm = { programmeId: '', yearOfStudy: '1', notes: '', lines: [{ feeItemId: '', amount: '', foreignAmount: '', instalment: '1' }] };

  return (
    <>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} justifyContent="space-between" sx={{ mb: 2 }}>
        <TextField select size="small" label="Academic year" value={yearId ?? ''} onChange={(e) => setYearId(Number(e.target.value))} sx={{ minWidth: 200 }}>
          {years.data?.map((y) => <MenuItem key={y.id} value={y.id}>{y.code}</MenuItem>)}
        </TextField>
        {canEdit && (
          <Stack direction="row" spacing={1} sx={{ '& .MuiButton-root': { whiteSpace: 'nowrap' } }}>
            <Tooltip title="Issue annual invoices to new students and accepted applicants of this year who have none">
              <span><Button variant="outlined" startIcon={<ReceiptLongIcon />} disabled={busy} onClick={() => run.mutate()}>Billing run</Button></span>
            </Tooltip>
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => setForm(blank)}>New fee structure</Button>
          </Stack>
        )}
      </Stack>
      {busy && <LinearProgress sx={{ mb: 2 }} />}
      <ErrorAlert error={list.error ?? run.error ?? approve.error ?? revise.error} />
      {notice && <Alert severity="success" onClose={() => setNotice(null)} sx={{ mb: 2 }}>{notice}</Alert>}
      <Card>
        <TableContainer>
          <Table size="small">
            <TableHead><TableRow>
              <TableCell>Programme</TableCell><TableCell align="center">Year</TableCell><TableCell align="center">Version</TableCell><TableCell>Status</TableCell>
              <TableCell align="right">Annual (TZ)</TableCell><TableCell align="right">Other nationalities</TableCell><TableCell align="right">1st instalment</TableCell><TableCell>Approved</TableCell>
            </TableRow></TableHead>
            <TableBody>
              {list.data?.map((s) => (
                <TableRow key={s.id} hover sx={{ cursor: 'pointer', opacity: s.status === 'SUPERSEDED' ? 0.55 : 1 }} onClick={() => setViewing(s)}>
                  <TableCell>{s.programmeName}</TableCell>
                  <TableCell align="center">{s.yearOfStudy}</TableCell>
                  <TableCell align="center">{s.structureVersion}</TableCell>
                  <TableCell><Chip size="small" color={s.status === 'APPROVED' ? 'success' : s.status === 'DRAFT' ? 'warning' : 'default'} label={humanise(s.status)} /></TableCell>
                  <TableCell align="right">{formatTzs(s.total)}</TableCell>
                  <TableCell align="right">{s.foreignTotal !== s.total ? formatTzs(s.foreignTotal) : '—'}</TableCell>
                  <TableCell align="right">{formatTzs(s.firstInstalment)}</TableCell>
                  <TableCell>{s.approvedAt ? <>{formatDate(s.approvedAt)}<Typography variant="caption" display="block" color="text.secondary">{s.approvedByName}</Typography></> : '—'}</TableCell>
                </TableRow>
              ))}
              {!list.data?.length && <TableRow><TableCell colSpan={8}><Typography color="text.secondary" sx={{ py: 2 }}>No fee structures for this academic year yet.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      </Card>

      {/* view */}
      <Dialog open={!!viewing} onClose={() => setViewing(null)} maxWidth="md" fullWidth>
        {viewing && (
          <>
            <DialogTitle>{viewing.programmeName} — year {viewing.yearOfStudy}, {viewing.academicYear}
              <Chip size="small" sx={{ ml: 1 }} color={viewing.status === 'APPROVED' ? 'success' : viewing.status === 'DRAFT' ? 'warning' : 'default'} label={`Version ${viewing.structureVersion} · ${humanise(viewing.status)}`} />
            </DialogTitle>
            <DialogContent>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                Prepared by {viewing.createdByName}{viewing.approvedAt && <> · approved by {viewing.approvedByName} on {formatDateTime(viewing.approvedAt)}</>}
                {viewing.notes && <> · {viewing.notes}</>}
              </Typography>
              <LinesTable s={viewing} />
              {viewing.status === 'DRAFT' && !viewing.canApprove && isBursar && <Alert severity="info" sx={{ mt: 2 }}>You prepared this draft, so another Bursar's office member must approve it.</Alert>}
              <ErrorAlert error={approve.error ?? revise.error} />
            </DialogContent>
            <DialogActions>
              <Button onClick={() => setViewing(null)}>Close</Button>
              {canEdit && viewing.status === 'DRAFT' && <Button onClick={() => { setForm(toForm(viewing)); setViewing(null); }}>Edit</Button>}
              {canEdit && viewing.status === 'APPROVED' && <Button disabled={busy} onClick={() => revise.mutate(viewing.id)}>Revise (new version)</Button>}
              {isBursar && viewing.status === 'DRAFT' && <Button variant="contained" disabled={!viewing.canApprove || busy} onClick={() => approve.mutate(viewing.id)}>Approve</Button>}
            </DialogActions>
          </>
        )}
      </Dialog>

      {/* create / edit */}
      <Dialog open={!!form} onClose={() => setForm(null)} maxWidth="md" fullWidth>
        <DialogTitle>{form?.id ? 'Edit draft fee structure' : 'New fee structure'}</DialogTitle>
        {form && (
          <DialogContent>
            <Grid container spacing={2} sx={{ mt: 0.5 }}>
              <Grid size={{ xs: 12, sm: 8 }}>
                <TextField select fullWidth label="Programme" value={form.programmeId} disabled={!!form.id} onChange={(e) => setForm({ ...form, programmeId: e.target.value })}>
                  {programmes.data?.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.name}</MenuItem>)}
                </TextField>
              </Grid>
              <Grid size={{ xs: 12, sm: 4 }}>
                <TextField select fullWidth label="Year of study" value={form.yearOfStudy} disabled={!!form.id} onChange={(e) => setForm({ ...form, yearOfStudy: e.target.value })}>
                  {[1, 2, 3, 4].map((y) => <MenuItem key={y} value={String(y)}>Year {y}</MenuItem>)}
                </TextField>
              </Grid>
            </Grid>
            <Typography variant="subtitle2" sx={{ mt: 2.5, mb: 1 }}>Fee items</Typography>
            {form.lines.map((l, i) => {
              const set = (k: keyof LineForm, v: string) => setForm({ ...form, lines: form.lines.map((x, j) => (j === i ? { ...x, [k]: v } : x)) });
              return (
                <Grid container spacing={1} key={i} sx={{ mb: 1 }} alignItems="center">
                  <Grid size={{ xs: 12, sm: 4 }}>
                    <TextField select fullWidth size="small" label="Item" value={l.feeItemId} onChange={(e) => set('feeItemId', e.target.value)}>
                      {items.data?.filter((it) => it.active).map((it) => <MenuItem key={it.id} value={String(it.id)}>{it.name}</MenuItem>)}
                    </TextField>
                  </Grid>
                  <Grid size={{ xs: 6, sm: 3 }}>
                    <TextField fullWidth size="small" label="Amount (TZ)" value={l.amount} onChange={(e) => set('amount', e.target.value.replace(/[^\d.]/g, ''))}
                      slotProps={{ input: { startAdornment: <InputAdornment position="start">TZS</InputAdornment> } }} />
                  </Grid>
                  <Grid size={{ xs: 6, sm: 2.5 }}>
                    <TextField fullWidth size="small" label="Other nationalities" placeholder="Same" value={l.foreignAmount} onChange={(e) => set('foreignAmount', e.target.value.replace(/[^\d.]/g, ''))} />
                  </Grid>
                  <Grid size={{ xs: 9, sm: 1.8 }}>
                    <TextField select fullWidth size="small" label="Instalment" value={l.instalment} onChange={(e) => set('instalment', e.target.value)}>
                      {[1, 2, 3, 4].map((n) => <MenuItem key={n} value={String(n)}>{n}</MenuItem>)}
                    </TextField>
                  </Grid>
                  <Grid size={{ xs: 3, sm: 0.7 }}>
                    <IconButton aria-label="Remove line" disabled={form.lines.length === 1} onClick={() => setForm({ ...form, lines: form.lines.filter((_, j) => j !== i) })}><DeleteIcon /></IconButton>
                  </Grid>
                </Grid>
              );
            })}
            <Button size="small" startIcon={<AddIcon />} onClick={() => setForm({ ...form, lines: [...form.lines, { feeItemId: '', amount: '', foreignAmount: '', instalment: '1' }] })}>Add item</Button>
            <Typography sx={{ mt: 1.5 }}>Total (Tanzanian students): <strong>{formatTzs(form.lines.reduce((t, l) => t + (Number(l.amount) || 0), 0))}</strong></Typography>
            <TextField label="Notes" fullWidth sx={{ mt: 2 }} value={form.notes} onChange={(e) => setForm({ ...form, notes: e.target.value })} inputProps={{ maxLength: 500 }} />
            <Box sx={{ mt: 2 }}><ErrorAlert error={save.error} /></Box>
          </DialogContent>
        )}
        <DialogActions>
          <Button onClick={() => setForm(null)}>Cancel</Button>
          <Button variant="contained" disabled={!form || !form.programmeId || form.lines.some((l) => !l.feeItemId || l.amount === '') || save.isPending}
            onClick={() => form && save.mutate(form)}>Save draft</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

function LinesTable({ s }: { s: FeeStructure }) {
  const instalments = [...new Set(s.lines.map((l) => l.instalment))].sort();
  return (
    <Table size="small">
      <TableHead><TableRow><TableCell>Item</TableCell><TableCell>Instalment</TableCell><TableCell align="right">Tanzanian</TableCell><TableCell align="right">Other nationalities</TableCell></TableRow></TableHead>
      <TableBody>
        {s.lines.map((l) => (
          <TableRow key={`${l.feeItemId}-${l.instalment}`}>
            <TableCell>{l.feeItemName}<Typography variant="caption" color="text.secondary" display="block">{l.feeItemCode}</Typography></TableCell>
            <TableCell>{l.instalment}</TableCell>
            <TableCell align="right">{formatTzs(l.amount)}</TableCell>
            <TableCell align="right">{l.foreignAmount != null ? formatTzs(l.foreignAmount) : 'Same'}</TableCell>
          </TableRow>
        ))}
        {instalments.length > 1 && instalments.map((n) => (
          <TableRow key={`i${n}`}><TableCell colSpan={2} sx={{ color: 'text.secondary' }}>Instalment {n}</TableCell>
            <TableCell align="right" sx={{ color: 'text.secondary' }}>{formatTzs(s.lines.filter((l) => l.instalment === n).reduce((t, l) => t + l.amount, 0))}</TableCell><TableCell /></TableRow>
        ))}
        <TableRow>
          <TableCell colSpan={2}><strong>Total per year</strong></TableCell>
          <TableCell align="right"><strong>{formatTzs(s.total)}</strong></TableCell>
          <TableCell align="right"><strong>{formatTzs(s.foreignTotal)}</strong></TableCell>
        </TableRow>
      </TableBody>
    </Table>
  );
}

// ============================================================ fee items

function ItemsTab() {
  const qc = useQueryClient();
  const { canEdit } = useRoles();
  const items = useQuery({ queryKey: ['staff', 'fee-items'], queryFn: () => api<FeeItem[]>('/api/finance/fee-items', staff) });
  type Form = { id?: number; code: string; name: string; category: string; gfsCode: string; allocationPriority: string; active: boolean };
  const [form, setForm] = useState<Form | null>(null);
  const save = useMutation({
    mutationFn: (f: Form) => api<FeeItem>(f.id ? `/api/finance/fee-items/${f.id}` : '/api/finance/fee-items', {
      method: f.id ? 'PUT' : 'POST', ...staff,
      body: { code: f.code.trim().toUpperCase(), name: f.name, category: f.category, gfsCode: f.gfsCode, allocationPriority: Number(f.allocationPriority) || 100, active: f.active },
    }),
    onSuccess: () => { setForm(null); qc.invalidateQueries({ queryKey: ['staff', 'fee-items'] }); },
  });
  if (items.isLoading) return <Loading />;
  return (
    <>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
        <Typography variant="body2" color="text.secondary">Payments are applied to the first instalment, then by priority (lower first) — e.g. the registration fee before tuition.</Typography>
        {canEdit && <Button variant="contained" startIcon={<AddIcon />} sx={{ whiteSpace: 'nowrap', ml: 2 }}
          onClick={() => setForm({ code: '', name: '', category: 'OTHER', gfsCode: '', allocationPriority: '100', active: true })}>New fee item</Button>}
      </Stack>
      <Card><TableContainer><Table size="small">
        <TableHead><TableRow><TableCell>Code</TableCell><TableCell>Name</TableCell><TableCell>Category</TableCell><TableCell>GFS code</TableCell><TableCell align="center">Priority</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
        <TableBody>
          {items.data?.map((i) => (
            <TableRow key={i.id} hover sx={{ cursor: canEdit ? 'pointer' : 'default' }}
              onClick={() => canEdit && setForm({ id: i.id, code: i.code, name: i.name, category: i.category, gfsCode: i.gfsCode ?? '', allocationPriority: String(i.allocationPriority), active: i.active })}>
              <TableCell sx={{ fontFamily: 'monospace' }}>{i.code}</TableCell><TableCell>{i.name}</TableCell><TableCell>{humanise(i.category)}</TableCell>
              <TableCell>{i.gfsCode ?? '—'}</TableCell><TableCell align="center">{i.allocationPriority}</TableCell>
              <TableCell>{i.active ? <Chip size="small" color="success" label="Active" /> : <Chip size="small" label="Inactive" />}</TableCell>
            </TableRow>
          ))}
          {!items.data?.length && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 2 }}>No fee items yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table></TableContainer></Card>
      <Dialog open={!!form} onClose={() => setForm(null)} maxWidth="sm" fullWidth>
        <DialogTitle>{form?.id ? 'Edit fee item' : 'New fee item'}</DialogTitle>
        {form && (
          <DialogContent>
            <Grid container spacing={2} sx={{ mt: 0.5 }}>
              <Grid size={{ xs: 12, sm: 5 }}><TextField label="Code" fullWidth value={form.code} disabled={!!form.id} helperText={form.id ? 'Printed on invoices; cannot change' : 'e.g. TUITION'}
                onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, '') })} inputProps={{ maxLength: 30 }} /></Grid>
              <Grid size={{ xs: 12, sm: 7 }}><TextField label="Name" fullWidth value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></Grid>
              <Grid size={{ xs: 12, sm: 6 }}><TextField select label="Category" fullWidth value={form.category} onChange={(e) => setForm({ ...form, category: e.target.value })}>
                {CATEGORIES.map((c) => <MenuItem key={c} value={c}>{humanise(c)}</MenuItem>)}</TextField></Grid>
              <Grid size={{ xs: 6, sm: 3 }}><TextField label="GFS code" fullWidth value={form.gfsCode} onChange={(e) => setForm({ ...form, gfsCode: e.target.value })} /></Grid>
              <Grid size={{ xs: 6, sm: 3 }}><TextField label="Priority" fullWidth value={form.allocationPriority} onChange={(e) => setForm({ ...form, allocationPriority: e.target.value.replace(/\D/g, '') })} /></Grid>
              <Grid size={12}><FormControlLabel control={<Switch checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />} label="Active (available for new fee structures)" /></Grid>
            </Grid>
            <ErrorAlert error={save.error} />
          </DialogContent>
        )}
        <DialogActions>
          <Button onClick={() => setForm(null)}>Cancel</Button>
          <Button variant="contained" disabled={!form?.code || !form?.name || save.isPending} onClick={() => form && save.mutate(form)}>Save</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

// ============================================================ clearance rules

const GATE_TEXT: Record<string, string> = {
  REGISTRATION: 'Before Registry approves registration', EXAMINATION: 'Before sitting semester examinations', RESULTS: 'Before results are released',
  HOSTEL: 'Before a hostel place is confirmed', GRADUATION: 'Before graduation clearance',
};

function GatesTab() {
  const qc = useQueryClient();
  const { isBursar } = useRoles();
  const gates = useQuery({ queryKey: ['staff', 'gates'], queryFn: () => api<ClearanceGateRule[]>('/api/finance/clearance-gates', staff) });
  const [edits, setEdits] = useState<Record<string, ClearanceGateRule>>({});
  const save = useMutation({
    mutationFn: (g: ClearanceGateRule) => api<ClearanceGateRule[]>(`/api/finance/clearance-gates/${g.gate}`, { method: 'PUT', ...staff, body: g }),
    onSuccess: (d, g) => { qc.setQueryData(['staff', 'gates'], d); setEdits((e) => { const n = { ...e }; delete n[g.gate]; return n; }); },
  });
  if (gates.isLoading) return <Loading />;
  return (
    <Card>
      <CardContent sx={{ pb: 0 }}>
        <Typography variant="body2" color="text.secondary">What must be paid of the year's fees before each step. A rule can require a share of the annual fees, the whole first instalment, or both.</Typography>
      </CardContent>
      <TableContainer><Table size="small">
        <TableHead><TableRow><TableCell>Step</TableCell><TableCell>Enabled</TableCell><TableCell>Minimum paid</TableCell><TableCell>First instalment</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {gates.data?.map((g0) => {
            const g = edits[g0.gate] ?? g0;
            const set = (p: Partial<ClearanceGateRule>) => setEdits({ ...edits, [g0.gate]: { ...g, ...p } });
            return (
              <TableRow key={g.gate}>
                <TableCell>
                  <Typography component="div">{humanise(g.gate)} {g.appliedNow ? <Chip size="small" color="primary" variant="outlined" label="In use" /> : <Chip size="small" variant="outlined" label="Applies when the module is in use" />}</Typography>
                  <Typography variant="caption" color="text.secondary">{GATE_TEXT[g.gate]}</Typography>
                </TableCell>
                <TableCell><Switch checked={g.enabled} disabled={!isBursar} onChange={(e) => set({ enabled: e.target.checked })} /></TableCell>
                <TableCell sx={{ width: 140 }}><TextField size="small" value={g.minPercentPaid} disabled={!isBursar || !g.enabled}
                  onChange={(e) => set({ minPercentPaid: Math.min(100, Number(e.target.value.replace(/[^\d.]/g, '')) || 0) })}
                  slotProps={{ input: { endAdornment: <InputAdornment position="end">%</InputAdornment> } }} /></TableCell>
                <TableCell><FormControlLabel control={<Checkbox checked={g.firstInstalmentRequired} disabled={!isBursar || !g.enabled} onChange={(e) => set({ firstInstalmentRequired: e.target.checked })} />} label="Required" /></TableCell>
                <TableCell align="right">{isBursar && edits[g0.gate] && <Button size="small" variant="contained" disabled={save.isPending} onClick={() => save.mutate(g)}>Save</Button>}</TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table></TableContainer>
      <CardContent><ErrorAlert error={save.error} /></CardContent>
    </Card>
  );
}

// ============================================================ reports

function Tile({ label, value, tone, small }: { label: string; value: ReactNode; tone?: 'success' | 'warning' | 'error'; small?: boolean }) {
  return (
    <Grid size={{ xs: 6, md: 'grow' }}>
      <Card sx={{ height: '100%' }}><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
        <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: small ? 17 : 22, whiteSpace: 'nowrap', color: tone ? `${tone}.main` : undefined }}>{value}</Typography>
      </CardContent></Card>
    </Grid>
  );
}

function CollectionsTab() {
  const today = new Date();
  const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const [from, setFrom] = useState(iso(new Date(today.getFullYear(), today.getMonth(), 1)));
  const [to, setTo] = useState(iso(today));
  const [dlError, setDlError] = useState<unknown>(null);
  const report = useQuery({
    queryKey: ['staff', 'collections', from, to], enabled: !!from && !!to,
    queryFn: () => api<CollectionsReport>(`/api/finance/reports/collections?from=${from}&to=${to}`, staff),
  });
  const r = report.data;
  return (
    <>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }} alignItems={{ sm: 'center' }}>
        <TextField size="small" type="date" label="From" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <TextField size="small" type="date" label="To" value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <Box sx={{ flex: 1 }} />
        <Button variant="outlined" startIcon={<DownloadIcon />} onClick={() => download(`/api/finance/reports/collections.csv?from=${from}&to=${to}`, 'staff', `collections-${from}-to-${to}.csv`).catch(setDlError)}>CSV</Button>
      </Stack>
      <ErrorAlert error={report.error ?? dlError} />
      {report.isLoading && <Loading />}
      {r && (
        <Stack spacing={2.5}>
          <Grid container spacing={1.5}>
            <Tile label="Collected" value={formatTzs(r.total)} tone="success" />
            <Tile label="Payments" value={r.payments} />
            <Tile label="Held as credit" value={formatTzs(r.credit)} />
          </Grid>
          <Grid container spacing={2.5}>
            <Grid size={{ xs: 12, md: 7 }}>
              <Card><CardContent><Typography variant="h6" sx={{ mb: 1 }}>By fee item</Typography>
                <AmountTable rows={r.byFeeItem} total={r.total} />
              </CardContent></Card>
            </Grid>
            <Grid size={{ xs: 12, md: 5 }}>
              <Card><CardContent><Typography variant="h6" sx={{ mb: 1 }}>By day</Typography>
                <AmountTable rows={r.byDay} total={r.total} dates />
              </CardContent></Card>
            </Grid>
          </Grid>
        </Stack>
      )}
    </>
  );
}

function AmountTable({ rows, total, dates }: { rows: CollectionsReport['byFeeItem']; total: number; dates?: boolean }) {
  if (!rows.length) return <Typography color="text.secondary">No payments in this period.</Typography>;
  return (
    <Table size="small">
      <TableBody>
        {rows.map((a) => (
          <TableRow key={a.key}>
            <TableCell>{dates ? formatDate(a.label) : a.label}{!dates && <Typography variant="caption" color="text.secondary" display="block">{a.key}</Typography>}</TableCell>
            <TableCell align="right">{a.count}</TableCell>
            <TableCell align="right">{formatTzs(a.amount)}</TableCell>
            <TableCell align="right" sx={{ color: 'text.secondary', width: 60 }}>{total ? `${Math.round((a.amount / total) * 100)}%` : ''}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function DebtorsTab() {
  const { years, yearId, setYearId } = useYears();
  const [dlError, setDlError] = useState<unknown>(null);
  const report = useQuery({
    queryKey: ['staff', 'debtors', yearId], enabled: yearId != null,
    queryFn: () => api<DebtorsReport>(`/api/finance/reports/debtors?academicYearId=${yearId}`, staff),
  });
  const r = report.data;
  return (
    <>
      <Stack direction="row" spacing={1.5} sx={{ mb: 2 }} alignItems="center">
        <TextField select size="small" label="Academic year" value={yearId ?? ''} onChange={(e) => setYearId(Number(e.target.value))} sx={{ minWidth: 200 }}>
          {years.data?.map((y) => <MenuItem key={y.id} value={y.id}>{y.code}</MenuItem>)}
        </TextField>
        <Box sx={{ flex: 1 }} />
        <Button variant="outlined" startIcon={<DownloadIcon />} onClick={() => download(`/api/finance/reports/debtors.csv?academicYearId=${yearId}`, 'staff', 'debtors.csv').catch(setDlError)}>CSV</Button>
      </Stack>
      <ErrorAlert error={report.error ?? dlError} />
      {report.isLoading && <Loading />}
      {r && (
        <Stack spacing={2.5}>
          <Grid container spacing={1.5}>
            <Tile small label="Owed in total" value={formatTzs(r.totalBalance)} tone={r.totalBalance ? 'warning' : undefined} />
            {r.byBucket.map((b) => <Tile small key={b.key} label={`${b.label} (${b.count})`} value={formatTzs(b.amount)} tone={b.key === 'OVER_90' && b.amount ? 'error' : undefined} />)}
          </Grid>
          <Card><TableContainer><Table size="small">
            <TableHead><TableRow><TableCell>Payer</TableCell><TableCell>Invoice</TableCell><TableCell align="right">Billed</TableCell><TableCell align="right">Paid</TableCell>
              <TableCell align="right">Balance</TableCell><TableCell>Due</TableCell><TableCell>Ageing</TableCell></TableRow></TableHead>
            <TableBody>
              {r.rows.map((d) => (
                <TableRow key={d.invoiceId}>
                  <TableCell>{d.payerName}<Typography variant="caption" color="text.secondary" display="block">{d.registrationNo ?? 'Applicant (not yet handed over)'}{d.phone ? ` · ${d.phone}` : ''}</Typography></TableCell>
                  <TableCell>{d.invoiceNo}<Typography variant="caption" color="text.secondary" display="block">{d.description}</Typography></TableCell>
                  <TableCell align="right">{formatTzs(d.total)}</TableCell>
                  <TableCell align="right">{formatTzs(d.paid)}</TableCell>
                  <TableCell align="right"><strong>{formatTzs(d.balance)}</strong></TableCell>
                  <TableCell>{formatDate(d.dueDate)}</TableCell>
                  <TableCell><Chip size="small" color={d.bucket === 'NOT_DUE' ? 'default' : d.bucket === 'OVER_90' ? 'error' : 'warning'}
                    label={d.bucket === 'NOT_DUE' ? 'Not yet due' : `${d.daysOverdue} days overdue`} /></TableCell>
                </TableRow>
              ))}
              {!r.rows.length && <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 2 }}>Nobody owes fees for this academic year.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table></TableContainer></Card>
        </Stack>
      )}
    </>
  );
}

// ============================================================ shared: a student's fees (used on the student record)

export function StatusChip({ status }: { status: string }) {
  return <Chip size="small" color={statusColour(status)} label={humanise(status)} />;
}
