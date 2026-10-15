import AddIcon from '@mui/icons-material/Add';
import DownloadIcon from '@mui/icons-material/Download';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem,
  Stack, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, download, formatDate, formatDateTime } from '../../api/client';
import type { Programme } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';

const staff = { realm: 'staff' as const };
export const EMPLOYMENT: Record<string, string> = {
  EMPLOYED: 'Employed', SELF_EMPLOYED: 'Self-employed', FURTHER_STUDIES: 'Further studies', SEEKING: 'Looking for work', NOT_SEEKING: 'Not looking for work',
};
interface Alumnus {
  studentId: number; registrationNo: string; name: string; programmeCode: string; programmeName: string; graduation?: string; phone?: string; email?: string;
  employmentStatus?: string; employer?: string; jobTitle?: string; sector?: string; region?: string; consentContact: boolean; updatedVia?: string; updatedAt?: string;
}
interface Survey {
  id: number; title: string; intro?: string; graduation?: string; programme?: string; status: 'DRAFT' | 'OPEN' | 'CLOSED'; closesOn?: string; cohort: number;
  invited: number; responded: number; questions: { code: string; text: string; type: string; options: string[] }[];
  distributions: Record<string, Record<string, number>>; employmentRate?: number; createdByName: string; openedAt?: string;
}

/** Alumni contacts and graduate tracer surveys (BR-31 / FR-GRD-05). */
export default function AlumniPage() {
  const [tab, setTab] = useState(0);
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Alumni" subtitle="Keep in touch with graduates and find out, through tracer surveys, where the programmes lead them." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2.5 }}><Tab label="Graduates" /><Tab label="Tracer surveys" /></Tabs>
      {tab === 0 ? <AlumniTab /> : <SurveysTab />}
    </Container>
  );
}

