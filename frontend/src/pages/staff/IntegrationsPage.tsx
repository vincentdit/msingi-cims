import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Stack,
  Switch, Tab, Table, TableBody, TableCell, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type { AdmissionWindow } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { StaffLink } from '../../components/StaffLink';
import { useGepg } from '../../components/HowToPay';

const staff = { realm: 'staff' as const };

interface Settings {
  gepgSpCode?: string | null; gepgSubSpCode?: string | null; gepgSystemId?: string | null; gepgGfsCode?: string | null;
  heslbEnabled: boolean; heslbCode?: string | null; nidaEnabled: boolean; museEnabled: boolean; museVoteCode?: string | null; museCashAccount?: string | null;
  tamisemiEnabled: boolean; tamisemiCode?: string | null;
  nhifEnabled: boolean; nhifCode?: string | null; nhifContribution?: number | null; nhifRequired: boolean;
}
interface SettingsView { settings: Settings; modes: Record<string, string>; institutionCode: string; updatedByName?: string }
interface Problem { key: string; message: string }
interface Exchange { id: number; kind: string; academicYear?: string; externalRef?: string; sent: number; accepted: number; problems: Problem[]; summary: string; doneByName: string; doneAt: string }
interface Disbursement { id: number; batchNo: string; disbursedOn: string; amount: number; students: number; status: string; invoiceNo?: string; recordedByName?: string }
interface HeslbOverview {
  enabled: boolean; institutionCode: string; academicYear: string; sponsored: number; sponsoredCap: number; exchanges: Exchange[];
  disbursements: Disbursement[]; openInvoices: { id: number; invoiceNo: string; balance: number }[];
}
interface MuseLine { account: string; gfsCode?: string; description: string; debit: number; credit: number }
interface MusePreview { from: string; to: string; voteCode?: string; lines: MuseLine[]; total: number; problems: string[] }
interface MusePosting { id: number; batchRef: string; from: string; to: string; total: number; lines: number; status: string; museRef?: string; message?: string; postedByName: string; postedAt: string }
interface Fetch { id: number; year: number; selections: number; importBatchId?: number; batchNo?: string; summary: string; doneByName: string; doneAt: string }
interface RegBatch { id: number; academicYear: string; batchRef: string; status: string; students: number; registered: number; queried: number; submittedByName: string; submittedAt: string; checkedAt?: string }
interface RegOverview { academicYear: string; waiting: number; withoutVerification: number; batches: RegBatch[] }
interface RegItem { studentId: number; registrationNo: string; name: string; programmeCode: string; status: string; reason?: string }

const has = (roles: string[], ...r: string[]) => roles.some((x) => r.includes(x));
const SYSTEMS: Record<string, string> = {
  GEPG: 'GePG', NACTVET: 'NACTVET verification', NACTVET_SUBMISSION: 'NACTVET registration & results', HESLB: 'HESLB', NIDA: 'NIDA', MUSE: 'MUSE', TAMISEMI: 'TAMISEMI', NHIF: 'NHIF',
};

/** The institution's government integrations (FR-INT-03/05/06/07/08, FR-REG-07): settings and the exchanges staff start. */
export default function IntegrationsPage() {
  const gepg = useGepg();
  const { user } = useSession();
  const roles = user('staff')?.roles ?? [];
  const tabs = [
    { key: 'settings', label: 'Settings', show: true },
    { key: 'nactvet', label: 'NACTVET registration', show: has(roles, 'INSTITUTION_ADMIN', 'REGISTRAR') },
    { key: 'heslb', label: 'HESLB', show: has(roles, 'INSTITUTION_ADMIN', 'REGISTRAR', 'BURSAR', 'ACCOUNTANT') },
    { key: 'muse', label: 'MUSE postings', show: has(roles, 'INSTITUTION_ADMIN', 'BURSAR', 'ACCOUNTANT') },
    { key: 'tamisemi', label: 'TAMISEMI selections', show: has(roles, 'INSTITUTION_ADMIN', 'ADMISSIONS_OFFICER') },
  ].filter((t) => t.show);
  const [tab, setTab] = useState(tabs[0].key);
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Integrations" subtitle={gepg ? 'Your codes at GePG, HESLB, MUSE, TAMISEMI and NHIF, and the exchanges with NACTVET, HESLB, MUSE and TAMISEMI.' : 'Your codes at HESLB, MUSE, TAMISEMI and NHIF, and the exchanges with NACTVET, HESLB, MUSE and TAMISEMI.'} />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" allowScrollButtonsMobile sx={{ mb: 2 }}>
        {tabs.map((t) => <Tab key={t.key} value={t.key} label={t.label} />)}
      </Tabs>
      {tab === 'settings' && <SettingsTab canEdit={has(roles, 'INSTITUTION_ADMIN', 'BURSAR')} />}
      {tab === 'nactvet' && <NactvetTab />}
      {tab === 'heslb' && <HeslbTab registry={has(roles, 'INSTITUTION_ADMIN', 'REGISTRAR')} bursary={has(roles, 'INSTITUTION_ADMIN', 'BURSAR', 'ACCOUNTANT')} />}
      {tab === 'muse' && <MuseTab />}
      {tab === 'tamisemi' && <TamisemiTab />}
    </Container>
  );
}

