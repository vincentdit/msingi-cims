import AddIcon from '@mui/icons-material/Add';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import SearchIcon from '@mui/icons-material/Search';
import ThumbDownIcon from '@mui/icons-material/ThumbDownOutlined';
import ThumbUpIcon from '@mui/icons-material/ThumbUpOutlined';
import {
  Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, Card, CardContent, Chip, InputAdornment, List, ListItemButton, MenuItem, Stack,
  TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ChangeEvent } from 'react';
import { api, download, formatDateTime, uploadForm, type Realm } from '../../api/client';
import { ErrorAlert, Loading } from '../common';
import { tr } from '../../locale/ui';
import { CloseRequest, Conversation, FilePicker, ReplyBox, TicketStatusChip, messageForm } from './parts';
import type { Article, Category, TicketRow, TicketView } from './types';

export type HelpMode = 'applicant' | 'student' | 'staff' | 'public';

interface GuestCreated { ticketNo: string; code: string; ticket: TicketView }

/**
 * Help for the people the institution serves: answers to common questions first, then their requests (raise one, follow the
 * conversation, reply with files, close it when solved). Without an account (mode "public") a request is made with a name and
 * mobile number and followed with its number and the code sent by SMS.
 */
export default function HelpCentre({ mode, intro }: { mode: HelpMode; intro?: string }) {
  const realm: Realm = mode === 'public' ? 'applicant' : mode;
  const base = mode === 'public' ? '/api/public/help' : '/api/help';
  const auth = mode === 'public' ? {} : { realm };
  const [openId, setOpenId] = useState<number | null>(null);
  const [asking, setAsking] = useState(false);
  const [guest, setGuest] = useState<{ ticketNo: string; code: string } | null>(null);
  const [created, setCreated] = useState<GuestCreated | null>(null);

  if (mode !== 'public' && openId != null) return <RequestDetail id={openId} realm={realm} onBack={() => setOpenId(null)} />;
  if (mode === 'public' && guest) return <GuestRequest access={guest} onBack={() => setGuest(null)} />;

  return (
    <Stack spacing={3}>
      {intro && <Typography color="text.secondary">{intro}</Typography>}
      <Answers base={base} auth={auth} />
      {mode === 'public' ? (
        <Grid container spacing={3}>
          <Grid size={{ xs: 12, md: 7 }}>
            {created ? (
              <Alert severity="success" ref={(el) => el?.scrollIntoView({ behavior: 'smooth', block: 'center' })} action={<Button color="inherit" onClick={() => { setGuest({ ticketNo: created.ticketNo, code: created.code }); setCreated(null); }}>Open it</Button>}>
                <Typography fontWeight={600}>We have received your request {created.ticketNo}.</Typography>
                <Typography variant="body2">Keep this code to follow it: <Box component="strong" sx={{ fontFamily: 'monospace', fontSize: 18, letterSpacing: 2 }}>{created.code}</Box></Typography>
                <Typography variant="body2">We have also sent the number and the code by SMS.</Typography>
              </Alert>
            ) : (
              <AskForm mode={mode} base={base} realm={realm} onDone={(r) => setCreated(r as GuestCreated)} />
            )}
          </Grid>
          <Grid size={{ xs: 12, md: 5 }}><FollowForm onOpen={setGuest} /></Grid>
        </Grid>
      ) : (
        <>
          <MyRequests realm={realm} onOpen={setOpenId} onAsk={() => setAsking(true)} />
          {asking && <AskForm mode={mode} base={base} realm={realm} onCancel={() => setAsking(false)}
            onDone={(r) => { setAsking(false); setOpenId((r as TicketView).ticket.id); }} />}
        </>
      )}
    </Stack>
  );
}

// ------------------------------------------------------------------ answers (knowledge base)

