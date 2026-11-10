import AddIcon from '@mui/icons-material/Add';
import DownloadIcon from '@mui/icons-material/Download';
import UploadIcon from '@mui/icons-material/UploadFile';
import VerifiedIcon from '@mui/icons-material/Verified';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton,
  MenuItem, Stack, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate, uploadForm } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { StaffLink } from '../../components/StaffLink';
import { BalancesTable, LeaveRequestsCard, NewLeaveDialog, type Balance, type LeaveRequest, type LeaveType } from './LeavePage';
import { useRoles } from './MarksPages';

const staff = { realm: 'staff' as const };

interface StaffSummary {
  id: number; staffNo: string; title?: string; fullName: string; gender: 'M' | 'F'; jobTitle?: string; category: string; employment: string; department?: string;
  departmentId?: number; campus?: string; highestQualification?: string; phone?: string; status: string; contractEndsOn?: string; hasAccount: boolean;
}
interface Qualification { id: number; level: string; award: string; institution: string; year: number; fileName?: string; verifiedByName?: string; verifiedAt?: string }
interface StaffDetail {
  id: number; staffNo: string; title?: string; fullName: string; gender: 'M' | 'F'; dateOfBirth?: string; nationality: string; nin?: string; phone?: string;
  email?: string; jobTitle?: string; category: string; employment: string; departmentId?: number; department?: string; campusId?: number; campus?: string;
  firstAppointedOn?: string; contractEndsOn?: string; highestQualification?: string; specialisation?: string; tutorRegNo?: string; status: string;
  leftOn?: string; leftReason?: string; userId?: number; username?: string; qualifications: Qualification[];
}
interface Overview { active: number; academic: number; female: number; contractsEnding: number; withoutQualification: number; endingSoon: StaffSummary[] }
interface Account { id: number; fullName: string; username: string; jobTitle?: string; linkedStaffId?: number }
interface Option { id: number; name: string; code?: string }

export const CATEGORY: Record<string, string> = { ACADEMIC: 'Academic (tutors)', ADMINISTRATIVE: 'Administrative', TECHNICAL: 'Technical', SUPPORT: 'Support' };
export const EMPLOYMENT: Record<string, string> = { PERMANENT: 'Permanent', CONTRACT: 'Contract', PART_TIME: 'Part-time', VOLUNTEER: 'Volunteer' };
export const QUALIFICATION: Record<string, string> = {
  PHD: 'PhD', MASTERS: 'Masters', POSTGRADUATE_DIPLOMA: 'Postgraduate diploma', BACHELOR: 'Bachelor', ADVANCED_DIPLOMA: 'Advanced diploma', DIPLOMA: 'Diploma',
  CERTIFICATE: 'Certificate', NONE: 'None',
};
const LEFT: Record<string, string> = { RESIGNED: 'Resigned', RETIRED: 'Retired', CONTRACT_ENDED: 'Contract ended', DISMISSED: 'Dismissed', TRANSFERRED: 'Transferred', DECEASED: 'Deceased', OTHER: 'Other' };

function useOptions() {
  const departments = useQuery({ queryKey: ['staff', 'departments'], queryFn: () => api<Option[]>('/api/academic/departments', staff) });
  const campuses = useQuery({ queryKey: ['staff', 'campuses'], queryFn: () => api<Option[]>('/api/academic/campuses', staff).catch(() => [] as Option[]) });
  return { departments: departments.data ?? [], campuses: campuses.data ?? [] };
}