function SettingsTab({ canEdit }: { canEdit: boolean }) {
  const gepg = useGepg();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['integration-settings'], queryFn: () => api<SettingsView>('/api/integration-settings', staff) });
  const [s, setS] = useState<Settings | null>(null);
  useEffect(() => { if (q.data) setS(q.data.settings); }, [q.data]);
  const save = useMutation({
    mutationFn: () => api<SettingsView>('/api/integration-settings', { ...staff, method: 'PUT', body: s }),
    onSuccess: (v) => qc.setQueryData(['integration-settings'], v),
  });
  if (q.isLoading || !s) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  const text = (k: keyof Settings, label: string, help?: string) => (
    <TextField label={label} value={(s[k] as string) ?? ''} helperText={help} disabled={!canEdit} fullWidth
      onChange={(e) => setS({ ...s, [k]: e.target.value })} />
  );
  const flag = (k: keyof Settings, label: string) => (
    <FormControlLabel control={<Switch checked={!!s[k]} disabled={!canEdit} onChange={(e) => setS({ ...s, [k]: e.target.checked })} />} label={label} />
  );
  return (
    <Stack spacing={2}>
      <Card><CardContent>
        <Typography variant="subtitle2" gutterBottom>Connections</Typography>
        <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">
          {Object.entries(q.data!.modes).filter(([k]) => gepg || s.nhifEnabled || k !== 'GEPG').map(([k, v]) => (
            <Chip key={k} size="small" color={v === 'LIVE' ? 'success' : 'default'} variant={v === 'LIVE' ? 'filled' : 'outlined'}
              label={`${SYSTEMS[k] ?? k}: ${v === 'LIVE' ? 'live' : 'simulated'}`} />
          ))}
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Msingi switches each connection to live once the body has given access. You are known to NACTVET and HESLB as <b>{q.data!.institutionCode}</b> unless you enter another code.
        </Typography>
      </CardContent></Card>
      {(gepg || s.nhifEnabled) && <Card><CardContent>
        <Typography variant="subtitle2" gutterBottom>{gepg ? 'GePG' : 'GePG (NHIF contributions)'}</Typography>
        {!gepg && <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>Fees are paid into your own accounts, but NHIF contributions are government payments and always go through GePG with a control number.</Typography>}
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>{text('gepgSpCode', 'SP code', 'e.g. SP19921')}</Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>{text('gepgSubSpCode', 'Sub SP code', 'Collection account')}</Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>{text('gepgSystemId', 'System id', 'Registered with GePG')}</Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>{text('gepgGfsCode', 'Default GFS code', 'For fee items without one')}</Grid>
        </Grid>
      </CardContent></Card>}
      <Card><CardContent>
        <Typography variant="subtitle2" gutterBottom>Optional exchanges</Typography>
        <Grid container spacing={2} alignItems="center">
          <Grid size={{ xs: 12, sm: 4 }}>{flag('heslbEnabled', 'HESLB loans')}</Grid>
          <Grid size={{ xs: 12, sm: 8 }}>{text('heslbCode', 'Code at HESLB (optional)')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{flag('tamisemiEnabled', 'TAMISEMI selections')}</Grid>
          <Grid size={{ xs: 12, sm: 8 }}>{text('tamisemiCode', 'Code at TAMISEMI (optional)')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{flag('museEnabled', 'MUSE fee postings')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{text('museVoteCode', 'Vote code')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{text('museCashAccount', 'Bank account in MUSE')}</Grid>
          <Grid size={{ xs: 12 }}>{flag('nidaEnabled', 'Check National Identification Numbers with NIDA')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{flag('nhifEnabled', 'NHIF health insurance')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{text('nhifCode', 'Code at NHIF (optional)')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>
            <TextField label="NHIF student contribution (TZS)" type="number" fullWidth disabled={!canEdit} value={s.nhifContribution ?? ''}
              helperText={gepg ? 'Per year; 0 when the institution pays NHIF itself' : 'Per year, paid through GePG with a control number; 0 when the institution pays NHIF itself'} inputProps={{ min: 0, step: 100 }}
              onChange={(e) => setS({ ...s, nhifContribution: e.target.value === '' ? null : Number(e.target.value) })} />
          </Grid>
          <Grid size={{ xs: 12 }}>{flag('nhifRequired', 'Students need health insurance before semester registration')}</Grid>
        </Grid>
        <ErrorAlert error={save.error} />
        {save.isSuccess && <Alert severity="success" sx={{ mt: 1 }}>Saved.</Alert>}
        {canEdit && <Box sx={{ mt: 2 }}><Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending}>Save</Button></Box>}
        {q.data!.updatedByName && <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>Last changed by {q.data!.updatedByName}</Typography>}
      </CardContent></Card>
    </Stack>
  );
}

function ExchangeTable({ rows }: { rows: Exchange[] }) {
  const [open, setOpen] = useState<Exchange | null>(null);
  if (!rows.length) return <Typography color="text.secondary" sx={{ p: 2 }}>Nothing exchanged yet.</Typography>;
  return (
    <>
      <Box sx={{ overflowX: 'auto' }}><Table size="small">
        <TableHead><TableRow><TableCell>When</TableCell><TableCell>What</TableCell><TableCell>Year</TableCell><TableCell>Result</TableCell><TableCell>By</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>{rows.map((e) => (
          <TableRow key={e.id}>
            <TableCell>{formatDateTime(e.doneAt)}</TableCell><TableCell>{e.kind.charAt(0) + e.kind.slice(1).toLowerCase()}</TableCell><TableCell>{e.academicYear}</TableCell>
            <TableCell>{e.summary}</TableCell><TableCell>{e.doneByName}</TableCell>
            <TableCell>{e.problems.length > 0 && <Button size="small" color="warning" onClick={() => setOpen(e)}>{e.problems.length} to look at</Button>}</TableCell>
          </TableRow>
        ))}</TableBody>
      </Table></Box>
      <Dialog open={!!open} onClose={() => setOpen(null)} fullWidth maxWidth="sm">
        <DialogTitle>Needs attention</DialogTitle>
        <DialogContent><Table size="small"><TableBody>{open?.problems.map((p, i) => (
          <TableRow key={i}><TableCell>{p.key}</TableCell><TableCell>{p.message}</TableCell></TableRow>
        ))}</TableBody></Table></DialogContent>
        <DialogActions><Button onClick={() => setOpen(null)}>Close</Button></DialogActions>
      </Dialog>
    </>
  );
}

function HeslbTab({ registry, bursary }: { registry: boolean; bursary: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['heslb'], queryFn: () => api<HeslbOverview>('/api/heslb', staff) });
  const done = () => qc.invalidateQueries({ queryKey: ['heslb'] });
  const send = useMutation({ mutationFn: (kind: string) => api<Exchange>(`/api/heslb/registrations?kind=${kind}`, { ...staff, method: 'POST' }), onSuccess: done });
  const ben = useMutation({ mutationFn: () => api<Exchange>('/api/heslb/beneficiaries', { ...staff, method: 'POST' }), onSuccess: done });
  const dis = useMutation({ mutationFn: () => api<Exchange>('/api/heslb/disbursements', { ...staff, method: 'POST' }), onSuccess: done });
  const [recording, setRecording] = useState<Disbursement | null>(null);
  const [invoiceId, setInvoiceId] = useState<number | ''>('');
  const record = useMutation({
    mutationFn: () => api(`/api/heslb/disbursements/${recording!.id}/record?invoiceId=${invoiceId}`, { ...staff, method: 'POST' }),
    onSuccess: () => { setRecording(null); done(); },
  });
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} />;
  const o = q.data;
  const last = send.data ?? ben.data ?? dis.data;
  return (
    <Stack spacing={2}>
      {!o.enabled && <Alert severity="info">The HESLB exchange is switched off. Switch it on under Settings.</Alert>}
      <Card><CardContent>
        <Grid container spacing={2} alignItems="center">
          <Grid size={{ xs: 12, md: 4 }}>
            <Typography variant="overline" color="text.secondary">{o.academicYear}</Typography>
            <Typography variant="h5">{o.sponsored} student{o.sponsored === 1 ? '' : 's'} with HESLB loans</Typography>
            <Typography color="text.secondary">HESLB pays up to {formatTzs(o.sponsoredCap)} for them</Typography>
          </Grid>
          <Grid size={{ xs: 12, md: 8 }}>
            <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap">
              {registry && <Button variant="outlined" disabled={!o.enabled || send.isPending} onClick={() => send.mutate('REGISTRATION')}>Send first-year registration</Button>}
              {registry && <Button variant="outlined" disabled={!o.enabled || send.isPending} onClick={() => send.mutate('CONTINUATION')}>Send continuing students</Button>}
              {bursary && <Button variant="contained" disabled={!o.enabled || ben.isPending} onClick={() => ben.mutate()}>Fetch beneficiaries</Button>}
              {bursary && <Button variant="contained" disabled={!o.enabled || dis.isPending} onClick={() => dis.mutate()}>Fetch disbursements</Button>}
            </Stack>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
              Beneficiaries become HESLB sponsorships, capped at what HESLB pays the institution; their share then moves onto the HESLB sponsor invoice (Sponsors).
            </Typography>
          </Grid>
        </Grid>
        <ErrorAlert error={send.error ?? ben.error ?? dis.error} />
        {last && <Alert severity={last.problems.length ? 'warning' : 'success'} sx={{ mt: 1 }}>{last.summary}</Alert>}
      </CardContent></Card>
      <Card>
        <CardContent sx={{ pb: 0 }}><Typography variant="subtitle2">Disbursements from HESLB</Typography></CardContent>
        {o.disbursements.length === 0 ? <Typography color="text.secondary" sx={{ p: 2 }}>None fetched yet.</Typography> : (
          <Box sx={{ overflowX: 'auto' }}><Table size="small">
            <TableHead><TableRow><TableCell>Batch</TableCell><TableCell>Date</TableCell><TableCell>Students</TableCell><TableCell align="right">Amount</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>{o.disbursements.map((d) => (
              <TableRow key={d.id}>
                <TableCell>{d.batchNo}</TableCell><TableCell>{formatDate(d.disbursedOn)}</TableCell><TableCell>{d.students}</TableCell>
                <TableCell align="right">{formatTzs(d.amount)}</TableCell>
                <TableCell>{d.status === 'RECORDED' ? <Chip size="small" color="success" label={`Recorded on ${d.invoiceNo}`} /> : <Chip size="small" color="warning" label="To record" />}</TableCell>
                <TableCell>{bursary && d.status !== 'RECORDED' && <Button size="small" onClick={() => { setRecording(d); setInvoiceId(o.openInvoices[0]?.id ?? ''); }}>Record payment</Button>}</TableCell>
              </TableRow>
            ))}</TableBody>
          </Table></Box>
        )}
      </Card>
      <Card><CardContent sx={{ pb: 0 }}><Typography variant="subtitle2">Exchanges</Typography></CardContent><ExchangeTable rows={o.exchanges} /></Card>
      <Dialog open={!!recording} onClose={() => setRecording(null)} fullWidth maxWidth="xs">
        <DialogTitle>Record {recording?.batchNo}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 2 }}>{formatTzs(recording?.amount)} paid by HESLB on {formatDate(recording?.disbursedOn)}, as payment of a HESLB sponsor invoice.</Typography>
          {o.openInvoices.length === 0 ? <Alert severity="info">No HESLB invoice is waiting for payment. Invoice HESLB first (Sponsors → HESLB → Invoice the sponsor).</Alert> : (
            <TextField select fullWidth label="HESLB invoice" value={invoiceId} onChange={(e) => setInvoiceId(Number(e.target.value))}>
              {o.openInvoices.map((i) => <MenuItem key={i.id} value={i.id}>{i.invoiceNo} · {formatTzs(i.balance)} owed</MenuItem>)}
            </TextField>
          )}
          <ErrorAlert error={record.error} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRecording(null)}>Cancel</Button>
          <Button variant="contained" disabled={!invoiceId || record.isPending} onClick={() => record.mutate()}>Record</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

function MuseTab() {
  const qc = useQueryClient();
  const firstOfMonth = new Date(); firstOfMonth.setDate(1); firstOfMonth.setMonth(firstOfMonth.getMonth() - 1);
  const lastOfMonth = new Date(firstOfMonth.getFullYear(), firstOfMonth.getMonth() + 1, 0);
  const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const [from, setFrom] = useState(iso(firstOfMonth));
  const [to, setTo] = useState(iso(lastOfMonth));
  const list = useQuery({ queryKey: ['muse'], queryFn: () => api<MusePosting[]>('/api/muse/postings', staff) });
  const preview = useMutation({ mutationFn: () => api<MusePreview>(`/api/muse/preview?from=${from}&to=${to}`, staff) });
  const post = useMutation({
    mutationFn: () => api<MusePosting>(`/api/muse/postings?from=${from}&to=${to}`, { ...staff, method: 'POST' }),
    onSuccess: () => { preview.reset(); qc.invalidateQueries({ queryKey: ['muse'] }); },
  });
  const p = preview.data;
  return (
    <Stack spacing={2}>
      <Card><CardContent>
        <Typography variant="subtitle2" gutterBottom>Post fee collections to MUSE</Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }}>
          <TextField type="date" label="From" value={from} onChange={(e) => { setFrom(e.target.value); preview.reset(); }} InputLabelProps={{ shrink: true }} />
          <TextField type="date" label="To" value={to} onChange={(e) => { setTo(e.target.value); preview.reset(); }} InputLabelProps={{ shrink: true }} />
          <Button variant="outlined" sx={{ whiteSpace: 'nowrap', flexShrink: 0 }} onClick={() => preview.mutate()} disabled={preview.isPending}>Preview the journal</Button>
          <Button variant="contained" sx={{ whiteSpace: 'nowrap', flexShrink: 0 }} disabled={!p || p.problems.length > 0 || post.isPending} onClick={() => post.mutate()}>Post to MUSE</Button>
        </Stack>
        <ErrorAlert error={preview.error ?? post.error} />
        {post.data && <Alert severity={post.data.status === 'POSTED' ? 'success' : 'error'} sx={{ mt: 1 }}>
          {post.data.status === 'POSTED' ? `Posted as ${post.data.museRef}` : `MUSE refused the journal: ${post.data.message}`}</Alert>}
        {p && (
          <Box sx={{ mt: 2 }}>
            {p.problems.map((x) => <Alert key={x} severity="warning" sx={{ mb: 1 }}>{x}</Alert>)}
            <Box sx={{ overflowX: 'auto' }}><Table size="small">
              <TableHead><TableRow><TableCell>Account</TableCell><TableCell>GFS code</TableCell><TableCell>Description</TableCell><TableCell align="right">Debit</TableCell><TableCell align="right">Credit</TableCell></TableRow></TableHead>
              <TableBody>{p.lines.map((l, i) => (
                <TableRow key={i}><TableCell>{l.account}</TableCell><TableCell>{l.gfsCode}</TableCell><TableCell>{l.description}</TableCell>
                  <TableCell align="right">{l.debit ? formatTzs(l.debit) : ''}</TableCell><TableCell align="right">{l.credit ? formatTzs(l.credit) : ''}</TableCell></TableRow>
              ))}</TableBody>
            </Table></Box>
          </Box>
        )}
      </CardContent></Card>
      <Card>
        <CardContent sx={{ pb: 0 }}><Typography variant="subtitle2">Postings</Typography></CardContent>
        {list.isLoading ? <Loading /> : (list.data ?? []).length === 0 ? <Typography color="text.secondary" sx={{ p: 2 }}>Nothing posted yet.</Typography> : (
          <Box sx={{ overflowX: 'auto' }}><Table size="small">
            <TableHead><TableRow><TableCell>Period</TableCell><TableCell align="right">Total</TableCell><TableCell>Status</TableCell><TableCell>MUSE reference</TableCell><TableCell>By</TableCell></TableRow></TableHead>
            <TableBody>{list.data!.map((x) => (
              <TableRow key={x.id}>
                <TableCell>{formatDate(x.from)} – {formatDate(x.to)}</TableCell><TableCell align="right">{formatTzs(x.total)}</TableCell>
                <TableCell><Chip size="small" color={x.status === 'POSTED' ? 'success' : 'error'} label={x.status === 'POSTED' ? 'Posted' : 'Refused'} title={x.message} /></TableCell>
                <TableCell>{x.museRef}</TableCell><TableCell>{x.postedByName} · {formatDateTime(x.postedAt)}</TableCell>
              </TableRow>
            ))}</TableBody>
          </Table></Box>
        )}
      </Card>
    </Stack>
  );
}