function Answers({ base, auth }: { base: string; auth: { realm?: Realm } }) {
  const [q, setQ] = useState('');
  const [query, setQuery] = useState('');
  useEffect(() => { const h = setTimeout(() => setQuery(q), 300); return () => clearTimeout(h); }, [q]);
  const list = useQuery({ queryKey: ['help', base, 'articles', query], queryFn: () => api<Article[]>(`${base}/articles?q=${encodeURIComponent(query)}`, auth) });
  const [voted, setVoted] = useState<Record<number, boolean>>({});
  const vote = useMutation({ mutationFn: ({ id, helpful }: { id: number; helpful: boolean }) => api(`${base}/articles/${id}/vote`, { ...auth, method: 'POST', body: { helpful } }),
    onSuccess: (_, v) => setVoted((x) => ({ ...x, [v.id]: v.helpful })) });
  const opened = (id: number) => { api(`${base}/articles/${id}`, auth).catch(() => undefined); };
  if (!list.data?.length && !query) return null;
  return (
    <Card>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 1 }}>Answers to common questions</Typography>
        <TextField fullWidth size="small" placeholder="Search the answers, e.g. control number" value={q} onChange={(e) => setQ(e.target.value)}
          InputProps={{ startAdornment: <InputAdornment position="start"><SearchIcon /></InputAdornment> }} inputProps={{ 'aria-label': tr('Search the answers') }} sx={{ mb: 1.5 }} />
        {list.data?.length === 0 && <Typography color="text.secondary">No answer matches. Ask us below.</Typography>}
        {list.data?.map((a) => (
          <Accordion key={a.id} disableGutters variant="outlined" onChange={(_, open) => open && opened(a.id)} sx={{ '&:before': { display: 'none' }, mb: 0.75, borderRadius: 1 }}>
            <AccordionSummary expandIcon={<ExpandMoreIcon />}>
              <Stack spacing={0.25}>
                <Typography fontWeight={600}>{a.title}</Typography>
                {a.categoryName && <Typography variant="caption" color="text.secondary">{a.categoryName}</Typography>}
              </Stack>
            </AccordionSummary>
            <AccordionDetails>
              <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{a.body}</Typography>
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mt: 1.5 }}>
                {voted[a.id] !== undefined ? <Typography variant="caption" color="text.secondary">Thank you for telling us.</Typography> : (
                  <>
                    <Typography variant="caption" color="text.secondary">Did this help?</Typography>
                    <Button size="small" startIcon={<ThumbUpIcon />} onClick={() => vote.mutate({ id: a.id, helpful: true })}>Yes</Button>
                    <Button size="small" startIcon={<ThumbDownIcon />} onClick={() => vote.mutate({ id: a.id, helpful: false })}>No</Button>
                  </>
                )}
              </Stack>
            </AccordionDetails>
          </Accordion>
        ))}
      </CardContent>
    </Card>
  );
}

// ------------------------------------------------------------------ the person's own requests

function MyRequests({ realm, onOpen, onAsk }: { realm: Realm; onOpen: (id: number) => void; onAsk: () => void }) {
  const q = useQuery({ queryKey: ['help', realm, 'mine'], queryFn: () => api<TicketRow[]>('/api/help/tickets', { realm }) });
  return (
    <Card>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }} spacing={1}>
          <Typography variant="h6">My requests</Typography>
          <Button variant="contained" startIcon={<AddIcon />} onClick={onAsk}>Ask for help</Button>
        </Stack>
        {q.isLoading ? <Loading /> : !q.data?.length ? (
          <Typography color="text.secondary">You have not asked for help yet. If the answers above do not help, ask us and the right office will reply.</Typography>
        ) : (
          <List disablePadding>
            {q.data.map((t) => (
              <ListItemButton key={t.id} divider onClick={() => onOpen(t.id)} sx={{ px: 1, alignItems: 'flex-start' }}>
                <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                  <Typography fontWeight={600} sx={{ overflowWrap: 'anywhere' }}>{t.subject}</Typography>
                  <Typography variant="caption" color="text.secondary" component="div">
                    <Box component="span" sx={{ fontFamily: 'monospace' }}>{t.ticketNo}</Box> · {t.categoryName} · {formatDateTime(t.updatedAt)}
                  </Typography>
                </Box>
                <Box sx={{ ml: 1, flexShrink: 0 }}><TicketStatusChip status={t.status} /></Box>
              </ListItemButton>
            ))}
          </List>
        )}
        <ErrorAlert error={q.error} />
      </CardContent>
    </Card>
  );
}

