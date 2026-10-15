import AddIcon from '@mui/icons-material/Add';
import KeyIcon from '@mui/icons-material/VpnKey';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel,
  MenuItem, Stack, Switch, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, formatDate, formatDateTime } from '../../api/client';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import type { PrivacyRequest } from '../../components/PrivacyCard';

const staff = { realm: 'staff' as const };

interface Settings {
  mfaPolicy: 'OPTIONAL' | 'PRIVILEGED' | 'ALL_STAFF'; staffIdleMinutes: number; studentIdleMinutes: number; applicantIdleMinutes: number;
  retentionApplicantMonths: number; retentionMessageMonths: number; retentionEnabled: boolean;
}
interface Retention {
  applicantsToAnonymise: number; messagesToClear: number; emailsToClear: number; applicantMonths: number; messageMonths: number; enabled: boolean;
  lastRuns: { ran_at: string; ran_by: string; applicants_anonymised: number; messages_cleared: number; emails_cleared: number }[];
}
interface ApiClient { id: number; clientId: string; name: string; scopes: string[]; active: boolean; createdBy: string; lastUsedAt?: string; createdAt: string }

const POLICY: Record<Settings['mfaPolicy'], string> = {
  OPTIONAL: 'Optional — each person decides',
  PRIVILEGED: 'Required for administrators, management, admissions, registry, examinations and finance',
  ALL_STAFF: 'Required for all staff',
};

/** Security and privacy: sign-in rules, connected systems, personal data requests and retention (BR-36, BR-38, BR-41, NFR-SEC-02). */
export default function SecurityPage() {
  const { user } = useSession();
  const admin = !!user('staff')?.roles.includes('INSTITUTION_ADMIN');
  const [tab, setTab] = useState(admin ? 0 : 2);
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Security & privacy" subtitle="Sign-in rules, systems connected to CIMS, and requests about personal data (Personal Data Protection Act, 2022)." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2.5 }} variant="scrollable" allowScrollButtonsMobile>
        {admin && <Tab value={0} label="Sign-in & sessions" />}
        {admin && <Tab value={1} label="Connected systems (API)" />}
        <Tab value={2} label="Personal data requests" />
        {admin && <Tab value={3} label="Retention" />}
      </Tabs>
      {tab === 0 && admin && <SignInTab />}
      {tab === 1 && admin && <ApiClientsTab />}
      {tab === 2 && <RequestsTab />}
      {tab === 3 && admin && <RetentionTab />}
    </Container>
  );
}

function useSettings() {
  return useQuery({ queryKey: ['security-settings'], queryFn: () => api<Settings>('/api/security-settings', staff) });
}

function SignInTab() {
  const qc = useQueryClient();
  const q = useSettings();
  const [f, setF] = useState<Settings | null>(null);
  useEffect(() => { if (q.data) setF(q.data); }, [q.data]);
  const save = useMutation({
    mutationFn: () => api<Settings>('/api/security-settings', { ...staff, method: 'PUT', body: f }),
    onSuccess: (s) => { qc.setQueryData(['security-settings'], s); qc.invalidateQueries({ queryKey: ['institution'] }); },
  });
  if (!f) return <Loading />;
  const num = (k: keyof Settings, label: string) => (
    <TextField size="small" type="number" label={label} value={f[k] as number} onChange={(e) => setF({ ...f, [k]: Number(e.target.value) })}
      inputProps={{ min: 5, max: 480 }} InputProps={{ endAdornment: <Typography variant="caption" color="text.secondary">min</Typography> }} fullWidth />
  );
  return (
    <Stack spacing={2.5}>
      <Card><CardContent>
        <Typography variant="h6">Two-step sign-in (authenticator app)</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Staff covered by the rule set it up at their next sign-in and cannot use CIMS until they do. Set it up on your own account first (My account).
        </Typography>
        <TextField select fullWidth size="small" label="Who must use it" value={f.mfaPolicy} onChange={(e) => setF({ ...f, mfaPolicy: e.target.value as Settings['mfaPolicy'] })}>
          {(Object.keys(POLICY) as Settings['mfaPolicy'][]).map((p) => <MenuItem key={p} value={p}>{POLICY[p]}</MenuItem>)}
        </TextField>
      </CardContent></Card>
      <Card><CardContent>
        <Typography variant="h6">Sign out after no activity</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>A warning shows a minute before. Shared computers in offices and labs need a short time.</Typography>
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, sm: 4 }}>{num('staffIdleMinutes', 'Staff')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{num('studentIdleMinutes', 'Students')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{num('applicantIdleMinutes', 'Applicants')}</Grid>
        </Grid>
      </CardContent></Card>
      <ErrorAlert error={save.error} />
      {save.isSuccess && <Alert severity="success">Saved.</Alert>}
      <Box><Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending}>Save</Button></Box>
    </Stack>
  );
}

