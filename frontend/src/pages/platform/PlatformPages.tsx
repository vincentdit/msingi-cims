import AddIcon from '@mui/icons-material/Add';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, Table, TableBody,
  TableCell, TableContainer, TableHead, TablePagination, TableRow, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link as RouterLink, useNavigate, useSearchParams } from 'react-router-dom';
import { api, formatDateTime } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import {
  HealthChip, IssuedPasswordDialog, MODE_LABEL, StatusChip, actionLabel, detailsText, n, platform,
  type AuditEntry, type InstitutionRow, type IntegrationCall, type Overview, type PageOf, type SystemHealth,
} from './shared';

// ============================================================ overview

export function OverviewPage() {
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ['platform', 'overview'], refetchInterval: 120_000, queryFn: () => api<Overview>('/api/platform/overview', platform) });
  if (q.isLoading) return <Loading />;
  const o = q.data;
  if (!o) return <ErrorAlert error={q.error} />;
  const failing = o.health.filter((h) => h.status === 'DOWN' || h.status === 'DEGRADED');
  return (
    <>
      <PageTitle title="Platform overview" subtitle="All institutions on Msingi CIMS, and the outside systems they depend on."
        action={<Button variant="contained" startIcon={<AddIcon />} onClick={() => navigate('/platform/institutions/new')}>Onboard institution</Button>} />
      {failing.length > 0 && (
        <Alert severity={failing.some((h) => h.status === 'DOWN') ? 'error' : 'warning'} sx={{ mb: 2.5 }}
          action={<Button color="inherit" component={RouterLink} to="/platform/integrations">Details</Button>}>
          {failing.map((h) => `${h.label}: ${h.status === 'DOWN' ? 'failing' : `${h.failures} of ${h.calls} calls failed`}`).join(' · ')} (last 24 hours)
        </Alert>
      )}
      <Grid container spacing={2.5} sx={{ mb: 2.5 }}>
        <Grid size={{ xs: 6, md: 3 }}><Big label="Institutions" value={n(o.institutions)} sub={`${o.active} active${o.suspended ? ` · ${o.suspended} suspended` : ''}`} /></Grid>
        <Grid size={{ xs: 6, md: 3 }}><Big label="Students studying" value={n(o.studentsActive)} /></Grid>
        <Grid size={{ xs: 6, md: 3 }}><Big label={`Applications ${new Date().getFullYear()}`} value={n(o.applicationsThisYear)} /></Grid>
        <Grid size={{ xs: 6, md: 3 }}><Big label="Staff accounts" value={n(o.staffActive)} /></Grid>
      </Grid>
      <Card sx={{ mb: 2.5 }}>
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
            <Typography variant="h6">Integrations (last 24 hours)</Typography>
            <Button component={RouterLink} to="/platform/integrations" size="small">All calls</Button>
          </Stack>
          <HealthTable rows={o.health} />
        </CardContent>
      </Card>
      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, lg: 7 }}>
          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>Needs attention</Typography>
              {o.attention.length === 0 && <Typography color="text.secondary">Nothing: every institution is active, set up and its integrations work.</Typography>}
              {o.attention.map((r) => <AttentionRow key={r.id} r={r} />)}
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, lg: 5 }}>
          <Card>
            <CardContent>
              <Typography variant="h6" sx={{ mb: 1 }}>Recent platform actions</Typography>
              {o.recentActions.map((a) => (
                <Box key={a.id} sx={{ py: 0.75, borderTop: 1, borderColor: 'divider' }}>
                  <Typography variant="body2"><strong>{actionLabel(a.action)}</strong>{a.tenantCode ? ` · ${a.tenantCode}` : ''}</Typography>
                  <Typography variant="caption" color="text.secondary">{a.actorName} · {formatDateTime(a.occurredAt)}</Typography>
                </Box>
              ))}
              <Button component={RouterLink} to="/platform/audit" size="small" sx={{ mt: 1 }}>Audit trail</Button>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </>
  );
}

