import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import DownloadIcon from '@mui/icons-material/Download';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, MenuItem,
  Stack, Switch, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime, formatTzs } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';

const staff = { realm: 'staff' as const };
const PREPARE = ['INSTITUTION_ADMIN', 'HR_OFFICER', 'ACCOUNTANT'];
const APPROVE = ['INSTITUTION_ADMIN', 'BURSAR', 'MANAGEMENT_VIEWER'];
const PAY = ['INSTITUTION_ADMIN', 'BURSAR', 'ACCOUNTANT'];

interface Band { from: number; rate: number }
interface Settings {
  pensionFund: 'NSSF' | 'PSSSF'; pensionEmployeeRate: number; pensionEmployerRate: number; pensionOnBasic: boolean; payeBands: Band[]; wcfRate: number; sdlApplies: boolean;
  sdlRate: number; nhifApplies: boolean; nhifEmployeeRate: number; nhifEmployerRate: number; heslbRate: number; updatedByName?: string; updatedAt?: string;
}
interface PayItem { id: number; kind: 'ALLOWANCE' | 'DEDUCTION'; name: string; amount: number; taxable: boolean; balance?: number; active: boolean }
interface Calc { gross: number; pensionEmployee: number; taxableIncome: number; paye: number; nhifEmployee: number; heslb: number; otherDeductions: number; net: number }
interface PayDetails {
  staffId: number; staffNo: string; fullName: string; jobTitle?: string; department?: string; onPayroll: boolean; basicSalary: number; bankName?: string; bankBranch?: string;
  bankAccount?: string; tin?: string; pensionNo?: string; nhifNo?: string; heslbLoan: boolean; heslbIndexNo?: string; items: PayItem[]; preview: Calc;
}
interface PayRow { staffId: number; staffNo: string; fullName: string; department?: string; category: string; onPayroll: boolean; basicSalary: number; allowances: number; deductions: number; bankReady: boolean }
interface Run {
  id: number; period: string; month: string; status: 'DRAFT' | 'APPROVED' | 'PAID'; staffCount: number; grossTotal: number; netTotal: number; employerTotal: number;
  preparedByName: string; preparedAt: string; approvedByName?: string; approvedAt?: string; paidByName?: string; paidAt?: string; paidOn?: string; canApprove: boolean; note?: string;
}
interface Line { kind: string; name: string; amount: number }
interface Slip {
  id: number; staffId: number; staffNo: string; fullName: string; department?: string; bankName?: string; bankAccount?: string; basic: number; gross: number;
  pensionEmployee: number; paye: number; nhifEmployee: number; heslb: number; otherDeductions: number; totalDeductions: number; net: number; pensionEmployer: number;
  wcf: number; sdl: number; nhifEmployer: number; lines: Line[]; warning?: string;
}
interface Totals { basic: number; gross: number; pensionEmployee: number; pensionEmployer: number; paye: number; nhifEmployee: number; nhifEmployer: number; heslb: number; otherDeductions: number; net: number; wcf: number; sdl: number }
interface RunDetail { run: Run; slips: Slip[]; totals: Totals; problems: string[] }
interface MySlip { id: number; month: string; period: string; gross: number; totalDeductions: number; net: number; paidOn?: string }

const STATUS: Record<string, { label: string; color: 'default' | 'warning' | 'info' | 'success' }> = {
  DRAFT: { label: 'Draft · to approve', color: 'warning' }, APPROVED: { label: 'Approved · to pay', color: 'info' }, PAID: { label: 'Paid', color: 'success' },
};

/** Payroll (FR-PAY-01..05). */
export default function PayrollPage() {
  const [tab, setTab] = useState('runs');
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Payroll" subtitle="Salaries each month: prepared by Human Resources or the Accountant, approved by the Bursar or the Principal, then paid." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
        <Tab value="runs" label="Monthly payroll" />
        <Tab value="staff" label="Pay details" />
        <Tab value="rates" label="Rates" />
      </Tabs>
      {tab === 'runs' && <Runs />}
      {tab === 'staff' && <PayStaff />}
      {tab === 'rates' && <Rates />}
    </Container>
  );
}