function ApiClientsTab() {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['api-clients'], queryFn: () => api<ApiClient[]>('/api/api-clients', staff) });
  const scopes = useQuery({ queryKey: ['api-scopes'], queryFn: () => api<Record<string, string>>('/api/api-clients/scopes', staff) });
  const [edit, setEdit] = useState<ApiClient | 'new' | null>(null);
  const [secret, setSecret] = useState<{ clientId: string; clientSecret: string } | null>(null);
  const [name, setName] = useState('');
  const [chosen, setChosen] = useState<string[]>([]);
  const [active, setActive] = useState(true);
  const open = (c: ApiClient | 'new') => {
    setEdit(c); setName(c === 'new' ? '' : c.name); setChosen(c === 'new' ? [] : c.scopes); setActive(c === 'new' ? true : c.active);
  };
  const done = () => { qc.invalidateQueries({ queryKey: ['api-clients'] }); setEdit(null); };
  const save = useMutation({
    mutationFn: (): Promise<{ client: ApiClient; clientSecret: string } | ApiClient> => edit === 'new'
      ? api<{ client: ApiClient; clientSecret: string }>('/api/api-clients', { ...staff, method: 'POST', body: { name, scopes: chosen } })
      : api<ApiClient>(`/api/api-clients/${(edit as ApiClient).id}?active=${active}`, { ...staff, method: 'PUT', body: { name, scopes: chosen } }),
    onSuccess: (r) => { if ('clientSecret' in r) setSecret({ clientId: r.client.clientId, clientSecret: r.clientSecret }); done(); },
  });
  const rotate = useMutation({
    mutationFn: (id: number) => api<{ client: ApiClient; clientSecret: string }>(`/api/api-clients/${id}/secret`, { ...staff, method: 'POST' }),
    onSuccess: (r) => { setSecret({ clientId: r.client.clientId, clientSecret: r.clientSecret }); done(); },
  });
  return (
    <Card><CardContent>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1} sx={{ mb: 1.5 }}>
        <Box>
          <Typography variant="h6">Connected systems</Typography>
          <Typography variant="body2" color="text.secondary">
            Other systems (an LMS, the accounting package, a library system) read CIMS through its API with their own credentials, limited to what you allow.
            They ask for a token at <Box component="code">POST /api/oauth/token</Box> (client credentials) and read <Box component="code">/api/integration/v1/…</Box>.
          </Typography>
        </Box>
        <Box><Button variant="contained" startIcon={<AddIcon />} onClick={() => open('new')}>Add a system</Button></Box>
      </Stack>
      <ErrorAlert error={list.error ?? rotate.error} />
      <TableContainer>
        <Table size="small">
          <TableHead><TableRow><TableCell>System</TableCell><TableCell>Client ID</TableCell><TableCell>May read</TableCell><TableCell>Last used</TableCell><TableCell /></TableRow></TableHead>
          <TableBody>
            {(list.data ?? []).map((c) => (
              <TableRow key={c.id} hover>
                <TableCell>{c.name} {!c.active && <Chip size="small" label="Off" sx={{ ml: 1 }} />}</TableCell>
                <TableCell sx={{ fontFamily: 'monospace' }}>{c.clientId}</TableCell>
                <TableCell>{c.scopes.map((s) => <Chip key={s} size="small" label={s} sx={{ mr: 0.5, mb: 0.5 }} />)}</TableCell>
                <TableCell>{c.lastUsedAt ? formatDateTime(c.lastUsedAt) : 'Never'}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  <Button size="small" onClick={() => open(c)}>Edit</Button>
                  <Button size="small" startIcon={<KeyIcon />} onClick={() => rotate.mutate(c.id)}>New secret</Button>
                </TableCell>
              </TableRow>
            ))}
            {list.data?.length === 0 && <TableRow><TableCell colSpan={5}><Typography color="text.secondary" sx={{ py: 2 }}>No systems connected yet.</Typography></TableCell></TableRow>}
          </TableBody>
        </Table>
      </TableContainer>
      <Dialog open={!!edit} onClose={() => setEdit(null)} fullWidth maxWidth="sm">
        <DialogTitle>{edit === 'new' ? 'Add a connected system' : 'Edit connected system'}</DialogTitle>
        <DialogContent>
          <Stack spacing={1.5} sx={{ mt: 1 }}>
            <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Moodle LMS" />
            <Typography variant="subtitle2">May read</Typography>
            {Object.entries(scopes.data ?? {}).map(([k, label]) => (
              <FormControlLabel key={k} control={<Checkbox checked={chosen.includes(k)} onChange={(e) => setChosen(e.target.checked ? [...chosen, k] : chosen.filter((x) => x !== k))} />}
                label={<span><b>{k}</b> — {label}</span>} />
            ))}
            {edit !== 'new' && <FormControlLabel control={<Switch checked={active} onChange={(e) => setActive(e.target.checked)} />} label="Allowed to connect" />}
            <ErrorAlert error={save.error} />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEdit(null)}>Cancel</Button>
          <Button variant="contained" disabled={!name.trim() || !chosen.length || save.isPending} onClick={() => save.mutate()}>Save</Button>
        </DialogActions>
      </Dialog>
      <Dialog open={!!secret} onClose={() => setSecret(null)} fullWidth maxWidth="sm">
        <DialogTitle>Credentials for the system</DialogTitle>
        <DialogContent>
          <Alert severity="warning" sx={{ mb: 2 }}>Copy the secret now and give it to the system's administrator securely. It is not shown again.</Alert>
          <Stack spacing={1}>
            <TextField label="Client ID" value={secret?.clientId ?? ''} InputProps={{ readOnly: true, sx: { fontFamily: 'monospace' } }} />
            <TextField label="Client secret" value={secret?.clientSecret ?? ''} InputProps={{ readOnly: true, sx: { fontFamily: 'monospace' } }} />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => navigator.clipboard?.writeText(`client_id=${secret?.clientId}\nclient_secret=${secret?.clientSecret}`)}>Copy both</Button>
          <Button variant="contained" onClick={() => setSecret(null)}>Done</Button>
        </DialogActions>
      </Dialog>
    </CardContent></Card>
  );
}

