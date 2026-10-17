import CloudSyncIcon from '@mui/icons-material/CloudSync';
import DownloadIcon from '@mui/icons-material/Download';
import ReceiptIcon from '@mui/icons-material/ReceiptLong';
import RefreshIcon from '@mui/icons-material/Refresh';
import SendIcon from '@mui/icons-material/Send';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, Table, TableBody,
  TableCell, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, download, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type { AcademicYear } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { HEALTH_COLOUR, type HealthCover } from '../../components/HealthCoverCard';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };

interface ProgrammeRow { code: string; name: string; students: number; active: number; underWay: number; problems: number; none: number }
interface Batch { id: number; academicYear: string; batchRef: string; status: string; students: number; registered: number; rejected: number; submittedByName: string; submittedAt: string; checkedAt?: string }
interface Overview {
  enabled: boolean; required: boolean; contribution: number; institutionCode: string; mode: string; academicYearId: number; academicYear: string;
  students: number; counts: Record<string, number>; expiringSoon: number; programmes: ProgrammeRow[]; batches: Batch[];
}
interface Result { summary: string; done: number; problems: string[] }

/** How each status reads in the office (the student portal has its own words). */
const LABEL: Record<string, string> = {
  ACTIVE: 'Covered', EXPIRED: 'Expired', INVALID: 'Card not accepted', AWAITING_PAYMENT: 'Awaiting payment', READY: 'Paid, to send to NHIF',
  SUBMITTED: 'With NHIF', REJECTED: 'Refused by NHIF', NONE: 'No cover recorded',
};
const ORDER = ['ACTIVE', 'SUBMITTED', 'READY', 'AWAITING_PAYMENT', 'INVALID', 'EXPIRED', 'REJECTED', 'NONE'];
const has = (roles: string[], ...r: string[]) => roles.some((x) => r.includes(x));

