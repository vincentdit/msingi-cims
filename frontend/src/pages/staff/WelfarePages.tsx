import AddIcon from '@mui/icons-material/Add';
import LockIcon from '@mui/icons-material/LockOutlined';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Stack, Tab,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, formatDate, formatDateTime, formatTzs } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';

const staff = { realm: 'staff' as const };
const READ = ['INSTITUTION_ADMIN', 'DEAN_OF_STUDENTS', 'MANAGEMENT_VIEWER', 'AUDITOR'];
const MANAGE = ['INSTITUTION_ADMIN', 'DEAN_OF_STUDENTS'];
const APPEALS = ['INSTITUTION_ADMIN', 'MANAGEMENT_VIEWER'];

interface CaseView {
  id: number; caseNo: string; studentId: number; registrationNo: string; studentName: string; programme: string; category: string; occurredOn: string; place?: string;
  description: string; reportedByName: string; reportedAt: string; status: string; hearingAt?: string; hearingPlace?: string; committee?: string; studentStatement?: string;
  finding?: string; outcome?: string; outcomeDetails?: string; amount?: number; suspendedFrom?: string; suspendedTo?: string; invoiceStatus?: string; controlNo?: string;
  decidedByName?: string; decidedAt?: string; appealBy?: string; appealText?: string; appealedAt?: string; appealResult?: string; appealNote?: string;
  appealDecidedByName?: string; appealDecidedAt?: string; earlierCases: number;
}
interface Overview { open: number; hearingsThisWeek: number; appeals: number; outcomesThisYear: Record<string, number>; counsellingOpen: number; counsellingNew: number }
interface CSummary { id: number; studentId: number; registrationNo: string; studentName: string; concern: string; selfReferred: boolean; referredByName?: string; status: string; nextSessionAt?: string; openedAt: string; notes: number }
interface CCase { summary: CSummary; requestText?: string; contactPhone?: string; studentPhone?: string; notes: { id: number; note: string; byName: string; at: string }[] }

export const CATEGORY: Record<string, string> = {
  MISCONDUCT: 'Misconduct', ABSENTEEISM: 'Absence without permission', PROPERTY_DAMAGE: 'Damage to property', VIOLENCE: 'Fighting or violence', SUBSTANCE_ABUSE: 'Alcohol or drugs',
  HARASSMENT: 'Harassment or bullying', THEFT: 'Theft', HOSTEL_RULES: 'Breaking hostel rules', OTHER: 'Other',
};
const OUTCOME: Record<string, string> = {
  NONE: 'No penalty', WARNING: 'Warning', REPRIMAND: 'Reprimand', FINE: 'Fine', COMPENSATION: 'Compensation for damage', COMMUNITY_SERVICE: 'Community service',
  SUSPENSION: 'Suspension', EXPULSION: 'Expulsion',
};
const STATUS: Record<string, { label: string; color: 'default' | 'warning' | 'info' | 'success' | 'error' }> = {
  REPORTED: { label: 'Reported', color: 'warning' }, HEARING: { label: 'Hearing called', color: 'warning' }, DECIDED: { label: 'Decided', color: 'info' },
  APPEALED: { label: 'Appealed', color: 'error' }, CLOSED: { label: 'Closed', color: 'default' }, WITHDRAWN: { label: 'Withdrawn', color: 'default' },
};
const CONCERN: Record<string, string> = { ACADEMIC: 'Studies', FINANCIAL: 'Money', HEALTH: 'Health', FAMILY: 'Family', PERSONAL: 'Personal', OTHER: 'Other' };