/** Human resources (FR-HR-01..04): staff records, leave to approve and the leave rules. */
export function HrPage() {
  const [tab, setTab] = useState('staff');
  const canWrite = useRoles()(['INSTITUTION_ADMIN', 'HR_OFFICER']);
  const ov = useQuery({ queryKey: ['staff', 'hr', 'overview'], queryFn: () => api<Overview>('/api/hr/overview', staff) });
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Human resources" subtitle="Staff records and qualifications, leave, and the staffing figures for NACTVET."
        action={<Button variant="outlined" startIcon={<DownloadIcon />} onClick={() => download('/api/hr/staffing-return', 'staff', 'staffing-return.xlsx')}>Staffing return</Button>} />
      {ov.data && (
        <Grid container spacing={1.5} sx={{ mb: 2 }}>
          {[['Staff', ov.data.active], ['Academic', ov.data.academic], ['Women', ov.data.female], ['Contracts ending in 60 days', ov.data.contractsEnding],
            ['Tutors without a qualification recorded', ov.data.withoutQualification]].map(([l, v]) => (
            <Grid key={l} size={{ xs: 6, sm: 4, md: 2.4 }}><Card><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
              <Typography variant="caption" color="text.secondary">{l}</Typography>
              <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 22 }}>{v}</Typography>
            </CardContent></Card></Grid>
          ))}
        </Grid>
      )}
      {!!ov.data?.endingSoon.length && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          {`Contracts ending soon: ${ov.data.endingSoon.map((s) => `${s.fullName} (${formatDate(s.contractEndsOn)})`).join(', ')}.`}
        </Alert>
      )}
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }} variant="scrollable">
        <Tab value="staff" label="Staff" />
        <Tab value="leave" label="Leave requests" />
        <Tab value="rules" label="Leave types and holidays" />
      </Tabs>
      {tab === 'staff' && <StaffList canWrite={canWrite} />}
      {tab === 'leave' && <LeaveRequestsCard hr />}
      {tab === 'rules' && <LeaveRules canWrite={canWrite} />}
    </Container>
  );
}

