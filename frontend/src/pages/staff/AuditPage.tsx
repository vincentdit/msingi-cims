import DownloadIcon from '@mui/icons-material/Download';
import HistoryIcon from '@mui/icons-material/History';
import SearchIcon from '@mui/icons-material/Search';
import {
  Alert, Autocomplete, Box, Button, Card, Chip, Container, Divider, Drawer, FormControlLabel, IconButton, InputAdornment, MenuItem, Stack,
  Switch, Tab, Tabs, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow, TextField, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { Fragment, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, download, formatDateTime } from '../../api/client';
import type { AuditEntry, AuditFacets, AuditSummary, Page } from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { CallsTable } from '../platform/PlatformPages';
import { HealthChip, MODE_LABEL, n, type IntegrationCall, type PageOf, type SystemHealth } from '../platform/shared';

const staff = { realm: 'staff' as const };

const VERBS: Record<string, string> = {
  CREATE: 'Created', UPDATE: 'Updated', DELETE: 'Deleted', UPLOAD: 'Uploaded', LOGIN: 'Signed in', LOGIN_FAILED: 'Sign-in failed',
  ACCOUNT_LOCKED: 'Account locked', PASSWORD_CHANGED: 'Changed their password', PASSWORD_RESET: 'Password reset', DISABLE: 'Disabled',
  ENABLE: 'Enabled', UNLOCK: 'Unlocked', AUDIT_EXPORTED: 'Exported the audit trail', OPEN: 'Opened', CLOSE: 'Closed', REOPEN: 'Reopened',
  PUBLISH: 'Published', SUBMIT: 'Submitted', START: 'Started', REGISTER: 'Registered',
};
const sentence = (s: string) => { const t = s.replace(/_/g, ' ').toLowerCase(); return t.charAt(0).toUpperCase() + t.slice(1); };
export const actionLabel = (a: string) => VERBS[a] ?? sentence(a);
export const entityLabel = (e: string) => sentence(e.replace(/([a-z])([A-Z])/g, '$1_$2'));
const keyLabel = (k: string) => sentence(k.replace(/([a-z])([A-Z])/g, '$1_$2'));
const value = (v: unknown): string => (v == null ? '—' : Array.isArray(v) ? v.map(value).join(', ') : typeof v === 'object' ? JSON.stringify(v) : String(v));
const iso = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
const daysAgo = (n: number) => { const d = new Date(); d.setDate(d.getDate() - n); return iso(d); };

type Filters = { from: string; to: string; area: string; action: string; actorId: number | null; q: string; security: boolean;
  entity?: string; entityId?: number };

/** The audit trail: every change made in CIMS, who made it, when and from where. For the Auditor and the administrator. */
function ChangesTrail({ tabs }: { tabs: React.ReactNode }) {
  const [period, setPeriod] = useState<'1' | '7' | '30' | 'custom'>('7');
  const [url] = useSearchParams();
  const [f, setF] = useState<Filters>({ from: daysAgo(6), to: iso(new Date()), area: '', action: '', q: '', security: false,
    actorId: url.get('actorId') ? Number(url.get('actorId')) : null,
    entity: url.get('entity') ?? undefined, entityId: url.get('entityId') ? Number(url.get('entityId')) : undefined });
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(50);
  const [open, setOpen] = useState<AuditEntry | null>(null);
  const history = !!(f.entity && f.entityId);

  const params = useMemo(() => {
    const p = new URLSearchParams();
    if (history) { p.set('entity', f.entity!); p.set('entityId', String(f.entityId)); } else {
      p.set('from', f.from); p.set('to', f.to);
      if (f.area) p.set('area', f.area);
      if (f.action) p.set('action', f.action);
      if (f.actorId) p.set('actorId', String(f.actorId));
      if (f.q) p.set('q', f.q);
      if (f.security) p.set('security', 'true');
    }
    return p;
  }, [f, history]);

  const logs = useQuery({ queryKey: ['audit', 'logs', params.toString(), page, size], placeholderData: keepPreviousData,
    queryFn: () => api<Page<AuditEntry>>(`/api/audit/logs?${params}&page=${page}&size=${size}`, staff) });
  const summary = useQuery({ queryKey: ['audit', 'summary', f.from, f.to], enabled: !history,
    queryFn: () => api<AuditSummary>(`/api/audit/summary?from=${f.from}&to=${f.to}`, staff) });
  const facets = useQuery({ queryKey: ['audit', 'facets'], queryFn: () => api<AuditFacets>('/api/audit/facets', staff), staleTime: 60_000 });
  const exportCsv = useMutation({ mutationFn: () => download(`/api/audit/logs.csv?${params}`, 'staff', `audit-trail-${f.from}-to-${f.to}.csv`) });

  const set = (patch: Partial<Filters>) => { setF({ ...f, ...patch }); setPage(0); };
  const choosePeriod = (p: typeof period) => {
    setPeriod(p);
    if (p !== 'custom') set({ from: daysAgo(Number(p) - 1), to: iso(new Date()) });
  };
  const s = summary.data;
  const actors = facets.data?.actors ?? [];
  const actor = actors.find((a) => Number(a.key) === f.actorId) ?? null;

  return (
    <Container maxWidth="xl" disableGutters>
      <PageTitle title="Audit trail"
        subtitle="Every change made in CIMS: who made it, when and from where. Entries cannot be changed or deleted, and exports are recorded too."
        action={<Button variant="outlined" sx={{ whiteSpace: 'nowrap' }} startIcon={<DownloadIcon />} disabled={exportCsv.isPending || !logs.data?.totalElements} onClick={() => exportCsv.mutate()}>
          {exportCsv.isPending ? 'Exporting…' : 'Export CSV'}
        </Button>} />
      {tabs}
      <ErrorAlert error={exportCsv.error} />

      {history ? (
        <Alert severity="info" icon={<HistoryIcon />} sx={{ mb: 2 }}
          action={<Button color="inherit" onClick={() => set({ entity: undefined, entityId: undefined })}>Back to the trail</Button>}>
          Full history of {entityLabel(f.entity!)} #{f.entityId}, all dates, oldest changes last.
        </Alert>
      ) : (
        <>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} alignItems={{ md: 'center' }} sx={{ mb: 2 }}>
            <ToggleButtonGroup size="small" exclusive value={period} onChange={(_, v) => v && choosePeriod(v)}>
              <ToggleButton value="1">Today</ToggleButton>
              <ToggleButton value="7">7 days</ToggleButton>
              <ToggleButton value="30">30 days</ToggleButton>
              <ToggleButton value="custom">Dates</ToggleButton>
            </ToggleButtonGroup>
            {period === 'custom' && (
              <Stack direction="row" spacing={1}>
                <TextField size="small" type="date" label="From" value={f.from} onChange={(e) => e.target.value && set({ from: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
                <TextField size="small" type="date" label="To" value={f.to} onChange={(e) => e.target.value && set({ to: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
              </Stack>
            )}
            {s && (
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                <Chip variant="outlined" label={`${s.actions.toLocaleString()} entries by ${s.people} ${s.people === 1 ? 'person' : 'people'}`} />
                <Chip variant="outlined" label={`${s.signIns} sign-ins`} onClick={() => set({ action: f.action === 'LOGIN' ? '' : 'LOGIN', area: '' })} />
                <Chip variant={f.action === 'LOGIN_FAILED' ? 'filled' : 'outlined'} color={s.failedSignIns ? 'error' : 'default'} label={`${s.failedSignIns} failed sign-ins`}
                  onClick={() => set({ action: f.action === 'LOGIN_FAILED' ? '' : 'LOGIN_FAILED', area: '' })} />
                {s.lockedAccounts > 0 && <Chip color="error" variant="outlined" label={`${s.lockedAccounts} accounts locked`} onClick={() => set({ action: 'ACCOUNT_LOCKED', area: '' })} />}
                <Chip variant="outlined" label={`${s.accountChanges} staff account changes`} onClick={() => set({ area: 'Security', action: '', q: '' })} />
              </Stack>
            )}
          </Stack>
          <Card sx={{ mb: 2 }}>
            <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} useFlexGap flexWrap="wrap" sx={{ p: 2 }} alignItems={{ md: 'center' }}>
              <TextField size="small" placeholder="Search names, records, details or IP address" value={search} sx={{ flexGrow: 1, minWidth: { md: 260 } }}
                onChange={(e) => setSearch(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && set({ q: search.trim() })} onBlur={() => search.trim() !== f.q && set({ q: search.trim() })}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment> } }} />
              <TextField size="small" select label="Area" value={f.area} onChange={(e) => set({ area: e.target.value })} sx={{ width: { md: 180 }, flexShrink: 0 }}>
                <MenuItem value="">All areas</MenuItem>
                {facets.data?.areas.map((a) => <MenuItem key={a} value={a}>{a}</MenuItem>)}
              </TextField>
              <Autocomplete size="small" options={actors} value={actor} getOptionLabel={(o) => o.label} isOptionEqualToValue={(a, b) => a.key === b.key}
                onChange={(_, v) => set({ actorId: v ? Number(v.key) : null })} sx={{ width: { md: 220 }, flexShrink: 0 }}
                renderInput={(p) => <TextField {...p} label="Person" />} />
              <TextField size="small" select label="Action" value={f.action} onChange={(e) => set({ action: e.target.value })} sx={{ width: { md: 220 }, flexShrink: 0 }}>
                <MenuItem value="">All actions</MenuItem>
                {facets.data?.actions.map((a) => <MenuItem key={a.key} value={a.key}>{actionLabel(a.key)}</MenuItem>)}
              </TextField>
              <FormControlLabel sx={{ flexShrink: 0, ml: 0 }} control={<Switch size="small" checked={f.security} onChange={(e) => set({ security: e.target.checked })} />} label="Security events" />
            </Stack>
          </Card>
        </>
      )}

      <Card>
        <ErrorAlert error={logs.error} />
        {logs.isLoading ? <Loading /> : (
          <>
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow><TableCell sx={{ width: 150 }}>When</TableCell><TableCell>Who</TableCell><TableCell>What</TableCell><TableCell>Record</TableCell><TableCell>Details</TableCell><TableCell>From</TableCell></TableRow>
                </TableHead>
                <TableBody>
                  {logs.data?.content.map((e) => (
                    <TableRow key={e.id} hover sx={{ cursor: 'pointer', verticalAlign: 'top' }} onClick={() => setOpen(e)}>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(e.occurredAt)}</TableCell>
                      <TableCell>{e.actorName ?? <Typography variant="body2" color="text.secondary">System</Typography>}</TableCell>
                      <TableCell>
                        <Typography variant="body2" sx={{ fontWeight: 600, color: e.action === 'LOGIN_FAILED' || e.action === 'ACCOUNT_LOCKED' ? 'error.main' : undefined }}>{actionLabel(e.action)}</Typography>
                        <Typography variant="caption" color="text.secondary">{e.area}</Typography>
                      </TableCell>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{entityLabel(e.entity)}{e.entityId != null && <Typography component="span" variant="body2" color="text.secondary"> #{e.entityId}</Typography>}</TableCell>
                      <TableCell sx={{ maxWidth: 380 }}>
                        <Typography variant="body2" color="text.secondary" sx={{ overflow: 'hidden', textOverflow: 'ellipsis', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical' }}>
                          {e.details ? Object.entries(e.details).map(([k, v]) => `${keyLabel(k)}: ${value(v)}`).join(' · ') : ''}
                        </Typography>
                      </TableCell>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}><Typography variant="body2" color="text.secondary">{e.ipAddress ?? '—'}</Typography></TableCell>
                    </TableRow>
                  ))}
                  {!logs.data?.content.length && (
                    <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 3 }}>Nothing recorded for these filters. Widen the dates or clear a filter.</Typography></TableCell></TableRow>
                  )}
                </TableBody>
              </Table>
            </TableContainer>
            <TablePagination component="div" count={logs.data?.totalElements ?? 0} page={page} rowsPerPage={size} rowsPerPageOptions={[25, 50, 100, 200]}
              onPageChange={(_, p) => setPage(p)} onRowsPerPageChange={(e) => { setSize(Number(e.target.value)); setPage(0); }} />
          </>
        )}
      </Card>

      <Drawer anchor="right" open={!!open} onClose={() => setOpen(null)} PaperProps={{ sx: { width: { xs: '100%', sm: 440 } } }}>
        {open && (
          <Box sx={{ p: 3 }}>
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
              <Box>
                <Typography variant="h6">{actionLabel(open.action)}</Typography>
                <Typography variant="body2" color="text.secondary">{entityLabel(open.entity)}{open.entityId != null ? ` #${open.entityId}` : ''} · {open.area}</Typography>
              </Box>
              <IconButton onClick={() => setOpen(null)} aria-label="Close"><CloseIcon /></IconButton>
            </Stack>
            <Divider sx={{ my: 2 }} />
            <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: '120px 1fr', rowGap: 1, columnGap: 2, '& dt': { color: 'text.secondary' }, '& dd': { m: 0, wordBreak: 'break-word' } }}>
              <dt>When</dt><dd>{formatDateTime(open.occurredAt)}</dd>
              <dt>Who</dt><dd>{open.actorName ?? 'System'}{open.actorUserId ? ` (user #${open.actorUserId})` : ''}</dd>
              <dt>From</dt><dd>{open.ipAddress ?? '—'}</dd>
              <dt>Entry</dt><dd>#{open.id}</dd>
              {open.details && Object.entries(open.details).map(([k, v]) => (<Fragment key={k}><dt>{keyLabel(k)}</dt><dd>{value(v)}</dd></Fragment>))}
            </Box>
            <Stack direction="row" spacing={1} sx={{ mt: 3 }} flexWrap="wrap" useFlexGap>
              {open.entityId != null && (
                <Button variant="outlined" startIcon={<HistoryIcon />} onClick={() => { set({ entity: open.entity, entityId: open.entityId }); setOpen(null); }}>
                  This record's history
                </Button>
              )}
              {open.actorUserId && (
                <Button onClick={() => { set({ actorId: open.actorUserId!, entity: undefined, entityId: undefined }); setOpen(null); }}>Everything this person did</Button>
              )}
            </Stack>
          </Box>
        )}
      </Drawer>
    </Container>
  );
}