function TamisemiTab() {
  const qc = useQueryClient();
  const windows = useQuery({ queryKey: ['staff', 'windows'], queryFn: () => api<AdmissionWindow[]>('/api/admission/windows', staff) });
  const history = useQuery({ queryKey: ['tamisemi'], queryFn: () => api<Fetch[]>('/api/tamisemi/fetches', staff) });
  const [windowId, setWindowId] = useState<number | ''>('');
  const [year, setYear] = useState(String(new Date().getFullYear()));
  const fetch = useMutation({
    mutationFn: () => api<Fetch>(`/api/tamisemi/fetch?windowId=${windowId}&year=${year}`, { ...staff, method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['tamisemi'] }),
  });
  return (
    <Stack spacing={2}>
      <Card><CardContent>
        <Typography variant="subtitle2" gutterBottom>Fetch TAMISEMI's selections</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          The students TAMISEMI selected to your programmes are checked like a selections file. A second person signs the import off under Data import; then each gets an applicant account, a SELECTED application and an SMS.
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }}>
          <TextField select label="Admission window" value={windowId} onChange={(e) => setWindowId(Number(e.target.value))} sx={{ minWidth: 260 }}>
            {(windows.data ?? []).filter((w) => w.state !== 'DRAFT').map((w) => <MenuItem key={w.id} value={w.id}>{w.name}</MenuItem>)}
          </TextField>
          <TextField label="Selection year" value={year} onChange={(e) => setYear(e.target.value)} sx={{ width: 140 }} />
          <Button variant="contained" disabled={!windowId || fetch.isPending} onClick={() => fetch.mutate()}>Fetch selections</Button>
        </Stack>
        <ErrorAlert error={fetch.error} />
        {fetch.data && <Alert severity="success" sx={{ mt: 1 }} action={<Button component={StaffLink} to="/staff/imports" size="small">Open import</Button>}>
          {fetch.data.summary} (import {fetch.data.batchNo})</Alert>}
      </CardContent></Card>
      <Card>
        <CardContent sx={{ pb: 0 }}><Typography variant="subtitle2">Earlier fetches</Typography></CardContent>
        {(history.data ?? []).length === 0 ? <Typography color="text.secondary" sx={{ p: 2 }}>None yet.</Typography> : (
          <Table size="small"><TableHead><TableRow><TableCell>When</TableCell><TableCell>Year</TableCell><TableCell>Selections</TableCell><TableCell>Import</TableCell><TableCell>By</TableCell></TableRow></TableHead>
            <TableBody>{history.data!.map((f) => (
              <TableRow key={f.id}><TableCell>{formatDateTime(f.doneAt)}</TableCell><TableCell>{f.year}</TableCell><TableCell>{f.selections}</TableCell>
                <TableCell>{f.batchNo}</TableCell><TableCell>{f.doneByName}</TableCell></TableRow>
            ))}</TableBody></Table>
        )}
      </Card>
    </Stack>
  );
}