function AskForm({ mode, base, realm, onDone, onCancel }: {
  mode: HelpMode; base: string; realm: Realm; onDone: (r: TicketView | GuestCreated) => void; onCancel?: () => void;
}) {
  const qc = useQueryClient();
  const categories = useQuery({ queryKey: ['help', base, 'categories'], queryFn: () => api<Category[]>(`${base}/categories`, mode === 'public' ? {} : { realm }) });
  const [f, setF] = useState({ name: '', phone: '', email: '', categoryId: '', subject: '', body: '' });
  const [files, setFiles] = useState<File[]>([]);
  const set = (k: keyof typeof f) => (e: ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });
  const chosen = categories.data?.find((c) => String(c.id) === f.categoryId);
  const send = useMutation({
    mutationFn: () => {
      const form = messageForm(f.body, files, { categoryId: f.categoryId, subject: f.subject,
        ...(mode === 'public' ? { name: f.name, phone: f.phone, email: f.email } : {}) });
      return uploadForm<TicketView | GuestCreated>(`${base}/tickets`, realm, form, 'POST');
    },
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ['help', realm, 'mine'] }); onDone(r); },
  });
  const ready = f.categoryId && f.subject.trim().length >= 3 && f.body.trim() && (mode !== 'public' || (f.name.trim() && f.phone.trim()));
  return (
    <Card>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 0.5 }}>Ask for help</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {mode === 'public' ? 'No account yet, or cannot sign in? Tell us what is wrong. We reply by SMS and on this page.'
            : 'Choose what it is about, so it goes straight to the office that can answer.'}
        </Typography>
        <Stack spacing={2}>
          {mode === 'public' && (
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField label="Your name" required value={f.name} onChange={set('name')} fullWidth inputProps={{ maxLength: 150 }} />
              <TextField label="Mobile number" required value={f.phone} onChange={set('phone')} fullWidth placeholder="0712 345 678" helperText="We send the request number and a code here" />
            </Stack>
          )}
          {mode === 'public' && <TextField label="Email (optional)" type="email" value={f.email} onChange={set('email')} inputProps={{ maxLength: 120 }} />}
          <TextField select label="What is it about?" required value={f.categoryId} onChange={set('categoryId')}
            helperText={chosen ? <>{chosen.description}{chosen.description ? ' · ' : ''}{`We aim to reply within ${chosen.responseHours} hours`}</> : undefined}>
            {categories.data?.map((c) => <MenuItem key={c.id} value={String(c.id)}>{c.name}</MenuItem>)}
          </TextField>
          <TextField label="Subject" required value={f.subject} onChange={set('subject')} inputProps={{ maxLength: 200 }} placeholder="e.g. My control number has not arrived" />
          <TextField label="Describe the problem" required multiline minRows={4} value={f.body} onChange={set('body')} inputProps={{ maxLength: 5000 }}
            helperText="Say what you tried and what you see. Do not write your password." />
          <FilePicker files={files} onChange={setFiles} />
          <ErrorAlert error={send.error ?? categories.error} />
          <Stack direction="row" spacing={1} justifyContent="flex-end">
            {onCancel && <Button onClick={onCancel}>Cancel</Button>}
            <Button variant="contained" disabled={!ready || send.isPending} onClick={() => send.mutate()}>Send request</Button>
          </Stack>
        </Stack>
      </CardContent>
    </Card>
  );
}

function TicketHeader({ v, onBack, back = 'All my requests' }: { v: TicketView; onBack: () => void; back?: string }) {
  const t = v.ticket;
  return (
    <Box>
      <Button startIcon={<ArrowBackIcon />} onClick={onBack} sx={{ mb: 1 }}>{back}</Button>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }} justifyContent="space-between">
        <Box>
          <Typography variant="h6">{t.subject}</Typography>
          <Typography variant="body2" color="text.secondary"><Box component="span" sx={{ fontFamily: 'monospace' }}>{t.ticketNo}</Box> · {t.categoryName} · {formatDateTime(t.createdAt)}</Typography>
        </Box>
        <TicketStatusChip status={t.status} />
      </Stack>
      {t.status === 'WAITING' && <Alert severity="warning" sx={{ mt: 1.5 }}>The office is waiting for your reply.</Alert>}
      {t.status === 'RESOLVED' && v.canReply && <Alert severity="success" sx={{ mt: 1.5 }}>Marked resolved. If you still need help, reply within 7 days; otherwise close it.</Alert>}
    </Box>
  );
}