/** The audit trail page: changes made in CIMS, and the institution's calls to outside systems. */
export default function AuditPage() {
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') === 'integrations' ? 'integrations' : 'changes';
  const tabs = (
    <Tabs value={tab} sx={{ mb: 2 }} onChange={(_, v) => { const p = new URLSearchParams(params); if (v === 'changes') p.delete('tab'); else p.set('tab', v); setParams(p); }}>
      <Tab value="changes" label="Changes" />
      <Tab value="integrations" label="Integration calls" />
    </Tabs>
  );
  return tab === 'changes' ? <ChangesTrail tabs={tabs} /> : <IntegrationLog tabs={tabs} />;
}

/** The institution's calls to NECTA, NACTVET, GePG and the SMS gateway (as NOAS keeps integration logs). */
function IntegrationLog({ tabs }: { tabs: React.ReactNode }) {
  const [system, setSystem] = useState('');
  const [outcome, setOutcome] = useState('');
  const [page, setPage] = useState(0);
  const health = useQuery({ queryKey: ['audit', 'integrations', 'health'], queryFn: () => api<SystemHealth[]>('/api/audit/integrations/health?hours=168', staff) });
  const qs = new URLSearchParams({ page: String(page), size: '50' });
  if (system) qs.set('system', system);
  if (outcome) qs.set('outcome', outcome);
  const calls = useQuery({ queryKey: ['audit', 'integrations', qs.toString()], placeholderData: keepPreviousData,
    queryFn: () => api<PageOf<IntegrationCall>>(`/api/audit/integrations?${qs}`, staff) });
  return (
    <Container maxWidth="xl" disableGutters>
      <PageTitle title="Audit trail" subtitle="Calls CIMS made for this institution to NECTA, NACTVET, GePG and the SMS gateway, and GePG payment notifications received. Only references are kept, never what was sent." />
      {tabs}
      <ErrorAlert error={health.error ?? calls.error} />
      <Stack direction="row" spacing={1.5} useFlexGap flexWrap="wrap" sx={{ mb: 2 }}>
        {(health.data ?? []).map((h) => (
          <Card key={h.system} sx={{ p: 1.5, minWidth: 210, flex: '1 1 210px' }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center"><Typography variant="body2" fontWeight={600}>{h.label}</Typography><HealthChip status={h.status} /></Stack>
            <Typography variant="body2" color="text.secondary">{n(h.calls)} calls · {n(h.failures)} failed · 7 days</Typography>
            <Typography variant="caption" color="text.secondary">{MODE_LABEL[h.mode] ?? h.mode}</Typography>
          </Card>
        ))}
      </Stack>
      <Card>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ p: 2, pb: 1 }}>
          <TextField select size="small" label="System" value={system} onChange={(e) => { setSystem(e.target.value); setPage(0); }} sx={{ minWidth: 240 }}
            slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
            <MenuItem value="">All systems</MenuItem>
            {(health.data ?? []).map((h) => <MenuItem key={h.system} value={h.system}>{h.label}</MenuItem>)}
          </TextField>
          <TextField select size="small" label="Outcome" value={outcome} onChange={(e) => { setOutcome(e.target.value); setPage(0); }} sx={{ minWidth: 150 }}
            slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
            <MenuItem value="">All</MenuItem><MenuItem value="FAILURE">Failed</MenuItem><MenuItem value="SUCCESS">Succeeded</MenuItem>
          </TextField>
        </Stack>
        {calls.isLoading ? <Loading /> : <CallsTable rows={calls.data?.content ?? []} />}
        <TablePagination component="div" count={calls.data?.totalElements ?? 0} page={page} onPageChange={(_, p) => setPage(p)} rowsPerPage={50} rowsPerPageOptions={[50]} />
      </Card>
    </Container>
  );
}
