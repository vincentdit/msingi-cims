import AddIcon from '@mui/icons-material/Add';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import EditIcon from '@mui/icons-material/Edit';
import RadioButtonUncheckedIcon from '@mui/icons-material/RadioButtonUnchecked';
import SearchIcon from '@mui/icons-material/Search';
import SmsIcon from '@mui/icons-material/Sms';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Divider, IconButton, InputAdornment,
  Link, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { api, formatDate, formatDateTime, formatTzs } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import {
  HealthChip, IssuedPasswordDialog, MODE_LABEL, StatusChip, actionLabel, detailsText, n, platform,
  type InstitutionDetail, type InstitutionRow, type IssuedAdmin,
} from './shared';

// ============================================================ list

export function InstitutionsPage() {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [status, setStatus] = useState('');
  const list = useQuery({ queryKey: ['platform', 'institutions'], queryFn: () => api<InstitutionRow[]>('/api/platform/institutions', platform) });
  const rows = useMemo(() => (list.data ?? []).filter((r) => (!status || r.status === status)
    && (!q || `${r.code} ${r.name} ${r.shortName ?? ''} ${r.primaryHost ?? ''} ${r.nactvetRegNo ?? ''}`.toLowerCase().includes(q.toLowerCase()))), [list.data, q, status]);
  return (
    <>
      <PageTitle title="Institutions" subtitle="Every college on the platform: status, usage, setup progress and integration problems."
        action={<Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/platform/institutions/new')}>Onboard institution</Button>} />
      <Card>
        <CardContent sx={{ pb: 1 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <TextField size="small" placeholder="Code, name, address or NACTVET number" value={q} onChange={(e) => setQ(e.target.value)} sx={{ flex: 1 }}
              slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment> } }} />
            <TextField select size="small" label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 160 }}
              slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
              <MenuItem value="">All</MenuItem>
              <MenuItem value="ACTIVE">Active</MenuItem>
              <MenuItem value="SUSPENDED">Suspended</MenuItem>
            </TextField>
          </Stack>
        </CardContent>
        <ErrorAlert error={list.error} />
        {list.isLoading ? <Loading /> : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Institution</TableCell><TableCell>Status</TableCell><TableCell align="right">Students</TableCell>
                  <TableCell align="right">Staff</TableCell><TableCell align="right">Applications {new Date().getFullYear()}</TableCell>
                  <TableCell align="right">SMS left</TableCell><TableCell>Setup</TableCell><TableCell>Last staff sign-in</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((r) => (
                  <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/platform/institutions/${r.id}`)}>
                    <TableCell>
                      <Typography fontWeight={600}>{r.name}</Typography>
                      <Typography variant="body2" color="text.secondary"><Box component="span" sx={{ fontFamily: 'monospace' }}>{r.code}</Box>{r.primaryHost ? ` · ${r.primaryHost}` : ''}</Typography>
                    </TableCell>
                    <TableCell>
                      <StatusChip status={r.status} />
                      {r.integrationFailures24h > 0 && <Chip size="small" color="warning" variant="outlined" sx={{ ml: 0.5 }} label={`${r.integrationFailures24h} failed calls`} />}
                    </TableCell>
                    <TableCell align="right">{n(r.usage.studentsActive)}</TableCell>
                    <TableCell align="right">{n(r.usage.staffActive)}</TableCell>
                    <TableCell align="right">{n(r.usage.applicationsThisYear)}</TableCell>
                    <TableCell align="right" sx={{ color: r.usage.smsBalance < 500 ? 'warning.main' : undefined }}>{n(r.usage.smsBalance)}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{r.setupDone} of {r.setupTotal}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{r.usage.lastStaffSignIn ? formatDateTime(r.usage.lastStaffSignIn) : 'Never'}</TableCell>
                  </TableRow>
                ))}
                {rows.length === 0 && (
                  <TableRow><TableCell colSpan={8}><Typography color="text.secondary" sx={{ py: 2 }}>
                    {list.data?.length ? 'No institution matches.' : 'No institution yet. Onboard the first one.'}
                  </Typography></TableCell></TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Card>
    </>
  );
}

// ============================================================ onboarding

const EMPTY = {
  code: '', name: '', shortName: '', nactvetRegNo: '', ownership: 'PRIVATE', contactEmail: '', contactPhone: '', primaryColor: '#1F4E8C',
  domains: '', notes: '', adminUsername: '', adminName: '', adminEmail: '', adminPhone: '',
};

/** Onboards an institution: its details, its address(es) and its first administrator, all at once. */
export function OnboardPage() {
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [f, setF] = useState(EMPTY);
  const [issued, setIssued] = useState<IssuedAdmin | null>(null);
  const set = (k: keyof typeof EMPTY) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });
  const domains = f.domains.split(/[\s,]+/).map((d) => d.trim()).filter(Boolean);
  const m = useMutation({
    mutationFn: () => api<IssuedAdmin>('/api/platform/institutions', {
      ...platform, method: 'POST', body: {
        code: f.code.trim(), name: f.name, shortName: f.shortName || null, nactvetRegNo: f.nactvetRegNo || null, ownership: f.ownership,
        contactEmail: f.contactEmail || null, contactPhone: f.contactPhone || null, primaryColor: f.primaryColor || null, domains, notes: f.notes || null,
        admin: { username: f.adminUsername.trim(), fullName: f.adminName, email: f.adminEmail || null, phone: f.adminPhone || null },
      },
    }),
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ['platform'] }); setIssued(r); },
  });
  const ready = /^[A-Za-z][A-Za-z0-9]{1,19}$/.test(f.code.trim()) && f.name.trim() && domains.length > 0 && f.adminUsername.trim().length >= 3 && f.adminName.trim();

  return (
    <>
      <Button component={RouterLink} to="/platform/institutions" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>Institutions</Button>
      <PageTitle title="Onboard an institution" subtitle="Creates the institution, the web address(es) its portals answer on, and its first administrator. The administrator then sets up the rest." />
      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, md: 7 }}>
          <Card sx={{ mb: 2.5 }}>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 2 }}>Institution</Typography>
              <Grid container spacing={2}>
                <Grid size={{ xs: 12, sm: 4 }}>
                  <TextField label="Code" required fullWidth value={f.code} onChange={set('code')} inputProps={{ maxLength: 20, style: { textTransform: 'uppercase', fontFamily: 'monospace' } }}
                    helperText="Fixed for good, e.g. MDTC" />
                </Grid>
                <Grid size={{ xs: 12, sm: 8 }}>
                  <TextField label="Full name" required fullWidth value={f.name} onChange={set('name')} inputProps={{ maxLength: 200 }} helperText="As registered with NACTVET" />
                </Grid>
                <Grid size={{ xs: 12, sm: 4 }}><TextField label="Short name" fullWidth value={f.shortName} onChange={set('shortName')} inputProps={{ maxLength: 60 }} /></Grid>
                <Grid size={{ xs: 12, sm: 4 }}><TextField label="NACTVET registration no." fullWidth value={f.nactvetRegNo} onChange={set('nactvetRegNo')} inputProps={{ maxLength: 40 }} /></Grid>
                <Grid size={{ xs: 12, sm: 4 }}>
                  <TextField select label="Ownership" fullWidth value={f.ownership} onChange={set('ownership')}>
                    <MenuItem value="PRIVATE">Private</MenuItem>
                    <MenuItem value="PUBLIC">Public</MenuItem>
                  </TextField>
                </Grid>
                <Grid size={{ xs: 12, sm: 5 }}><TextField label="Contact email" type="email" fullWidth value={f.contactEmail} onChange={set('contactEmail')} /></Grid>
                <Grid size={{ xs: 12, sm: 4 }}><TextField label="Contact phone" fullWidth value={f.contactPhone} onChange={set('contactPhone')} /></Grid>
                <Grid size={{ xs: 12, sm: 3 }}>
                  <TextField label="Colour" type="color" fullWidth value={f.primaryColor} onChange={set('primaryColor')} helperText={f.primaryColor} />
                </Grid>
                <Grid size={12}>
                  <TextField label="Web address(es)" required fullWidth value={f.domains} onChange={set('domains')}
                    placeholder="cims.college.ac.tz" helperText="Host names its portals answer on, separated by spaces or commas. Point DNS at CIMS first." />
                </Grid>
                <Grid size={12}>
                  <TextField label="Msingi notes (not shown to the institution)" fullWidth multiline minRows={2} value={f.notes} onChange={set('notes')} inputProps={{ maxLength: 1000 }}
                    placeholder="Contract, contact person, support arrangements…" />
                </Grid>
              </Grid>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 5 }}>
          <Card sx={{ mb: 2.5 }}>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 0.5 }}>First administrator</Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Usually the college's ICT officer. They get a temporary password and add the other staff.</Typography>
              <Stack spacing={2}>
                <TextField label="Username" required value={f.adminUsername} onChange={set('adminUsername')} inputProps={{ maxLength: 60 }} />
                <TextField label="Full name" required value={f.adminName} onChange={set('adminName')} inputProps={{ maxLength: 150 }} />
                <TextField label="Email" type="email" value={f.adminEmail} onChange={set('adminEmail')} />
                <TextField label="Phone" value={f.adminPhone} onChange={set('adminPhone')} />
              </Stack>
            </CardContent>
          </Card>
          <ErrorAlert error={m.error} />
          <Button variant="contained" size="large" fullWidth disabled={!ready || m.isPending} onClick={() => m.mutate()}>Onboard institution</Button>
        </Grid>
      </Grid>
      {issued && (
        <IssuedPasswordDialog open title={`${issued.institution.name} is on CIMS`} username={issued.username} password={issued.temporaryPassword}
          note={issued.signInUrl && <Typography variant="body2">They sign in at <strong>{issued.signInUrl}</strong>.</Typography>}
          onClose={() => navigate(`/platform/institutions/${issued.institution.id}`, { replace: true })} />
      )}
    </>
  );
}

// ============================================================ detail

export function InstitutionDetailPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const key = ['platform', 'institution', id];
  const q = useQuery({ queryKey: key, queryFn: () => api<InstitutionDetail>(`/api/platform/institutions/${id}`, platform) });
  const [dialog, setDialog] = useState<null | 'edit' | 'suspend' | 'reactivate' | 'domain' | 'admin' | 'sms'>(null);
  const [issued, setIssued] = useState<IssuedAdmin | null>(null);
  const [confirmReset, setConfirmReset] = useState<{ id: number; name: string } | null>(null);
  const done = (d: InstitutionDetail) => { qc.setQueryData(key, d); qc.invalidateQueries({ queryKey: ['platform', 'institutions'] }); qc.invalidateQueries({ queryKey: ['platform', 'overview'] }); setDialog(null); };
  const removeDomain = useMutation({ mutationFn: (domainId: number) => api<InstitutionDetail>(`/api/platform/institutions/${id}/domains/${domainId}`, { ...platform, method: 'DELETE' }), onSuccess: done });
  const reset = useMutation({
    mutationFn: (userId: number) => api<IssuedAdmin>(`/api/platform/institutions/${id}/administrators/${userId}/reset-password`, { ...platform, method: 'POST' }),
    onSuccess: (r) => { done(r.institution); setConfirmReset(null); setIssued(r); },
  });

  if (q.isLoading) return <Loading />;
  const d = q.data;
  if (!d) return <ErrorAlert error={q.error} />;
  const setupDone = d.setup.filter((s) => s.done).length;

  return (
    <>
      <Button component={RouterLink} to="/platform/institutions" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>Institutions</Button>
      <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" alignItems={{ md: 'flex-start' }} spacing={1.5} sx={{ mb: 3 }}>
        <Stack direction="row" spacing={2} alignItems="center">
          <Box sx={{ width: 52, height: 52, borderRadius: '50%', bgcolor: d.primaryColor, color: '#fff', display: 'flex', alignItems: 'center',
            justifyContent: 'center', fontWeight: 700, fontSize: 14, flexShrink: 0 }}>{(d.shortName ?? d.code).slice(0, 5)}</Box>
          <Box>
            <Typography variant="h5" component="h1">{d.name}</Typography>
            <Typography color="text.secondary"><Box component="span" sx={{ fontFamily: 'monospace' }}>{d.code}</Box>
              {d.nactvetRegNo ? ` · NACTVET ${d.nactvetRegNo}` : ''} · {d.ownership === 'PUBLIC' ? 'Public' : 'Private'} · on CIMS since {formatDate(d.createdAt)}</Typography>
          </Box>
        </Stack>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          <StatusChip status={d.status} />
          <Button variant="outlined" startIcon={<EditIcon />} onClick={() => setDialog('edit')}>Edit details</Button>
          {d.status === 'SUSPENDED'
            ? <Button variant="contained" color="success" onClick={() => setDialog('reactivate')}>Reactivate</Button>
            : <Button variant="outlined" color="error" onClick={() => setDialog('suspend')}>Suspend</Button>}
        </Stack>
      </Stack>

      {d.status === 'SUSPENDED' && (
        <Alert severity="error" sx={{ mb: 2.5 }}>
          <strong>Suspended</strong> {d.statusChangedAt && <>on {formatDateTime(d.statusChangedAt)} by {d.statusChangedBy}</>}: {d.statusReason}.
          Its portals answer "temporarily unavailable"; its data is kept and GePG payments are still recorded.
        </Alert>
      )}

      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}><Stat label="Students studying" value={n(d.usage.studentsActive)} sub={`${n(d.usage.studentsTotal)} records in all`} /></Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}><Stat label={`Applications ${new Date().getFullYear()}`} value={n(d.usage.applicationsThisYear)} sub={`${n(d.usage.programmes)} programmes`} /></Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}><Stat label="Staff accounts" value={n(d.usage.staffActive)} sub={d.usage.lastStaffSignIn ? `Last sign-in ${formatDateTime(d.usage.lastStaffSignIn)}` : 'No staff sign-in yet'} /></Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <Stat label="SMS left" value={n(d.usage.smsBalance)} sub={`${n(d.usage.smsPartsThisMonth)} sent this month`} warn={d.usage.smsBalance < 500}
            action={<Button size="small" startIcon={<SmsIcon />} onClick={() => setDialog('sms')}>Credit</Button>} />
        </Grid>

        <Grid size={{ xs: 12, md: 7 }}>
          <Card sx={{ mb: 2.5 }}>
            <CardContent>
              <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
                <Typography variant="h6">Administrators</Typography>
                <Button size="small" startIcon={<AddIcon />} onClick={() => setDialog('admin')}>Add administrator</Button>
              </Stack>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
                The institution's administrators manage its staff. Msingi resets an administrator's password only when they are locked out; other staff are reset by the administrator.
                {d.staffSignInUrl && <> Staff sign in at <Link href={d.staffSignInUrl} target="_blank" rel="noreferrer">{d.staffSignInUrl}</Link>.</>}
              </Typography>
              {d.administrators.map((a) => (
                <Stack key={a.id} direction="row" justifyContent="space-between" alignItems="center" spacing={1} sx={{ py: 1, borderTop: 1, borderColor: 'divider' }}>
                  <Box sx={{ minWidth: 0 }}>
                    <Typography fontWeight={600}>{a.fullName} <Box component="span" sx={{ fontFamily: 'monospace', fontWeight: 400, color: 'text.secondary' }}>· {a.username}</Box></Typography>
                    <Typography variant="body2" color="text.secondary">
                      {[a.email, a.phone].filter(Boolean).join(' · ') || 'No contact on record'} · {a.lastLoginAt ? `last signed in ${formatDateTime(a.lastLoginAt)}` : 'never signed in'}
                    </Typography>
                  </Box>
                  <Stack direction="row" spacing={1} alignItems="center">
                    {a.status !== 'ACTIVE' && <Chip size="small" color={a.status === 'LOCKED' ? 'warning' : 'default'} label={a.status === 'LOCKED' ? 'Locked' : 'Disabled'} />}
                    {a.mustChangePassword && <Chip size="small" variant="outlined" label="Temporary password" />}
                    <Button size="small" onClick={() => { reset.reset(); setConfirmReset({ id: a.id, name: a.fullName }); }}>Reset password</Button>
                  </Stack>
                </Stack>
              ))}
            </CardContent>
          </Card>

          <Card sx={{ mb: 2.5 }}>
            <CardContent>
              <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
                <Typography variant="h6">Web addresses</Typography>
                <Button size="small" startIcon={<AddIcon />} onClick={() => setDialog('domain')}>Add address</Button>
              </Stack>
              <ErrorAlert error={removeDomain.error} />
              {d.domains.map((x, i) => (
                <Stack key={x.id} direction="row" justifyContent="space-between" alignItems="center" sx={{ py: 0.75, borderTop: 1, borderColor: 'divider' }}>
                  <Box>
                    <Typography sx={{ fontFamily: 'monospace' }}>{x.host}</Typography>
                    <Typography variant="caption" color="text.secondary">{x.purpose === 'ALL' ? 'All portals' : `${x.purpose.charAt(0)}${x.purpose.slice(1).toLowerCase()} portal`}
                      {i === 0 ? ' · used in links printed on documents' : ''} · added {formatDate(x.createdAt)}</Typography>
                  </Box>
                  <Tooltip title={d.domains.length <= 1 ? 'An institution keeps at least one address' : 'Remove'}>
                    <span><IconButton size="small" disabled={d.domains.length <= 1 || removeDomain.isPending} onClick={() => removeDomain.mutate(x.id)} aria-label={`Remove ${x.host}`}>
                      <DeleteIcon fontSize="small" /></IconButton></span>
                  </Tooltip>
                </Stack>
              ))}
            </CardContent>
          </Card>

          <Card sx={{ mb: 2.5 }}>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>Integrations (last 7 days)</Typography>
              <Table size="small">
                <TableHead><TableRow><TableCell>System</TableCell><TableCell>Mode</TableCell><TableCell align="right">Calls</TableCell><TableCell align="right">Failed</TableCell><TableCell>State</TableCell></TableRow></TableHead>
                <TableBody>
                  {d.health.map((h) => (
                    <TableRow key={h.system}>
                      <TableCell>{h.label}{h.lastError && <Typography variant="caption" color="error" sx={{ display: 'block' }}>{h.lastError}</Typography>}</TableCell>
                      <TableCell>{MODE_LABEL[h.mode] ?? h.mode}</TableCell>
                      <TableCell align="right">{n(h.calls)}</TableCell>
                      <TableCell align="right">{n(h.failures)}</TableCell>
                      <TableCell><HealthChip status={h.status} /></TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Button component={RouterLink} to={`/platform/integrations?tenantId=${d.id}`} size="small" sx={{ mt: 1 }}>See the calls</Button>
            </CardContent>
          </Card>
        </Grid>

        <Grid size={{ xs: 12, md: 5 }}>
          <Card sx={{ mb: 2.5 }}>
            <CardContent>
              <Stack direction="row" justifyContent="space-between" alignItems="baseline" sx={{ mb: 1 }}>
                <Typography variant="h6">Setup</Typography>
                <Typography color="text.secondary">{setupDone} of {d.setup.length}</Typography>
              </Stack>
              <Box sx={{ height: 6, borderRadius: 3, bgcolor: 'action.hover', mb: 1.5, overflow: 'hidden' }}>
                <Box sx={{ height: '100%', width: `${(setupDone / d.setup.length) * 100}%`, bgcolor: 'success.main' }} />
              </Box>
              {d.setup.map((s) => (
                <Stack key={s.key} direction="row" spacing={1.25} sx={{ py: 0.6 }}>
                  {s.done ? <CheckCircleIcon color="success" fontSize="small" sx={{ mt: 0.2 }} /> : <RadioButtonUncheckedIcon color="disabled" fontSize="small" sx={{ mt: 0.2 }} />}
                  <Box>
                    <Typography variant="body2" fontWeight={600} color={s.done ? 'text.primary' : 'text.secondary'}>{s.label}</Typography>
                    {!s.done && <Typography variant="caption" color="text.secondary">{s.hint}</Typography>}
                  </Box>
                </Stack>
              ))}
            </CardContent>
          </Card>

          <Card sx={{ mb: 2.5 }}>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>Contact</Typography>
              <Field label="Email" value={d.contactEmail} />
              <Field label="Phone" value={d.contactPhone} />
              <Field label="Onboarded by" value={d.onboardedBy} />
              {d.notes && <><Divider sx={{ my: 1.5 }} /><Typography variant="body2" color="text.secondary">Msingi notes</Typography><Typography sx={{ whiteSpace: 'pre-wrap' }}>{d.notes}</Typography></>}
            </CardContent>
          </Card>

          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>Recent platform actions</Typography>
              {d.recentActions.length === 0 && <Typography variant="body2" color="text.secondary">None yet.</Typography>}
              {d.recentActions.map((a) => (
                <Box key={a.id} sx={{ py: 0.75, borderTop: 1, borderColor: 'divider' }}>
                  <Typography variant="body2"><strong>{actionLabel(a.action)}</strong> · {a.actorName}</Typography>
                  <Typography variant="caption" color="text.secondary">{formatDateTime(a.occurredAt)}{a.details ? ` · ${detailsText(a.details)}` : ''}</Typography>
                </Box>
              ))}
              <Button component={RouterLink} to={`/platform/audit?tenantId=${d.id}`} size="small" sx={{ mt: 1 }}>Full audit trail</Button>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      {dialog === 'edit' && <EditDialog d={d} onClose={() => setDialog(null)} onDone={done} />}
      {(dialog === 'suspend' || dialog === 'reactivate') && <StatusDialog d={d} action={dialog} onClose={() => setDialog(null)} onDone={done} />}
      {dialog === 'domain' && <DomainDialog id={d.id} onClose={() => setDialog(null)} onDone={done} />}
      {dialog === 'sms' && <SmsDialog id={d.id} onClose={() => setDialog(null)} onDone={done} />}
      {dialog === 'admin' && <AdminDialog id={d.id} onClose={() => setDialog(null)} onDone={(r) => { done(r.institution); setIssued(r); }} />}
      <Dialog open={!!confirmReset} onClose={() => setConfirmReset(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Reset {confirmReset?.name}'s password?</DialogTitle>
        <DialogContent>
          <Typography>They get a temporary password, are unlocked and signed out everywhere. Do this only after confirming who is asking, by phone to the college.</Typography>
          <ErrorAlert error={reset.error} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmReset(null)}>Cancel</Button>
          <Button variant="contained" disabled={reset.isPending} onClick={() => confirmReset && reset.mutate(confirmReset.id)}>Reset password</Button>
        </DialogActions>
      </Dialog>
      {issued && <IssuedPasswordDialog open title="Temporary password" username={issued.username} password={issued.temporaryPassword}
        note={issued.signInUrl && <Typography variant="body2">They sign in at <strong>{issued.signInUrl}</strong>.</Typography>} onClose={() => setIssued(null)} />}
    </>
  );
}

function Stat({ label, value, sub, warn, action }: { label: string; value: string; sub?: string; warn?: boolean; action?: React.ReactNode }) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
          <Typography variant="body2" color="text.secondary">{label}</Typography>
          {action}
        </Stack>
        <Typography sx={{ fontFamily: 'Sora', fontSize: 28, fontWeight: 700, color: warn ? 'warning.main' : 'text.primary' }}>{value}</Typography>
        {sub && <Typography variant="caption" color="text.secondary">{sub}</Typography>}
      </CardContent>
    </Card>
  );
}

function Field({ label, value }: { label: string; value?: string | null }) {
  return (
    <Stack direction="row" spacing={2} sx={{ py: 0.5 }}>
      <Typography variant="body2" color="text.secondary" sx={{ width: 110, flexShrink: 0 }}>{label}</Typography>
      <Typography variant="body2">{value || '—'}</Typography>
    </Stack>
  );
}

// ============================================================ dialogs

function EditDialog({ d, onClose, onDone }: { d: InstitutionDetail; onClose: () => void; onDone: (d: InstitutionDetail) => void }) {
  const [f, setF] = useState({ name: d.name, shortName: d.shortName ?? '', nactvetRegNo: d.nactvetRegNo ?? '', ownership: d.ownership,
    contactEmail: d.contactEmail ?? '', contactPhone: d.contactPhone ?? '', primaryColor: d.primaryColor, notes: d.notes ?? '' });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });
  const m = useMutation({
    mutationFn: () => api<InstitutionDetail>(`/api/platform/institutions/${d.id}`, { ...platform, method: 'PUT', body: {
      ...f, shortName: f.shortName || null, nactvetRegNo: f.nactvetRegNo || null, contactEmail: f.contactEmail || null, contactPhone: f.contactPhone || null, notes: f.notes || null } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Edit {d.code}</DialogTitle>
      <DialogContent>
        <Grid container spacing={2} sx={{ mt: 0.5 }}>
          <Grid size={12}><TextField label="Full name" required fullWidth value={f.name} onChange={set('name')} /></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><TextField label="Short name" fullWidth value={f.shortName} onChange={set('shortName')} /></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><TextField label="NACTVET registration no." fullWidth value={f.nactvetRegNo} onChange={set('nactvetRegNo')} /></Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <TextField select label="Ownership" fullWidth value={f.ownership} onChange={set('ownership')}>
              <MenuItem value="PRIVATE">Private</MenuItem><MenuItem value="PUBLIC">Public</MenuItem>
            </TextField>
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}><TextField label="Colour" type="color" fullWidth value={f.primaryColor} onChange={set('primaryColor')} /></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><TextField label="Contact email" fullWidth value={f.contactEmail} onChange={set('contactEmail')} /></Grid>
          <Grid size={{ xs: 12, sm: 6 }}><TextField label="Contact phone" fullWidth value={f.contactPhone} onChange={set('contactPhone')} /></Grid>
          <Grid size={12}><TextField label="Msingi notes" fullWidth multiline minRows={3} value={f.notes} onChange={set('notes')} inputProps={{ maxLength: 1000 }} /></Grid>
        </Grid>
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!f.name.trim() || m.isPending} onClick={() => m.mutate()}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}

function StatusDialog({ d, action, onClose, onDone }: { d: InstitutionDetail; action: 'suspend' | 'reactivate'; onClose: () => void; onDone: (d: InstitutionDetail) => void }) {
  const [reason, setReason] = useState('');
  const [confirm, setConfirm] = useState('');
  const suspend = action === 'suspend';
  const m = useMutation({ mutationFn: () => api<InstitutionDetail>(`/api/platform/institutions/${d.id}/${action}`, { ...platform, method: 'POST', body: { reason } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{suspend ? `Suspend ${d.name}?` : `Reactivate ${d.name}?`}</DialogTitle>
      <DialogContent>
        {suspend ? (
          <Alert severity="warning" sx={{ mb: 2 }}>
            Every portal of the institution (applicants, students and staff) shows "service temporarily unavailable" within a few minutes, and everyone signed in is stopped.
            Its data is kept, scheduled jobs pause and GePG payments are still recorded. The institution's audit trail records the suspension.
          </Alert>
        ) : <Typography sx={{ mb: 2 }}>Its portals open again within a few minutes.</Typography>}
        <TextField label={suspend ? 'Reason' : 'Note (optional)'} required={suspend} fullWidth multiline minRows={2} value={reason} onChange={(e) => setReason(e.target.value)} inputProps={{ maxLength: 300 }} />
        {suspend && <TextField label={`Type ${d.code} to confirm`} fullWidth sx={{ mt: 2 }} value={confirm} onChange={(e) => setConfirm(e.target.value)} />}
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color={suspend ? 'error' : 'success'} disabled={m.isPending || (suspend && (!reason.trim() || confirm.trim().toUpperCase() !== d.code))}
          onClick={() => m.mutate()}>{suspend ? 'Suspend' : 'Reactivate'}</Button>
      </DialogActions>
    </Dialog>
  );
}

function DomainDialog({ id, onClose, onDone }: { id: number; onClose: () => void; onDone: (d: InstitutionDetail) => void }) {
  const [host, setHost] = useState('');
  const [purpose, setPurpose] = useState('ALL');
  const m = useMutation({ mutationFn: () => api<InstitutionDetail>(`/api/platform/institutions/${id}/domains`, { ...platform, method: 'POST', body: { host, purpose } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Add a web address</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 0.5 }}>
          <TextField label="Host name" required value={host} onChange={(e) => setHost(e.target.value)} placeholder="apply.college.ac.tz" helperText="Point its DNS at CIMS first." />
          <TextField select label="Used for" value={purpose} onChange={(e) => setPurpose(e.target.value)}>
            <MenuItem value="ALL">All portals</MenuItem><MenuItem value="ADMISSION">Admission portal</MenuItem>
            <MenuItem value="STUDENT">Student portal</MenuItem><MenuItem value="STAFF">Staff back-office</MenuItem>
          </TextField>
        </Stack>
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!host.trim() || m.isPending} onClick={() => m.mutate()}>Add</Button>
      </DialogActions>
    </Dialog>
  );
}

function SmsDialog({ id, onClose, onDone }: { id: number; onClose: () => void; onDone: (d: InstitutionDetail) => void }) {
  const [units, setUnits] = useState('');
  const [amount, setAmount] = useState('');
  const [reference, setReference] = useState('');
  const m = useMutation({ mutationFn: () => api<InstitutionDetail>(`/api/platform/institutions/${id}/sms-credit`, { ...platform, method: 'POST',
    body: { units: Number(units), amountPaid: Number(amount || 0), reference } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Credit an SMS bundle</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>For a bundle Msingi sold to the institution. It shows on the institution's messaging page as a top-up.</Typography>
        <Stack spacing={2}>
          <TextField label="SMS units" type="number" required value={units} onChange={(e) => setUnits(e.target.value)} inputProps={{ min: 1 }} />
          <TextField label="Amount paid (TZS)" type="number" value={amount} onChange={(e) => setAmount(e.target.value)} inputProps={{ min: 0 }}
            helperText={amount ? formatTzs(Number(amount)) : undefined} />
          <TextField label="Reference (invoice or receipt)" required value={reference} onChange={(e) => setReference(e.target.value)} inputProps={{ maxLength: 80 }} />
        </Stack>
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!(Number(units) > 0) || !reference.trim() || m.isPending} onClick={() => m.mutate()}>Credit</Button>
      </DialogActions>
    </Dialog>
  );
}

function AdminDialog({ id, onClose, onDone }: { id: number; onClose: () => void; onDone: (r: IssuedAdmin) => void }) {
  const [f, setF] = useState({ username: '', fullName: '', email: '', phone: '' });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });
  const m = useMutation({ mutationFn: () => api<IssuedAdmin>(`/api/platform/institutions/${id}/administrators`, { ...platform, method: 'POST',
    body: { username: f.username.trim(), fullName: f.fullName, email: f.email || null, phone: f.phone || null } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Add an administrator</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>For example when the institution's only administrator has left. Confirm the request with the college first.</Typography>
        <Stack spacing={2}>
          <TextField label="Username" required value={f.username} onChange={set('username')} />
          <TextField label="Full name" required value={f.fullName} onChange={set('fullName')} />
          <TextField label="Email" value={f.email} onChange={set('email')} />
          <TextField label="Phone" value={f.phone} onChange={set('phone')} />
        </Stack>
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={f.username.trim().length < 3 || !f.fullName.trim() || m.isPending} onClick={() => m.mutate()}>Add</Button>
      </DialogActions>
    </Dialog>
  );
}
