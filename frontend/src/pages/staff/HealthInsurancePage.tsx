import CloudSyncIcon from '@mui/icons-material/CloudSync';
import DownloadIcon from '@mui/icons-material/Download';
import HowToRegIcon from '@mui/icons-material/HowToReg';
import PlaylistAddIcon from '@mui/icons-material/PlaylistAdd';
import ScienceIcon from '@mui/icons-material/Science';
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
interface Batch { id: number; kind: 'REGISTRATION' | 'CARD_APPLICATION'; intakeCode?: string; academicYear: string; batchRef: string; status: string; students: number; registered: number; rejected: number; submittedByName: string; submittedAt: string; checkedAt?: string }
interface Overview {
  enabled: boolean; required: boolean; contribution: number; institutionCode: string; mode: string; setUp: boolean; academicYearId: number; academicYear: string;
  students: number; counts: Record<string, number>; expiringSoon: number; programmes: ProgrammeRow[]; batches: Batch[];
}
interface Result { summary: string; done: number; problems: string[] }
interface Intake { name: string; code: string; startDate?: string; endDate?: string }

/** How each status reads in the office (the student portal has its own words). */
const LABEL: Record<string, string> = {
  ACTIVE: 'Covered', EXPIRED: 'Expired', INVALID: 'Card not accepted', READY: 'To register with NHIF', REGISTERED: 'Registered, card to apply for',
  APPLIED: 'Card applied for', AWAITING_PAYMENT: 'Student to pay NHIF', PAID: 'Paid, card on its way', SUBMITTED: 'With NHIF', REJECTED: 'Refused by NHIF',
  NONE: 'No cover recorded',
};
const ORDER = ['ACTIVE', 'PAID', 'AWAITING_PAYMENT', 'APPLIED', 'REGISTERED', 'READY', 'SUBMITTED', 'INVALID', 'EXPIRED', 'REJECTED', 'NONE'];
const UNDER_WAY = ['READY', 'REGISTERED', 'APPLIED', 'AWAITING_PAYMENT', 'PAID', 'SUBMITTED'];
const has = (roles: string[], ...r: string[]) => roles.some((x) => r.includes(x));

/**
 * Students' health insurance with NHIF: who is covered; students without cover registered with NHIF and their cards applied for (NHIF's
 * Student Registration API), the control numbers NHIF gives them to pay, and own cards checked again.
 */
