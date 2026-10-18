import AddIcon from '@mui/icons-material/Add';
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesomeOutlined';
import BookmarkAddIcon from '@mui/icons-material/BookmarkAddOutlined';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import EditIcon from '@mui/icons-material/Edit';
import SearchIcon from '@mui/icons-material/Search';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Divider, FormControlLabel, FormGroup,
  InputAdornment, MenuItem, Stack, Switch, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow, Tabs, TextField,
  ToggleButton, ToggleButtonGroup, Typography, Paper,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Fragment, useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, download, formatDateTime, uploadForm } from '../../api/client';
import { ROLE_LABEL } from '../../auth/access';
import { tr } from '../../locale/ui';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import HelpCentre from '../../components/helpdesk/HelpCentre';
import {
  CloseRequest, Conversation, DueText, FilePicker, PRIORITY_LABEL, PriorityChip, ReplyBox, STATUS_LABEL, SUPPORT_CATEGORY, TicketStatusChip, formatPhone, messageForm,
} from '../../components/helpdesk/parts';
import type {
  Article, AssistantStats, Category, Counts, Gap, StaffSuggestions, SuggestedReply, SupportRow, SupportView, TicketRow, TicketView,
} from '../../components/helpdesk/types';

const staff = { realm: 'staff' as const };
const OFFICES = ['ADMISSIONS_OFFICER', 'REGISTRAR', 'BURSAR', 'ACCOUNTANT', 'EXAMINATIONS_OFFICER', 'HOSTEL_WARDEN', 'IPT_COORDINATOR', 'CLEARANCE_OFFICER',
  'SPONSOR_OFFICER', 'DEPUTY_PRINCIPAL_ACADEMIC', 'INSTITUTION_ADMIN'];
const REQUESTER_LABEL: Record<string, string> = { APPLICANT: 'Applicant', STUDENT: 'Student', STAFF: 'Staff', GUEST: 'No account' };
const officeLabel = (r: string) => (r === 'INSTITUTION_ADMIN' ? 'ICT / Administrator' : ROLE_LABEL[r] ?? r);
const STAFF_STATUSES = [{ value: 'WAITING', label: 'Wait for the requester' }, { value: 'RESOLVED', label: 'Mark resolved' }];

/** Staff who need help raise it here (to ICT or another office). */
export function StaffHelpPage() {
  return (
    <>
      <PageTitle title="Ask for help" subtitle="Problems with CIMS, your account or anything else: ask the office that can help, and follow the answer here." />
      <HelpCentre mode="staff" />
    </>
  );
}

/** The helpdesk: requests to the offices of the signed-in person, how well they are answered, the answers people read, and the categories. */
export function HelpdeskPage() {
  const { user } = useSession();
  const roles = user('staff')?.roles ?? [];
  const admin = roles.includes('INSTITUTION_ADMIN');
  const [params, setParams] = useSearchParams();
  const tab = params.get('tab') ?? 'requests';
  const setTab = (v: string) => { const p = new URLSearchParams(params); p.set('tab', v); setParams(p, { replace: true }); };
  return (
    <>
      <PageTitle title="Helpdesk" subtitle="Requests from applicants, students, staff and the public help page, for your office." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2.5 }} variant="scrollable" allowScrollButtonsMobile>
        <Tab value="requests" label="Requests" />
        <Tab value="performance" label="Performance" />
        <Tab value="answers" label="Answers" />
        <Tab value="categories" label="Categories" />
        {admin && <Tab value="msingi" label="Msingi support" />}
      </Tabs>
      {tab === 'requests' && <Queue />}
      {tab === 'performance' && <PerformanceTab />}
      {tab === 'answers' && <ArticlesTab />}
      {tab === 'categories' && <CategoriesTab admin={admin} />}
      {tab === 'msingi' && admin && <MsingiSupportTab />}
    </>
  );
}

// ------------------------------------------------------------------ the queue

const VIEWS: { value: string; label: string; count?: keyof Counts }[] = [
  { value: 'open', label: 'Open', count: 'open' }, { value: 'mine', label: 'Assigned to me', count: 'mine' },
  { value: 'unassigned', label: 'Not assigned', count: 'unassigned' }, { value: 'overdue', label: 'Overdue', count: 'overdue' },
  { value: 'done', label: 'Resolved & closed' }, { value: 'all', label: 'All' },
];