/** Students' health insurance with NHIF: who is covered, contributions, registration with NHIF and own cards checked again. */
export default function HealthInsurancePage() {
  const { user } = useSession();
  const roles = user('staff')?.roles ?? [];
  const registry = has(roles, 'INSTITUTION_ADMIN', 'REGISTRAR');
  const bursary = has(roles, 'INSTITUTION_ADMIN', 'BURSAR', 'ACCOUNTANT');
  const qc = useQueryClient();
  const [yearId, setYearId] = useState<number | ''>('');
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const [notice, setNotice] = useState<{ text: string; problems?: string[] } | null>(null);
  const [items, setItems] = useState<Batch | null>(null);
  const [billOpen, setBillOpen] = useState(false);
  const yq = yearId === '' ? '' : `academicYearId=${yearId}`;
  const years = useQuery({ queryKey: ['staff', 'years'], queryFn: () => api<AcademicYear[]>('/api/academic/years', staff) });
  const o = useQuery({ queryKey: ['health', 'overview', yearId], queryFn: () => api<Overview>(`/api/health-covers/overview?${yq}`, staff) });
  const list = useQuery({
    queryKey: ['health', 'list', yearId, status, search],
    queryFn: () => api<HealthCover[]>(`/api/health-covers?${[yq, status && `status=${status}`, search.trim() && `q=${encodeURIComponent(search.trim())}`].filter(Boolean).join('&')}`, staff),
  });
  const batchItems = useQuery({ queryKey: ['health', 'items', items?.id], enabled: !!items,
    queryFn: () => api<HealthCover[]>(`/api/health-covers/registrations/${items!.id}/items`, staff) });
  const refresh = () => qc.invalidateQueries({ queryKey: ['health'] });
  const onResult = (r: Result) => { setNotice({ text: r.summary, problems: r.problems }); refresh(); };
  const bill = useMutation({ mutationFn: () => api<Result>(`/api/health-covers/bill?${yq}`, { ...staff, method: 'POST' }), onSuccess: (r) => { setBillOpen(false); onResult(r); } });
  const recheck = useMutation({ mutationFn: () => api<Result>(`/api/health-covers/recheck?${yq}`, { ...staff, method: 'POST' }), onSuccess: onResult });
  const send = useMutation({
    mutationFn: () => api<Batch>(`/api/health-covers/registrations?${yq}`, { ...staff, method: 'POST' }),
    onSuccess: (b) => { setNotice({ text: `${b.students} student(s) sent to NHIF as ${b.batchRef}: ${b.registered} registered, ${b.rejected} refused${b.status === 'COMPLETED' ? '.' : ', the rest still with NHIF.'}` }); refresh(); },
  });
  const check = useMutation({
    mutationFn: (id: number) => api<Batch>(`/api/health-covers/registrations/${id}/check`, { ...staff, method: 'POST' }),
    onSuccess: (b) => { setNotice({ text: `${b.batchRef}: ${b.registered} registered, ${b.rejected} refused${b.status === 'COMPLETED' ? '.' : ', the rest still with NHIF.'}` }); refresh(); },
  });
  const busy = bill.isPending || recheck.isPending || send.isPending || check.isPending;
  if (o.isLoading) return <Loading />;
  if (o.error || !o.data) return <ErrorAlert error={o.error} />;
  const d = o.data;
  const pct = (n: number) => d.students ? `${Math.round((100 * n) / d.students)}%` : '–';

  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Health insurance (NHIF)"
        subtitle="Every student needs health cover for the year: their own NHIF card, checked with NHIF, or registration through the institution after paying NHIF's student contribution." />
      {!d.enabled && (
        <Alert severity="info" sx={{ mb: 2 }} action={<Button color="inherit" size="small" component={StaffLink} to="/staff/integrations">Integrations</Button>}>
          Health insurance with NHIF is switched off. The administrator or the Bursar switches it on under Integrations, with NHIF's contribution and whether cover is needed before semester registration.
        </Alert>
      )}
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 2 }} alignItems={{ sm: 'center' }}>
        <TextField select size="small" label="Academic year" value={yearId === '' ? d.academicYearId : yearId} sx={{ width: { xs: '100%', sm: 200 }, flexShrink: 0 }}
          onChange={(e) => setYearId(Number(e.target.value))}>
          {(years.data ?? []).map((y) => <MenuItem key={y.id} value={y.id}>{y.code}</MenuItem>)}
        </TextField>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
          <Chip size="small" variant="outlined" label={`Contribution ${formatTzs(d.contribution)}`} />
          <Chip size="small" variant="outlined" color={d.required ? 'warning' : 'default'} label={d.required ? 'Needed before semester registration' : 'Not needed for registration'} />
          <Chip size="small" variant="outlined" label={`NHIF: ${d.mode === 'LIVE' ? 'live' : 'simulated'} · code ${d.institutionCode}`} />
        </Stack>
      </Stack>
      {notice && (
        <Alert severity={notice.problems?.length ? 'warning' : 'success'} sx={{ mb: 2 }} onClose={() => setNotice(null)}>
          {notice.text}
          {!!notice.problems?.length && <Box component="ul" sx={{ m: 0, pl: 2 }}>{notice.problems.slice(0, 10).map((p) => <li key={p}>{p}</li>)}</Box>}
        </Alert>
      )}
      <ErrorAlert error={bill.error ?? recheck.error ?? send.error ?? check.error} />

      <Grid container spacing={2} sx={{ mb: 2 }}>
        <Grid size={{ xs: 6, md: 3 }}><Figure label="Current students" value={d.students} /></Grid>
        <Grid size={{ xs: 6, md: 3 }}><Figure label="Covered" value={d.counts.ACTIVE ?? 0} note={pct(d.counts.ACTIVE ?? 0)} colour="success.main" /></Grid>
        <Grid size={{ xs: 6, md: 3 }}><Figure label="Registration under way" value={(d.counts.AWAITING_PAYMENT ?? 0) + (d.counts.READY ?? 0) + (d.counts.SUBMITTED ?? 0)}
          note={`${d.counts.READY ?? 0} paid and waiting to be sent`} /></Grid>
        <Grid size={{ xs: 6, md: 3 }}><Figure label="Not covered" value={(d.counts.NONE ?? 0) + (d.counts.INVALID ?? 0) + (d.counts.EXPIRED ?? 0) + (d.counts.REJECTED ?? 0)}
          note={d.expiringSoon ? `${d.expiringSoon} card(s) end within 30 days` : undefined} colour={(d.counts.NONE ?? 0) > 0 ? 'warning.main' : undefined} /></Grid>
      </Grid>

      {d.enabled && (registry || bursary) && (
        <Card sx={{ mb: 2 }}><CardContent>
          <Typography variant="subtitle2" gutterBottom>What to do</Typography>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} flexWrap="wrap" useFlexGap>
            {bursary && <Button variant="outlined" startIcon={<ReceiptIcon />} disabled={busy || !(d.counts.NONE ?? 0)} onClick={() => setBillOpen(true)}>
              Bill students without cover ({d.counts.NONE ?? 0})</Button>}
            {registry && <Button variant="contained" startIcon={<SendIcon />} disabled={busy || !(d.counts.READY ?? 0)} onClick={() => send.mutate()}>
              Send paid students to NHIF ({d.counts.READY ?? 0})</Button>}
            {registry && <Button variant="outlined" startIcon={<RefreshIcon />} disabled={busy} onClick={() => recheck.mutate()}>Check own cards with NHIF again</Button>}
            {registry && <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/health-covers/registration-file?${yq}`, 'staff', `nhif-student-registration-${d.academicYear.replace('/', '-')}.csv`)}>
              File for NHIF's portal</Button>}
          </Stack>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            Students give their own card number on their profile in the student portal, or choose to be registered through the institution, which bills the contribution.
            Once a contribution is paid, send the student to NHIF; the card number comes back and the student is told by SMS.
          </Typography>
        </CardContent></Card>
      )}

      <Card sx={{ mb: 2 }}><CardContent>
        <Typography variant="subtitle2" gutterBottom>By programme</Typography>
        {d.programmes.length === 0 ? <Typography color="text.secondary">No current students.</Typography> : (
          <Box sx={{ overflowX: 'auto' }}><Table size="small">
            <TableHead><TableRow><TableCell>Programme</TableCell><TableCell align="right">Students</TableCell><TableCell align="right">Covered</TableCell>
              <TableCell align="right">Under way</TableCell><TableCell align="right">Needs attention</TableCell><TableCell align="right">No cover</TableCell></TableRow></TableHead>
            <TableBody>{d.programmes.map((p) => (
              <TableRow key={p.code}>
                <TableCell>{p.name} <Typography component="span" variant="caption" color="text.secondary">({p.code})</Typography></TableCell>
                <TableCell align="right">{p.students}</TableCell><TableCell align="right">{p.active}</TableCell><TableCell align="right">{p.underWay}</TableCell>
                <TableCell align="right">{p.problems}</TableCell><TableCell align="right">{p.none}</TableCell>
              </TableRow>
            ))}</TableBody>
          </Table></Box>
        )}
      </CardContent></Card>

      <Card sx={{ mb: 2 }}><CardContent>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mb: 1.5 }} alignItems={{ sm: 'center' }}>
          <Typography variant="subtitle2" sx={{ flex: 1 }}>Students</Typography>
          <TextField select size="small" label="Situation" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 200 }}>
            <MenuItem value="">All</MenuItem>
            {ORDER.map((k) => <MenuItem key={k} value={k}>{LABEL[k]} ({d.counts[k] ?? 0})</MenuItem>)}
          </TextField>
          <TextField size="small" label="Search name, reg. no. or card" value={search} onChange={(e) => setSearch(e.target.value)} />
        </Stack>
        {list.isLoading ? <Loading /> : <CoverTable rows={list.data ?? []} />}
      </CardContent></Card>

      <Card><CardContent>
        <Typography variant="subtitle2" gutterBottom>Sent to NHIF</Typography>
        {d.batches.length === 0 ? <Typography color="text.secondary">{`Nothing sent to NHIF for ${d.academicYear} yet.`}</Typography> : (
          <Box sx={{ overflowX: 'auto' }}><Table size="small">
            <TableHead><TableRow><TableCell>Batch</TableCell><TableCell>Sent</TableCell><TableCell>Outcome</TableCell><TableCell>Status</TableCell><TableCell align="right" /></TableRow></TableHead>
            <TableBody>{d.batches.map((b) => (
              <TableRow key={b.id}>
                <TableCell>{b.batchRef}</TableCell>
                <TableCell>{formatDateTime(b.submittedAt)}<Typography variant="caption" display="block" color="text.secondary">{b.submittedByName}</Typography></TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                    <Chip size="small" variant="outlined" label={`${b.students} sent`} />
                    {!!b.registered && <Chip size="small" color="success" label={`${b.registered} registered`} />}
                    {!!b.rejected && <Chip size="small" color="error" label={`${b.rejected} refused`} />}
                  </Stack>
                </TableCell>
                <TableCell><Chip size="small" color={b.status === 'COMPLETED' ? 'success' : 'info'} label={b.status === 'COMPLETED' ? 'Completed' : 'With NHIF'} /></TableCell>
                <TableCell align="right">
                  <Stack direction="row" spacing={0.5} justifyContent="flex-end">
                    {registry && b.status !== 'COMPLETED' && <Button size="small" startIcon={<CloudSyncIcon />} disabled={busy} onClick={() => check.mutate(b.id)}>Ask NHIF</Button>}
                    <Button size="small" onClick={() => setItems(b)}>Students</Button>
                  </Stack>
                </TableCell>
              </TableRow>
            ))}</TableBody>
          </Table></Box>
        )}
      </CardContent></Card>

      <Dialog open={billOpen} onClose={() => setBillOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Bill students without cover</DialogTitle>
        <DialogContent>
          <Typography>
            {`${d.counts.NONE ?? 0} current student(s) have no cover recorded for ${d.academicYear}. Each will be billed NHIF's contribution of ${formatTzs(d.contribution)} and registered with NHIF through the institution once it is paid. A student who then gives an active NHIF card of their own has the unpaid bill cancelled.`}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setBillOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={bill.isPending} onClick={() => bill.mutate()}>Bill {d.counts.NONE ?? 0}</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={!!items} onClose={() => setItems(null)} maxWidth="md" fullWidth>
        <DialogTitle>{items?.batchRef}</DialogTitle>
        <DialogContent>{batchItems.isLoading ? <Loading /> : <CoverTable rows={batchItems.data ?? []} />}</DialogContent>
        <DialogActions><Button onClick={() => setItems(null)}>Close</Button></DialogActions>
      </Dialog>
    </Container>
  );
}