const KIND_LABEL: Record<PrivacyRequest['kind'], string> = { ACCESS: 'Copy of data', CORRECTION: 'Correction', ERASURE: 'Erasure', OBJECTION: 'Objection' };

function RequestsTab() {
  const qc = useQueryClient();
  const [status, setStatus] = useState('OPEN');
  const list = useQuery({ queryKey: ['privacy', status], queryFn: () => api<PrivacyRequest[]>(`/api/privacy/requests${status ? `?status=${status}` : ''}`, staff) });
  const [open, setOpen] = useState<PrivacyRequest | null>(null);
  const [outcome, setOutcome] = useState<'COMPLETED' | 'REJECTED'>('COMPLETED');
  const [response, setResponse] = useState('');
  const [erase, setErase] = useState(false);
  const decide = useMutation({
    mutationFn: () => api(`/api/privacy/requests/${open!.id}/decision`, { ...staff, method: 'POST', body: { outcome, response, erase } }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['privacy'] }); setOpen(null); },
  });
  return (
    <Card><CardContent>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1} sx={{ mb: 1.5 }}>
        <Box>
          <Typography variant="h6">Requests from applicants, students and staff</Typography>
          <Typography variant="body2" color="text.secondary">Answer within 30 days. A person can download a copy of their data themselves at any time from their account.</Typography>
        </Box>
        <TextField select size="small" label="Show" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 160 }}>
          <MenuItem value="OPEN">Waiting</MenuItem><MenuItem value="COMPLETED">Done</MenuItem><MenuItem value="REJECTED">Not done</MenuItem><MenuItem value="">All</MenuItem>
        </TextField>
      </Stack>
      <ErrorAlert error={list.error} />
      <TableContainer>
        <Table size="small">
          <TableHead><TableRow><TableCell>Request</TableCell><TableCell>Person</TableCell><TableCell>Kind</TableCell><TableCell>Due</TableCell><TableCell>Status</TableCell></TableRow></TableHead>
          <TableBody>
            {(list.data ?? []).map((r) => (
              <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => { setOpen(r); setOutcome('COMPLETED'); setResponse(''); setErase(false); }}>
                <TableCell>{r.requestNo}</TableCell>
                <TableCell>{r.subjectName} <Typography component="span" variant="caption" color="text.secondary">({r.userType.toLowerCase()})</Typography></TableCell>
                <TableCell>{KIND_LABEL[r.kind]}</TableCell>
                <TableCell>{formatDate(r.dueOn)} {r.overdue && <Chip size="small" color="error" label="Overdue" />}</TableCell>
                <TableCell><Chip size="small" label={r.status === 'OPEN' ? 'Waiting' : r.status === 'COMPLETED' ? 'Done' : 'Not done'}
                  color={r.status === 'OPEN' ? 'warning' : r.status === 'COMPLETED' ? 'success' : 'default'} /></TableCell>
              </TableRow>
            ))}
            {list.data?.length === 0 && <TableRow><TableCell colSpan={5}><Typography color="text.secondary" sx={{ py: 2 }}>Nothing here.</Typography></TableCell></TableRow>}
          </TableBody>
        </Table>
      </TableContainer>
      <Dialog open={!!open} onClose={() => setOpen(null)} fullWidth maxWidth="sm">
        <DialogTitle>{open?.requestNo} · {open && KIND_LABEL[open.kind]}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary">{open?.subjectName} · sent {formatDateTime(open?.createdAt)}</Typography>
          <Typography sx={{ my: 1.5, whiteSpace: 'pre-wrap' }}>{open?.details}</Typography>
          {open?.status !== 'OPEN' ? (
            <Alert severity="info">{open?.response} — {open?.handledByName}, {formatDateTime(open?.handledAt)}</Alert>
          ) : (
            <Stack spacing={1.5}>
              {open.kind === 'CORRECTION' && <Alert severity="info">Correct the details in the person's record first, then answer here.</Alert>}
              {open.kind === 'ERASURE' && (open.erasable
                ? <FormControlLabel control={<Checkbox checked={erase} onChange={(e) => { setErase(e.target.checked); if (e.target.checked) setOutcome('COMPLETED'); }} />}
                    label="Erase this applicant's personal details now (cannot be undone; amounts paid stay in the accounts without the name)" />
                : <Alert severity="warning">This person is or was a student. Their academic and financial records must be kept; explain what is kept and why.</Alert>)}
              <TextField select size="small" label="Outcome" value={outcome} onChange={(e) => setOutcome(e.target.value as typeof outcome)} disabled={erase}>
                <MenuItem value="COMPLETED">Done</MenuItem><MenuItem value="REJECTED">Not done (explain why)</MenuItem>
              </TextField>
              <TextField label="Answer to the person" value={response} onChange={(e) => setResponse(e.target.value)} multiline minRows={3} />
              <ErrorAlert error={decide.error} />
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(null)}>Close</Button>
          {open?.status === 'OPEN' && <Button variant="contained" color={erase ? 'error' : 'primary'} disabled={!response.trim() || decide.isPending} onClick={() => decide.mutate()}>{erase ? 'Erase and answer' : 'Send answer'}</Button>}
        </DialogActions>
      </Dialog>
    </CardContent></Card>
  );
}