function AttentionRow({ r }: { r: InstitutionRow }) {
  const reasons = [
    r.status === 'SUSPENDED' ? `Suspended${r.statusReason ? `: ${r.statusReason}` : ''}` : null,
    r.integrationFailures24h > 0 ? `${r.integrationFailures24h} failed integration calls today` : null,
    r.setupDone < r.setupTotal ? `Setup ${r.setupDone} of ${r.setupTotal}` : null,
  ].filter(Boolean);
  return (
    <Stack component={RouterLink} to={`/platform/institutions/${r.id}`} direction="row" justifyContent="space-between" alignItems="center" spacing={1}
      sx={{ py: 1, borderTop: 1, borderColor: 'divider', color: 'inherit', textDecoration: 'none', '&:hover': { bgcolor: 'action.hover' } }}>
      <Box sx={{ minWidth: 0 }}>
        <Typography fontWeight={600}>{r.name} <Box component="span" sx={{ fontFamily: 'monospace', fontWeight: 400, color: 'text.secondary' }}>· {r.code}</Box></Typography>
        <Typography variant="body2" color="text.secondary">{reasons.join(' · ')}</Typography>
      </Box>
      <StatusChip status={r.status} />
    </Stack>
  );
}

function Big({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
        <Typography sx={{ fontFamily: 'Sora', fontSize: 30, fontWeight: 700 }}>{value}</Typography>
        {sub && <Typography variant="caption" color="text.secondary">{sub}</Typography>}
      </CardContent>
    </Card>
  );
}