function AlumniTab() {
  const { user } = useSession();
  const canEdit = user('staff')?.roles.some((r) => ['INSTITUTION_ADMIN', 'REGISTRAR'].includes(r));
  const [programmeId, setProgrammeId] = useState('');
  const [status, setStatus] = useState('');
  const [q, setQ] = useState('');
  const [edit, setEdit] = useState<Alumnus | null>(null);
  const programmes = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const params = new URLSearchParams({ ...(programmeId ? { programmeId } : {}), ...(status ? { status } : {}), ...(q ? { q } : {}) });
  const list = useQuery({ queryKey: ['alumni', params.toString()], queryFn: () => api<Alumnus[]>(`/api/alumni?${params}`, staff) });
  const counts = (list.data ?? []).reduce<Record<string, number>>((m, a) => { const k = a.employmentStatus ?? 'UNKNOWN'; m[k] = (m[k] ?? 0) + 1; return m; }, {});
  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}>
        <TextField size="small" label="Search name, number or employer" value={q} onChange={(e) => setQ(e.target.value)} sx={{ flex: 1 }} />
        <TextField select size="small" label="Programme" value={programmeId} onChange={(e) => setProgrammeId(e.target.value)} sx={{ minWidth: 240 }}>
          <MenuItem value="">All</MenuItem>{(programmes.data ?? []).map((p) => <MenuItem key={p.id} value={p.id}>{p.code}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Doing now" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 200 }}>
          <MenuItem value="">All</MenuItem>{Object.entries(EMPLOYMENT).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}
        </TextField>
        <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/alumni/export?${params}`, 'staff', 'alumni.csv')}>CSV</Button>
      </Stack>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
        {Object.entries(counts).map(([k, n]) => <Chip key={k} label={`${EMPLOYMENT[k] ?? 'Not known'}: ${n}`} variant="outlined" />)}
      </Stack>
      <Card>
        {list.isLoading ? <Loading /> : (
          <TableContainer>
            <Table size="small">
              <TableHead><TableRow><TableCell>Graduate</TableCell><TableCell>Programme</TableCell><TableCell>Contact</TableCell><TableCell>Doing now</TableCell><TableCell>Updated</TableCell><TableCell /></TableRow></TableHead>
              <TableBody>
                {(list.data ?? []).map((a) => (
                  <TableRow key={a.studentId} hover>
                    <TableCell><b>{a.name}</b><Typography variant="caption" component="div" color="text.secondary">{a.registrationNo}{a.graduation ? ` · ${a.graduation}` : ''}</Typography></TableCell>
                    <TableCell>{a.programmeCode}</TableCell>
                    <TableCell>{a.phone}{a.email && <Typography variant="caption" component="div">{a.email}</Typography>}{!a.consentContact && <Chip size="small" label="Do not contact" sx={{ mt: 0.5 }} />}</TableCell>
                    <TableCell>{a.employmentStatus ? EMPLOYMENT[a.employmentStatus] : '—'}{a.employer && <Typography variant="caption" component="div">{a.jobTitle ? `${a.jobTitle}, ` : ''}{a.employer}</Typography>}</TableCell>
                    <TableCell>{a.updatedAt ? <>{formatDate(a.updatedAt)}<Typography variant="caption" component="div" color="text.secondary">{a.updatedVia?.toLowerCase()}</Typography></> : '—'}</TableCell>
                    <TableCell align="right">{canEdit && <Button size="small" onClick={() => setEdit(a)}>Edit</Button>}</TableCell>
                  </TableRow>
                ))}
                {list.data?.length === 0 && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 2 }}>No graduates match.</Typography></TableCell></TableRow>}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Card>
      {edit && <AlumnusDialog a={edit} onClose={() => setEdit(null)} />}
    </Stack>
  );
}

function AlumnusDialog({ a, onClose }: { a: Alumnus; onClose: () => void }) {
  const qc = useQueryClient();
  const [f, setF] = useState({ phone: a.phone ?? '', email: a.email ?? '', employmentStatus: a.employmentStatus ?? '', employer: a.employer ?? '', jobTitle: a.jobTitle ?? '',
    sector: a.sector ?? '', region: a.region ?? '', consentContact: a.consentContact });
  const save = useMutation({
    mutationFn: () => api(`/api/alumni/${a.studentId}`, { ...staff, method: 'PUT', body: { ...f, employmentStatus: f.employmentStatus || null } }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['alumni'] }); onClose(); },
  });
  const tf = (k: keyof typeof f, label: string) => <TextField size="small" fullWidth label={label} value={f[k] as string} onChange={(e) => setF({ ...f, [k]: e.target.value })} />;
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{a.name}</DialogTitle>
      <DialogContent>
        <Grid container spacing={2} sx={{ mt: 0.5 }}>
          <Grid size={{ xs: 12, sm: 6 }}>{tf('phone', 'Mobile number')}</Grid>
          <Grid size={{ xs: 12, sm: 6 }}>{tf('email', 'Email')}</Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <TextField select size="small" fullWidth label="Doing now" value={f.employmentStatus} onChange={(e) => setF({ ...f, employmentStatus: e.target.value })}>
              <MenuItem value="">Not known</MenuItem>{Object.entries(EMPLOYMENT).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>{tf('sector', 'Sector')}</Grid>
          <Grid size={{ xs: 12, sm: 6 }}>{tf('employer', 'Employer or business')}</Grid>
          <Grid size={{ xs: 12, sm: 6 }}>{tf('jobTitle', 'Job title')}</Grid>
          <Grid size={{ xs: 12, sm: 6 }}>{tf('region', 'Region')}</Grid>
          <Grid size={{ xs: 12 }}><FormControlLabel control={<Checkbox checked={f.consentContact} onChange={(e) => setF({ ...f, consentContact: e.target.checked })} />} label="The graduate agrees to be contacted (surveys, alumni news)" /></Grid>
        </Grid>
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending}>Save</Button></DialogActions>
    </Dialog>
  );
}

function SurveysTab() {
  const qc = useQueryClient();
  const { user } = useSession();
  const canEdit = user('staff')?.roles.some((r) => ['INSTITUTION_ADMIN', 'REGISTRAR'].includes(r));
  const list = useQuery({ queryKey: ['tracer-surveys'], queryFn: () => api<Survey[]>('/api/tracer-surveys', staff) });
  const programmes = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const graduations = useQuery({ queryKey: ['staff', 'graduations'], queryFn: () => api<{ id: number; name: string }[]>('/api/graduations', staff) });
  const [form, setForm] = useState<{ title: string; intro: string; programmeId: string; graduationId: string; closesOn: string } | null>(null);
  const [open, setOpen] = useState<Survey | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['tracer-surveys'] });
  const create = useMutation({
    mutationFn: () => api<Survey>('/api/tracer-surveys', { ...staff, method: 'POST', body: { title: form!.title, intro: form!.intro || null,
      programmeId: form!.programmeId ? Number(form!.programmeId) : null, graduationId: form!.graduationId ? Number(form!.graduationId) : null, closesOn: form!.closesOn || null } }),
    onSuccess: (s) => { refresh(); setForm(null); setOpen(s); },
  });
  const act = useMutation({ mutationFn: ({ id, what }: { id: number; what: 'open' | 'close' }) => api<Survey>(`/api/tracer-surveys/${id}/${what}`, { ...staff, method: 'POST' }),
    onSuccess: (s) => { refresh(); setOpen(s); } });
  return (
    <Stack spacing={2}>
      {canEdit && <Box><Button variant="contained" startIcon={<AddIcon />} onClick={() => setForm({ title: `Tracer study ${new Date().getFullYear()}`, intro: '', programmeId: '', graduationId: '', closesOn: '' })}>New survey</Button></Box>}
      <ErrorAlert error={list.error ?? act.error} />
      {(list.data ?? []).map((s) => (
        <Card key={s.id} sx={{ cursor: 'pointer' }} onClick={() => setOpen(s)}><CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="center">
            <Box>
              <Typography variant="h6">{s.title}</Typography>
              <Typography variant="body2" color="text.secondary">{[s.programme ?? 'All programmes', s.graduation].filter(Boolean).join(' · ')} · {s.cohort} graduates</Typography>
            </Box>
            <Stack alignItems="flex-end">
              <Chip size="small" color={s.status === 'OPEN' ? 'success' : 'default'} label={s.status === 'OPEN' ? 'Open' : s.status === 'DRAFT' ? 'Draft' : 'Closed'} />
              {s.status !== 'DRAFT' && <Typography variant="caption" sx={{ mt: 0.5 }}>{s.responded} of {s.invited} answered{s.employmentRate != null ? ` · ${s.employmentRate}% working` : ''}</Typography>}
            </Stack>
          </Stack>
        </CardContent></Card>
      ))}
      {list.data?.length === 0 && <Alert severity="info">No tracer surveys yet. A survey invites a cohort of graduates by SMS with a personal link; answers update the alumni records.</Alert>}
      <Dialog open={!!form} onClose={() => setForm(null)} fullWidth maxWidth="sm">
        <DialogTitle>New tracer survey</DialogTitle>
        {form && <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField label="Title" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} />
            <TextField label="Introduction (optional)" multiline minRows={2} value={form.intro} onChange={(e) => setForm({ ...form, intro: e.target.value })} />
            <TextField select label="Programme" value={form.programmeId} onChange={(e) => setForm({ ...form, programmeId: e.target.value })}>
              <MenuItem value="">All programmes</MenuItem>{(programmes.data ?? []).map((p) => <MenuItem key={p.id} value={p.id}>{p.code} · {p.name}</MenuItem>)}
            </TextField>
            <TextField select label="Graduation" value={form.graduationId} onChange={(e) => setForm({ ...form, graduationId: e.target.value })}>
              <MenuItem value="">All graduates</MenuItem>{(graduations.data ?? []).map((g) => <MenuItem key={g.id} value={g.id}>{g.name}</MenuItem>)}
            </TextField>
            <TextField type="date" label="Closes on (optional)" value={form.closesOn} onChange={(e) => setForm({ ...form, closesOn: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
            <Typography variant="caption" color="text.secondary">The survey uses the standard tracer questions: what the graduate does now, time to first job, employer, sector, relevance of the training, preparation for work, recommendation and suggestions.</Typography>
            <ErrorAlert error={create.error} />
          </Stack>
        </DialogContent>}
        <DialogActions><Button onClick={() => setForm(null)}>Cancel</Button><Button variant="contained" disabled={!form?.title.trim() || create.isPending} onClick={() => create.mutate()}>Create</Button></DialogActions>
      </Dialog>
      {open && (
        <Dialog open onClose={() => setOpen(null)} fullWidth maxWidth="md">
          <DialogTitle>{open.title}</DialogTitle>
          <DialogContent>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
              {open.cohort} graduates in the cohort · {open.invited} invited · {open.responded} answered{open.openedAt ? ` · opened ${formatDateTime(open.openedAt)}` : ''}
            </Typography>
            {open.status === 'DRAFT' && <Alert severity="info" sx={{ mb: 2 }}>Opening the survey sends each graduate who may be contacted an SMS with a personal link.</Alert>}
            {open.employmentRate != null && <Alert severity="success" sx={{ mb: 2 }}>{open.employmentRate}% of those who answered are employed or self-employed.</Alert>}
            {open.questions.filter((q) => q.type !== 'TEXT').map((q) => {
              const d = open.distributions[q.code] ?? {};
              const total = Object.values(d).reduce((x, y) => x + y, 0);
              return (
                <Box key={q.code} sx={{ mb: 2 }}>
                  <Typography sx={{ fontWeight: 600 }}>{q.text}</Typography>
                  {q.options.map((o) => (
                    <Stack key={o} direction="row" spacing={1} alignItems="center" sx={{ my: 0.25 }}>
                      <Typography variant="body2" sx={{ width: 200 }}>{EMPLOYMENT[o] ?? o}</Typography>
                      <Box sx={{ flex: 1, height: 12, bgcolor: 'action.hover', borderRadius: 1, overflow: 'hidden' }}>
                        <Box sx={{ width: total ? `${((d[o] ?? 0) * 100) / total}%` : 0, height: '100%', bgcolor: 'primary.main' }} />
                      </Box>
                      <Typography variant="body2" sx={{ width: 32, textAlign: 'right' }}>{d[o] ?? 0}</Typography>
                    </Stack>
                  ))}
                </Box>
              );
            })}
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setOpen(null)}>Close</Button>
            {canEdit && open.status === 'DRAFT' && <Button variant="contained" disabled={act.isPending} onClick={() => act.mutate({ id: open.id, what: 'open' })}>Open and invite</Button>}
            {canEdit && open.status === 'OPEN' && <Button color="warning" disabled={act.isPending} onClick={() => act.mutate({ id: open.id, what: 'close' })}>Close the survey</Button>}
          </DialogActions>
        </Dialog>
      )}
    </Stack>
  );
}
