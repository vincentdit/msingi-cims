import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import SearchIcon from '@mui/icons-material/Search';
import {
  Box, Button, Card, CardContent, Chip, Divider, InputAdornment, Link, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead,
  TablePagination, TableRow, TextField, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, download, formatDateTime, uploadForm } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { tr } from '../../locale/ui';
import { Conversation, DueText, PRIORITY_LABEL, PriorityChip, ReplyBox, STATUS_LABEL, SUPPORT_CATEGORY, TicketStatusChip, formatPhone, messageForm } from '../../components/helpdesk/parts';
import type { Counts, SupportRow, SupportView } from '../../components/helpdesk/types';
import { platform, usePlatformSession, type InstitutionRow } from './shared';

const VIEWS: { value: string; label: string; count?: keyof Counts }[] = [
  { value: 'open', label: 'Open', count: 'open' }, { value: 'mine', label: 'Assigned to me', count: 'mine' },
  { value: 'unassigned', label: 'Not assigned', count: 'unassigned' }, { value: 'overdue', label: 'Overdue', count: 'overdue' },
  { value: 'done', label: 'Resolved & closed' }, { value: 'all', label: 'All' },
];

/** Support tickets from every institution's administrators, for Msingi staff. */
export function SupportPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const view = params.get('view') ?? 'open';
  const tenantId = params.get('tenantId') ?? '';
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  useEffect(() => { const h = setTimeout(() => { setQuery(q); setPage(0); }, 300); return () => clearTimeout(h); }, [q]);
  const institutions = useQuery({ queryKey: ['platform', 'institutions'], queryFn: () => api<InstitutionRow[]>('/api/platform/institutions', platform) });
  const list = useQuery({
    queryKey: ['platform', 'support', view, tenantId, query, page], refetchInterval: 60_000,
    queryFn: () => api<{ rows: SupportRow[]; total: number; counts: Counts }>(`/api/platform/support/tickets?view=${view}&page=${page}&size=25`
      + (tenantId ? `&tenantId=${tenantId}` : '') + (query ? `&q=${encodeURIComponent(query)}` : ''), platform),
  });
  const set = (k: string, v: string) => { const p = new URLSearchParams(params); if (v) p.set(k, v); else p.delete(k); setParams(p, { replace: true }); setPage(0); };
  const counts = list.data?.counts;
  return (
    <>
      <PageTitle title="Support" subtitle="Tickets from institutions' administrators. Urgent: reply within 1 hour; high: 4 hours; normal: 8 hours." />
      <Card>
        <CardContent>
          <Stack direction={{ xs: 'column', lg: 'row' }} spacing={1.5} justifyContent="space-between" alignItems={{ lg: 'center' }} sx={{ mb: 2 }}>
            <ToggleButtonGroup size="small" exclusive value={view} onChange={(_, v) => v && set('view', v)} sx={{ flexWrap: 'wrap' }}>
              {VIEWS.map((v) => (
                <ToggleButton key={v.value} value={v.value} sx={{ textTransform: 'none' }}>
                  {v.label}{v.count && counts ? <Chip size="small" label={counts[v.count]} sx={{ ml: 1, height: 20 }} color={v.count === 'overdue' && counts.overdue ? 'error' : 'default'} /> : null}
                </ToggleButton>
              ))}
            </ToggleButtonGroup>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
              <TextField select size="small" label="Institution" value={tenantId} onChange={(e) => set('tenantId', e.target.value)} sx={{ minWidth: 220 }}>
                <MenuItem value="">All institutions</MenuItem>
                {institutions.data?.map((i) => <MenuItem key={i.id} value={String(i.id)}>{i.name}</MenuItem>)}
              </TextField>
              <TextField size="small" placeholder="Number, subject or name" value={q} onChange={(e) => setQ(e.target.value)}
                InputProps={{ startAdornment: <InputAdornment position="start"><SearchIcon /></InputAdornment> }} inputProps={{ 'aria-label': tr('Search tickets') }} />
            </Stack>
          </Stack>
          {list.isLoading ? <Loading /> : (
            <TableContainer>
              <Table size="small">
                <TableHead>
                  <TableRow><TableCell>Ticket</TableCell><TableCell>Institution</TableCell><TableCell>About</TableCell><TableCell>Status</TableCell><TableCell>Assigned to</TableCell><TableCell>Target</TableCell></TableRow>
                </TableHead>
                <TableBody>
                  {list.data?.rows.map((s) => (
                    <TableRow key={s.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/platform/support/${s.id}`)}>
                      <TableCell sx={{ maxWidth: 240 }}>
                        <Stack direction="row" spacing={1} alignItems="center"><Typography fontWeight={600} noWrap title={s.subject}>{s.subject}</Typography><PriorityChip priority={s.priority} /></Stack>
                        <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>{s.ticketNo}</Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">{s.institutionName}</Typography>
                        <Typography variant="caption" color="text.secondary">{s.raisedByName}</Typography>
                      </TableCell>
                      <TableCell>{SUPPORT_CATEGORY[s.category] ?? s.category}</TableCell>
                      <TableCell><TicketStatusChip status={s.status} /></TableCell>
                      <TableCell>{s.assigneeName ?? '—'}</TableCell>
                      <TableCell><DueText {...s} /></TableCell>
                    </TableRow>
                  ))}
                  {list.data?.rows.length === 0 && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 2 }}>No tickets here.</Typography></TableCell></TableRow>}
                </TableBody>
              </Table>
            </TableContainer>
          )}
          {(list.data?.total ?? 0) > 25 && (
            <TablePagination component="div" count={list.data?.total ?? 0} page={page} rowsPerPage={25} rowsPerPageOptions={[25]} onPageChange={(_, p) => setPage(p)} />
          )}
          <ErrorAlert error={list.error} />
        </CardContent>
      </Card>
    </>
  );
}

/** One support ticket: the conversation with the institution, Msingi's own notes, assignment, priority and status. */
export function SupportTicketPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const me = usePlatformSession().me;
  const key = ['platform', 'support', 'ticket', id];
  const q = useQuery({ queryKey: key, queryFn: () => api<SupportView>(`/api/platform/support/tickets/${id}`, platform), refetchInterval: 60_000 });
  const agents = useQuery({ queryKey: ['platform', 'support', 'agents'], queryFn: () => api<{ id: number; name: string }[]>('/api/platform/support/agents', platform) });
  const done = (v: SupportView) => { qc.setQueryData(key, v); qc.invalidateQueries({ queryKey: ['platform', 'support'] }); };
  const message = useMutation({
    mutationFn: ({ body, files, internal, statusAfter }: { body: string; files: File[]; internal: boolean; statusAfter?: string }) =>
      uploadForm<SupportView>(`/api/platform/support/tickets/${id}/messages`, 'platform', messageForm(body, files, { internal: String(internal), statusAfter }), 'POST'),
    onSuccess: done,
  });
  const act = useMutation({ mutationFn: ({ path, body }: { path: string; body: unknown }) => api<SupportView>(`/api/platform/support/tickets/${id}/${path}`, { ...platform, method: 'POST', body }), onSuccess: done });
  if (!q.data) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  const v = q.data;
  const t = v.ticket;
  const myId = me?.id;
  return (
    <>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/platform/support')} sx={{ mb: 1 }}>Support</Button>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'flex-start' }} spacing={1} sx={{ mb: 2 }}>
        <Box>
          <Typography variant="h5" component="h1">{t.subject}</Typography>
          <Typography color="text.secondary">
            <Box component="span" sx={{ fontFamily: 'monospace' }}>{t.ticketNo}</Box> · {SUPPORT_CATEGORY[t.category] ?? t.category} · {formatDateTime(t.createdAt)}
            {t.helpdeskTicketNo && ` · from the institution's ${t.helpdeskTicketNo}`}
          </Typography>
        </Box>
        <Stack direction="row" spacing={1} alignItems="center"><PriorityChip priority={t.priority} /><TicketStatusChip status={t.status} /><DueText {...t} /></Stack>
      </Stack>
      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, md: 8 }}>
          <Stack spacing={2}>
            <Conversation messages={v.messages} ourSide="MSINGI" onOpen={(a) => download(`/api/platform/support/attachments/${a.id}`, 'platform', a.fileName)} />
            <ReplyBox staff sending={message.isPending} placeholder={`Reply to ${t.raisedByName} (${t.institutionCode})`}
              statuses={[{ value: 'WAITING', label: 'Wait for the institution' }, { value: 'RESOLVED', label: 'Mark resolved' }]}
              onSend={(body, files, o) => message.mutateAsync({ body, files, internal: o.internal, statusAfter: o.statusAfter })} />
            <ErrorAlert error={message.error ?? act.error} />
          </Stack>
        </Grid>
        <Grid size={{ xs: 12, md: 4 }}>
          <Card>
            <CardContent>
              <Link component={RouterLink} to={`/platform/institutions/${t.tenantId}`} variant="subtitle1" sx={{ fontWeight: 600 }}>{t.institutionName}</Link>
              <Typography variant="body2" color="text.secondary" sx={{ fontFamily: 'monospace' }}>{t.institutionCode}</Typography>
              <Divider sx={{ my: 1.5 }} />
              <Typography variant="body2" sx={{ fontWeight: 600 }}>{t.raisedByName}</Typography>
              {v.raisedByEmail && <Typography variant="body2">{v.raisedByEmail}</Typography>}
              {v.raisedByPhone && <Typography variant="body2">{formatPhone(v.raisedByPhone)}</Typography>}
              <Divider sx={{ my: 1.5 }} />
              <Typography variant="body2" color="text.secondary">First reply {t.firstResponseAt ? formatDateTime(t.firstResponseAt) : `due ${formatDateTime(t.responseDueAt)}`}</Typography>
              <Typography variant="body2" color="text.secondary">Resolve by {formatDateTime(t.resolutionDueAt)}</Typography>
              {t.satisfied != null && <Typography variant="body2" sx={{ mt: 0.5 }}>The institution says: {t.satisfied ? 'the answer helped' : 'the answer did not help'}</Typography>}
              <Stack spacing={1.5} sx={{ mt: 2 }}>
                <Stack direction="row" spacing={1} alignItems="center">
                  <TextField select size="small" fullWidth label="Assigned to" value={t.assigneeId ? String(t.assigneeId) : ''}
                    onChange={(e) => act.mutate({ path: 'assign', body: { platformUserId: e.target.value ? Number(e.target.value) : null } })}>
                    <MenuItem value="">Nobody</MenuItem>
                    {agents.data?.map((a) => <MenuItem key={a.id} value={String(a.id)}>{a.name}</MenuItem>)}
                  </TextField>
                  {myId && t.assigneeId !== myId && <Button size="small" onClick={() => act.mutate({ path: 'assign', body: { platformUserId: myId } })} sx={{ whiteSpace: 'nowrap' }}>Take it</Button>}
                </Stack>
                <TextField select size="small" label="Status" value={t.status} onChange={(e) => act.mutate({ path: 'status', body: { status: e.target.value } })}>
                  {Object.entries(STATUS_LABEL).map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
                </TextField>
                <TextField select size="small" label="Priority" value={t.priority} onChange={(e) => act.mutate({ path: 'priority', body: { priority: e.target.value } })}
                  helperText="The targets follow the priority, counted from when the ticket was raised">
                  {Object.entries(PRIORITY_LABEL).map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
                </TextField>
              </Stack>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </>
  );
}