/** Discipline and welfare (FR-WEL-01..04). */
export default function WelfarePage() {
  const can = useRoles();
  const reader = can(READ);
  const dean = can(['DEAN_OF_STUDENTS']);
  const [tab, setTab] = useState('cases');
  const [reporting, setReporting] = useState(false);
  const [referring, setReferring] = useState(false);
  const ov = useQuery({ queryKey: ['staff', 'welfare', 'overview'], enabled: reader, queryFn: () => api<Overview>('/api/welfare/overview', staff) });
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Discipline and welfare" subtitle={reader ? 'Disciplinary cases from report to appeal, and counselling handled by the Dean of Students.'
        : 'Report an incident involving a student, or refer a student to the Dean of Students.'} />
      <Stack direction="row" spacing={1} sx={{ mb: 2 }} flexWrap="wrap" useFlexGap>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setReporting(true)}>Report an incident</Button>
        <Button variant="outlined" onClick={() => setReferring(true)}>Refer a student for counselling</Button>
      </Stack>
      {ov.data && (
        <Grid container spacing={1.5} sx={{ mb: 2 }}>
          {[['Open cases', ov.data.open], ['Hearings in the next 7 days', ov.data.hearingsThisWeek], ['Appeals waiting', ov.data.appeals],
            ['Counselling open', ov.data.counsellingOpen], ['Counselling not yet met', ov.data.counsellingNew]].map(([l, v]) => (
            <Grid key={l as string} size={{ xs: 6, sm: 4, md: 2.4 }}><Card><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
              <Typography variant="caption" color="text.secondary">{l}</Typography>
              <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 20 }}>{v}</Typography>
            </CardContent></Card></Grid>
          ))}
        </Grid>
      )}
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
        <Tab value="cases" label={reader ? 'Disciplinary cases' : 'Cases I reported'} />
        {dean && <Tab value="counselling" label="Counselling" />}
      </Tabs>
      {tab === 'cases' && <Cases />}
      {tab === 'counselling' && dean && <Counselling />}
      {reporting && <ReportDialog onClose={() => setReporting(false)} />}
      {referring && <ReferDialog onClose={() => setReferring(false)} />}
    </Container>
  );
}