function RetentionTab() {
  const qc = useQueryClient();
  const settings = useSettings();
  const preview = useQuery({ queryKey: ['retention'], queryFn: () => api<Retention>('/api/privacy/retention', staff) });
  const [f, setF] = useState<Settings | null>(null);
  const [confirm, setConfirm] = useState(false);
  useEffect(() => { if (settings.data) setF(settings.data); }, [settings.data]);
  const save = useMutation({
    mutationFn: () => api<Settings>('/api/security-settings', { ...staff, method: 'PUT', body: f }),
    onSuccess: (s) => { qc.setQueryData(['security-settings'], s); qc.invalidateQueries({ queryKey: ['retention'] }); },
  });
  const run = useMutation({
    mutationFn: () => api('/api/privacy/retention/run', { ...staff, method: 'POST' }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['retention'] }); setConfirm(false); },
  });
  if (!f || !preview.data) return <Loading />;
  const p = preview.data;
  return (
    <Stack spacing={2.5}>
      <Card><CardContent>
        <Typography variant="h6">How long personal data is kept</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Applicants who never became students are anonymised after the period below; old SMS and email texts are cleared. Student academic and financial records are never removed.
        </Typography>
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, sm: 4 }}>
            <TextField size="small" fullWidth type="number" label="Applicants who did not join (months)" value={f.retentionApplicantMonths}
              onChange={(e) => setF({ ...f, retentionApplicantMonths: Number(e.target.value) })} inputProps={{ min: 6, max: 120 }} />
          </Grid>
          <Grid size={{ xs: 12, sm: 4 }}>
            <TextField size="small" fullWidth type="number" label="SMS and email texts (months)" value={f.retentionMessageMonths}
              onChange={(e) => setF({ ...f, retentionMessageMonths: Number(e.target.value) })} inputProps={{ min: 3, max: 120 }} />
          </Grid>
          <Grid size={{ xs: 12, sm: 4 }}>
            <FormControlLabel control={<Switch checked={f.retentionEnabled} onChange={(e) => setF({ ...f, retentionEnabled: e.target.checked })} />} label="Apply automatically each month" />
          </Grid>
        </Grid>
        <ErrorAlert error={save.error} />
        <Box sx={{ mt: 2 }}><Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending}>Save</Button></Box>
      </CardContent></Card>
      <Card><CardContent>
        <Typography variant="h6">Due now</Typography>
        <Grid container spacing={2} sx={{ my: 1 }}>
          {[['Applicants to anonymise', p.applicantsToAnonymise], ['SMS texts to clear', p.messagesToClear], ['Email texts to clear', p.emailsToClear]].map(([l, v]) => (
            <Grid key={l as string} size={{ xs: 12, sm: 4 }}>
              <Typography variant="body2" color="text.secondary">{l}</Typography>
              <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 30 }}>{v}</Typography>
            </Grid>
          ))}
        </Grid>
        {!confirm
          ? <Button color="error" variant="outlined" disabled={!p.applicantsToAnonymise && !p.messagesToClear && !p.emailsToClear} onClick={() => setConfirm(true)}>Apply now</Button>
          : <Alert severity="warning" action={<><Button color="inherit" onClick={() => setConfirm(false)}>Cancel</Button><Button color="error" disabled={run.isPending} onClick={() => run.mutate()}>Apply</Button></>}>
              This cannot be undone.
            </Alert>}
        <ErrorAlert error={run.error} />
        {p.lastRuns.length > 0 && (
          <Table size="small" sx={{ mt: 2 }}>
            <TableHead><TableRow><TableCell>When</TableCell><TableCell>By</TableCell><TableCell align="right">Applicants</TableCell><TableCell align="right">SMS</TableCell><TableCell align="right">Emails</TableCell></TableRow></TableHead>
            <TableBody>
              {p.lastRuns.map((r) => (
                <TableRow key={r.ran_at}><TableCell>{formatDateTime(r.ran_at)}</TableCell><TableCell>{r.ran_by}</TableCell>
                  <TableCell align="right">{r.applicants_anonymised}</TableCell><TableCell align="right">{r.messages_cleared}</TableCell><TableCell align="right">{r.emails_cleared}</TableCell></TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent></Card>
    </Stack>
  );
}