function Figure({ label, value, note, colour }: { label: string; value: number; note?: string; colour?: string }) {
  return (
    <Card sx={{ height: '100%' }}><CardContent>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography variant="h4" sx={{ color: colour }}>{value}</Typography>
      {note && <Typography variant="caption" color="text.secondary">{note}</Typography>}
    </CardContent></Card>
  );
}

function CoverTable({ rows }: { rows: HealthCover[] }) {
  if (!rows.length) return <Typography color="text.secondary">No students.</Typography>;
  return (
    <Box sx={{ overflowX: 'auto' }}><Table size="small">
      <TableHead><TableRow><TableCell>Student</TableCell><TableCell>Programme</TableCell><TableCell>Situation</TableCell><TableCell>Card</TableCell><TableCell>Notes</TableCell></TableRow></TableHead>
      <TableBody>{rows.slice(0, 500).map((c) => (
        <TableRow key={c.studentId}>
          <TableCell>
            <StaffLink to={`/staff/students/${c.studentId}`}>{c.studentName}</StaffLink>
            <Typography variant="caption" display="block" color="text.secondary">{c.registrationNo}</Typography>
          </TableCell>
          <TableCell>{`${c.programmeCode} · year ${c.yearOfStudy}`}</TableCell>
          <TableCell>
            <Chip size="small" color={HEALTH_COLOUR[c.status] ?? 'default'} label={LABEL[c.status] ?? c.status} />
            {c.route && <Typography variant="caption" display="block" color="text.secondary">{c.route === 'OWN_CARD' ? 'Own card' : 'Through the institution'}</Typography>}
          </TableCell>
          <TableCell>
            {c.cardNo && <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>{c.cardNo}</Typography>}
            {c.validTo && <Typography variant="caption" color="text.secondary">{`valid to ${formatDate(c.validTo)}`}</Typography>}
          </TableCell>
          <TableCell sx={{ maxWidth: 320, whiteSpace: 'normal' }}>
            {c.invoice && c.status === 'AWAITING_PAYMENT' && <Typography variant="caption" display="block">{`${c.invoice.invoiceNo} · ${formatTzs(c.invoice.balance)} to pay`}</Typography>}
            {c.reason && <Typography variant="caption" color="error">{c.reason}</Typography>}
          </TableCell>
        </TableRow>
      ))}</TableBody>
    </Table></Box>
  );
}