function Cases() {
  const navigate = useNavigate();
  const [status, setStatus] = useState('OPEN');
  const [q, setQ] = useState('');
  const list = useQuery({ queryKey: ['staff', 'welfare', 'cases', status, q], queryFn: () => api<CaseView[]>(`/api/discipline/cases?status=${status}&q=${encodeURIComponent(q)}`, staff) });
  return (
    <Card><CardContent>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mb: 2 }}>
        <TextField size="small" fullWidth label="Registration number, name or case number" value={q} onChange={(e) => setQ(e.target.value)} />
        <TextField select size="small" label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 180 }}>
          <MenuItem value="OPEN">Not yet closed</MenuItem>{Object.entries(STATUS).map(([k, v]) => <MenuItem key={k} value={k}>{v.label}</MenuItem>)}<MenuItem value="">All</MenuItem>
        </TextField>
      </Stack>
      <ErrorAlert error={list.error} />
      {list.isLoading && <Loading />}
      {list.data?.length === 0 && <Typography color="text.secondary">No cases.</Typography>}
      {!!list.data?.length && (
        <TableContainer>
          <Table size="small" sx={{ minWidth: 760 }}>
            <TableHead><TableRow><TableCell>Case</TableCell><TableCell>Student</TableCell><TableCell>What</TableCell><TableCell>Status</TableCell><TableCell>Outcome</TableCell></TableRow></TableHead>
            <TableBody>
              {list.data.map((c) => (
                <TableRow key={c.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/welfare/cases/${c.id}`)}>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}><Typography variant="body2" sx={{ fontFamily: 'monospace' }}>{c.caseNo}</Typography><Typography variant="caption" color="text.secondary">{formatDate(c.occurredOn)}</Typography></TableCell>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{c.studentName}</Typography><Typography variant="caption" color="text.secondary">{c.registrationNo} · {c.programme}</Typography></TableCell>
                  <TableCell>{CATEGORY[c.category]}{c.earlierCases > 0 && <Chip size="small" sx={{ ml: 1 }} color="error" variant="outlined" label={`${c.earlierCases} earlier`} />}</TableCell>
                  <TableCell><Chip size="small" color={STATUS[c.status].color} label={STATUS[c.status].label} /></TableCell>
                  <TableCell>{c.outcome ? OUTCOME[c.outcome] : '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </CardContent></Card>
  );
}

function ReportDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [f, setF] = useState({ registrationNo: '', category: 'MISCONDUCT', occurredOn: new Date().toISOString().slice(0, 10), place: '', description: '' });
  const save = useMutation({
    mutationFn: () => api<CaseView>('/api/discipline/cases', { ...staff, method: 'POST', body: f }),
    onSuccess: (c) => { qc.invalidateQueries({ queryKey: ['staff', 'welfare'] }); onClose(); navigate(`/staff/welfare/cases/${c.id}`); },
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Report an incident</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField label="Student's registration number" value={f.registrationNo} onChange={(e) => setF({ ...f, registrationNo: e.target.value })} />
          <TextField select label="What happened" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })}>
            {Object.entries(CATEGORY).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField>
          <Stack direction="row" spacing={1}>
            <TextField fullWidth type="date" label="When" value={f.occurredOn} onChange={(e) => setF({ ...f, occurredOn: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
            <TextField fullWidth label="Where" value={f.place} onChange={(e) => setF({ ...f, place: e.target.value })} />
          </Stack>
          <TextField label="Describe what happened, and who saw it" multiline minRows={4} value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} inputProps={{ maxLength: 2000 }} />
        </Stack>
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!f.registrationNo || !f.description.trim() || save.isPending} onClick={() => save.mutate()}>Report</Button></DialogActions>
    </Dialog>
  );
}

function ReferDialog({ onClose }: { onClose: () => void }) {
  const [f, setF] = useState({ registrationNo: '', concern: 'ACADEMIC', text: '' });
  const [done, setDone] = useState(false);
  const save = useMutation({ mutationFn: () => api('/api/welfare/referrals', { ...staff, method: 'POST', body: f }), onSuccess: () => setDone(true) });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Refer a student to the Dean of Students</DialogTitle>
      <DialogContent>
        {done ? <Alert severity="success" sx={{ mt: 1 }}>The Dean of Students has the referral. Only the Dean reads it; you will not see the case afterwards.</Alert> : (
          <Stack spacing={2} sx={{ mt: 1 }}>
            <Alert icon={<LockIcon />} severity="info">Only the Dean of Students reads what you write here.</Alert>
            <TextField label="Student's registration number" value={f.registrationNo} onChange={(e) => setF({ ...f, registrationNo: e.target.value })} />
            <TextField select label="About" value={f.concern} onChange={(e) => setF({ ...f, concern: e.target.value })}>
              {Object.entries(CONCERN).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField>
            <TextField label="Why you are concerned" multiline minRows={3} value={f.text} onChange={(e) => setF({ ...f, text: e.target.value })} inputProps={{ maxLength: 2000 }} />
          </Stack>
        )}
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>{done ? 'Close' : 'Cancel'}</Button>
        {!done && <Button variant="contained" disabled={!f.registrationNo || !f.text.trim() || save.isPending} onClick={() => save.mutate()}>Refer</Button>}</DialogActions>
    </Dialog>
  );
}

export function DisciplineCasePage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const can = useRoles();
  const q = useQuery({ queryKey: ['staff', 'welfare', 'case', id], queryFn: () => api<CaseView>(`/api/discipline/cases/${id}`, staff) });
  const [dialog, setDialog] = useState<'hearing' | 'decision' | 'withdraw' | 'appeal' | null>(null);
  const act = useMutation({
    mutationFn: (x: { path: string; body?: unknown; method?: string }) => api<CaseView>(`/api/discipline/cases/${id}/${x.path}`, { ...staff, method: x.method ?? 'POST', body: x.body }),
    onSuccess: (d) => { qc.setQueryData(['staff', 'welfare', 'case', id], d); qc.invalidateQueries({ queryKey: ['staff', 'welfare', 'cases'] }); setDialog(null); },
  });
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} />;
  const c = q.data;
  const manage = can(MANAGE);
  return (
    <Container maxWidth="md" disableGutters>
      <PageTitle title={`Case ${c.caseNo}`} subtitle={`${c.studentName} · ${c.registrationNo} · ${c.programme}`} action={<Chip color={STATUS[c.status].color} label={STATUS[c.status].label} />} />
      {c.earlierCases > 0 && <Alert severity="warning" sx={{ mb: 2 }}>The student was found responsible in {c.earlierCases} earlier {c.earlierCases === 1 ? 'case' : 'cases'}.</Alert>}
      <Card sx={{ mb: 2 }}><CardContent>
        <Typography variant="subtitle1">{CATEGORY[c.category]}</Typography>
        <Typography variant="caption" color="text.secondary">{formatDate(c.occurredOn)}{c.place ? ` · ${c.place}` : ''} · reported by {c.reportedByName}, {formatDateTime(c.reportedAt)}</Typography>
        <Typography variant="body2" sx={{ mt: 1, whiteSpace: 'pre-wrap' }}>{c.description}</Typography>
      </CardContent></Card>
      {(c.hearingAt || c.studentStatement) && (
        <Card sx={{ mb: 2 }}><CardContent>
          <Typography variant="subtitle1">Hearing</Typography>
          {c.hearingAt && <Typography variant="body2">{formatDateTime(c.hearingAt)} · {c.hearingPlace}{c.committee ? ` · ${c.committee}` : ''}</Typography>}
          {c.studentStatement ? <><Typography variant="subtitle2" sx={{ mt: 1 }}>The student's statement</Typography><Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{c.studentStatement}</Typography></>
            : <Typography variant="caption" color="text.secondary">The student has not sent a written statement.</Typography>}
        </CardContent></Card>
      )}
      {c.decidedAt && (
        <Card sx={{ mb: 2 }}><CardContent>
          <Typography variant="subtitle1">{c.status === 'WITHDRAWN' ? 'Withdrawn' : 'Decision'}</Typography>
          {c.finding && <Typography variant="body2">{c.finding === 'GUILTY' ? 'Found responsible' : 'Not found responsible'} · {c.outcome ? OUTCOME[c.outcome] : ''}</Typography>}
          {c.outcomeDetails && <Typography variant="body2">{c.outcomeDetails}</Typography>}
          {c.amount != null && <Typography variant="body2">{formatTzs(c.amount)}{c.controlNo ? ` · control number ${c.controlNo}` : ''}{c.invoiceStatus ? ` · ${c.invoiceStatus.toLowerCase()}` : ''}</Typography>}
          {c.suspendedFrom && <Typography variant="body2">Suspended {formatDate(c.suspendedFrom)} to {formatDate(c.suspendedTo)}. Record it under Status changes too.</Typography>}
          <Typography variant="caption" color="text.secondary">{c.decidedByName}, {formatDateTime(c.decidedAt)}{c.appealBy && c.status === 'DECIDED' ? ` · the student may appeal until ${formatDate(c.appealBy)}` : ''}</Typography>
        </CardContent></Card>
      )}
      {c.appealText && (
        <Card sx={{ mb: 2 }}><CardContent>
          <Typography variant="subtitle1">Appeal</Typography>
          <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{c.appealText}</Typography>
          {c.appealResult && <Alert severity="info" sx={{ mt: 1 }}>{({ UPHELD: 'Not allowed: the decision stands.', VARIED: 'Allowed in part: the penalty was changed.', SET_ASIDE: 'Allowed: the decision was set aside.' } as Record<string, string>)[c.appealResult]} {c.appealNote}
            <Typography variant="caption" component="div">{c.appealDecidedByName}, {formatDateTime(c.appealDecidedAt)}</Typography></Alert>}
        </CardContent></Card>
      )}
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
        {manage && ['REPORTED', 'HEARING'].includes(c.status) && <Button variant={c.status === 'REPORTED' ? 'contained' : 'outlined'} onClick={() => setDialog('hearing')}>{c.hearingAt ? 'Change the hearing' : 'Call a hearing'}</Button>}
        {manage && c.status === 'HEARING' && <Button variant="contained" onClick={() => setDialog('decision')}>Record the decision</Button>}
        {manage && ['REPORTED', 'HEARING'].includes(c.status) && <Button color="error" onClick={() => setDialog('withdraw')}>Withdraw</Button>}
        {manage && c.status === 'DECIDED' && <Button onClick={() => act.mutate({ path: 'close' })}>Close the case</Button>}
        {can(APPEALS) && c.status === 'APPEALED' && <Button variant="contained" onClick={() => setDialog('appeal')}>Decide the appeal</Button>}
      </Stack>
      <ErrorAlert error={act.error} />
      {dialog === 'hearing' && <HearingDialog c={c} onClose={() => setDialog(null)} onSave={(body) => act.mutate({ path: 'hearing', method: 'PUT', body })} busy={act.isPending} />}
      {dialog === 'decision' && <DecisionDialog title="Record the decision" withFinding onClose={() => setDialog(null)} onSave={(body) => act.mutate({ path: 'decision', body })} busy={act.isPending} />}
      {dialog === 'appeal' && <DecisionDialog title="Decide the appeal" appeal onClose={() => setDialog(null)} onSave={(body) => act.mutate({ path: 'appeal-decision', body })} busy={act.isPending} />}
      {dialog === 'withdraw' && <TextDialog title="Withdraw the case" label="Why the case is withdrawn" onClose={() => setDialog(null)} onSave={(text) => act.mutate({ path: 'withdraw', body: { text } })} busy={act.isPending} />}
    </Container>
  );
}

function HearingDialog({ c, onClose, onSave, busy }: { c: CaseView; onClose: () => void; onSave: (b: unknown) => void; busy: boolean }) {
  const [f, setF] = useState({ at: c.hearingAt ? c.hearingAt.slice(0, 16) : '', place: c.hearingPlace ?? '', committee: c.committee ?? '', tellStudent: true });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Call a hearing</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField type="datetime-local" label="Date and time" value={f.at} onChange={(e) => setF({ ...f, at: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField label="Where" value={f.place} onChange={(e) => setF({ ...f, place: e.target.value })} />
          <TextField label="Committee members" value={f.committee} onChange={(e) => setF({ ...f, committee: e.target.value })} />
          <FormControlLabel control={<Checkbox checked={f.tellStudent} onChange={(e) => setF({ ...f, tellStudent: e.target.checked })} />} label="Tell the student by SMS (they may bring a written statement and a witness)" />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!f.at || !f.place || busy} onClick={() => onSave({ hearingAt: new Date(f.at).toISOString(), place: f.place, committee: f.committee, tellStudent: f.tellStudent })}>Save</Button></DialogActions>
    </Dialog>
  );
}

function DecisionDialog({ title, withFinding, appeal, onClose, onSave, busy }: { title: string; withFinding?: boolean; appeal?: boolean; onClose: () => void; onSave: (b: unknown) => void; busy: boolean }) {
  const [f, setF] = useState({ finding: 'GUILTY', result: 'UPHELD', note: '', outcome: 'WARNING', details: '', amount: '', suspendedFrom: '', suspendedTo: '' });
  const needsOutcome = withFinding ? f.finding === 'GUILTY' : f.result === 'VARIED';
  const body = { ...f, amount: f.amount === '' ? undefined : Number(f.amount), suspendedFrom: f.suspendedFrom || undefined, suspendedTo: f.suspendedTo || undefined };
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {withFinding && <TextField select label="Finding" value={f.finding} onChange={(e) => setF({ ...f, finding: e.target.value })}>
            <MenuItem value="GUILTY">Responsible</MenuItem><MenuItem value="NOT_GUILTY">Not responsible</MenuItem></TextField>}
          {appeal && (
            <>
              <TextField select label="Appeal" value={f.result} onChange={(e) => setF({ ...f, result: e.target.value })}>
                <MenuItem value="UPHELD">Not allowed: the decision stands</MenuItem><MenuItem value="VARIED">Allowed in part: change the penalty</MenuItem>
                <MenuItem value="SET_ASIDE">Allowed: set the decision aside</MenuItem></TextField>
              <TextField label="Reasons" multiline minRows={2} value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} />
            </>
          )}
          {needsOutcome && (
            <>
              <TextField select label="Penalty" value={f.outcome} onChange={(e) => setF({ ...f, outcome: e.target.value })}>
                {Object.entries(OUTCOME).filter(([k]) => k !== 'NONE').map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField>
              {['FINE', 'COMPENSATION'].includes(f.outcome) && <TextField type="number" label="Amount (TZS), charged to the student's fees account" value={f.amount} onChange={(e) => setF({ ...f, amount: e.target.value })} />}
              {f.outcome === 'SUSPENSION' && (
                <Stack direction="row" spacing={1}>
                  <TextField fullWidth type="date" label="From" value={f.suspendedFrom} onChange={(e) => setF({ ...f, suspendedFrom: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
                  <TextField fullWidth type="date" label="To" value={f.suspendedTo} onChange={(e) => setF({ ...f, suspendedTo: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
                </Stack>
              )}
              <TextField label="Details" multiline minRows={2} value={f.details} onChange={(e) => setF({ ...f, details: e.target.value })} />
            </>
          )}
          {withFinding && <Typography variant="caption" color="text.secondary">The student is told by SMS and may appeal to the Principal within 14 days.</Typography>}
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={busy || (appeal && !f.note.trim())} onClick={() => onSave(body)}>Save</Button></DialogActions>
    </Dialog>
  );
}

function TextDialog({ title, label, onClose, onSave, busy }: { title: string; label: string; onClose: () => void; onSave: (t: string) => void; busy: boolean }) {
  const [t, setT] = useState('');
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent><TextField fullWidth multiline minRows={3} label={label} value={t} onChange={(e) => setT(e.target.value)} sx={{ mt: 1 }} /></DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!t.trim() || busy} onClick={() => onSave(t)}>Save</Button></DialogActions>
    </Dialog>
  );
}

function Counselling() {
  const [status, setStatus] = useState('OPEN');
  const [open, setOpen] = useState<number | null>(null);
  const list = useQuery({ queryKey: ['staff', 'welfare', 'counselling', status], queryFn: () => api<CSummary[]>(`/api/welfare/counselling?status=${status}`, staff) });
  return (
    <Card><CardContent>
      <Alert icon={<LockIcon />} severity="info" sx={{ mb: 2 }}>Only the Dean of Students sees these. What is written is kept encrypted, and each time a case is opened it is recorded.</Alert>
      <TextField select size="small" label="Show" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ mb: 2, minWidth: 160 }}>
        <MenuItem value="OPEN">Open</MenuItem><MenuItem value="CLOSED">Closed</MenuItem><MenuItem value="">All</MenuItem></TextField>
      <ErrorAlert error={list.error} />
      {list.data?.length === 0 && <Typography color="text.secondary">Nothing here.</Typography>}
      {!!list.data?.length && (
        <Table size="small">
          <TableHead><TableRow><TableCell>Student</TableCell><TableCell>About</TableCell><TableCell>From</TableCell><TableCell>Next meeting</TableCell></TableRow></TableHead>
          <TableBody>
            {list.data.map((c) => (
              <TableRow key={c.id} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(c.id)}>
                <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{c.studentName}</Typography><Typography variant="caption" color="text.secondary">{c.registrationNo}</Typography></TableCell>
                <TableCell>{CONCERN[c.concern]}</TableCell>
                <TableCell>{c.selfReferred ? 'The student' : c.referredByName}<Typography variant="caption" color="text.secondary" component="div">{formatDate(c.openedAt)}</Typography></TableCell>
                <TableCell>{c.nextSessionAt ? formatDateTime(c.nextSessionAt) : <Chip size="small" color="warning" label="Not yet met" />}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {open !== null && <CounsellingDialog id={open} onClose={() => setOpen(null)} />}
    </CardContent></Card>
  );
}

function CounsellingDialog({ id, onClose }: { id: number; onClose: () => void }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'welfare', 'counselling-case', id], queryFn: () => api<CCase>(`/api/welfare/counselling/${id}`, staff) });
  const [note, setNote] = useState('');
  const [at, setAt] = useState('');
  const act = useMutation({
    mutationFn: (x: { path: string; body: unknown; method?: string }) => api<CCase>(`/api/welfare/counselling/${id}/${x.path}`, { ...staff, method: x.method ?? 'POST', body: x.body }),
    onSuccess: (d) => { qc.setQueryData(['staff', 'welfare', 'counselling-case', id], d); qc.invalidateQueries({ queryKey: ['staff', 'welfare', 'counselling'] }); setNote(''); setAt(''); },
  });
  const c = q.data;
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{c ? `${c.summary.studentName} · ${c.summary.registrationNo}` : 'Counselling'}</DialogTitle>
      <DialogContent>
        {!c ? <Loading /> : (
          <Stack spacing={2}>
            <Box>
              <Typography variant="caption" color="text.secondary">{CONCERN[c.summary.concern]} · {c.summary.selfReferred ? 'asked by the student' : `referred by ${c.summary.referredByName}`} · {formatDateTime(c.summary.openedAt)}</Typography>
              {c.requestText && <Typography variant="body2" sx={{ mt: 0.5, whiteSpace: 'pre-wrap' }}>{c.requestText}</Typography>}
              <Typography variant="body2" sx={{ mt: 0.5 }}>Phone: {c.contactPhone ?? c.studentPhone ?? '—'}</Typography>
            </Box>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }}>
              <TextField size="small" type="datetime-local" label="Next meeting" value={at} onChange={(e) => setAt(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
              <Button variant="outlined" disabled={!at || act.isPending} onClick={() => act.mutate({ path: 'appointment', method: 'PUT', body: { at: new Date(at).toISOString() } })}>Set and tell the student</Button>
              {c.summary.nextSessionAt && <Typography variant="body2">Booked: {formatDateTime(c.summary.nextSessionAt)}</Typography>}
            </Stack>
            <Box>
              <Typography variant="subtitle1">Notes</Typography>
              {c.notes.map((n) => <Box key={n.id} sx={{ py: 1, borderBottom: '1px solid', borderColor: 'divider' }}>
                <Typography variant="caption" color="text.secondary">{formatDateTime(n.at)} · {n.byName}</Typography>
                <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{n.note}</Typography></Box>)}
              <TextField fullWidth multiline minRows={3} label="Add a note" value={note} onChange={(e) => setNote(e.target.value)} sx={{ mt: 1 }} />
              <Button sx={{ mt: 1 }} variant="contained" disabled={!note.trim() || act.isPending} onClick={() => act.mutate({ path: 'notes', body: { text: note } })}>Save the note</Button>
            </Box>
          </Stack>
        )}
        <ErrorAlert error={q.error ?? act.error} />
      </DialogContent>
      <DialogActions>
        {c && <Button onClick={() => act.mutate({ path: 'open', method: 'PUT', body: { open: c.summary.status !== 'OPEN' } })}>{c.summary.status === 'OPEN' ? 'Close the case' : 'Reopen'}</Button>}
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