function NactvetTab() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['nactvet-registration'], queryFn: () => api<RegOverview>('/api/nactvet-registration', staff) });
  const done = () => qc.invalidateQueries({ queryKey: ['nactvet-registration'] });
  const submit = useMutation({ mutationFn: () => api<RegBatch>('/api/nactvet-registration', { ...staff, method: 'POST' }), onSuccess: done });
  const refresh = useMutation({ mutationFn: (id: number) => api<RegBatch>(`/api/nactvet-registration/${id}/refresh`, { ...staff, method: 'POST' }), onSuccess: done });
  const [open, setOpen] = useState<RegBatch | null>(null);
  const items = useQuery({ queryKey: ['nactvet-registration', open?.id], enabled: !!open, queryFn: () => api<RegItem[]>(`/api/nactvet-registration/${open!.id}/items`, staff) });
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} />;
  const o = q.data;
  return (
    <Stack spacing={2}>
      <Card><CardContent>
        <Grid container spacing={2} alignItems="center">
          <Grid size={{ xs: 12, md: 6 }}>
            <Typography variant="overline" color="text.secondary">{o.academicYear}</Typography>
            <Typography variant="h5">{o.waiting} new student{o.waiting === 1 ? '' : 's'} to register with NACTVET</Typography>
            {o.withoutVerification > 0 && <Typography color="warning.main">{o.withoutVerification} without a NACTVET verification number: NACTVET will query them.</Typography>}
          </Grid>
          <Grid size={{ xs: 12, md: 6 }}>
            <Button variant="contained" disabled={o.waiting === 0 || submit.isPending} onClick={() => submit.mutate()}>Send to NACTVET</Button>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Active students who entered this year and are not yet registered (or were queried and corrected).</Typography>
          </Grid>
        </Grid>
        <ErrorAlert error={submit.error ?? refresh.error} />
      </CardContent></Card>
      <Card>
        <CardContent sx={{ pb: 0 }}><Typography variant="subtitle2">Batches sent</Typography></CardContent>
        {o.batches.length === 0 ? <Typography color="text.secondary" sx={{ p: 2 }}>Nothing sent yet.</Typography> : (
          <Box sx={{ overflowX: 'auto' }}><Table size="small">
            <TableHead><TableRow><TableCell>Sent</TableCell><TableCell>NACTVET batch</TableCell><TableCell>Students</TableCell><TableCell>Registered</TableCell><TableCell>Queried</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>{o.batches.map((b) => (
              <TableRow key={b.id}>
                <TableCell>{formatDateTime(b.submittedAt)}<Typography variant="caption" display="block" color="text.secondary">{b.submittedByName}</Typography></TableCell>
                <TableCell>{b.batchRef}</TableCell><TableCell>{b.students}</TableCell><TableCell>{b.registered}</TableCell>
                <TableCell>{b.queried > 0 ? <Typography color="warning.main" variant="body2">{b.queried}</Typography> : 0}</TableCell>
                <TableCell><Chip size="small" color={b.status === 'COMPLETED' ? 'success' : 'info'} label={b.status === 'COMPLETED' ? 'Answered' : 'Waiting for NACTVET'} /></TableCell>
                <TableCell>
                  {b.status !== 'COMPLETED' && <Button size="small" onClick={() => refresh.mutate(b.id)}>Check now</Button>}
                  <Button size="small" onClick={() => setOpen(b)}>Students</Button>
                </TableCell>
              </TableRow>
            ))}</TableBody>
          </Table></Box>
        )}
      </Card>
      <Dialog open={!!open} onClose={() => setOpen(null)} fullWidth maxWidth="md">
        <DialogTitle>{open?.batchRef}</DialogTitle>
        <DialogContent>
          {items.isLoading ? <Loading /> : (
            <Table size="small"><TableHead><TableRow><TableCell>Registration no.</TableCell><TableCell>Name</TableCell><TableCell>Programme</TableCell><TableCell>Outcome</TableCell></TableRow></TableHead>
              <TableBody>{(items.data ?? []).map((i) => (
                <TableRow key={i.studentId}><TableCell>{i.registrationNo}</TableCell><TableCell>{i.name}</TableCell><TableCell>{i.programmeCode}</TableCell>
                  <TableCell>{i.status === 'REGISTERED' ? <Chip size="small" color="success" label="Registered" /> : i.status === 'QUERIED'
                    ? <><Chip size="small" color="warning" label="Queried" /> <Typography variant="caption">{i.reason}</Typography></> : <Chip size="small" label="Waiting" />}</TableCell></TableRow>
              ))}</TableBody></Table>
          )}
        </DialogContent>
        <DialogActions><Button onClick={() => setOpen(null)}>Close</Button></DialogActions>
      </Dialog>
    </Stack>
  );
}