function Queue() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const view = params.get('view') ?? 'open';
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  const [categoryId, setCategoryId] = useState('');
  const [page, setPage] = useState(0);
  useEffect(() => { const h = setTimeout(() => { setQuery(q); setPage(0); }, 300); return () => clearTimeout(h); }, [q]);
  const categories = useQuery({ queryKey: ['helpdesk', 'categories'], queryFn: () => api<Category[]>('/api/helpdesk/categories', staff) });
  const list = useQuery({
    queryKey: ['helpdesk', 'tickets', view, categoryId, query, page], refetchInterval: 60_000,
    queryFn: () => api<{ rows: TicketRow[]; total: number; counts: Counts }>(`/api/helpdesk/tickets?view=${view}&page=${page}&size=25`
      + (categoryId ? `&categoryId=${categoryId}` : '') + (query ? `&q=${encodeURIComponent(query)}` : ''), staff),
  });
  const counts = list.data?.counts;
  return (
    <Card>
      <CardContent>
        <Stack spacing={1.5} sx={{ mb: 2 }}>
          <ToggleButtonGroup size="small" exclusive value={view} sx={{ flexWrap: 'wrap' }}
            onChange={(_, v) => { if (v) { const p = new URLSearchParams(params); p.set('view', v); setParams(p, { replace: true }); setPage(0); } }}>
            {VIEWS.map((v) => (
              <ToggleButton key={v.value} value={v.value} sx={{ textTransform: 'none' }}>
                {v.label}{v.count && counts ? <Chip size="small" label={counts[v.count]} sx={{ ml: 1, height: 20 }} color={v.count === 'overdue' && counts.overdue ? 'error' : 'default'} /> : null}
              </ToggleButton>
            ))}
          </ToggleButtonGroup>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
            <TextField select size="small" label="Category" value={categoryId} onChange={(e) => { setCategoryId(e.target.value); setPage(0); }} sx={{ minWidth: 200 }}>
              <MenuItem value="">All categories</MenuItem>
              {categories.data?.map((c) => <MenuItem key={c.id} value={String(c.id)}>{c.name}</MenuItem>)}
            </TextField>
            <TextField size="small" placeholder="Number, subject, name or phone" value={q} onChange={(e) => setQ(e.target.value)} sx={{ minWidth: 300 }}
              InputProps={{ startAdornment: <InputAdornment position="start"><SearchIcon /></InputAdornment> }} inputProps={{ 'aria-label': tr('Search requests') }} />
          </Stack>
        </Stack>
        {list.isLoading ? <Loading /> : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Request</TableCell><TableCell>Requester</TableCell><TableCell>Category</TableCell><TableCell>Status</TableCell>
                  <TableCell>Assigned to</TableCell><TableCell>Target</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {list.data?.rows.map((t) => (
                  <TableRow key={t.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/helpdesk/${t.id}`)}>
                    <TableCell sx={{ maxWidth: 240 }}>
                      <Stack direction="row" spacing={1} alignItems="center"><Typography fontWeight={600} noWrap title={t.subject}>{t.subject}</Typography><PriorityChip priority={t.priority} /></Stack>
                      <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>{t.ticketNo}</Typography>
                    </TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      <Typography variant="body2">{t.requesterName}</Typography>
                      <Typography variant="caption" color="text.secondary">{REQUESTER_LABEL[t.requesterType]}</Typography>
                    </TableCell>
                    <TableCell sx={{ minWidth: 140 }}>{t.categoryName}</TableCell>
                    <TableCell><TicketStatusChip status={t.status} /></TableCell>
                    <TableCell>{t.assigneeName ?? <Typography variant="body2" color="text.secondary">—</Typography>}</TableCell>
                    <TableCell><DueText {...t} /></TableCell>
                  </TableRow>
                ))}
                {list.data?.rows.length === 0 && (
                  <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 2 }}>No requests here.</Typography></TableCell></TableRow>
                )}
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
  );
}

// ------------------------------------------------------------------ one request

export function HelpdeskTicketPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const { user } = useSession();
  const admin = user('staff')?.roles.includes('INSTITUTION_ADMIN');
  const key = ['helpdesk', 'ticket', id];
  const q = useQuery({ queryKey: key, queryFn: () => api<TicketView>(`/api/helpdesk/tickets/${id}`, staff), refetchInterval: 60_000 });
  const agents = useQuery({ queryKey: ['helpdesk', 'agents', id, q.data?.ticket.officeRole], enabled: !!q.data?.canManage,
    queryFn: () => api<{ id: number; name: string }[]>(`/api/helpdesk/tickets/${id}/agents`, staff) });
  const categories = useQuery({ queryKey: ['helpdesk', 'categories'], queryFn: () => api<Category[]>('/api/helpdesk/categories', staff) });
  const done = (v: TicketView | undefined) => {
    qc.invalidateQueries({ queryKey: ['helpdesk', 'tickets'] });
    if (v) qc.setQueryData(key, v); else navigate('/staff/helpdesk');   // moved to an office this person does not answer for
  };
  const message = useMutation({
    mutationFn: ({ body, files, internal, statusAfter }: { body: string; files: File[]; internal: boolean; statusAfter?: string }) =>
      uploadForm<TicketView>(`/api/helpdesk/tickets/${id}/messages`, 'staff', messageForm(body, files, { internal: String(internal), statusAfter }), 'POST'),
    onSuccess: done,
  });
  const act = useMutation({ mutationFn: ({ path, body }: { path: string; body: unknown }) => api<TicketView | undefined>(`/api/helpdesk/tickets/${id}/${path}`, { ...staff, method: 'POST', body }), onSuccess: done });
  const [escalating, setEscalating] = useState(false);
  const [draft, setDraft] = useState<{ text: string; n: number }>();
  const [saving, setSaving] = useState<Partial<Article> | null>(null);
  if (!q.data) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  const v = q.data;
  const t = v.ticket;
  const me = user('staff')?.id;
  const open = ['OPEN', 'IN_PROGRESS', 'WAITING'].includes(t.status);
  return (
    <>
      <Button startIcon={<ArrowBackIcon />} onClick={() => navigate('/staff/helpdesk')} sx={{ mb: 1 }}>Helpdesk</Button>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'flex-start' }} spacing={1} sx={{ mb: 2 }}>
        <Box>
          <Typography variant="h5" component="h1">{t.subject}</Typography>
          <Typography color="text.secondary"><Box component="span" sx={{ fontFamily: 'monospace' }}>{t.ticketNo}</Box> · {t.categoryName} · {formatDateTime(t.createdAt)}</Typography>
        </Box>
        <Stack direction="row" spacing={1} alignItems="center"><PriorityChip priority={t.priority} /><TicketStatusChip status={t.status} /><DueText {...t} /></Stack>
      </Stack>
      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, md: 8 }}>
          <Stack spacing={2}>
            <Conversation messages={v.messages} ourSide="STAFF" onOpen={(a) => download(`/api/helpdesk/attachments/${a.id}`, 'staff', a.fileName)}
              actions={(m) => v.canManage && m.authorType === 'STAFF' && !m.internal && m.body.length >= 15 && (
                <Button size="small" startIcon={<BookmarkAddIcon />} sx={{ mt: 0.5, ml: -0.5 }} onClick={() => setSaving(answerFrom(t, m.body))}>Save as an answer</Button>
              )} />
            {v.canManage && open && <SuggestedReplies ticket={t} onUse={(r) => setDraft({ text: r.text, n: Date.now() })}
              onMove={(categoryId) => act.mutate({ path: 'route', body: { categoryId } })} />}
            {v.canManage && (
              <ReplyBox staff statuses={STAFF_STATUSES} sending={message.isPending} placeholder={`Reply to ${t.requesterName}`} draft={draft}
                onSend={(body, files, o) => message.mutateAsync({ body, files, internal: o.internal, statusAfter: o.statusAfter })} />
            )}
            {!v.canManage && <Alert severity="info">You can read this request; the office that answers it replies.</Alert>}
            <ErrorAlert error={message.error ?? act.error} />
          </Stack>
        </Grid>
        <Grid size={{ xs: 12, md: 4 }}>
          <Card>
            <CardContent>
              <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>{t.requesterName}</Typography>
              <Typography variant="body2" color="text.secondary">{REQUESTER_LABEL[t.requesterType]}</Typography>
              {v.requesterPhone && <Typography variant="body2" sx={{ mt: 0.5 }}>{formatPhone(v.requesterPhone)}</Typography>}
              {v.requesterEmail && <Typography variant="body2">{v.requesterEmail}</Typography>}
              <Divider sx={{ my: 1.5 }} />
              <Detail label="Answered by" value={officeLabel(t.officeRole)} />
              <Detail label="First reply" value={t.firstResponseAt ? formatDateTime(t.firstResponseAt) : `due ${formatDateTime(t.responseDueAt)}`} />
              <Detail label="Resolve by" value={formatDateTime(t.resolutionDueAt)} />
              {t.escalationLevel > 0 && <Detail label="Escalated" value={t.escalationLevel === 1 ? 'Office reminded' : 'Administrators told'} />}
              {t.satisfied != null && <Detail label="Requester says" value={t.satisfied ? 'The answer helped' : 'The answer did not help'} />}
              {v.msingiTicketNo && <Detail label="Sent to Msingi" value={v.msingiTicketNo} />}
              {v.canManage && (
                <Stack spacing={1.5} sx={{ mt: 2 }}>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <TextField select size="small" fullWidth label="Assigned to" value={t.assigneeUserId ? String(t.assigneeUserId) : ''}
                      onChange={(e) => act.mutate({ path: 'assign', body: { userId: e.target.value ? Number(e.target.value) : null } })}>
                      <MenuItem value="">Nobody</MenuItem>
                      {agents.data?.map((a) => <MenuItem key={a.id} value={String(a.id)}>{a.name}</MenuItem>)}
                    </TextField>
                    {t.assigneeUserId !== me && agents.data?.some((a) => a.id === me) && (
                      <Button size="small" onClick={() => act.mutate({ path: 'assign', body: { userId: me } })} sx={{ whiteSpace: 'nowrap' }}>Take it</Button>
                    )}
                  </Stack>
                  <TextField select size="small" label="Status" value={t.status} onChange={(e) => act.mutate({ path: 'status', body: { status: e.target.value } })}>
                    {Object.entries(STATUS_LABEL).map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
                  </TextField>
                  <TextField select size="small" label="Priority" value={t.priority} onChange={(e) => act.mutate({ path: 'priority', body: { priority: e.target.value } })}>
                    {Object.entries(PRIORITY_LABEL).map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
                  </TextField>
                  {['OPEN', 'IN_PROGRESS', 'WAITING'].includes(t.status) && (
                    <TextField select size="small" label="Move to another category" value={String(t.categoryId)}
                      helperText="The request goes to the office that answers that category"
                      onChange={(e) => act.mutate({ path: 'route', body: { categoryId: Number(e.target.value) } })}>
                      {categories.data?.filter((c) => c.active || c.id === t.categoryId).map((c) => (
                        <MenuItem key={c.id} value={String(c.id)}>{c.name} · {officeLabel(c.officeRole)}</MenuItem>
                      ))}
                    </TextField>
                  )}
                  {admin && !v.msingiTicketNo && t.status !== 'CLOSED' && (
                    <Button variant="outlined" size="small" onClick={() => setEscalating(true)}>Send to Msingi support</Button>
                  )}
                </Stack>
              )}
            </CardContent>
          </Card>
        </Grid>
      </Grid>
      {escalating && <EscalateDialog ticketId={t.id} onClose={() => setEscalating(false)} onDone={() => { setEscalating(false); q.refetch(); }} />}
      <ArticleDialog editing={saving} onClose={() => setSaving(null)} />
    </>
  );
}

/** A new answer prefilled from a reply on a request: the question is the subject, for the kind of person who asked. */
const answerFrom = (t: TicketRow, body: string): Partial<Article> => ({
  title: t.subject, body, categoryId: t.categoryId, published: true,
  forApplicants: t.requesterType === 'APPLICANT' || t.requesterType === 'GUEST', forStudents: t.requesterType === 'STUDENT',
  forStaff: t.requesterType === 'STAFF', forPublic: t.requesterType === 'GUEST',
});

/**
 * Replies the assistant suggests for a request: published answers that match it and replies that solved similar requests before.
 * "Use" puts the text in the reply box to adjust before sending. When the request seems to belong to another category, says so.
 */
function SuggestedReplies({ ticket, onUse, onMove }: { ticket: TicketRow; onUse: (r: SuggestedReply) => void; onMove: (categoryId: number) => void }) {
  const q = useQuery({ queryKey: ['helpdesk', 'suggestions', ticket.id, ticket.categoryId], staleTime: 5 * 60_000,
    queryFn: () => api<StaffSuggestions>(`/api/helpdesk/tickets/${ticket.id}/suggestions`, staff) });
  const [shown, setShown] = useState(2);
  const used = useMutation({ mutationFn: (id: number) => api(`/api/helpdesk/suggestions/${id}/used`, { ...staff, method: 'POST' }) });
  const use = (r: SuggestedReply) => { onUse(r); if (r.source === 'PAST_REPLY') used.mutate(r.sourceId); };
  const guess = q.data?.category;
  const replies = q.data?.replies ?? [];
  if (!replies.length && !(guess && guess.id !== ticket.categoryId)) return null;
  return (
    <Box role="region" aria-label={tr('Suggested replies')} sx={{ border: 1, borderColor: 'success.light', bgcolor: '#F1F8F4', borderRadius: 2, p: 1.5 }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
        <AutoAwesomeIcon fontSize="small" color="success" />
        <Typography fontWeight={600}>Suggested replies</Typography>
      </Stack>
      {guess && guess.id !== ticket.categoryId && (
        <Alert severity="info" sx={{ mb: 1 }} action={<Button color="inherit" size="small" onClick={() => onMove(guess.id)}>Move</Button>}>
          {`This looks like ${guess.name}.`}
        </Alert>
      )}
      <Stack spacing={1}>
        {replies.slice(0, shown).map((r) => (
          <Paper key={`${r.source}${r.sourceId}`} variant="outlined" sx={{ p: 1.25, borderRadius: 1.5 }}>
            <Stack direction="row" spacing={1} alignItems="flex-start" justifyContent="space-between">
              <Box sx={{ minWidth: 0 }}>
                <Typography variant="body2" fontWeight={600}>{r.title}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {r.source === 'ANSWER' ? 'Published answer' : r.confirmed ? `Solved request ${r.ticketNo}` : `Reply on request ${r.ticketNo}`}
                  {' · '}{`${Math.round(r.confidence * 100)}% match`}
                </Typography>
              </Box>
              <Button size="small" variant="outlined" onClick={() => use(r)} sx={{ flexShrink: 0 }}>Use</Button>
            </Stack>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, whiteSpace: 'pre-wrap', display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
              {r.text}
            </Typography>
          </Paper>
        ))}
      </Stack>
      {replies.length > shown && <Button size="small" sx={{ mt: 0.5 }} onClick={() => setShown(replies.length)}>{`Show ${replies.length - shown} more`}</Button>}
    </Box>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <Stack direction="row" justifyContent="space-between" spacing={2} sx={{ py: 0.25 }}>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography variant="body2" sx={{ textAlign: 'right' }}>{value}</Typography>
    </Stack>
  );
}

// ------------------------------------------------------------------ performance

interface Stats { categoryId?: number; name: string; received: number; resolved: number; open: number; late: number; averageFirstReplyHours?: number;
  repliedInTimePercent?: number; resolvedInTimePercent?: number; satisfiedPercent?: number }

function PerformanceTab() {
  const [days, setDays] = useState(30);
  const q = useQuery({ queryKey: ['helpdesk', 'performance', days],
    queryFn: () => api<{ overall: Stats; categories: Stats[]; assistant?: AssistantStats }>(`/api/helpdesk/performance?days=${days}`, staff) });
  const pct = (n?: number) => (n == null ? '—' : `${n}%`);
  const row = (s: Stats, bold = false) => (
    <TableRow key={s.categoryId ?? 'all'} sx={bold ? { '& td': { fontWeight: 700 } } : undefined}>
      <TableCell>{s.name === 'All' ? 'All categories' : s.name}</TableCell>
      <TableCell align="right">{s.received}</TableCell><TableCell align="right">{s.resolved}</TableCell><TableCell align="right">{s.open}</TableCell>
      <TableCell align="right" sx={s.late ? { color: 'error.main' } : undefined}>{s.late}</TableCell>
      <TableCell align="right">{s.averageFirstReplyHours == null ? '—' : s.averageFirstReplyHours < 1 ? `${Math.max(1, Math.round(s.averageFirstReplyHours * 60))} min` : `${s.averageFirstReplyHours} h`}</TableCell>
      <TableCell align="right">{pct(s.repliedInTimePercent)}</TableCell><TableCell align="right">{pct(s.resolvedInTimePercent)}</TableCell>
      <TableCell align="right">{pct(s.satisfiedPercent)}</TableCell>
    </TableRow>
  );
  return (
    <Card>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }}>
          <Typography variant="h6">How requests are answered</Typography>
          <TextField select size="small" label="Period" value={days} onChange={(e) => setDays(Number(e.target.value))} sx={{ width: 190, flexShrink: 0 }}>
            <MenuItem value={7}>Last 7 days</MenuItem><MenuItem value={30}>Last 30 days</MenuItem><MenuItem value={90}>Last 90 days</MenuItem><MenuItem value={365}>Last 12 months</MenuItem>
          </TextField>
        </Stack>
        {!q.data ? <Loading /> : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Category</TableCell><TableCell align="right">Received</TableCell><TableCell align="right">Resolved</TableCell><TableCell align="right">Open</TableCell>
                  <TableCell align="right">Overdue</TableCell><TableCell align="right">Average first reply</TableCell><TableCell align="right">Replied in time</TableCell>
                  <TableCell align="right">Resolved in time</TableCell><TableCell align="right">Helped</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {q.data.categories.filter((c) => c.received > 0).map((c) => row(c))}
                {row(q.data.overall, true)}
              </TableBody>
            </Table>
          </TableContainer>
        )}
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>
          Requests raised in the period, in the categories you answer. "Helped" is what requesters said when they closed their request.
        </Typography>
        <ErrorAlert error={q.error} />
        {q.data?.assistant && <AssistantFigures a={q.data.assistant} />}
      </CardContent>
    </Card>
  );
}

/** What the assistant did in the period. */
function AssistantFigures({ a }: { a: AssistantStats }) {
  const share = (n: number, of: number) => (of ? `${Math.round((100 * n) / of)}%` : '—');
  const figures = [
    { value: String(a.solvedBeforeAsking), label: 'Solved before asking', hint: 'People who found the answer while writing and did not send a request' },
    { value: String(a.instantAnswers), label: 'Answered at once', hint: 'Requests that got a suggested answer as soon as they were sent' },
    { value: share(a.instantAccepted, a.instantAccepted + a.instantRejected), label: 'Answer at once solved it', hint: `${a.instantAccepted} yes, ${a.instantRejected} no` },
    { value: share(a.categoryRight, a.categoryGuessed), label: 'Category guessed right', hint: 'The category stayed the one the assistant predicted' },
    { value: String(a.learnedReplies), label: 'Replies learned', hint: 'Replies from solved requests, now suggested to staff' },
  ];
  return (
    <Box sx={{ mt: 3 }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
        <AutoAwesomeIcon fontSize="small" color="success" />
        <Typography variant="subtitle1" fontWeight={600}>The assistant</Typography>
      </Stack>
      <Grid container spacing={1.5}>
        {figures.map((f) => (
          <Grid key={f.label} size={{ xs: 6, sm: 4, md: 2.4 }}>
            <Paper variant="outlined" sx={{ p: 1.5, height: '100%' }} title={tr(f.hint)}>
              <Typography variant="h5" fontWeight={700}>{f.value}</Typography>
              <Typography variant="body2">{f.label}</Typography>
              <Typography variant="caption" color="text.secondary">{f.hint}</Typography>
            </Paper>
          </Grid>
        ))}
      </Grid>
    </Box>
  );
}

// ------------------------------------------------------------------ answers (knowledge base)

const AUDIENCES = [{ key: 'forApplicants', label: 'Applicants' }, { key: 'forStudents', label: 'Students' }, { key: 'forStaff', label: 'Staff' },
  { key: 'forPublic', label: 'Public help page' }] as const;

function ArticlesTab() {
  const q = useQuery({ queryKey: ['helpdesk', 'articles'], queryFn: () => api<Article[]>('/api/helpdesk/articles', staff) });
  const [editing, setEditing] = useState<Partial<Article> | null>(null);
  return (
    <Stack spacing={2.5}>
    <Gaps onWrite={(g) => setEditing({ title: g.question, body: g.answer ?? '', categoryId: g.categoryId, forApplicants: true, forStudents: true, published: true })} />
    <Card>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Box>
            <Typography variant="h6">Answers to common questions</Typography>
            <Typography variant="body2" color="text.secondary">Shown on the help pages before people ask, so they find the answer themselves.</Typography>
          </Box>
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setEditing({ forApplicants: true, published: true })}>Add an answer</Button>
        </Stack>
        {!q.data ? <Loading /> : (
          <TableContainer>
            <Table size="small">
              <TableHead><TableRow><TableCell>Question</TableCell><TableCell>Who reads it</TableCell><TableCell align="right">Read</TableCell><TableCell align="right">Helped / not</TableCell><TableCell /></TableRow></TableHead>
              <TableBody>
                {q.data.map((a) => (
                  <TableRow key={a.id}>
                    <TableCell>
                      <Stack direction="row" spacing={1} alignItems="center"><Typography fontWeight={600}>{a.title}</Typography>{!a.published && <Chip size="small" label="Draft" />}</Stack>
                      <Typography variant="caption" color="text.secondary">{a.categoryName ?? 'General'} · {a.updatedByName}, {formatDateTime(a.updatedAt)}</Typography>
                    </TableCell>
                    <TableCell>{AUDIENCES.filter((x) => a[x.key]).map((x, i) => <Fragment key={x.key}>{i > 0 && ', '}{x.label}</Fragment>)}</TableCell>
                    <TableCell align="right">{a.views}</TableCell>
                    <TableCell align="right">{a.helpful} / {a.notHelpful}</TableCell>
                    <TableCell align="right">{a.canEdit && <Button size="small" startIcon={<EditIcon />} onClick={() => setEditing(a)}>Edit</Button>}</TableCell>
                  </TableRow>
                ))}
                {q.data.length === 0 && <TableRow><TableCell colSpan={5}><Typography color="text.secondary" sx={{ py: 2 }}>No answers yet. Add the questions your office is asked most often.</Typography></TableCell></TableRow>}
              </TableBody>
            </Table>
          </TableContainer>
        )}
        <ErrorAlert error={q.error} />
      </CardContent>
      <ArticleDialog editing={editing} onClose={() => setEditing(null)} />
    </Card>
    </Stack>
  );
}

/** Writing or changing an answer (also from a reply on a request, or from a question asked often). */
function ArticleDialog({ editing: start, onClose }: { editing: Partial<Article> | null; onClose: () => void }) {
  const qc = useQueryClient();
  const categories = useQuery({ queryKey: ['helpdesk', 'categories'], queryFn: () => api<Category[]>('/api/helpdesk/categories', staff) });
  const [editing, setEditing] = useState<Partial<Article> | null>(start);
  useEffect(() => setEditing(start), [start]);
  const finished = () => { onClose(); qc.invalidateQueries({ queryKey: ['helpdesk', 'articles'] }); qc.invalidateQueries({ queryKey: ['helpdesk', 'gaps'] }); };
  const save = useMutation({
    mutationFn: (a: Partial<Article>) => api(a.id ? `/api/helpdesk/articles/${a.id}` : '/api/helpdesk/articles', { ...staff, method: a.id ? 'PUT' : 'POST',
      body: { categoryId: a.categoryId ?? null, title: a.title, body: a.body, forApplicants: !!a.forApplicants, forStudents: !!a.forStudents, forStaff: !!a.forStaff,
        forPublic: !!a.forPublic, published: !!a.published } }),
    onSuccess: finished,
  });
  const remove = useMutation({ mutationFn: (id: number) => api(`/api/helpdesk/articles/${id}`, { ...staff, method: 'DELETE' }), onSuccess: finished });
  return (
      <Dialog open={!!editing} onClose={onClose} fullWidth maxWidth="md">
        <DialogTitle>{editing?.id ? 'Edit the answer' : 'Add an answer'}</DialogTitle>
        {editing && (
          <DialogContent dividers>
            <Stack spacing={2}>
              <TextField label="Question" required value={editing.title ?? ''} onChange={(e) => setEditing({ ...editing, title: e.target.value })} inputProps={{ maxLength: 200 }}
                placeholder="e.g. I paid but the payment does not show. What do I do?" />
              <TextField label="Answer" required multiline minRows={6} value={editing.body ?? ''} onChange={(e) => setEditing({ ...editing, body: e.target.value })} inputProps={{ maxLength: 10000 }} />
              <TextField select label="Category" value={editing.categoryId ? String(editing.categoryId) : ''} onChange={(e) => setEditing({ ...editing, categoryId: e.target.value ? Number(e.target.value) : undefined })}
                helperText="The office that answers the category keeps this answer up to date">
                <MenuItem value="">General (administrator)</MenuItem>
                {categories.data?.map((c) => <MenuItem key={c.id} value={String(c.id)}>{c.name}</MenuItem>)}
              </TextField>
              <Box>
                <Typography variant="subtitle2">Who can read it</Typography>
                <FormGroup row>
                  {AUDIENCES.map((x) => (
                    <FormControlLabel key={x.key} control={<Checkbox checked={!!editing[x.key]} onChange={(e) => setEditing({ ...editing, [x.key]: e.target.checked })} />} label={x.label} />
                  ))}
                </FormGroup>
              </Box>
              <FormControlLabel control={<Switch checked={!!editing.published} onChange={(e) => setEditing({ ...editing, published: e.target.checked })} />} label="Published (shown on the help pages)" />
              <ErrorAlert error={save.error ?? remove.error} />
            </Stack>
          </DialogContent>
        )}
        <DialogActions>
          {editing?.id && <Button color="error" onClick={() => remove.mutate(editing.id!)} disabled={remove.isPending} sx={{ mr: 'auto' }}>Delete</Button>}
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="contained" disabled={save.isPending || !editing?.title?.trim() || !editing?.body?.trim()} onClick={() => editing && save.mutate(editing)}>Save</Button>
        </DialogActions>
      </Dialog>
  );
}

/** Questions asked several times that no answer covers yet, grouped by what they are about. */
function Gaps({ onWrite }: { onWrite: (g: Gap) => void }) {
  const [days, setDays] = useState(90);
  const q = useQuery({ queryKey: ['helpdesk', 'gaps', days], queryFn: () => api<Gap[]>(`/api/helpdesk/gaps?days=${days}`, staff) });
  if (!q.data?.length && !q.error) return null;
  return (
    <Card sx={{ borderColor: 'success.light' }} variant="outlined">
      <CardContent>
        <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={1} sx={{ mb: 1 }}>
          <Box>
            <Stack direction="row" spacing={1} alignItems="center">
              <AutoAwesomeIcon fontSize="small" color="success" />
              <Typography variant="h6">Asked often, no answer yet</Typography>
            </Stack>
            <Typography variant="body2" color="text.secondary">Requests that are alike and that no published answer covers. Writing an answer lets people solve these themselves.</Typography>
          </Box>
          <TextField select size="small" label="Period" value={days} onChange={(e) => setDays(Number(e.target.value))} sx={{ width: 190, flexShrink: 0 }}>
            <MenuItem value={30}>Last 30 days</MenuItem><MenuItem value={90}>Last 90 days</MenuItem><MenuItem value={365}>Last 12 months</MenuItem>
          </TextField>
        </Stack>
        <Stack divider={<Divider flexItem />}>
          {q.data?.map((g) => (
            <Stack key={g.topic + g.latest} direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }} sx={{ py: 1.25 }}>
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Typography fontWeight={600}>{g.question}</Typography>
                  <Chip size="small" label={`${g.requests} requests`} />
                  {g.categoryName && <Typography variant="caption" color="text.secondary">{g.categoryName}</Typography>}
                </Stack>
                <Typography variant="caption" color="text.secondary" component="div" sx={{ overflowWrap: 'anywhere' }}>
                  {g.examples.filter((e) => e !== g.question).map((e, i) => <Fragment key={i}>{i > 0 && ' · '}“{e}”</Fragment>)}
                </Typography>
              </Box>
              <Button size="small" variant="outlined" startIcon={<AddIcon />} onClick={() => onWrite(g)} sx={{ flexShrink: 0 }}>Write an answer</Button>
            </Stack>
          ))}
        </Stack>
        <ErrorAlert error={q.error} />
      </CardContent>
    </Card>
  );
}

// ------------------------------------------------------------------ categories

const WHO = [{ key: 'forApplicants', label: 'Applicants' }, { key: 'forStudents', label: 'Students' }, { key: 'forStaff', label: 'Staff' },
  { key: 'forGuests', label: 'No account' }] as const;

function CategoriesTab({ admin }: { admin: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['helpdesk', 'categories'], queryFn: () => api<Category[]>('/api/helpdesk/categories', staff) });
  const [editing, setEditing] = useState<Partial<Category> | null>(null);
  const save = useMutation({
    mutationFn: (c: Partial<Category>) => api(c.id ? `/api/helpdesk/categories/${c.id}` : '/api/helpdesk/categories', { ...staff, method: c.id ? 'PUT' : 'POST',
      body: { name: c.name, description: c.description ?? null, officeRole: c.officeRole, forApplicants: !!c.forApplicants, forStudents: !!c.forStudents,
        forStaff: !!c.forStaff, forGuests: !!c.forGuests, responseHours: Number(c.responseHours), resolutionHours: Number(c.resolutionHours), active: c.active !== false,
        sortOrder: Number(c.sortOrder ?? 100) } }),
    onSuccess: () => { setEditing(null); qc.invalidateQueries({ queryKey: ['helpdesk', 'categories'] }); },
  });
  return (
    <Card>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Box>
            <Typography variant="h6">What people can ask about</Typography>
            <Typography variant="body2" color="text.secondary">Each category goes to an office, with the time to a first reply and to resolve it.</Typography>
          </Box>
          {admin && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setEditing({ officeRole: 'REGISTRAR', forStudents: true, responseHours: 8, resolutionHours: 72, active: true, sortOrder: 100 })}>Add a category</Button>}
        </Stack>
        {!q.data ? <Loading /> : (
          <TableContainer>
            <Table size="small">
              <TableHead><TableRow><TableCell>Category</TableCell><TableCell>Answered by</TableCell><TableCell>Who can choose it</TableCell><TableCell align="right">First reply</TableCell><TableCell align="right">Resolve</TableCell><TableCell align="right">Open</TableCell>{admin && <TableCell />}</TableRow></TableHead>
              <TableBody>
                {q.data.map((c) => (
                  <TableRow key={c.id} sx={!c.active ? { opacity: 0.55 } : undefined}>
                    <TableCell>
                      <Stack direction="row" spacing={1} alignItems="center"><Typography fontWeight={600}>{c.name}</Typography>{!c.active && <Chip size="small" label="Not used" />}</Stack>
                      {c.description && <Typography variant="caption" color="text.secondary">{c.description}</Typography>}
                    </TableCell>
                    <TableCell>{officeLabel(c.officeRole)}</TableCell>
                    <TableCell>{WHO.filter((w) => c[w.key]).map((w, i) => <Fragment key={w.key}>{i > 0 && ', '}{w.label}</Fragment>)}</TableCell>
                    <TableCell align="right">{c.responseHours} h</TableCell>
                    <TableCell align="right">{c.resolutionHours} h</TableCell>
                    <TableCell align="right">{c.openTickets}</TableCell>
                    {admin && <TableCell align="right"><Button size="small" startIcon={<EditIcon />} onClick={() => setEditing(c)}>Edit</Button></TableCell>}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
        <ErrorAlert error={q.error} />
      </CardContent>
      <Dialog open={!!editing} onClose={() => setEditing(null)} fullWidth maxWidth="sm">
        <DialogTitle>{editing?.id ? 'Edit the category' : 'Add a category'}</DialogTitle>
        {editing && (
          <DialogContent dividers>
            <Stack spacing={2}>
              <TextField label="Name" required value={editing.name ?? ''} onChange={(e) => setEditing({ ...editing, name: e.target.value })} inputProps={{ maxLength: 100 }} />
              <TextField label="Description" value={editing.description ?? ''} onChange={(e) => setEditing({ ...editing, description: e.target.value })} inputProps={{ maxLength: 300 }}
                helperText="Shown to people choosing it" />
              <TextField select label="Answered by" value={editing.officeRole ?? ''} onChange={(e) => setEditing({ ...editing, officeRole: e.target.value })}
                helperText={editing.id ? 'Open requests of this category move to the new office' : 'Staff with this role see and answer these requests'}>
                {OFFICES.map((o) => <MenuItem key={o} value={o}>{officeLabel(o)}</MenuItem>)}
              </TextField>
              <Box>
                <Typography variant="subtitle2">Who can choose it</Typography>
                <FormGroup row>
                  {WHO.map((w) => <FormControlLabel key={w.key} control={<Checkbox checked={!!editing[w.key]} onChange={(e) => setEditing({ ...editing, [w.key]: e.target.checked })} />} label={w.label} />)}
                </FormGroup>
              </Box>
              <Stack direction="row" spacing={2}>
                <TextField type="number" label="First reply within (hours)" value={editing.responseHours ?? ''} onChange={(e) => setEditing({ ...editing, responseHours: Number(e.target.value) })} fullWidth inputProps={{ min: 1, max: 720 }} />
                <TextField type="number" label="Resolve within (hours)" value={editing.resolutionHours ?? ''} onChange={(e) => setEditing({ ...editing, resolutionHours: Number(e.target.value) })} fullWidth inputProps={{ min: 1, max: 2160 }} />
              </Stack>
              <Stack direction="row" spacing={2} alignItems="center">
                <TextField type="number" label="Order in the list" value={editing.sortOrder ?? 100} onChange={(e) => setEditing({ ...editing, sortOrder: Number(e.target.value) })} sx={{ maxWidth: 180 }} />
                <FormControlLabel control={<Switch checked={editing.active !== false} onChange={(e) => setEditing({ ...editing, active: e.target.checked })} />} label="In use" />
              </Stack>
              <ErrorAlert error={save.error} />
            </Stack>
          </DialogContent>
        )}
        <DialogActions>
          <Button onClick={() => setEditing(null)}>Cancel</Button>
          <Button variant="contained" disabled={save.isPending || !editing?.name?.trim()} onClick={() => editing && save.mutate(editing)}>Save</Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}

// ------------------------------------------------------------------ Msingi support (administrators)


function MsingiSupportTab() {
  const [open, setOpen] = useState<number | null>(null);
  const [raising, setRaising] = useState(false);
  const q = useQuery({ queryKey: ['msingi-support'], queryFn: () => api<SupportRow[]>('/api/msingi-support/tickets', staff) });
  if (open != null) return <MsingiTicket id={open} onBack={() => setOpen(null)} />;
  return (
    <Stack spacing={2.5}>
      <Card>
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
            <Box>
              <Typography variant="h6">Support from Msingi Technologies</Typography>
              <Typography variant="body2" color="text.secondary">For problems your ICT office cannot solve: system faults, integrations, data corrections, training. Urgent: first reply within 1 hour.</Typography>
            </Box>
            <Button variant="contained" startIcon={<AddIcon />} onClick={() => setRaising(true)}>Raise a ticket</Button>
          </Stack>
          {!q.data ? <Loading /> : !q.data.length ? <Typography color="text.secondary">No tickets yet.</Typography> : (
            <TableContainer>
              <Table size="small">
                <TableHead><TableRow><TableCell>Ticket</TableCell><TableCell>About</TableCell><TableCell>Raised by</TableCell><TableCell>Status</TableCell><TableCell>Msingi</TableCell><TableCell>Updated</TableCell></TableRow></TableHead>
                <TableBody>
                  {q.data.map((s) => (
                    <TableRow key={s.id} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(s.id)}>
                      <TableCell>
                        <Stack direction="row" spacing={1} alignItems="center"><Typography fontWeight={600}>{s.subject}</Typography><PriorityChip priority={s.priority} /></Stack>
                        <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>{s.ticketNo}</Typography>
                      </TableCell>
                      <TableCell>{SUPPORT_CATEGORY[s.category] ?? s.category}</TableCell>
                      <TableCell>{s.raisedByName}</TableCell>
                      <TableCell><TicketStatusChip status={s.status} /></TableCell>
                      <TableCell>{s.assigneeName ?? '—'}</TableCell>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(s.updatedAt)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
          <ErrorAlert error={q.error} />
        </CardContent>
      </Card>
      {raising && <RaiseSupport onCancel={() => setRaising(false)} onDone={(id) => { setRaising(false); setOpen(id); }} />}
    </Stack>
  );
}

function RaiseSupport({ onCancel, onDone }: { onCancel: () => void; onDone: (id: number) => void }) {
  const qc = useQueryClient();
  const [f, setF] = useState({ category: 'PROBLEM', priority: 'NORMAL', subject: '', body: '' });
  const [files, setFiles] = useState<File[]>([]);
  const send = useMutation({
    mutationFn: () => uploadForm<SupportView>('/api/msingi-support/tickets', 'staff', messageForm(f.body, files, { category: f.category, priority: f.priority, subject: f.subject }), 'POST'),
    onSuccess: (v) => { qc.invalidateQueries({ queryKey: ['msingi-support'] }); onDone(v.ticket.id); },
  });
  return (
    <Card>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 2 }}>Raise a ticket to Msingi</Typography>
        <Stack spacing={2}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
            <TextField select label="About" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })} fullWidth>
              {Object.entries(SUPPORT_CATEGORY).map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
            </TextField>
            <TextField select label="Priority" value={f.priority} onChange={(e) => setF({ ...f, priority: e.target.value })} fullWidth
              helperText={f.priority === 'URGENT' ? 'Only when work has stopped (e.g. payments not coming in)' : f.priority === 'HIGH' ? 'First reply within 4 hours' : 'First reply within 8 hours'}>
              {Object.entries(PRIORITY_LABEL).map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
            </TextField>
          </Stack>
          <TextField label="Subject" required value={f.subject} onChange={(e) => setF({ ...f, subject: e.target.value })} inputProps={{ maxLength: 200 }} />
          <TextField label="What is happening" required multiline minRows={5} value={f.body} onChange={(e) => setF({ ...f, body: e.target.value })} inputProps={{ maxLength: 5000 }}
            helperText="When it started, who is affected, what you see (screenshots help). Never send passwords." />
          <FilePicker files={files} onChange={setFiles} />
          <ErrorAlert error={send.error} />
          <Stack direction="row" spacing={1} justifyContent="flex-end">
            <Button onClick={onCancel}>Cancel</Button>
            <Button variant="contained" disabled={send.isPending || f.subject.trim().length < 3 || !f.body.trim()} onClick={() => send.mutate()}>Send to Msingi</Button>
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  );
}

function MsingiTicket({ id, onBack }: { id: number; onBack: () => void }) {
  const qc = useQueryClient();
  const key = ['msingi-support', id];
  const q = useQuery({ queryKey: key, queryFn: () => api<SupportView>(`/api/msingi-support/tickets/${id}`, staff), refetchInterval: 60_000 });
  const done = (v: SupportView) => { qc.setQueryData(key, v); qc.invalidateQueries({ queryKey: ['msingi-support'] }); };
  const reply = useMutation({ mutationFn: ({ body, files }: { body: string; files: File[] }) => uploadForm<SupportView>(`/api/msingi-support/tickets/${id}/messages`, 'staff', messageForm(body, files), 'POST'), onSuccess: done });
  const close = useMutation({ mutationFn: (satisfied: boolean | null) => api<SupportView>(`/api/msingi-support/tickets/${id}/close`, { ...staff, method: 'POST', body: { satisfied } }), onSuccess: done });
  if (!q.data) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  const v = q.data;
  const t = v.ticket;
  return (
    <Stack spacing={2}>
      <Box><Button startIcon={<ArrowBackIcon />} onClick={onBack}>All Msingi tickets</Button></Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
        <Box>
          <Typography variant="h6">{t.subject}</Typography>
          <Typography variant="body2" color="text.secondary">
            <Box component="span" sx={{ fontFamily: 'monospace' }}>{t.ticketNo}</Box> · {SUPPORT_CATEGORY[t.category]} · {t.raisedByName}, {formatDateTime(t.createdAt)}
            {t.helpdeskTicketNo && ` · from ${t.helpdeskTicketNo}`}
          </Typography>
        </Box>
        <Stack direction="row" spacing={1} alignItems="center"><PriorityChip priority={t.priority} /><TicketStatusChip status={t.status} /></Stack>
      </Stack>
      {t.status === 'WAITING' && <Alert severity="warning">Msingi is waiting for your reply.</Alert>}
      <Conversation messages={v.messages} ourSide="INSTITUTION" onOpen={(a) => download(`/api/msingi-support/attachments/${a.id}`, 'staff', a.fileName)} />
      {v.canReply && <ReplyBox onSend={(body, files) => reply.mutateAsync({ body, files })} sending={reply.isPending} placeholder="Reply to Msingi" />}
      {v.canClose && <CloseRequest busy={close.isPending} onClose={(s) => close.mutate(s)} />}
      <ErrorAlert error={reply.error ?? close.error} />
    </Stack>
  );
}

function EscalateDialog({ ticketId, onClose, onDone }: { ticketId: number; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ category: 'PROBLEM', priority: 'NORMAL', note: '' });
  const send = useMutation({ mutationFn: () => api(`/api/msingi-support/escalate/${ticketId}`, { ...staff, method: 'POST', body: f }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Send to Msingi support</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          <Typography variant="body2" color="text.secondary">Msingi sees the subject and your note, not the requester's conversation. Their answer comes under Helpdesk › Msingi support.</Typography>
          <TextField select label="About" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })}>
            {Object.entries(SUPPORT_CATEGORY).map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
          </TextField>
          <TextField select label="Priority" value={f.priority} onChange={(e) => setF({ ...f, priority: e.target.value })}>
            {Object.entries(PRIORITY_LABEL).map(([k, l]) => <MenuItem key={k} value={k}>{l}</MenuItem>)}
          </TextField>
          <TextField label="What Msingi should know" multiline minRows={4} value={f.note} onChange={(e) => setF({ ...f, note: e.target.value })} inputProps={{ maxLength: 4000 }} />
          <ErrorAlert error={send.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={send.isPending || !f.note.trim()} onClick={() => send.mutate()}>Send</Button>
      </DialogActions>
    </Dialog>
  );
}