function HealthTable({ rows }: { rows: SystemHealth[] }) {
  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>System</TableCell><TableCell>Mode</TableCell><TableCell align="right">Calls</TableCell><TableCell align="right">Failed</TableCell>
            <TableCell align="right">Average</TableCell><TableCell>Last success</TableCell><TableCell>State</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((h) => (
            <TableRow key={h.system}>
              <TableCell sx={{ minWidth: 200 }}>
                {h.label}
                {h.lastError && <Typography variant="caption" color="error" sx={{ display: 'block', maxWidth: 360 }}>Last error {formatDateTime(h.lastFailureAt)}: {h.lastError}</Typography>}
              </TableCell>
              <TableCell>{h.mode === 'SIMULATED' ? <Chip size="small" variant="outlined" label="Simulated" /> : MODE_LABEL[h.mode] ?? h.mode}</TableCell>
              <TableCell align="right">{n(h.calls)}</TableCell>
              <TableCell align="right">{n(h.failures)}{h.failureRate ? ` (${h.failureRate}%)` : ''}</TableCell>
              <TableCell align="right">{h.averageMs != null ? `${n(h.averageMs)} ms` : '—'}</TableCell>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{h.lastSuccessAt ? formatDateTime(h.lastSuccessAt) : '—'}</TableCell>
              <TableCell><HealthChip status={h.status} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

// ============================================================ integration health

const PERIODS = [{ v: 1, l: 'Last hour' }, { v: 24, l: 'Last 24 hours' }, { v: 168, l: 'Last 7 days' }, { v: 720, l: 'Last 30 days' }];

export function IntegrationsPage() {
  const [params, setParams] = useSearchParams();
  const tenantId = params.get('tenantId') ?? '';
  const [hours, setHours] = useState(24);
  const [system, setSystem] = useState('');
  const [outcome, setOutcome] = useState('');
  const [page, setPage] = useState(0);
  const institutions = useQuery({ queryKey: ['platform', 'institutions'], queryFn: () => api<InstitutionRow[]>('/api/platform/institutions', platform) });
  const health = useQuery({ queryKey: ['platform', 'health', hours, tenantId], refetchInterval: 60_000,
    queryFn: () => api<SystemHealth[]>(`/api/platform/integrations/health?hours=${hours}${tenantId ? `&tenantId=${tenantId}` : ''}`, platform) });
  const qs = new URLSearchParams({ page: String(page), size: '50' });
  if (tenantId) qs.set('tenantId', tenantId);
  if (system) qs.set('system', system);
  if (outcome) qs.set('outcome', outcome);
  const calls = useQuery({ queryKey: ['platform', 'calls', qs.toString()], queryFn: () => api<PageOf<IntegrationCall>>(`/api/platform/integrations/calls?${qs}`, platform) });
  return (
    <>
      <PageTitle title="Integration health" subtitle="Calls to NECTA, NACTVET, GePG and the SMS gateway from every institution. Only references are kept, never what was sent." />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }}>
        <TextField select size="small" label="Institution" value={tenantId} sx={{ minWidth: 260 }}
          onChange={(e) => { const p = new URLSearchParams(params); if (e.target.value) p.set('tenantId', e.target.value); else p.delete('tenantId'); setParams(p); setPage(0); }}
          slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
          <MenuItem value="">All institutions</MenuItem>
          {(institutions.data ?? []).map((i) => <MenuItem key={i.id} value={String(i.id)}>{i.code} · {i.name}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Period" value={hours} onChange={(e) => setHours(Number(e.target.value))} sx={{ minWidth: 170 }}>
          {PERIODS.map((p) => <MenuItem key={p.v} value={p.v}>{p.l}</MenuItem>)}
        </TextField>
      </Stack>
      <Card sx={{ mb: 2.5 }}>
        <CardContent>
          <ErrorAlert error={health.error} />
          {health.data ? <HealthTable rows={health.data} /> : <Loading />}
        </CardContent>
      </Card>
      <Card>
        <CardContent sx={{ pb: 1 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }}>
            <Typography variant="h6" sx={{ flex: 1 }}>Calls</Typography>
            <TextField select size="small" label="System" value={system} onChange={(e) => { setSystem(e.target.value); setPage(0); }} sx={{ minWidth: 220 }}
              slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
              <MenuItem value="">All systems</MenuItem>
              {(health.data ?? []).map((h) => <MenuItem key={h.system} value={h.system}>{h.label}</MenuItem>)}
            </TextField>
            <TextField select size="small" label="Outcome" value={outcome} onChange={(e) => { setOutcome(e.target.value); setPage(0); }} sx={{ minWidth: 150 }}
              slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
              <MenuItem value="">All</MenuItem><MenuItem value="FAILURE">Failed</MenuItem><MenuItem value="SUCCESS">Succeeded</MenuItem>
            </TextField>
          </Stack>
        </CardContent>
        <ErrorAlert error={calls.error} />
        <CallsTable rows={calls.data?.content ?? []} showTenant />
        <TablePagination component="div" count={calls.data?.totalElements ?? 0} page={page} onPageChange={(_, p) => setPage(p)} rowsPerPage={50} rowsPerPageOptions={[50]} />
      </Card>
    </>
  );
}

export function CallsTable({ rows, showTenant }: { rows: IntegrationCall[]; showTenant?: boolean }) {
  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>When</TableCell>{showTenant && <TableCell>Institution</TableCell>}<TableCell>System</TableCell><TableCell>Call</TableCell>
            <TableCell>Reference</TableCell><TableCell align="right">Time</TableCell><TableCell>Outcome</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((c) => (
            <TableRow key={c.id} sx={{ verticalAlign: 'top' }}>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(c.occurredAt)}</TableCell>
              {showTenant && <TableCell sx={{ fontFamily: 'monospace' }}>{c.tenantCode ?? '—'}</TableCell>}
              <TableCell>{c.system.replace('_', ' ')}{c.mode === 'SIMULATED' && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>simulated</Typography>}</TableCell>
              <TableCell>{c.direction === 'IN' ? '← ' : '→ '}{c.operation}</TableCell>
              <TableCell sx={{ fontFamily: 'monospace', fontSize: 13 }}>{c.reference ?? '—'}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{c.durationMs != null ? `${n(c.durationMs)} ms` : '—'}</TableCell>
              <TableCell sx={{ maxWidth: 320 }}>
                <Chip size="small" color={c.outcome === 'SUCCESS' ? 'success' : 'error'} variant={c.outcome === 'SUCCESS' ? 'outlined' : 'filled'} label={c.outcome === 'SUCCESS' ? 'OK' : 'Failed'} />
                {c.detail && <Typography variant="caption" color={c.outcome === 'FAILURE' ? 'error' : 'text.secondary'} sx={{ display: 'block' }}>{c.detail}</Typography>}
              </TableCell>
            </TableRow>
          ))}
          {rows.length === 0 && <TableRow><TableCell colSpan={showTenant ? 7 : 6}><Typography color="text.secondary" sx={{ py: 2 }}>No calls in this selection.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

// ============================================================ platform users

interface PlatformUserView {
  id: number; username: string; fullName: string; email?: string; phone?: string; status: 'ACTIVE' | 'LOCKED' | 'DISABLED';
  mustChangePassword: boolean; lastLoginAt?: string; createdByName?: string; createdAt: string; self: boolean;
}
interface IssuedPlatform { user: PlatformUserView; temporaryPassword: string }

export function PlatformUsersPage() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['platform', 'users'], queryFn: () => api<PlatformUserView[]>('/api/platform/users', platform) });
  const [adding, setAdding] = useState(false);
  const [issued, setIssued] = useState<IssuedPlatform | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['platform', 'users'] });
  const act = useMutation({
    mutationFn: ({ id, action }: { id: number; action: 'disable' | 'enable' | 'reset-password' }) =>
      api<PlatformUserView | IssuedPlatform>(`/api/platform/users/${id}/${action}`, { ...platform, method: 'POST' }),
    onSuccess: (r) => { refresh(); if ('temporaryPassword' in r) setIssued(r); },
  });
  return (
    <>
      <PageTitle title="Platform users" subtitle="Msingi Technologies staff who can use this console. Keep this list short."
        action={<Button variant="contained" startIcon={<AddIcon />} onClick={() => setAdding(true)}>Add platform user</Button>} />
      <ErrorAlert error={q.error ?? act.error} />
      <Card>
        <TableContainer>
          <Table size="small">
            <TableHead><TableRow><TableCell>Name</TableCell><TableCell>Contact</TableCell><TableCell>Last sign-in</TableCell><TableCell>Status</TableCell><TableCell align="right" /></TableRow></TableHead>
            <TableBody>
              {(q.data ?? []).map((u) => (
                <TableRow key={u.id}>
                  <TableCell>
                    <Typography fontWeight={600}>{u.fullName}{u.self ? ' (you)' : ''}</Typography>
                    <Typography variant="body2" color="text.secondary" sx={{ fontFamily: 'monospace' }}>{u.username}</Typography>
                  </TableCell>
                  <TableCell>{[u.email, u.phone].filter(Boolean).join(' · ') || '—'}</TableCell>
                  <TableCell>{u.lastLoginAt ? formatDateTime(u.lastLoginAt) : 'Never'}</TableCell>
                  <TableCell>
                    <Chip size="small" color={u.status === 'ACTIVE' ? 'success' : u.status === 'LOCKED' ? 'warning' : 'default'}
                      label={u.status === 'ACTIVE' ? 'Active' : u.status === 'LOCKED' ? 'Locked' : 'Disabled'} />
                    {u.mustChangePassword && <Chip size="small" variant="outlined" label="Temporary password" sx={{ ml: 0.5 }} />}
                  </TableCell>
                  <TableCell align="right">
                    {!u.self && (
                      <Stack direction="row" spacing={1} justifyContent="flex-end">
                        <Button size="small" onClick={() => act.mutate({ id: u.id, action: 'reset-password' })}>Reset password</Button>
                        {u.status === 'DISABLED'
                          ? <Button size="small" onClick={() => act.mutate({ id: u.id, action: 'enable' })}>Enable</Button>
                          : <Button size="small" color="error" onClick={() => act.mutate({ id: u.id, action: 'disable' })}>Disable</Button>}
                      </Stack>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Card>
      {adding && <AddPlatformUser onClose={() => setAdding(false)} onDone={(r) => { setAdding(false); refresh(); setIssued(r); }} />}
      {issued && <IssuedPasswordDialog open title="Platform user" username={issued.user.username} password={issued.temporaryPassword} onClose={() => setIssued(null)} />}
    </>
  );
}

function AddPlatformUser({ onClose, onDone }: { onClose: () => void; onDone: (r: IssuedPlatform) => void }) {
  const [f, setF] = useState({ username: '', fullName: '', email: '', phone: '' });
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });
  const m = useMutation({ mutationFn: () => api<IssuedPlatform>('/api/platform/users', { ...platform, method: 'POST',
    body: { username: f.username.trim(), fullName: f.fullName, email: f.email || null, phone: f.phone || null } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Add a platform user</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>They can onboard, suspend and support every institution.</Typography>
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

// ============================================================ platform audit trail

export function PlatformAuditPage() {
  const [params, setParams] = useSearchParams();
  const tenantId = params.get('tenantId') ?? '';
  const [action, setAction] = useState('');
  const [page, setPage] = useState(0);
  const institutions = useQuery({ queryKey: ['platform', 'institutions'], queryFn: () => api<InstitutionRow[]>('/api/platform/institutions', platform) });
  const actions = useQuery({ queryKey: ['platform', 'audit-actions'], queryFn: () => api<string[]>('/api/platform/audit/actions', platform) });
  const qs = new URLSearchParams({ page: String(page), size: '50' });
  if (tenantId) qs.set('tenantId', tenantId);
  if (action) qs.set('action', action);
  const q = useQuery({ queryKey: ['platform', 'audit', qs.toString()], queryFn: () => api<PageOf<AuditEntry>>(`/api/platform/audit?${qs}`, platform) });
  return (
    <>
      <PageTitle title="Platform audit trail" subtitle="Everything Msingi staff did on the platform. It cannot be changed or deleted. What they did inside an institution also appears in that institution's own audit trail." />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }}>
        <TextField select size="small" label="Institution" value={tenantId} sx={{ minWidth: 260 }}
          onChange={(e) => { const p = new URLSearchParams(params); if (e.target.value) p.set('tenantId', e.target.value); else p.delete('tenantId'); setParams(p); setPage(0); }}
          slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
          <MenuItem value="">All</MenuItem>
          {(institutions.data ?? []).map((i) => <MenuItem key={i.id} value={String(i.id)}>{i.code} · {i.name}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Action" value={action} onChange={(e) => { setAction(e.target.value); setPage(0); }} sx={{ minWidth: 260 }}
          slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
          <MenuItem value="">All actions</MenuItem>
          {(actions.data ?? []).map((a) => <MenuItem key={a} value={a}>{actionLabel(a)}</MenuItem>)}
        </TextField>
      </Stack>
      <ErrorAlert error={q.error} />
      <Card>
        <TableContainer>
          <Table size="small">
            <TableHead><TableRow><TableCell>When</TableCell><TableCell>Who</TableCell><TableCell>What</TableCell><TableCell>Institution</TableCell><TableCell>Details</TableCell></TableRow></TableHead>
            <TableBody>
              {(q.data?.content ?? []).map((a) => (
                <TableRow key={a.id} sx={{ verticalAlign: 'top' }}>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(a.occurredAt)}</TableCell>
                  <TableCell>{a.actorName}{a.ipAddress && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>{a.ipAddress}</Typography>}</TableCell>
                  <TableCell>{actionLabel(a.action)}</TableCell>
                  <TableCell sx={{ fontFamily: 'monospace' }}>{a.tenantCode ? <RouterLink to={`/platform/institutions/${a.tenantId}`}>{a.tenantCode}</RouterLink> : '—'}</TableCell>
                  <TableCell sx={{ maxWidth: 420, fontSize: 13, color: 'text.secondary' }}>{detailsText(a.details)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
        <TablePagination component="div" count={q.data?.totalElements ?? 0} page={page} onPageChange={(_, p) => setPage(p)} rowsPerPage={50} rowsPerPageOptions={[50]} />
      </Card>
    </>
  );
}
