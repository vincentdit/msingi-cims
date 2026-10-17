import AddIcon from '@mui/icons-material/PersonAdd';
import CopyIcon from '@mui/icons-material/ContentCopy';
import SearchIcon from '@mui/icons-material/Search';
import {
  Alert, Box, Button, Card, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, InputAdornment,
  MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { api, formatDateTime } from '../../api/client';
import type { Department, IssuedPassword, Programme, RoleGrant, RoleInfo, RoleScope, StaffUser, StaffUserSummary } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };
const STATUS: Record<StaffUser['status'], { label: string; color: 'success' | 'default' | 'error' | 'warning' }> = {
  ACTIVE: { label: 'Active', color: 'success' },
  DISABLED: { label: 'Disabled', color: 'default' },
  LOCKED: { label: 'Locked', color: 'error' },
  PENDING_VERIFICATION: { label: 'Not verified', color: 'warning' },
};

/** Staff accounts and their roles (the Institution Administrator manages them; the Auditor can look). */
export default function UsersPage() {
  const { user } = useSession();
  const isAdmin = !!user('staff')?.roles.includes('INSTITUTION_ADMIN');
  const list = useQuery({ queryKey: ['staff', 'users'], queryFn: () => api<StaffUser[]>('/api/admin/users', staff) });
  const summary = useQuery({ queryKey: ['staff', 'users', 'summary'], queryFn: () => api<StaffUserSummary>('/api/admin/users/summary', staff) });
  const roles = useQuery({ queryKey: ['staff', 'roles'], queryFn: () => api<RoleInfo[]>('/api/admin/roles', staff), staleTime: Infinity });
  const [q, setQ] = useState('');
  const [role, setRole] = useState('');
  const [status, setStatus] = useState('');
  const [editing, setEditing] = useState<StaffUser | 'new' | null>(null);
  const [issued, setIssued] = useState<IssuedPassword | null>(null);

  const shown = useMemo(() => (list.data ?? []).filter((u) => {
    const text = `${u.fullName} ${u.username} ${u.email ?? ''} ${u.jobTitle ?? ''}`.toLowerCase();
    return (!q || text.includes(q.toLowerCase())) && (!role || u.roles.some((r) => r.role === role)) && (!status || u.status === status);
  }), [list.data, q, role, status]);

  const s = summary.data;
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Users & roles"
        subtitle="Staff accounts and what each person can see and do. Roles decide the menu and the actions; a Head of Department or Programme Coordinator can be limited to one department or programme."
        action={isAdmin && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setEditing('new')}>New user</Button>} />
      {s && (
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 2 }}>
          <Chip label={`${s.active} active`} color="success" variant="outlined" onClick={() => setStatus(status === 'ACTIVE' ? '' : 'ACTIVE')} />
          {s.locked > 0 && <Chip label={`${s.locked} locked`} color="error" variant="outlined" onClick={() => setStatus(status === 'LOCKED' ? '' : 'LOCKED')} />}
          {s.disabled > 0 && <Chip label={`${s.disabled} disabled`} variant="outlined" onClick={() => setStatus(status === 'DISABLED' ? '' : 'DISABLED')} />}
          {s.mustChangePassword > 0 && <Chip label={`${s.mustChangePassword} yet to choose a password`} color="warning" variant="outlined" />}
        </Stack>
      )}
      <Card>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} sx={{ p: 2 }}>
          <TextField size="small" placeholder="Search name, username or email" value={q} onChange={(e) => setQ(e.target.value)} sx={{ flexGrow: 1, minWidth: { md: 280 } }}
            slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment> } }} />
          <TextField size="small" select label="Role" value={role} onChange={(e) => setRole(e.target.value)} sx={{ width: { md: 280 }, flexShrink: 0 }}>
            <MenuItem value="">All roles</MenuItem>
            {roles.data?.map((r) => <MenuItem key={r.role} value={r.role}>{r.label}</MenuItem>)}
          </TextField>
          <TextField size="small" select label="Status" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ width: { md: 180 }, flexShrink: 0 }}>
            <MenuItem value="">Any status</MenuItem>
            {Object.entries(STATUS).filter(([k]) => k !== 'PENDING_VERIFICATION').map(([k, v]) => <MenuItem key={k} value={k}>{v.label}</MenuItem>)}
          </TextField>
        </Stack>
        <ErrorAlert error={list.error} />
        {list.isLoading ? <Loading /> : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow><TableCell>Name</TableCell><TableCell>Username</TableCell><TableCell>Roles</TableCell><TableCell>Status</TableCell><TableCell>Last signed in</TableCell></TableRow>
              </TableHead>
              <TableBody>
                {shown.map((u) => (
                  <TableRow key={u.id} hover sx={{ cursor: 'pointer' }} onClick={() => setEditing(u)}>
                    <TableCell>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{u.fullName}{u.self && <Typography component="span" variant="caption" color="text.secondary"> (you)</Typography>}</Typography>
                      {u.jobTitle && <Typography variant="caption" color="text.secondary">{u.jobTitle}</Typography>}
                    </TableCell>
                    <TableCell>{u.username}</TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                        {u.roles.map((r) => <Chip key={r.role} size="small" label={r.scopeName ? `${r.label}: ${r.scopeName}` : r.label} />)}
                      </Stack>
                    </TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={0.5} flexWrap="wrap" useFlexGap>
                        <Chip size="small" color={STATUS[u.status].color} label={STATUS[u.status].label} />
                        {u.mustChangePassword && u.status === 'ACTIVE' && <Chip size="small" color="warning" variant="outlined" label="Temporary password" />}
                      </Stack>
                    </TableCell>
                    <TableCell>{u.lastLoginAt ? formatDateTime(u.lastLoginAt) : <Typography variant="body2" color="text.secondary">Never</Typography>}</TableCell>
                  </TableRow>
                ))}
                {!shown.length && <TableRow><TableCell colSpan={5}><Typography color="text.secondary" sx={{ py: 2 }}>No staff match these filters.</Typography></TableCell></TableRow>}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Card>
      {editing && roles.data && (
        <UserDialog key={editing === 'new' ? 'new' : editing.id} user={editing === 'new' ? null : editing} roles={roles.data} readOnly={!isAdmin}
          onClose={() => setEditing(null)} onIssued={(i) => { setEditing(null); setIssued(i); }} />
      )}
      <PasswordIssued issued={issued} onClose={() => setIssued(null)} />
    </Container>
  );
}