function RequestDetail({ id, realm, onBack }: { id: number; realm: Realm; onBack: () => void }) {
  const qc = useQueryClient();
  const key = ['help', realm, 'ticket', id];
  const q = useQuery({ queryKey: key, queryFn: () => api<TicketView>(`/api/help/tickets/${id}`, { realm }), refetchInterval: 60_000 });
  const done = (v: TicketView) => { qc.setQueryData(key, v); qc.invalidateQueries({ queryKey: ['help', realm, 'mine'] }); };
  const reply = useMutation({ mutationFn: ({ body, files }: { body: string; files: File[] }) => uploadForm<TicketView>(`/api/help/tickets/${id}/messages`, realm, messageForm(body, files), 'POST'), onSuccess: done });
  const close = useMutation({ mutationFn: (satisfied: boolean | null) => api<TicketView>(`/api/help/tickets/${id}/close`, { realm, method: 'POST', body: { satisfied } }), onSuccess: done });
  if (!q.data) return q.error ? <ErrorAlert error={q.error} /> : <Loading />;
  const v = q.data;
  return (
    <Stack spacing={2}>
      <TicketHeader v={v} onBack={onBack} />
      <Conversation messages={v.messages} ourSide="REQUESTER" onOpen={(a) => download(`/api/help/attachments/${a.id}`, realm, a.fileName)} />
      {v.canReply && <ReplyBox onSend={(body, files) => reply.mutateAsync({ body, files })} sending={reply.isPending} placeholder="Reply to the office" />}
      {v.canClose && <CloseRequest busy={close.isPending} onClose={(s) => close.mutate(s)} />}
      {!v.canReply && v.ticket.status === 'CLOSED' && <Typography color="text.secondary">This request is closed. Ask again if you still need help.</Typography>}
      <ErrorAlert error={reply.error ?? close.error} />
    </Stack>
  );
}

// ------------------------------------------------------------------ without an account

function FollowForm({ onOpen }: { onOpen: (a: { ticketNo: string; code: string }) => void }) {
  const [ticketNo, setTicketNo] = useState('');
  const [code, setCode] = useState('');
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 0.5 }}>Follow a request</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Enter the request number and the code from the SMS we sent you.</Typography>
        <Stack spacing={2}>
          <TextField label="Request number" value={ticketNo} onChange={(e) => setTicketNo(e.target.value)} placeholder="HD-2026-00042" />
          <TextField label="Your code" value={code} onChange={(e) => setCode(e.target.value)} inputProps={{ maxLength: 8, style: { fontFamily: 'monospace', letterSpacing: 2 } }} />
          <Box><Button variant="outlined" disabled={!ticketNo.trim() || code.trim().length < 6} onClick={() => onOpen({ ticketNo: ticketNo.trim(), code: code.trim() })}>Open my request</Button></Box>
        </Stack>
      </CardContent>
    </Card>
  );
}

function GuestRequest({ access, onBack }: { access: { ticketNo: string; code: string }; onBack: () => void }) {
  const qc = useQueryClient();
  const key = ['help', 'guest', access.ticketNo];
  const q = useQuery({ queryKey: key, retry: false, queryFn: () => api<TicketView>('/api/public/help/tickets/open', { method: 'POST', body: access }) });
  const reply = useMutation({
    mutationFn: ({ body, files }: { body: string; files: File[] }) =>
      uploadForm<TicketView>('/api/public/help/tickets/reply', 'applicant', messageForm(body, files, { ticketNo: access.ticketNo, code: access.code }), 'POST'),
    onSuccess: (v) => qc.setQueryData(key, v),
  });
  const close = useMutation({ mutationFn: (satisfied: boolean | null) => api<TicketView>('/api/public/help/tickets/close', { method: 'POST', body: { ...access, satisfied } }),
    onSuccess: (v) => qc.setQueryData(key, v) });
  if (q.error) return <Stack spacing={2}><Box><Button startIcon={<ArrowBackIcon />} onClick={onBack}>Back</Button></Box><ErrorAlert error={q.error} /></Stack>;
  if (!q.data) return <Loading />;
  const v = q.data;
  return (
    <Stack spacing={2}>
      <TicketHeader v={v} onBack={onBack} back="Back" />
      <Conversation messages={v.messages} ourSide="REQUESTER"
        onOpen={(a) => download(`/api/public/help/tickets/attachments/${a.id}`, 'applicant', a.fileName, 'POST', access)} />
      {v.canReply && <ReplyBox onSend={(body, files) => reply.mutateAsync({ body, files })} sending={reply.isPending} placeholder="Reply to the office" />}
      {v.canClose && <CloseRequest busy={close.isPending} onClose={(s) => close.mutate(s)} />}
      <ErrorAlert error={reply.error ?? close.error} />
      <Chip variant="outlined" label="Keep your request number and code private: anyone with both can read this conversation." sx={{ alignSelf: 'flex-start', height: 'auto', py: 0.5, '& .MuiChip-label': { whiteSpace: 'normal' } }} />
    </Stack>
  );
}