function Runs() {
  const navigate = useNavigate();
  const canPrepare = useRoles()(PREPARE);
  const runs = useQuery({ queryKey: ['staff', 'payroll', 'runs'], queryFn: () => api<Run[]>('/api/payroll/runs', staff) });
  const now = new Date();
  const [month, setMonth] = useState(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`);
  const prepare = useMutation({
    mutationFn: () => api<RunDetail>('/api/payroll/runs', { ...staff, method: 'POST', body: { month } }),
    onSuccess: (d) => navigate(`/staff/payroll/runs/${d.run.id}`),
  });
  return (
    <Card><CardContent>
      {canPrepare && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mb: 2 }} alignItems={{ sm: 'center' }}>
          <TextField size="small" type="month" label="Month" value={month} onChange={(e) => setMonth(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <Button variant="contained" startIcon={<AddIcon />} disabled={prepare.isPending || !month} onClick={() => prepare.mutate()}>Prepare the payroll</Button>
          <Typography variant="caption" color="text.secondary">Preparing a month again works it out afresh, as long as it is not yet approved.</Typography>
        </Stack>
      )}
      <ErrorAlert error={prepare.error ?? runs.error} />
      {runs.isLoading && <Loading />}
      {runs.data?.length === 0 && <Typography color="text.secondary">No payroll yet. Fill in each person's pay details first.</Typography>}
      {!!runs.data?.length && (
        <TableContainer>
          <Table size="small" sx={{ minWidth: 700 }}>
            <TableHead><TableRow><TableCell>Month</TableCell><TableCell>Status</TableCell><TableCell align="right">Staff</TableCell><TableCell align="right">Gross</TableCell>
              <TableCell align="right">Net pay</TableCell><TableCell align="right">Employer costs</TableCell><TableCell>Prepared by</TableCell></TableRow></TableHead>
            <TableBody>
              {runs.data.map((r) => (
                <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/payroll/runs/${r.id}`)}>
                  <TableCell sx={{ fontWeight: 600 }}>{r.month}</TableCell>
                  <TableCell><Chip size="small" color={STATUS[r.status].color} label={STATUS[r.status].label} /></TableCell>
                  <TableCell align="right">{r.staffCount}</TableCell>
                  <TableCell align="right">{formatTzs(r.grossTotal)}</TableCell>
                  <TableCell align="right">{formatTzs(r.netTotal)}</TableCell>
                  <TableCell align="right">{formatTzs(r.employerTotal)}</TableCell>
                  <TableCell>{r.preparedByName}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </CardContent></Card>
  );
}