function UserDialog({ user, roles, readOnly, onClose, onIssued }: {
  user: StaffUser | null; roles: RoleInfo[]; readOnly: boolean; onClose: () => void; onIssued: (i: IssuedPassword) => void;
}) {
  const qc = useQueryClient();
  const depts = useQuery({ queryKey: ['staff', 'departments'], queryFn: () => api<Department[]>('/api/academic/departments', staff) });
  const progs = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const sponsors = useQuery({ queryKey: ['staff', 'sponsors'], queryFn: () => api<{ id: number; name: string }[]>('/api/finance/sponsors', staff) });
  const [f, setF] = useState({
    username: user?.username ?? '', fullName: user?.fullName ?? '', jobTitle: user?.jobTitle ?? '', email: user?.email ?? '',
    phone: user?.phone ? '0' + user.phone.replace(/^255/, '') : '',
  });
  const [grants, setGrants] = useState<Record<string, RoleGrant>>(Object.fromEntries((user?.roles ?? []).map((r) => [r.role, r])));
  const [confirmDisable, setConfirmDisable] = useState(false);
  const [reason, setReason] = useState('');
  const refresh = () => { qc.invalidateQueries({ queryKey: ['staff', 'users'] }); };
  const body = () => ({ ...f, jobTitle: f.jobTitle || null, email: f.email || null, phone: f.phone || null,
    roles: Object.values(grants).map((g) => ({ role: g.role, scope: g.scope, scopeId: g.scope === 'INSTITUTION' ? null : g.scopeId ?? null })) });
  const save = useMutation({
    mutationFn: () => user
      ? api<StaffUser>(`/api/admin/users/${user.id}`, { ...staff, method: 'PUT', body: body() }).then(() => null)
      : api<IssuedPassword>('/api/admin/users', { ...staff, method: 'POST', body: body() }),
    onSuccess: (r) => { refresh(); if (r) onIssued(r); else onClose(); },
  });
  const reset = useMutation({ mutationFn: () => api<IssuedPassword>(`/api/admin/users/${user!.id}/reset-password`, { ...staff, method: 'POST' }),
    onSuccess: (r) => { refresh(); onIssued(r); } });
  const toggle = useMutation({
    mutationFn: (enable: boolean) => api<StaffUser>(`/api/admin/users/${user!.id}/${enable ? 'enable' : 'disable'}`, { ...staff, method: 'POST', body: enable ? undefined : { reason: reason || null } }),
    onSuccess: () => { refresh(); onClose(); },
  });
  const mfaReset = useMutation({ mutationFn: () => api(`/api/admin/users/${user!.id}/mfa/reset`, { ...staff, method: 'POST' }), onSuccess: () => { refresh(); onClose(); } });
  const error = save.error ?? reset.error ?? toggle.error ?? mfaReset.error;
  const tf = (k: keyof typeof f, label: string, extra: object = {}) => (
    <TextField fullWidth size="small" label={label} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} disabled={readOnly} {...extra} />
  );
  const setRole = (r: RoleInfo, on: boolean) => {
    const next = { ...grants };
    if (on) next[r.role] = { role: r.role, scope: r.scopes.includes('INSTITUTION') ? 'INSTITUTION' : r.scopes[0] }; else delete next[r.role];
    setGrants(next);
  };

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{user ? user.fullName : 'New staff account'}</DialogTitle>
      <DialogContent dividers>
        <Grid container spacing={2}>
          <Grid size={{ xs: 12, sm: 6 }}>{tf('fullName', 'Full name', { required: true })}</Grid>
          <Grid size={{ xs: 12, sm: 6 }}>{tf('username', 'Username', { required: true, disabled: readOnly || !!user, helperText: user ? 'A username cannot be changed.' : 'Used to sign in, e.g. a.mrisho' })}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{tf('jobTitle', 'Job title')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{tf('email', 'Email')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{tf('phone', 'Mobile number', { placeholder: '0712345678' })}</Grid>
        </Grid>
        <Typography variant="subtitle1" sx={{ mt: 3, mb: 0.5, fontWeight: 600 }}>Roles</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>Choose everything this person does. Their menu shows the pages these roles use.</Typography>
        <Grid container spacing={1}>
          {roles.map((r) => {
            const g = grants[r.role];
            return (
              <Grid key={r.role} size={{ xs: 12, md: 6 }}>
                <Box sx={{ p: 1.25, border: 1, borderColor: g ? 'primary.main' : 'divider', borderRadius: 2, height: '100%', bgcolor: g ? 'action.hover' : undefined }}>
                  <FormControlLabel sx={{ alignItems: 'flex-start', m: 0 }} disabled={readOnly}
                    control={<Checkbox size="small" checked={!!g} onChange={(e) => setRole(r, e.target.checked)} sx={{ p: 0.25, mr: 1 }} />}
                    label={<Box><Typography variant="body2" sx={{ fontWeight: 600 }}>{r.label}</Typography><Typography variant="caption" color="text.secondary">{r.description}</Typography></Box>} />
                  {g && r.scopes.length === 1 && r.scopes[0] === 'SPONSOR' && (
                    <TextField select size="small" fullWidth sx={{ mt: 1 }} disabled={readOnly} label="Sponsor" required
                      value={g.scopeId ? String(g.scopeId) : ''}
                      onChange={(e) => setGrants({ ...grants, [r.role]: { ...g, scope: 'SPONSOR', scopeId: Number(e.target.value) } })}
                      helperText={!sponsors.data?.length ? 'Add the sponsor under Finance › Sponsors first.' : undefined}>
                      {sponsors.data?.map((x) => <MenuItem key={x.id} value={String(x.id)}>{x.name}</MenuItem>)}
                    </TextField>
                  )}
                  {g && r.scopes.length > 1 && (
                    <TextField select size="small" fullWidth sx={{ mt: 1 }} disabled={readOnly} label="Limited to"
                      value={g.scope === 'INSTITUTION' ? 'INSTITUTION' : String(g.scopeId ?? '')}
                      onChange={(e) => {
                        const v = e.target.value;
                        const scope: RoleScope = v === 'INSTITUTION' ? 'INSTITUTION' : r.scopes.includes('DEPARTMENT') ? 'DEPARTMENT' : 'PROGRAMME';
                        setGrants({ ...grants, [r.role]: { ...g, scope, scopeId: v === 'INSTITUTION' ? null : Number(v) } });
                      }}>
                      <MenuItem value="INSTITUTION">The whole institution</MenuItem>
                      {r.scopes.includes('DEPARTMENT') && depts.data?.map((d) => <MenuItem key={d.id} value={String(d.id)}>{d.name}</MenuItem>)}
                      {r.scopes.includes('PROGRAMME') && progs.data?.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.code} · {p.name}</MenuItem>)}
                    </TextField>
                  )}
                </Box>
              </Grid>
            );
          })}
        </Grid>
        {user && (
          <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 2 }}>
            Created {formatDateTime(user.createdAt)}{user.createdByName ? ` by ${user.createdByName}` : ''}. Last signed in {user.lastLoginAt ? formatDateTime(user.lastLoginAt) : 'never'}.
            Changing roles signs the person out so the new roles apply at once.
          </Typography>
        )}
        {confirmDisable && (
          <Alert severity="warning" sx={{ mt: 2 }}>
            {user?.fullName} will be signed out and unable to sign in until the account is enabled again.
            <TextField size="small" fullWidth label="Reason (optional)" value={reason} onChange={(e) => setReason(e.target.value)} sx={{ mt: 1, bgcolor: 'background.paper' }} />
          </Alert>
        )}
        <Box sx={{ mt: 2 }}><ErrorAlert error={error} /></Box>
      </DialogContent>
      <DialogActions sx={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: 1 }}>
        <Stack direction="row" spacing={1}>
          {user && <Button component={StaffLink} to={`/staff/audit?actorId=${user.id}`}>Their activity</Button>}
          {user && <Button component={StaffLink} to={`/staff/audit?entity=StaffUser&entityId=${user.id}`}>Account history</Button>}
          {user && !readOnly && !user.self && (
            <>
              <Button disabled={reset.isPending} onClick={() => reset.mutate()}>Reset password</Button>
              {user.mfaEnabled && <Button disabled={mfaReset.isPending} onClick={() => mfaReset.mutate()} title="Lost phone: they set up two-step sign-in again at their next sign-in">Reset two-step sign-in</Button>}
              {user.status === 'ACTIVE'
                ? <Button color="error" disabled={toggle.isPending} onClick={() => (confirmDisable ? toggle.mutate(false) : setConfirmDisable(true))}>{confirmDisable ? 'Disable now' : 'Disable'}</Button>
                : <Button color="success" disabled={toggle.isPending} onClick={() => toggle.mutate(true)}>{user.status === 'LOCKED' ? 'Unlock' : 'Enable'}</Button>}
            </>
          )}
        </Stack>
        <Stack direction="row" spacing={1}>
          <Button onClick={onClose}>{readOnly ? 'Close' : 'Cancel'}</Button>
          {!readOnly && (
            <Button variant="contained" disabled={save.isPending || !f.fullName.trim() || !f.username.trim() || !Object.keys(grants).length} onClick={() => save.mutate()}>
              {user ? 'Save changes' : 'Create account'}
            </Button>
          )}
        </Stack>
      </DialogActions>
    </Dialog>
  );
}

function PasswordIssued({ issued, onClose }: { issued: IssuedPassword | null; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  if (!issued) return null;
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Temporary password for {issued.user.fullName}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 2 }}>
          Give this to {issued.user.fullName.split(' ')[0]} with the username <b>{issued.user.username}</b>. They choose their own password when they first sign in.
          It is not shown again.
        </Typography>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ p: 1.5, bgcolor: 'action.hover', borderRadius: 2 }}>
          <Typography data-testid="temporary-password" sx={{ fontFamily: 'ui-monospace, Menlo, Consolas, monospace', fontSize: 20, letterSpacing: 1, flex: 1 }}>{issued.temporaryPassword}</Typography>
          <Button size="small" startIcon={<CopyIcon />} onClick={() => { navigator.clipboard?.writeText(issued.temporaryPassword); setCopied(true); }}>{copied ? 'Copied' : 'Copy'}</Button>
        </Stack>
      </DialogContent>
      <DialogActions><Button variant="contained" onClick={onClose}>Done</Button></DialogActions>
    </Dialog>
  );
}