export default function HealthInsurancePage() {
  const { user } = useSession();
  const roles = user('staff')?.roles ?? [];
  const registry = has(roles, 'INSTITUTION_ADMIN', 'REGISTRAR');
  const qc = useQueryClient();
  const [yearId, setYearId] = useState<number | ''>('');
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const [notice, setNotice] = useState<{ text: string; problems?: string[] } | null>(null);
  const [items, setItems] = useState<Batch | null>(null);
  const [enrolOpen, setEnrolOpen] = useState(false);
  const [applyOpen, setApplyOpen] = useState(false);
  const [intake, setIntake] = useState('');
  const yq = yearId === '' ? '' : `academicYearId=${yearId}`;
  const years = useQuery({ queryKey: ['staff', 'years'], queryFn: () => api<AcademicYear[]>('/api/academic/years', staff) });
  const o = useQuery({ queryKey: ['health', 'overview', yearId], queryFn: () => api<Overview>(`/api/health-covers/overview?${yq}`, staff) });
  const list = useQuery({
    queryKey: ['health', 'list', yearId, status, search],
    queryFn: () => api<HealthCover[]>(`/api/health-covers?${[yq, status && `status=${status}`, search.trim() && `q=${encodeURIComponent(search.trim())}`].filter(Boolean).join('&')}`, staff),
  });
  const batchItems = useQuery({ queryKey: ['health', 'items', items?.id], enabled: !!items,
    queryFn: () => api<HealthCover[]>(`/api/health-covers/registrations/${items!.id}/items`, staff) });
  const intakes = useQuery({ queryKey: ['health', 'intakes'], enabled: applyOpen, retry: false,
    queryFn: () => api<Intake[]>('/api/health-covers/nhif-intakes', staff) });
  const refresh = () => qc.invalidateQueries({ queryKey: ['health'] });
  const onResult = (r: Result) => { setNotice({ text: r.summary, problems: r.problems }); refresh(); };
  const post = (path: string) => api<Result>(`${path}${path.includes('?') ? '&' : '?'}${yq}`, { ...staff, method: 'POST' });
  const enrol = useMutation({ mutationFn: () => post('/api/health-covers/enrol'), onSuccess: (r) => { setEnrolOpen(false); onResult(r); } });
  const register = useMutation({ mutationFn: () => post('/api/health-covers/registrations'), onSuccess: onResult });
  const apply = useMutation({
    mutationFn: () => post(`/api/health-covers/card-applications${intake ? `?intakeCode=${intake}` : ''}`),
    onSuccess: (r) => { setApplyOpen(false); onResult(r); },
  });
  const simulate = useMutation({ mutationFn: () => post('/api/health-covers/simulate-nhif'), onSuccess: onResult });
  const recheck = useMutation({ mutationFn: () => post('/api/health-covers/recheck'), onSuccess: onResult });
  const check = useMutation({
    mutationFn: (id: number) => api<Batch>(`/api/health-covers/registrations/${id}/check`, { ...staff, method: 'POST' }),
    onSuccess: (b) => { setNotice({ text: `${b.batchRef}: control numbers asked of NHIF again.` }); refresh(); },
  });
  const busy = enrol.isPending || register.isPending || apply.isPending || simulate.isPending || recheck.isPending || check.isPending;
  if (o.isLoading) return <Loading />;
  if (o.error || !o.data) return <ErrorAlert error={o.error} />;
  const d = o.data;
  const pct = (n: number) => d.students ? `${Math.round((100 * n) / d.students)}%` : '–';
  const n = (k: string) => d.counts[k] ?? 0;

  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Health insurance (NHIF)"
        subtitle="Every student needs health cover for the year: their own NHIF card, checked with NHIF, or registration through the institution, after which NHIF bills the student directly." />
      {!d.enabled && (
        <Alert severity="info" sx={{ mb: 2 }} action={<Button color="inherit" size="small" component={StaffLink} to="/staff/integrations">Integrations</Button>}>
          Health insurance with NHIF is switched off. The administrator or the Bursar switches it on under Integrations, with NHIF's contribution and whether cover is needed before semester registration.
        </Alert>
      )}
      {d.enabled && !d.setUp && (
        <Alert severity="warning" sx={{ mb: 2 }} action={<Button color="inherit" size="small" component={StaffLink} to="/staff/integrations">Integrations</Button>}>
          NHIF is live but the institution's NHIF credentials are not complete. The administrator enters the client id, secret, SystemId and SubSystemId NHIF issued under Integrations › NHIF.
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
          <Chip size="small" variant="outlined" label={`NHIF: ${d.mode === 'LIVE' ? 'live' : 'simulated'} · agent ${d.institutionCode}`} />
        </Stack>
      </Stack>
      {notice && (
        <Alert severity={notice.problems?.length ? 'warning' : 'success'} sx={{ mb: 2 }} onClose={() => setNotice(null)}>
          {notice.text}
          {!!notice.problems?.length && <Box component="ul" sx={{ m: 0, pl: 2 }}>{notice.problems.slice(0, 10).map((p) => <li key={p}>{p}</li>)}</Box>}
        </Alert>
      )}
      <ErrorAlert error={enrol.error ?? register.error ?? apply.error ?? simulate.error ?? recheck.error ?? check.error} />

      <Grid container spacing={2} sx={{ mb: 2 }}>
        <Grid size={{ xs: 6, md: 3 }}><Figure label="Current students" value={d.students} /></Grid>
        <Grid size={{ xs: 6, md: 3 }}><Figure label="Covered" value={d.counts.ACTIVE ?? 0} note={pct(d.counts.ACTIVE ?? 0)} colour="success.main" /></Grid>
        <Grid size={{ xs: 6, md: 3 }}><Figure label="Through the institution" value={UNDER_WAY.reduce((t, k) => t + n(k), 0)}
          note={`${n('AWAITING_PAYMENT')} to pay NHIF · ${n('PAID')} paid`} /></Grid>
        <Grid size={{ xs: 6, md: 3 }}><Figure label="Not covered" value={(d.counts.NONE ?? 0) + (d.counts.INVALID ?? 0) + (d.counts.EXPIRED ?? 0) + (d.counts.REJECTED ?? 0)}
          note={d.expiringSoon ? `${d.expiringSoon} card(s) end within 30 days` : undefined} colour={(d.counts.NONE ?? 0) > 0 ? 'warning.main' : undefined} /></Grid>
      </Grid>

      {d.enabled && registry && (
        <Card sx={{ mb: 2 }}><CardContent>
          <Typography variant="subtitle2" gutterBottom>What to do</Typography>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} flexWrap="wrap" useFlexGap>
            <Button variant="outlined" startIcon={<PlaylistAddIcon />} disabled={busy || !n('NONE')} onClick={() => setEnrolOpen(true)}>
              {`Enrol students without cover (${n('NONE')})`}</Button>
            <Button variant={n('READY') ? 'contained' : 'outlined'} startIcon={<HowToRegIcon />} disabled={busy || !n('READY') || !d.setUp} onClick={() => register.mutate()}>
              {`1 · Register with NHIF (${n('READY')})`}</Button>
            <Button variant={n('REGISTERED') ? 'contained' : 'outlined'} startIcon={<SendIcon />} disabled={busy || !n('REGISTERED') || !d.setUp} onClick={() => { setIntake(''); setApplyOpen(true); }}>
              {`2 · Apply for cards (${n('REGISTERED')})`}</Button>
            {d.mode !== 'LIVE' && <Button color="secondary" startIcon={<ScienceIcon />} disabled={busy || !(n('AWAITING_PAYMENT') + n('PAID'))} onClick={() => simulate.mutate()}>
              {`Rehearse: students pay, cards issued (${n('AWAITING_PAYMENT') + n('PAID')})`}</Button>}
            <Button variant="outlined" startIcon={<RefreshIcon />} disabled={busy} onClick={() => recheck.mutate()}>Check own cards with NHIF again</Button>
            <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/health-covers/registration-file?${yq}`, 'staff', `nhif-student-registration-${d.academicYear.replace('/', '-')}.csv`)}>
              File for NHIF's portal</Button>
          </Stack>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            Students give their own card number on their profile in the student portal, or choose to be registered through the institution. Register those students with NHIF, then apply for their cards:
            NHIF gives each student a control number (sent by SMS) to pay the contribution, and the card number follows by NHIF's callback once paid.
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
          <TextField size="small" label="Search name, reg. no., card or control no." value={search} onChange={(e) => setSearch(e.target.value)} />
        </Stack>
        {list.isLoading ? <Loading /> : <CoverTable rows={list.data ?? []} />}
      </CardContent></Card>

      <Card><CardContent>
        <Typography variant="subtitle2" gutterBottom>Sent to NHIF</Typography>
        {d.batches.length === 0 ? <Typography color="text.secondary">{`Nothing sent to NHIF for ${d.academicYear} yet.`}</Typography> : (
          <Box sx={{ overflowX: 'auto' }}><Table size="small">
            <TableHead><TableRow><TableCell>Batch</TableCell><TableCell>Sent</TableCell><TableCell>Outcome</TableCell><TableCell align="right" /></TableRow></TableHead>
            <TableBody>{d.batches.map((b) => (
              <TableRow key={b.id}>
                <TableCell>
                  <Typography variant="body2" sx={{ fontFamily: b.kind === 'CARD_APPLICATION' ? 'monospace' : undefined }}>{b.batchRef}</Typography>
                  <Typography variant="caption" color="text.secondary">{b.kind === 'CARD_APPLICATION' ? `Card application${b.intakeCode ? ` · intake ${b.intakeCode}` : ''}` : 'Registration'}</Typography>
                </TableCell>
                <TableCell>{formatDateTime(b.submittedAt)}<Typography variant="caption" display="block" color="text.secondary">{b.submittedByName}</Typography></TableCell>
                <TableCell>
                  <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                    <Chip size="small" variant="outlined" label={`${b.students} sent`} />
                    {!!b.registered && <Chip size="small" color="success" label={`${b.registered} ${b.kind === 'CARD_APPLICATION' ? 'taken' : 'registered'}`} />}
                    {!!b.rejected && <Chip size="small" color="error" label={`${b.rejected} refused`} />}
                  </Stack>
                </TableCell>
                <TableCell align="right">
                  <Stack direction="row" spacing={0.5} justifyContent="flex-end">
                    {registry && b.kind === 'CARD_APPLICATION' && !!b.registered && <Button size="small" startIcon={<CloudSyncIcon />} disabled={busy} onClick={() => check.mutate(b.id)}>Control numbers</Button>}
                    <Button size="small" onClick={() => setItems(b)}>Students</Button>
                  </Stack>
                </TableCell>
              </TableRow>
            ))}</TableBody>
          </Table></Box>
        )}
      </CardContent></Card>

      <Dialog open={enrolOpen} onClose={() => setEnrolOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Enrol students without cover</DialogTitle>
        <DialogContent>
          <Typography>
            {`${n('NONE')} current student(s) have no cover recorded for ${d.academicYear}. Each will be registered with NHIF through the institution; NHIF then sends them a control number to pay the student contribution (about ${formatTzs(d.contribution)}). A student who gives an active NHIF card of their own before the card application is taken off the list.`}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEnrolOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={enrol.isPending} onClick={() => enrol.mutate()}>{`Enrol ${n('NONE')}`}</Button>
        </DialogActions>
      </Dialog>

      <Dialog open={applyOpen} onClose={() => setApplyOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Apply to NHIF for student cards</DialogTitle>
        <DialogContent>
          <Typography sx={{ mb: 2 }}>
            {`${n('REGISTERED')} student(s) registered with NHIF go in one card application. NHIF takes all of them or none: if it refuses, the students it names are marked with NHIF's reason and the others can be sent again.`}
          </Typography>
          <ErrorAlert error={intakes.error} />
          <TextField select fullWidth size="small" label="NHIF intake" value={intake} onChange={(e) => setIntake(e.target.value)}
            SelectProps={{ displayEmpty: true }} InputLabelProps={{ shrink: true }}
            helperText="Leave as the students' own intake unless NHIF asks for another">
            <MenuItem value="">{"Each student's own intake"}</MenuItem>
            {(intakes.data ?? []).map((i) => <MenuItem key={i.code} value={i.code}>{`${i.name} (${i.code})`}</MenuItem>)}
          </TextField>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setApplyOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={apply.isPending} onClick={() => apply.mutate()}>{`Apply for ${n('REGISTERED')}`}</Button>
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
            {c.nhifBill && <Typography variant="caption" display="block">{`Control no. ${c.nhifBill.controlNo}${c.nhifBill.amount != null ? ` · ${formatTzs(c.nhifBill.amount)}` : ''}${c.nhifBill.paidOn ? ` · paid ${formatDate(c.nhifBill.paidOn)}` : ''}`}</Typography>}
            {!c.nhifBill && c.invoice && c.status === 'AWAITING_PAYMENT' && <Typography variant="caption" display="block">{`${c.invoice.invoiceNo} · ${formatTzs(c.invoice.balance)} to pay`}</Typography>}
            {c.reason && <Typography variant="caption" color="error">{c.reason}</Typography>}
          </TableCell>
        </TableRow>
      ))}</TableBody>
    </Table></Box>
  );
}