function StaffList({ canWrite }: { canWrite: boolean }) {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [q, setQ] = useState('');
  const [status, setStatus] = useState('ACTIVE');
  const [category, setCategory] = useState('');
  const [adding, setAdding] = useState(false);
  const [importing, setImporting] = useState(false);
  const list = useQuery({ queryKey: ['staff', 'hr', 'list', q, status, category],
    queryFn: () => api<StaffSummary[]>(`/api/hr/staff?q=${encodeURIComponent(q)}&status=${status}${category ? `&category=${category}` : ''}`, staff) });
  return (
    <Card><CardContent>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ mb: 2 }}>
        <TextField size="small" fullWidth label="Name, staff number or job title" value={q} onChange={(e) => setQ(e.target.value)} />
        <TextField select size="small" label="Category" value={category} onChange={(e) => setCategory(e.target.value)} sx={{ minWidth: 180 }}>
          <MenuItem value="">All</MenuItem>{Object.entries(CATEGORY).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 140 }}>
          <MenuItem value="ACTIVE">Working</MenuItem><MenuItem value="LEFT">Left</MenuItem><MenuItem value="ALL">All</MenuItem>
        </TextField>
        {canWrite && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setAdding(true)} sx={{ whiteSpace: 'nowrap' }}>Add</Button>}
        {canWrite && <Button startIcon={<UploadIcon />} onClick={() => setImporting(true)}>Import</Button>}
      </Stack>
      <ErrorAlert error={list.error} />
      {list.isLoading && <Loading />}
      {list.data?.length === 0 && <Typography color="text.secondary">No staff records yet.</Typography>}
      {!!list.data?.length && (
        <TableContainer>
          <Table size="small" sx={{ minWidth: 720 }}>
            <TableHead><TableRow><TableCell>Name</TableCell><TableCell>Job</TableCell><TableCell>Department</TableCell><TableCell>Employment</TableCell><TableCell>Qualification</TableCell></TableRow></TableHead>
            <TableBody>
              {list.data.map((s) => (
                <TableRow key={s.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/hr/${s.id}`)}>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{`${s.title ? `${s.title} ` : ''}${s.fullName}`}</Typography>
                    <Typography variant="caption" color="text.secondary">{`${s.staffNo}${s.hasAccount ? '' : ' · no CIMS account'}`}</Typography></TableCell>
                  <TableCell>{s.jobTitle ?? '—'}<Typography variant="caption" color="text.secondary" component="div">{CATEGORY[s.category]}</Typography></TableCell>
                  <TableCell>{s.department ?? '—'}</TableCell>
                  <TableCell>{EMPLOYMENT[s.employment]}{s.contractEndsOn && <Typography variant="caption" color="text.secondary" component="div">{`to ${formatDate(s.contractEndsOn)}`}</Typography>}</TableCell>
                  <TableCell>{s.highestQualification ? QUALIFICATION[s.highestQualification] : '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {adding && <StaffDialog onClose={() => setAdding(false)} onSaved={(d) => { setAdding(false); navigate(`/staff/hr/${d.id}`); }} />}
      {importing && <ImportStaffDialog onClose={() => { setImporting(false); qc.invalidateQueries({ queryKey: ['staff', 'hr'] }); }} />}
    </CardContent></Card>
  );
}

function StaffDialog({ current, onClose, onSaved }: { current?: StaffDetail; onClose: () => void; onSaved: (d: StaffDetail) => void }) {
  const { departments, campuses } = useOptions();
  const accounts = useQuery({ queryKey: ['staff', 'hr', 'accounts'], queryFn: () => api<Account[]>('/api/hr/accounts', staff) });
  const [f, setF] = useState<Partial<StaffDetail>>(current ?? { gender: 'F', category: 'ACADEMIC', employment: 'PERMANENT', nationality: 'Tanzanian' });
  const save = useMutation({
    mutationFn: () => api<StaffDetail>(current ? `/api/hr/staff/${current.id}` : '/api/hr/staff', { ...staff, method: current ? 'PUT' : 'POST', body: f }),
    onSuccess: onSaved,
  });
  const set = (k: keyof StaffDetail, v: unknown) => setF({ ...f, [k]: v === '' ? undefined : v });
  const t = (k: keyof StaffDetail, label: string, size: number, extra?: object) => (
    <Grid size={{ xs: 12, sm: size }}><TextField fullWidth label={label} value={(f[k] as string | number | undefined) ?? ''} onChange={(e) => set(k, e.target.value)} {...extra} /></Grid>
  );
  const date = (k: keyof StaffDetail, label: string) => t(k, label, 4, { type: 'date', slotProps: { inputLabel: { shrink: true } } });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{current ? 'Edit the staff record' : 'Add a member of staff'}</DialogTitle>
      <DialogContent>
        <Grid container spacing={2} sx={{ mt: 0.5 }}>
          {t('staffNo', 'Staff number', 3)}
          {t('title', 'Title', 2)}
          {t('fullName', 'Full name', 7)}
          <Grid size={{ xs: 12, sm: 3 }}><TextField select fullWidth label="Sex" value={f.gender ?? ''} onChange={(e) => set('gender', e.target.value)}>
            <MenuItem value="F">Female</MenuItem><MenuItem value="M">Male</MenuItem></TextField></Grid>
          {date('dateOfBirth', 'Date of birth')}
          {t('nationality', 'Nationality', 5)}
          {t('phone', 'Mobile number', 4)}
          {t('email', 'Email', 5)}
          {t('nin', 'NIDA number', 3)}
          {t('jobTitle', 'Job title', 5)}
          <Grid size={{ xs: 12, sm: 4 }}><TextField select fullWidth label="Category" value={f.category ?? ''} onChange={(e) => set('category', e.target.value)}>
            {Object.entries(CATEGORY).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField></Grid>
          <Grid size={{ xs: 12, sm: 3 }}><TextField select fullWidth label="Employment" value={f.employment ?? ''} onChange={(e) => set('employment', e.target.value)}>
            {Object.entries(EMPLOYMENT).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><TextField select fullWidth label="Department" value={f.departmentId ?? ''} onChange={(e) => set('departmentId', e.target.value === '' ? '' : Number(e.target.value))}>
            <MenuItem value="">None</MenuItem>{departments.map((d) => <MenuItem key={d.id} value={d.id}>{d.name}</MenuItem>)}</TextField></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><TextField select fullWidth label="Campus" value={f.campusId ?? ''} onChange={(e) => set('campusId', e.target.value === '' ? '' : Number(e.target.value))}>
            <MenuItem value="">None</MenuItem>{campuses.map((d) => <MenuItem key={d.id} value={d.id}>{d.name}</MenuItem>)}</TextField></Grid>
          {date('firstAppointedOn', 'First appointed')}
          {date('contractEndsOn', 'Contract ends')}
          <Grid size={{ xs: 12, sm: 4 }}><TextField select fullWidth label="Highest qualification" value={f.highestQualification ?? ''} onChange={(e) => set('highestQualification', e.target.value)}>
            <MenuItem value="">Not recorded</MenuItem>{Object.entries(QUALIFICATION).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField></Grid>
          {t('specialisation', 'Specialisation', 8)}
          {t('tutorRegNo', 'Tutor registration number', 4)}
          <Grid size={12}><TextField select fullWidth label="CIMS account (for leave requests in CIMS)" value={f.userId ?? ''} onChange={(e) => set('userId', e.target.value === '' ? '' : Number(e.target.value))}>
            <MenuItem value="">No account</MenuItem>
            {(accounts.data ?? []).filter((a) => !a.linkedStaffId || a.linkedStaffId === current?.id).map((a) => <MenuItem key={a.id} value={a.id}>{`${a.fullName} (${a.username})`}</MenuItem>)}
          </TextField></Grid>
        </Grid>
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}

function ImportStaffDialog({ onClose }: { onClose: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const go = useMutation({ mutationFn: () => { const f = new FormData(); f.append('file', file!); return uploadForm<{ added: number; skipped: number; problems: string[] }>('/api/hr/staff/import', 'staff', f, 'POST'); } });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Import staff</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 2 }}>
          A CSV or Excel file with the columns Staff no, Full name, Gender, Category (Academic, Administrative, Technical, Support), Employment (Permanent, Contract,
          Part-time, Volunteer), Job title, Department, Phone, Email, Highest qualification, Specialisation, First appointed, Contract ends (dates as yyyy-mm-dd).
        </Typography>
        <Button component="label" variant="outlined" startIcon={<UploadIcon />}>{file ? file.name : 'Choose the file'}
          <input hidden type="file" accept=".csv,.xlsx" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></Button>
        <ErrorAlert error={go.error} />
        {go.data && (
          <Alert severity={go.data.problems.length ? 'warning' : 'success'} sx={{ mt: 2 }}>
            {`${go.data.added} added, ${go.data.skipped} skipped.`}
            {go.data.problems.slice(0, 12).map((p) => <div key={p}>{p}</div>)}
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={!file || go.isPending || !!go.data} onClick={() => go.mutate()}>Import</Button>
      </DialogActions>
    </Dialog>
  );
}

/** One member of staff: the record, qualifications and leave. */
export function StaffMemberPage() {
  const id = Number(useParams().id);
  const qc = useQueryClient();
  const canWrite = useRoles()(['INSTITUTION_ADMIN', 'HR_OFFICER']);
  const q = useQuery({ queryKey: ['staff', 'hr', 'member', id], queryFn: () => api<StaffDetail>(`/api/hr/staff/${id}`, staff) });
  const year = new Date().getFullYear();
  const balances = useQuery({ queryKey: ['staff', 'hr', 'balances', id, year], queryFn: () => api<Balance[]>(`/api/hr/staff/${id}/leave-balances?year=${year}`, staff) });
  const history = useQuery({ queryKey: ['staff', 'hr', 'history', id], queryFn: () => api<LeaveRequest[]>(`/api/hr/staff/${id}/leave`, staff) });
  const types = useQuery({ queryKey: ['staff', 'leave', 'types'], queryFn: () => api<LeaveType[]>('/api/leave/types', staff) });
  const [editing, setEditing] = useState(false);
  const [leaving, setLeaving] = useState(false);
  const [qual, setQual] = useState(false);
  const [recording, setRecording] = useState(false);
  const [adjusting, setAdjusting] = useState(false);
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff', 'hr'] });
  const verify = useMutation({ mutationFn: (qid: number) => api(`/api/hr/qualifications/${qid}/verify`, { ...staff, method: 'POST' }), onSuccess: refresh });
  const removeQ = useMutation({ mutationFn: (qid: number) => api(`/api/hr/qualifications/${qid}`, { ...staff, method: 'DELETE' }), onSuccess: refresh });
  const back = useMutation({ mutationFn: () => api(`/api/hr/staff/${id}/left`, { ...staff, method: 'PUT', body: { reason: null } }), onSuccess: refresh });
  const s = q.data;
  if (q.isLoading) return <Loading />;
  if (!s) return <ErrorAlert error={q.error} />;
  const row = (label: string, value?: string | null) => (
    <Grid size={{ xs: 12, sm: 6, md: 4 }}><Typography variant="caption" color="text.secondary">{label}</Typography><Typography variant="body2">{value || '—'}</Typography></Grid>
  );
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title={`${s.title ? `${s.title} ` : ''}${s.fullName}`} subtitle={`${s.staffNo} · ${s.jobTitle ?? CATEGORY[s.category]}${s.department ? ` · ${s.department}` : ''}`}
        action={<Button component={StaffLink} to="/staff/hr">All staff</Button>} />
      {s.status === 'LEFT' && <Alert severity="info" sx={{ mb: 2 }} action={canWrite && <Button color="inherit" size="small" onClick={() => back.mutate()}>Back at work</Button>}>
        {`Left on ${formatDate(s.leftOn)} (${LEFT[s.leftReason ?? 'OTHER']}).`}</Alert>}
      <Stack spacing={2}>
        <Card><CardContent>
          <Stack direction="row" alignItems="center" sx={{ mb: 1.5 }}>
            <Typography variant="h6" sx={{ flexGrow: 1 }}>Record</Typography>
            {canWrite && <Button size="small" onClick={() => setEditing(true)}>Edit</Button>}
            {canWrite && s.status === 'ACTIVE' && <Button size="small" color="warning" onClick={() => setLeaving(true)}>Left the institution</Button>}
          </Stack>
          <Grid container spacing={1.5}>
            {row('Sex', s.gender === 'F' ? 'Female' : 'Male')}
            {row('Date of birth', s.dateOfBirth && formatDate(s.dateOfBirth))}
            {row('Nationality', s.nationality)}
            {row('Mobile', s.phone)}
            {row('Email', s.email)}
            {row('NIDA number', s.nin)}
            {row('Employment', `${EMPLOYMENT[s.employment]}${s.contractEndsOn ? `, to ${formatDate(s.contractEndsOn)}` : ''}`)}
            {row('First appointed', s.firstAppointedOn && formatDate(s.firstAppointedOn))}
            {row('Campus', s.campus)}
            {row('Highest qualification', s.highestQualification && QUALIFICATION[s.highestQualification])}
            {row('Specialisation', s.specialisation)}
            {row('Tutor registration', s.tutorRegNo)}
            {row('CIMS account', s.username ?? 'None: leave is recorded by HR')}
          </Grid>
        </CardContent></Card>

        <Card><CardContent>
          <Stack direction="row" alignItems="center" sx={{ mb: 1 }}>
            <Typography variant="h6" sx={{ flexGrow: 1 }}>Qualifications</Typography>
            {canWrite && <Button size="small" startIcon={<AddIcon />} onClick={() => setQual(true)}>Add</Button>}
          </Stack>
          <ErrorAlert error={verify.error ?? removeQ.error} />
          {s.qualifications.length === 0 && <Typography variant="body2" color="text.secondary">None recorded.</Typography>}
          <Stack spacing={1}>
            {s.qualifications.map((x) => (
              <Box key={x.id} sx={{ display: 'flex', gap: 1, alignItems: 'center', p: 1.25, border: 1, borderColor: 'divider', borderRadius: 1 }}>
                <Box sx={{ flexGrow: 1 }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{x.award}</Typography>
                  <Typography variant="caption" color="text.secondary">{`${QUALIFICATION[x.level] ?? x.level} · ${x.institution} · ${x.year}`}</Typography>
                </Box>
                {x.verifiedAt ? <Chip size="small" color="success" icon={<VerifiedIcon />} label={`Checked by ${x.verifiedByName}`} />
                  : canWrite && <Button size="small" onClick={() => verify.mutate(x.id)}>Mark as checked</Button>}
                {x.fileName && <Button size="small" startIcon={<DownloadIcon />} onClick={() => download(`/api/hr/qualifications/${x.id}/file`, 'staff', x.fileName!)}>Certificate</Button>}
                {canWrite && <IconButton size="small" aria-label="Remove" onClick={() => { if (window.confirm('Remove this qualification?')) removeQ.mutate(x.id); }}><DeleteIcon fontSize="small" /></IconButton>}
              </Box>
            ))}
          </Stack>
        </CardContent></Card>

        <Card><CardContent>
          <Stack direction="row" alignItems="center" sx={{ mb: 1 }}>
            <Typography variant="h6" sx={{ flexGrow: 1 }}>{`Leave in ${year}`}</Typography>
            {canWrite && s.status === 'ACTIVE' && <Button size="small" onClick={() => setAdjusting(true)}>Adjust a balance</Button>}
            {canWrite && s.status === 'ACTIVE' && <Button size="small" variant="outlined" onClick={() => setRecording(true)}>Record leave</Button>}
          </Stack>
          <ErrorAlert error={balances.error ?? history.error} />
          <BalancesTable balances={balances.data ?? []} />
          {!!history.data?.length && (
            <TableContainer sx={{ mt: 2 }}>
              <Table size="small"><TableHead><TableRow><TableCell>Leave</TableCell><TableCell>Dates</TableCell><TableCell align="right">Days</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
                <TableBody>{history.data.map((r) => (
                  <TableRow key={r.id}><TableCell>{r.leaveType}</TableCell><TableCell>{`${formatDate(r.startOn)} – ${formatDate(r.endOn)}`}</TableCell>
                    <TableCell align="right">{r.days}</TableCell><TableCell>{STATUS[r.status]}{r.decidedByName ? ` · ${r.decidedByName}` : ''}</TableCell></TableRow>
                ))}</TableBody></Table>
            </TableContainer>
          )}
        </CardContent></Card>
      </Stack>
      {editing && <StaffDialog current={s} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); refresh(); }} />}
      {leaving && <LeftDialog id={id} onClose={() => setLeaving(false)} onDone={() => { setLeaving(false); refresh(); }} />}
      {qual && <QualificationDialog id={id} onClose={() => setQual(false)} onDone={() => { setQual(false); refresh(); }} />}
      {recording && <NewLeaveDialog types={(types.data ?? []).filter((t) => t.active && (!t.onlyGender || t.onlyGender === s.gender))}
        path={`/api/hr/staff/${id}/leave`} title={`Record leave for ${s.fullName}`} onClose={() => setRecording(false)}
        onDone={() => { setRecording(false); refresh(); }} />}
      {adjusting && <AdjustDialog id={id} types={types.data ?? []} onClose={() => setAdjusting(false)} onDone={() => { setAdjusting(false); refresh(); }} />}
    </Container>
  );
}

const STATUS: Record<string, string> = { SUBMITTED: 'Waiting', APPROVED: 'Approved', REJECTED: 'Not approved', CANCELLED: 'Cancelled' };

function LeftDialog({ id, onClose, onDone }: { id: number; onClose: () => void; onDone: () => void }) {
  const [on, setOn] = useState(new Date().toISOString().slice(0, 10));
  const [reason, setReason] = useState('RESIGNED');
  const go = useMutation({ mutationFn: () => api(`/api/hr/staff/${id}/left`, { ...staff, method: 'PUT', body: { leftOn: on, reason } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Left the institution</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField type="date" label="Last day" value={on} onChange={(e) => setOn(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField select label="Reason" value={reason} onChange={(e) => setReason(e.target.value)}>{Object.entries(LEFT).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField>
          <Typography variant="caption" color="text.secondary">Waiting leave requests are cancelled. Disable the person's CIMS account in Users & roles.</Typography>
          <ErrorAlert error={go.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" color="warning" disabled={go.isPending} onClick={() => go.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

function QualificationDialog({ id, onClose, onDone }: { id: number; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ level: 'BACHELOR', award: '', institution: '', year: '' });
  const [file, setFile] = useState<File | null>(null);
  const go = useMutation({
    mutationFn: () => { const d = new FormData(); Object.entries(f).forEach(([k, v]) => d.append(k, v)); if (file) d.append('file', file); return uploadForm(`/api/hr/staff/${id}/qualifications`, 'staff', d, 'POST'); },
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Add a qualification</DialogTitle>
      <DialogContent>
        <Grid container spacing={2} sx={{ mt: 0.5 }}>
          <Grid size={{ xs: 12, sm: 5 }}><TextField select fullWidth label="Level" value={f.level} onChange={(e) => setF({ ...f, level: e.target.value })}>
            {Object.entries(QUALIFICATION).filter(([k]) => k !== 'NONE').map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}</TextField></Grid>
          <Grid size={{ xs: 12, sm: 7 }}><TextField fullWidth label="Award (e.g. BSc Computer Science)" value={f.award} onChange={(e) => setF({ ...f, award: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, sm: 8 }}><TextField fullWidth label="Awarded by" value={f.institution} onChange={(e) => setF({ ...f, institution: e.target.value })} /></Grid>
          <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth type="number" label="Year" value={f.year} onChange={(e) => setF({ ...f, year: e.target.value })} /></Grid>
          <Grid size={12}>
            <Button component="label" variant="outlined" startIcon={<UploadIcon />}>{file ? file.name : 'Attach the certificate (PDF or picture)'}
              <input hidden type="file" accept=".pdf,.jpg,.jpeg,.png" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></Button>
          </Grid>
        </Grid>
        <ErrorAlert error={go.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={go.isPending || !f.award || !f.institution || !f.year} onClick={() => go.mutate()}>Add</Button></DialogActions>
    </Dialog>
  );
}

function AdjustDialog({ id, types, onClose, onDone }: { id: number; types: LeaveType[]; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ leaveTypeId: types.find((t) => t.code === 'ANNUAL')?.id ?? types[0]?.id, year: new Date().getFullYear(), days: '', reason: 'Days carried forward' });
  const go = useMutation({ mutationFn: () => api(`/api/hr/staff/${id}/leave-adjustments`, { ...staff, method: 'POST', body: { ...f, days: Number(f.days) } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Adjust a leave balance</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select label="Leave" value={f.leaveTypeId ?? ''} onChange={(e) => setF({ ...f, leaveTypeId: Number(e.target.value) })}>
            {types.filter((t) => t.daysPerYear != null).map((t) => <MenuItem key={t.id} value={t.id}>{t.name}</MenuItem>)}</TextField>
          <TextField type="number" label="Year" value={f.year} onChange={(e) => setF({ ...f, year: Number(e.target.value) })} />
          <TextField type="number" label="Days to add (negative to take away)" value={f.days} onChange={(e) => setF({ ...f, days: e.target.value })} />
          <TextField label="Reason" value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })} />
          <ErrorAlert error={go.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={go.isPending || !f.days} onClick={() => go.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

function LeaveRules({ canWrite }: { canWrite: boolean }) {
  const qc = useQueryClient();
  const year = new Date().getFullYear();
  const [y, setY] = useState(year);
  const types = useQuery({ queryKey: ['staff', 'leave', 'types'], queryFn: () => api<LeaveType[]>('/api/leave/types', staff) });
  const holidays = useQuery({ queryKey: ['staff', 'leave', 'holidays', y], queryFn: () => api<{ id?: number; day: string; name: string; fixed: boolean }[]>(`/api/leave/holidays?year=${y}`, staff) });
  const [edit, setEdit] = useState<Partial<LeaveType> | null>(null);
  const [h, setH] = useState({ day: '', name: '' });
  const saveType = useMutation({
    mutationFn: () => api<LeaveType[]>(edit?.id ? `/api/leave/types/${edit.id}` : '/api/leave/types', { ...staff, method: edit?.id ? 'PUT' : 'POST', body: edit }),
    onSuccess: (d) => { qc.setQueryData(['staff', 'leave', 'types'], d); setEdit(null); },
  });
  const addHoliday = useMutation({ mutationFn: () => api('/api/leave/holidays', { ...staff, method: 'POST', body: h }),
    onSuccess: () => { setH({ day: '', name: '' }); qc.invalidateQueries({ queryKey: ['staff', 'leave', 'holidays'] }); } });
  const removeHoliday = useMutation({ mutationFn: (id: number) => api(`/api/leave/holidays/${id}`, { ...staff, method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staff', 'leave', 'holidays'] }) });
  return (
    <Grid container spacing={2}>
      <Grid size={{ xs: 12, md: 7 }}>
        <Card><CardContent>
          <Stack direction="row" alignItems="center" sx={{ mb: 1 }}>
            <Typography variant="h6" sx={{ flexGrow: 1 }}>Leave types</Typography>
            {canWrite && <Button size="small" startIcon={<AddIcon />} onClick={() => setEdit({ active: true, calendarDays: false })}>Add</Button>}
          </Stack>
          <TableContainer><Table size="small">
            <TableHead><TableRow><TableCell>Leave</TableCell><TableCell align="right">Days a year</TableCell><TableCell>Counted in</TableCell><TableCell>For</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>{(types.data ?? []).map((t) => (
              <TableRow key={t.id} sx={{ opacity: t.active ? 1 : 0.5 }}>
                <TableCell>{t.name}</TableCell><TableCell align="right">{t.daysPerYear ?? 'As granted'}</TableCell>
                <TableCell>{t.calendarDays ? 'Calendar days' : 'Working days'}</TableCell>
                <TableCell>{t.onlyGender === 'F' ? 'Women' : t.onlyGender === 'M' ? 'Men' : 'Everyone'}</TableCell>
                <TableCell align="right">{canWrite && <Button size="small" onClick={() => setEdit(t)}>Edit</Button>}</TableCell>
              </TableRow>
            ))}</TableBody>
          </Table></TableContainer>
          <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 1 }}>
            Set to the Employment and Labour Relations Act at the start; change them to your institution's scheme of service.
          </Typography>
        </CardContent></Card>
      </Grid>
      <Grid size={{ xs: 12, md: 5 }}>
        <Card><CardContent>
          <Stack direction="row" alignItems="center" sx={{ mb: 1 }}>
            <Typography variant="h6" sx={{ flexGrow: 1 }}>Public holidays</Typography>
            <TextField select size="small" value={y} onChange={(e) => setY(Number(e.target.value))}>{[year - 1, year, year + 1].map((v) => <MenuItem key={v} value={v}>{v}</MenuItem>)}</TextField>
          </Stack>
          <Stack spacing={0.5}>
            {(holidays.data ?? []).map((x) => (
              <Stack key={x.day} direction="row" alignItems="center" spacing={1}>
                <Typography variant="body2" sx={{ width: 110 }}>{formatDate(x.day)}</Typography>
                <Typography variant="body2" sx={{ flexGrow: 1 }}>{x.name}</Typography>
                {!x.fixed && canWrite && <IconButton size="small" aria-label="Remove" onClick={() => removeHoliday.mutate(x.id!)}><DeleteIcon fontSize="small" /></IconButton>}
              </Stack>
            ))}
          </Stack>
          {canWrite && (
            <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
              <TextField size="small" type="date" value={h.day} onChange={(e) => setH({ ...h, day: e.target.value })} />
              <TextField size="small" label="e.g. Eid el-Fitr" value={h.name} onChange={(e) => setH({ ...h, name: e.target.value })} sx={{ flexGrow: 1 }} />
              <Button variant="outlined" disabled={!h.day || !h.name || addHoliday.isPending} onClick={() => addHoliday.mutate()}>Add</Button>
            </Stack>
          )}
          <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 1 }}>The fixed national days are always counted; add Good Friday, Easter Monday, Eid and Maulid each year.</Typography>
          <ErrorAlert error={addHoliday.error ?? removeHoliday.error} />
        </CardContent></Card>
      </Grid>
      {edit && (
        <Dialog open onClose={() => setEdit(null)} fullWidth maxWidth="xs">
          <DialogTitle>{edit.id ? 'Edit leave type' : 'Add a leave type'}</DialogTitle>
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              <TextField label="Name" value={edit.name ?? ''} onChange={(e) => setEdit({ ...edit, name: e.target.value })} />
              <TextField type="number" label="Days a year (empty: as granted)" value={edit.daysPerYear ?? ''} onChange={(e) => setEdit({ ...edit, daysPerYear: e.target.value === '' ? undefined : Number(e.target.value) })} />
              <TextField select label="For" value={edit.onlyGender ?? ''} onChange={(e) => setEdit({ ...edit, onlyGender: (e.target.value || undefined) as LeaveType['onlyGender'] })}>
                <MenuItem value="">Everyone</MenuItem><MenuItem value="F">Women</MenuItem><MenuItem value="M">Men</MenuItem></TextField>
              <FormControlLabel control={<Checkbox checked={!!edit.calendarDays} onChange={(e) => setEdit({ ...edit, calendarDays: e.target.checked })} />} label="Count calendar days (weekends and holidays included)" />
              <FormControlLabel control={<Checkbox checked={edit.active !== false} onChange={(e) => setEdit({ ...edit, active: e.target.checked })} />} label="In use" />
              <ErrorAlert error={saveType.error} />
            </Stack>
          </DialogContent>
          <DialogActions><Button onClick={() => setEdit(null)}>Cancel</Button><Button variant="contained" disabled={saveType.isPending} onClick={() => saveType.mutate()}>Save</Button></DialogActions>
        </Dialog>
      )}
    </Grid>
  );
}