export function PayrollRunPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const can = useRoles();
  const q = useQuery({ queryKey: ['staff', 'payroll', 'run', id], queryFn: () => api<RunDetail>(`/api/payroll/runs/${id}`, staff) });
  const [returning, setReturning] = useState(false);
  const [note, setNote] = useState('');
  const [paidOn, setPaidOn] = useState(new Date().toISOString().slice(0, 10));
  const act = useMutation({
    mutationFn: (x: { path: string; body?: unknown; method?: string }) => api<RunDetail>(`/api/payroll/runs/${id}${x.path}`, { ...staff, method: x.method ?? 'POST', body: x.body }),
    onSuccess: (d, x) => {
      if (x.method === 'DELETE') { navigate('/staff/payroll'); return; }
      qc.setQueryData(['staff', 'payroll', 'run', id], d); qc.invalidateQueries({ queryKey: ['staff', 'payroll', 'runs'] }); setReturning(false);
    },
  });
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} />;
  const { run, slips, totals, problems } = q.data;
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title={`Payroll, ${run.month}`} subtitle={`Prepared by ${run.preparedByName} on ${formatDateTime(run.preparedAt)}`
        + (run.approvedByName ? ` · approved by ${run.approvedByName}` : '') + (run.paidOn ? ` · paid on ${formatDate(run.paidOn)}` : '')}
        action={<Chip color={STATUS[run.status].color} label={STATUS[run.status].label} />} />
      <Grid container spacing={1.5} sx={{ mb: 2 }}>
        {[['Staff', run.staffCount], ['Gross pay', formatTzs(totals.gross)], ['PAYE', formatTzs(totals.paye)], ['Pension (both shares)', formatTzs(totals.pensionEmployee + totals.pensionEmployer)],
          ['Net pay to banks', formatTzs(totals.net)], ['Employer costs', formatTzs(run.employerTotal)]].map(([l, v]) => (
          <Grid key={l as string} size={{ xs: 6, sm: 4, md: 2 }}><Card><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
            <Typography variant="caption" color="text.secondary">{l}</Typography>
            <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 18 }}>{v}</Typography>
          </CardContent></Card></Grid>
        ))}
      </Grid>
      {problems.length > 0 && <Alert severity="warning" sx={{ mb: 2 }}>{problems.map((p) => <div key={p}>{p}</div>)}</Alert>}
      {run.note && <Alert severity="info" sx={{ mb: 2 }}>{run.note}</Alert>}
      <Stack direction="row" spacing={1} sx={{ mb: 2 }} flexWrap="wrap" useFlexGap>
        {run.status === 'DRAFT' && can(PREPARE) && <Button variant="outlined" disabled={act.isPending}
          onClick={() => api<RunDetail>('/api/payroll/runs', { ...staff, method: 'POST', body: { month: run.period.slice(0, 7) } }).then((d) => qc.setQueryData(['staff', 'payroll', 'run', id], d))}>Work it out again</Button>}
        {run.canApprove && <Button variant="contained" disabled={act.isPending} onClick={() => act.mutate({ path: '/approve' })}>Approve</Button>}
        {run.status === 'APPROVED' && can(APPROVE) && <Button disabled={act.isPending} onClick={() => setReturning(true)}>Send back for correction</Button>}
        {run.status === 'APPROVED' && can(PAY) && (
          <>
            <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/payroll/runs/${id}/bank-file`, 'staff', `salaries-${run.period.slice(0, 7)}.csv`)}>Bank file</Button>
            <TextField size="small" type="date" label="Paid on" value={paidOn} onChange={(e) => setPaidOn(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
            <Button variant="contained" disabled={act.isPending} onClick={() => act.mutate({ path: '/paid', body: { paidOn } })}>Record as paid</Button>
          </>
        )}
        {run.status === 'PAID' && can(PAY) && <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/payroll/runs/${id}/bank-file`, 'staff', `salaries-${run.period.slice(0, 7)}.csv`)}>Bank file</Button>}
        <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/payroll/runs/${id}/workbook`, 'staff', `payroll-${run.period.slice(0, 7)}.xlsx`)}>Payroll and schedules (Excel)</Button>
        {run.status === 'DRAFT' && can(PREPARE) && <Button color="error" startIcon={<DeleteIcon />} disabled={act.isPending} onClick={() => act.mutate({ path: '', method: 'DELETE' })}>Delete the draft</Button>}
      </Stack>
      <ErrorAlert error={act.error} />
      <Card><CardContent>
        <TableContainer>
          <Table size="small" sx={{ minWidth: 980 }}>
            <TableHead><TableRow><TableCell>Staff</TableCell><TableCell align="right">Basic</TableCell><TableCell align="right">Gross</TableCell><TableCell align="right">Pension</TableCell>
              <TableCell align="right">PAYE</TableCell><TableCell align="right">NHIF</TableCell><TableCell align="right">HESLB</TableCell><TableCell align="right">Other</TableCell>
              <TableCell align="right">Net pay</TableCell><TableCell>Bank</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {slips.map((s) => (
                <TableRow key={s.id}>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{s.fullName}</Typography>
                    <Typography variant="caption" color="text.secondary">{s.staffNo}{s.department ? ` · ${s.department}` : ''}</Typography>
                    {s.warning && <Typography variant="caption" color="warning.main" component="div">{s.warning}</Typography>}</TableCell>
                  {[s.basic, s.gross, s.pensionEmployee, s.paye, s.nhifEmployee, s.heslb, s.otherDeductions].map((v, i) => <TableCell key={i} align="right">{formatTzs(v)}</TableCell>)}
                  <TableCell align="right" sx={{ fontWeight: 700 }}>{formatTzs(s.net)}</TableCell>
                  <TableCell>{s.bankName ? `${s.bankName} ${s.bankAccount ?? ''}` : <Typography variant="caption" color="warning.main">None</Typography>}</TableCell>
                  <TableCell><IconButton size="small" aria-label="Payslip" onClick={() => download(`/api/payroll/payslips/${s.id}/pdf`, 'staff', `payslip-${s.staffNo}.pdf`)}><DownloadIcon fontSize="small" /></IconButton></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </CardContent></Card>
      {returning && (
        <Dialog open onClose={() => setReturning(false)} fullWidth maxWidth="sm">
          <DialogTitle>Send back for correction</DialogTitle>
          <DialogContent><TextField fullWidth multiline minRows={3} label="What is to be corrected" value={note} onChange={(e) => setNote(e.target.value)} sx={{ mt: 1 }} /></DialogContent>
          <DialogActions><Button onClick={() => setReturning(false)}>Cancel</Button>
            <Button variant="contained" disabled={!note.trim() || act.isPending} onClick={() => act.mutate({ path: '/return', body: { note } })}>Send back</Button></DialogActions>
        </Dialog>
      )}
    </Container>
  );
}

function PayStaff() {
  const [q, setQ] = useState('');
  const [open, setOpen] = useState<number | null>(null);
  const list = useQuery({ queryKey: ['staff', 'payroll', 'staff', q], queryFn: () => api<PayRow[]>(`/api/payroll/staff?q=${encodeURIComponent(q)}`, staff) });
  return (
    <Card><CardContent>
      <TextField size="small" fullWidth label="Name or staff number" value={q} onChange={(e) => setQ(e.target.value)} sx={{ mb: 2 }} />
      <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 1 }}>Staff records come from Human resources. Click a person to set their salary, bank and deductions.</Typography>
      <ErrorAlert error={list.error} />
      {list.isLoading && <Loading />}
      {!!list.data?.length && (
        <TableContainer>
          <Table size="small" sx={{ minWidth: 700 }}>
            <TableHead><TableRow><TableCell>Staff</TableCell><TableCell>Department</TableCell><TableCell align="right">Basic salary</TableCell><TableCell align="right">Allowances</TableCell>
              <TableCell align="right">Deductions</TableCell><TableCell>Payroll</TableCell></TableRow></TableHead>
            <TableBody>
              {list.data.map((r) => (
                <TableRow key={r.staffId} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(r.staffId)}>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{r.fullName}</Typography><Typography variant="caption" color="text.secondary">{r.staffNo}</Typography></TableCell>
                  <TableCell>{r.department ?? '—'}</TableCell>
                  <TableCell align="right">{r.basicSalary > 0 ? formatTzs(r.basicSalary) : '—'}</TableCell>
                  <TableCell align="right">{r.allowances > 0 ? formatTzs(r.allowances) : '—'}</TableCell>
                  <TableCell align="right">{r.deductions > 0 ? formatTzs(r.deductions) : '—'}</TableCell>
                  <TableCell>{!r.onPayroll || r.basicSalary === 0 ? <Chip size="small" label="Not on payroll" /> : !r.bankReady ? <Chip size="small" color="warning" label="No bank account" /> : <Chip size="small" color="success" label="Ready" />}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {open !== null && <PayDialog staffId={open} onClose={() => setOpen(null)} />}
    </CardContent></Card>
  );
}

function PayDialog({ staffId, onClose }: { staffId: number; onClose: () => void }) {
  const qc = useQueryClient();
  const canEdit = useRoles()(PREPARE);
  const q = useQuery({ queryKey: ['staff', 'payroll', 'person', staffId], queryFn: () => api<PayDetails>(`/api/payroll/staff/${staffId}`, staff) });
  const [form, setForm] = useState<Partial<PayDetails> | null>(null);
  const [item, setItem] = useState<Partial<PayItem> | null>(null);
  const done = (d: PayDetails) => { qc.setQueryData(['staff', 'payroll', 'person', staffId], d); qc.invalidateQueries({ queryKey: ['staff', 'payroll', 'staff'] }); };
  const save = useMutation({ mutationFn: () => api<PayDetails>(`/api/payroll/staff/${staffId}`, { ...staff, method: 'PUT', body: form }), onSuccess: (d) => { done(d); setForm(null); } });
  const saveItem = useMutation({
    mutationFn: (x: Partial<PayItem>) => api<PayDetails>(x.id ? `/api/payroll/items/${x.id}` : `/api/payroll/staff/${staffId}/items`, { ...staff, method: x.id ? 'PUT' : 'POST', body: x }),
    onSuccess: (d) => { done(d); setItem(null); },
  });
  const d = q.data;
  const f = form;
  const set = (k: keyof PayDetails, v: unknown) => setForm({ ...(form ?? {}), [k]: v });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{d ? `${d.fullName} · ${d.staffNo}` : 'Pay details'}</DialogTitle>
      <DialogContent>
        {!d ? <Loading /> : f ? (
          <Grid container spacing={2} sx={{ mt: 0.5 }}>
            <Grid size={{ xs: 12, sm: 6 }}><TextField fullWidth type="number" label="Basic salary a month (TZS)" value={f.basicSalary ?? ''} onChange={(e) => set('basicSalary', Number(e.target.value))} /></Grid>
            <Grid size={{ xs: 12, sm: 6 }}><FormControlLabel control={<Switch checked={f.onPayroll ?? true} onChange={(e) => set('onPayroll', e.target.checked)} />} label="On the payroll" /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="Bank" value={f.bankName ?? ''} onChange={(e) => set('bankName', e.target.value)} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="Branch" value={f.bankBranch ?? ''} onChange={(e) => set('bankBranch', e.target.value)} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="Account number" value={f.bankAccount ?? ''} onChange={(e) => set('bankAccount', e.target.value)} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="TIN" value={f.tin ?? ''} onChange={(e) => set('tin', e.target.value)} placeholder="123-456-789" /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="Pension fund member number" value={f.pensionNo ?? ''} onChange={(e) => set('pensionNo', e.target.value)} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="NHIF number" value={f.nhifNo ?? ''} onChange={(e) => set('nhifNo', e.target.value)} /></Grid>
            <Grid size={{ xs: 12, sm: 6 }}><FormControlLabel control={<Checkbox checked={!!f.heslbLoan} onChange={(e) => set('heslbLoan', e.target.checked)} />} label="Repays a HESLB loan" /></Grid>
            {f.heslbLoan && <Grid size={{ xs: 12, sm: 6 }}><TextField fullWidth label="Form four index number (HESLB)" value={f.heslbIndexNo ?? ''} onChange={(e) => set('heslbIndexNo', e.target.value)} /></Grid>}
          </Grid>
        ) : (
          <Box>
            <Grid container spacing={1.5}>
              {[['Basic salary', d.basicSalary > 0 ? formatTzs(d.basicSalary) : null], ['Bank', d.bankName && `${d.bankName}${d.bankBranch ? `, ${d.bankBranch}` : ''} · ${d.bankAccount ?? ''}`],
                ['TIN', d.tin], ['Pension number', d.pensionNo], ['NHIF number', d.nhifNo], ['HESLB loan', d.heslbLoan ? `Yes · ${d.heslbIndexNo}` : 'No'],
                ['On the payroll', d.onPayroll ? 'Yes' : 'No']].map(([l, v]) => (
                <Grid key={l as string} size={{ xs: 12, sm: 6, md: 4 }}><Typography variant="caption" color="text.secondary">{l}</Typography><Typography variant="body2">{v || '—'}</Typography></Grid>
              ))}
            </Grid>
            {canEdit && <Button size="small" sx={{ mt: 1 }} onClick={() => setForm(d)}>Edit</Button>}
            <Typography variant="subtitle1" sx={{ mt: 2 }}>Allowances and deductions every month</Typography>
            <Table size="small">
              <TableBody>
                {d.items.map((i) => (
                  <TableRow key={i.id} sx={{ opacity: i.active ? 1 : 0.5 }}>
                    <TableCell>{i.kind === 'ALLOWANCE' ? 'Allowance' : 'Deduction'}</TableCell>
                    <TableCell>{i.name}{i.kind === 'ALLOWANCE' && !i.taxable ? ' (not taxed)' : ''}{!i.active ? ' · stopped' : ''}</TableCell>
                    <TableCell align="right">{formatTzs(i.amount)}</TableCell>
                    <TableCell>{i.balance != null ? `Still owed ${formatTzs(i.balance)}` : ''}</TableCell>
                    <TableCell>{canEdit && <Button size="small" onClick={() => setItem(i)}>Change</Button>}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            {canEdit && <Button size="small" startIcon={<AddIcon />} sx={{ mt: 1 }} onClick={() => setItem({ kind: 'ALLOWANCE', taxable: true, active: true })}>Add</Button>}
            {d.basicSalary > 0 && (
              <Alert severity="info" sx={{ mt: 2 }}>
                A month's pay now: gross {formatTzs(d.preview.gross)}, pension {formatTzs(d.preview.pensionEmployee)}, PAYE {formatTzs(d.preview.paye)}
                {d.preview.nhifEmployee > 0 ? `, NHIF ${formatTzs(d.preview.nhifEmployee)}` : ''}{d.preview.heslb > 0 ? `, HESLB ${formatTzs(d.preview.heslb)}` : ''}
                {d.preview.otherDeductions > 0 ? `, other ${formatTzs(d.preview.otherDeductions)}` : ''}; net <b>{formatTzs(d.preview.net)}</b>.
              </Alert>
            )}
          </Box>
        )}
        {item && (
          <Grid container spacing={2} sx={{ mt: 2 }}>
            {!item.id && <Grid size={{ xs: 12, sm: 4 }}><TextField select fullWidth label="Kind" value={item.kind} onChange={(e) => setItem({ ...item, kind: e.target.value as PayItem['kind'] })}>
              <MenuItem value="ALLOWANCE">Allowance</MenuItem><MenuItem value="DEDUCTION">Deduction</MenuItem></TextField></Grid>}
            <Grid size={{ xs: 12, sm: item.id ? 6 : 4 }}><TextField fullWidth label="Name" value={item.name ?? ''} onChange={(e) => setItem({ ...item, name: e.target.value })}
              placeholder={item.kind === 'ALLOWANCE' ? 'Housing, Transport, Responsibility' : 'SACCOS, Salary advance, Union'} /></Grid>
            <Grid size={{ xs: 12, sm: item.id ? 6 : 4 }}><TextField fullWidth type="number" label="Amount a month (TZS)" value={item.amount ?? ''} onChange={(e) => setItem({ ...item, amount: Number(e.target.value) })} /></Grid>
            {item.kind === 'ALLOWANCE'
              ? <Grid size={12}><FormControlLabel control={<Checkbox checked={item.taxable ?? true} onChange={(e) => setItem({ ...item, taxable: e.target.checked })} />} label="Taxed (part of taxable income)" /></Grid>
              : <Grid size={{ xs: 12, sm: 6 }}><TextField fullWidth type="number" label="Still owed (for a loan or advance)" helperText="Empty: a standing deduction" value={item.balance ?? ''}
                onChange={(e) => setItem({ ...item, balance: e.target.value === '' ? undefined : Number(e.target.value) })} /></Grid>}
            {item.id && <Grid size={12}><FormControlLabel control={<Switch checked={item.active ?? true} onChange={(e) => setItem({ ...item, active: e.target.checked })} />} label="Active" /></Grid>}
            <Grid size={12}><Stack direction="row" spacing={1}><Button onClick={() => setItem(null)}>Cancel</Button>
              <Button variant="contained" disabled={!item.name || !item.amount || saveItem.isPending} onClick={() => saveItem.mutate(item)}>Save</Button></Stack></Grid>
          </Grid>
        )}
        <ErrorAlert error={save.error ?? saveItem.error ?? q.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        {f && <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Save</Button>}
      </DialogActions>
    </Dialog>
  );
}

function Rates() {
  const qc = useQueryClient();
  const canEdit = useRoles()(['INSTITUTION_ADMIN', 'HR_OFFICER', 'BURSAR']);
  const q = useQuery({ queryKey: ['staff', 'payroll', 'settings'], queryFn: () => api<Settings>('/api/payroll/settings', staff) });
  const [f, setF] = useState<Settings | null>(null);
  const save = useMutation({ mutationFn: () => api<Settings>('/api/payroll/settings', { ...staff, method: 'PUT', body: f }), onSuccess: (d) => { qc.setQueryData(['staff', 'payroll', 'settings'], d); setF(null); } });
  if (!q.data) return q.isLoading ? <Loading /> : <ErrorAlert error={q.error} />;
  const s = f ?? q.data;
  const edit = !!f;
  const num = (k: keyof Settings, label: string) => (
    <TextField fullWidth size="small" type="number" label={label} value={s[k] as number} disabled={!edit} onChange={(e) => setF({ ...s, [k]: Number(e.target.value) })} />
  );
  const fund = (v: 'NSSF' | 'PSSSF') => setF({ ...s, pensionFund: v, pensionEmployeeRate: v === 'NSSF' ? 10 : 5, pensionEmployerRate: v === 'NSSF' ? 10 : 15, pensionOnBasic: v === 'PSSSF' });
  return (
    <Card><CardContent>
      <Alert severity="info" sx={{ mb: 2 }}>These follow Tanzania mainland law as CIMS was set up. Check them with TRA, the pension fund and your auditor when the Finance Act changes, and change them here.</Alert>
      <Grid container spacing={2}>
        <Grid size={{ xs: 12, sm: 4 }}><TextField select fullWidth size="small" label="Pension fund" value={s.pensionFund} disabled={!edit} onChange={(e) => fund(e.target.value as 'NSSF' | 'PSSSF')}>
          <MenuItem value="NSSF">NSSF (private institutions)</MenuItem><MenuItem value="PSSSF">PSSSF (public institutions)</MenuItem></TextField></Grid>
        <Grid size={{ xs: 6, sm: 2 }}>{num('pensionEmployeeRate', 'Employee %')}</Grid>
        <Grid size={{ xs: 6, sm: 2 }}>{num('pensionEmployerRate', 'Employer %')}</Grid>
        <Grid size={{ xs: 12, sm: 4 }}><FormControlLabel control={<Switch checked={s.pensionOnBasic} disabled={!edit} onChange={(e) => setF({ ...s, pensionOnBasic: e.target.checked })} />} label="On basic salary (not gross)" /></Grid>
        <Grid size={{ xs: 6, sm: 3 }}>{num('wcfRate', 'WCF %')}</Grid>
        <Grid size={{ xs: 6, sm: 3 }}>{num('heslbRate', 'HESLB % of basic')}</Grid>
        <Grid size={{ xs: 12, sm: 3 }}><FormControlLabel control={<Switch checked={s.sdlApplies} disabled={!edit} onChange={(e) => setF({ ...s, sdlApplies: e.target.checked })} />} label="SDL is charged" /></Grid>
        <Grid size={{ xs: 6, sm: 3 }}>{num('sdlRate', 'SDL %')}</Grid>
        <Grid size={{ xs: 12, sm: 4 }}><FormControlLabel control={<Switch checked={s.nhifApplies} disabled={!edit} onChange={(e) => setF({ ...s, nhifApplies: e.target.checked })} />} label="NHIF is deducted" /></Grid>
        <Grid size={{ xs: 6, sm: 2 }}>{num('nhifEmployeeRate', 'NHIF employee %')}</Grid>
        <Grid size={{ xs: 6, sm: 2 }}>{num('nhifEmployerRate', 'NHIF employer %')}</Grid>
      </Grid>
      <Typography variant="subtitle1" sx={{ mt: 3 }}>PAYE bands (monthly, residents)</Typography>
      <Table size="small" sx={{ maxWidth: 480 }}>
        <TableHead><TableRow><TableCell>From (TZS)</TableCell><TableCell>Rate %</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {s.payeBands.map((b, i) => (
            <TableRow key={i}>
              <TableCell><TextField size="small" type="number" value={b.from} disabled={!edit} onChange={(e) => setF({ ...s, payeBands: s.payeBands.map((x, j) => j === i ? { ...x, from: Number(e.target.value) } : x) })} /></TableCell>
              <TableCell><TextField size="small" type="number" value={b.rate} disabled={!edit} onChange={(e) => setF({ ...s, payeBands: s.payeBands.map((x, j) => j === i ? { ...x, rate: Number(e.target.value) } : x) })} /></TableCell>
              <TableCell>{edit && i > 0 && <IconButton size="small" aria-label="Remove band" onClick={() => setF({ ...s, payeBands: s.payeBands.filter((_, j) => j !== i) })}><DeleteIcon fontSize="small" /></IconButton>}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {edit && <Button size="small" startIcon={<AddIcon />} onClick={() => setF({ ...s, payeBands: [...s.payeBands, { from: 0, rate: 0 }] })}>Add a band</Button>}
      {q.data.updatedByName && <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 2 }}>Last changed by {q.data.updatedByName}{q.data.updatedAt ? `, ${formatDateTime(q.data.updatedAt)}` : ''}</Typography>}
      <ErrorAlert error={save.error} />
      {canEdit && (
        <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
          {!edit ? <Button variant="outlined" onClick={() => setF(q.data!)}>Change the rates</Button> : (
            <><Button onClick={() => setF(null)}>Cancel</Button><Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Save</Button></>
          )}
        </Stack>
      )}
    </CardContent></Card>
  );
}

/** Every member of staff: their paid payslips. */
export function MyPayslipsPage() {
  const q = useQuery({ queryKey: ['staff', 'my', 'payslips'], queryFn: () => api<MySlip[]>('/api/my/payslips', staff) });
  return (
    <Container maxWidth="md" disableGutters>
      <PageTitle title="My payslips" subtitle="Each month's payslip once salaries are paid." />
      {q.isLoading && <Loading />}
      <ErrorAlert error={q.error} />
      {q.data?.length === 0 && <Typography color="text.secondary">No payslips yet.</Typography>}
      {!!q.data?.length && (
        <Card><CardContent>
          <Table size="small">
            <TableHead><TableRow><TableCell>Month</TableCell><TableCell align="right">Gross</TableCell><TableCell align="right">Deductions</TableCell><TableCell align="right">Net pay</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {q.data.map((s) => (
                <TableRow key={s.id}>
                  <TableCell sx={{ fontWeight: 600 }}>{s.month}{s.paidOn && <Typography variant="caption" color="text.secondary" component="div">Paid {formatDate(s.paidOn)}</Typography>}</TableCell>
                  <TableCell align="right">{formatTzs(s.gross)}</TableCell>
                  <TableCell align="right">{formatTzs(s.totalDeductions)}</TableCell>
                  <TableCell align="right" sx={{ fontWeight: 700 }}>{formatTzs(s.net)}</TableCell>
                  <TableCell><Button size="small" startIcon={<DownloadIcon />} onClick={() => download(`/api/payroll/payslips/${s.id}/pdf`, 'staff', `payslip-${s.period.slice(0, 7)}.pdf`)}>PDF</Button></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent></Card>
      )}
    </Container>
  );
}
