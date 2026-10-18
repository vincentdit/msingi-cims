import AttachFileIcon from '@mui/icons-material/AttachFile';
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesomeOutlined';
import CloseIcon from '@mui/icons-material/Close';
import DescriptionIcon from '@mui/icons-material/DescriptionOutlined';
import ImageIcon from '@mui/icons-material/ImageOutlined';
import LockIcon from '@mui/icons-material/LockOutlined';
import {
  Alert, Box, Button, Chip, FormControlLabel, IconButton, MenuItem, Paper, Stack, Switch, TextField, Tooltip, Typography,
} from '@mui/material';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { formatDateTime } from '../../api/client';
import { tr } from '../../locale/ui';
import type { Attachment, Message, Priority, TicketStatus } from './types';

export const STATUS_LABEL: Record<TicketStatus, string> = {
  OPEN: 'New', IN_PROGRESS: 'In progress', WAITING: 'Waiting for reply', RESOLVED: 'Resolved', CLOSED: 'Closed',
};
const STATUS_COLOUR: Record<TicketStatus, 'info' | 'primary' | 'warning' | 'success' | 'default'> = {
  OPEN: 'info', IN_PROGRESS: 'primary', WAITING: 'warning', RESOLVED: 'success', CLOSED: 'default',
};
/** What a support ticket to Msingi is about. */
export const SUPPORT_CATEGORY: Record<string, string> = {
  ACCOUNT_ACCESS: 'Account or access', PROBLEM: 'Something is not working', INTEGRATION: 'Integration (NECTA, NACTVET, GePG, SMS…)', DATA_CHANGE: 'Data correction',
  TRAINING: 'Training or how-to', NEW_FEATURE: 'New feature', BILLING: 'Contract or billing', OTHER: 'Other',
};

export const PRIORITY_LABEL: Record<Priority, string> = { NORMAL: 'Normal', HIGH: 'High', URGENT: 'Urgent' };

export function TicketStatusChip({ status }: { status: TicketStatus }) {
  return <Chip size="small" color={STATUS_COLOUR[status]} variant={status === 'CLOSED' ? 'outlined' : 'filled'} label={STATUS_LABEL[status]} />;
}

export function PriorityChip({ priority }: { priority: Priority }) {
  if (priority === 'NORMAL') return null;
  return <Chip size="small" color={priority === 'URGENT' ? 'error' : 'warning'} variant="outlined" label={PRIORITY_LABEL[priority]} />;
}

/** When the next target falls, or that it was missed. */
export function DueText({ late, firstResponseAt, responseDueAt, resolutionDueAt, status }: {
  late?: string; firstResponseAt?: string; responseDueAt: string; resolutionDueAt: string; status: TicketStatus;
}) {
  if (status === 'RESOLVED' || status === 'CLOSED') return null;
  if (late) {
    return <Chip size="small" color="error" label={late === 'RESPONSE' ? 'Reply overdue' : 'Resolution overdue'} />;
  }
  if (status === 'WAITING') return <Typography variant="caption" color="text.secondary">Clock stopped</Typography>;
  return (
    <Typography variant="caption" color="text.secondary" component="div" sx={{ lineHeight: 1.4 }}>
      <Box component="span" sx={{ display: 'block' }}>{!firstResponseAt ? 'Reply by' : 'Resolve by'}</Box>
      <Box component="span" sx={{ display: 'block', whiteSpace: 'nowrap' }}>{shortWhen(!firstResponseAt ? responseDueAt : resolutionDueAt)}</Box>
    </Typography>
  );
}

/** "18 Oct, 16:00" (the year only when it is not this year). */
const shortWhen = (iso: string) => {
  const d = new Date(iso);
  const sw = document.documentElement.lang === 'sw';
  return d.toLocaleString(sw ? 'sw-TZ' : 'en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
    ...(d.getFullYear() !== new Date().getFullYear() ? { year: 'numeric' } : {}) });
};

/** 255716555111 → 0716 555 111 (Tanzanian mobile numbers as people write them). */
export const formatPhone = (p?: string) => (p && /^255\d{9}$/.test(p) ? `0${p.slice(3, 6)} ${p.slice(6, 9)} ${p.slice(9)}` : p ?? '');

const size = (b: number) => (b > 1024 * 1024 ? `${(b / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(b / 1024))} KB`);

/**
 * The conversation of a ticket. Who wrote each message is shown by side: the requester (or the institution) on the left,
 * the answering side on the right, what happened in the middle; notes only staff see are marked.
 */
export function Conversation({ messages, ourSide, onOpen, actions }: {
  messages: Message[]; ourSide: 'REQUESTER' | 'STAFF' | 'INSTITUTION' | 'MSINGI'; onOpen: (a: Attachment) => void;
  /** Buttons under a message (e.g. save a reply as an answer). */
  actions?: (m: Message) => ReactNode;
}) {
  return (
    <Stack spacing={1.5} aria-label="Conversation">
      {messages.map((m) => {
        const side = m.authorType ?? m.authorSide;
        if (side === 'SYSTEM') {
          return (
            <Typography key={m.id} variant="caption" color="text.secondary" sx={{ textAlign: 'center', display: 'block' }}>
              {m.internal && <LockIcon sx={{ fontSize: 12, mr: 0.5, verticalAlign: '-2px' }} />}
              {m.body} · {formatDateTime(m.createdAt)}
            </Typography>
          );
        }
        if (side === 'ASSISTANT') return <AssistantMessage key={m.id} m={m} />;
        const mine = side === ourSide;
        return (
          <Box key={m.id} sx={{ display: 'flex', justifyContent: mine ? 'flex-end' : 'flex-start' }}>
            <Paper variant="outlined" sx={{
              p: 1.5, maxWidth: { xs: '100%', md: '78%' }, borderRadius: 2,
              bgcolor: m.internal ? '#FFF8E1' : mine ? '#EEF3FB' : 'background.paper',
              borderColor: m.internal ? 'warning.light' : mine ? 'primary.light' : 'divider',
            }}>
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }} flexWrap="wrap" useFlexGap>
                <Typography variant="subtitle2">{m.authorName}</Typography>
                {m.internal && <Chip size="small" icon={<LockIcon />} label="Note for staff only" color="warning" variant="outlined" />}
                <Typography variant="caption" color="text.secondary">{formatDateTime(m.createdAt)}</Typography>
              </Stack>
              <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{m.body}</Typography>
              {m.attachments.length > 0 && (
                <Stack direction="row" spacing={1} sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>
                  {m.attachments.map((a) => (
                    <Chip key={a.id} size="small" variant="outlined" clickable onClick={() => onOpen(a)}
                      icon={a.contentType === 'application/pdf' ? <DescriptionIcon /> : <ImageIcon />} label={`${a.fileName} (${size(a.sizeBytes)})`} />
                  ))}
                </Stack>
              )}
              {actions?.(m)}
            </Paper>
          </Box>
        );
      })}
    </Stack>
  );
}

/** An answer the CIMS assistant gave from the published answers: its title in bold, marked as a suggestion. */
function AssistantMessage({ m }: { m: Message }) {
  const [title, ...rest] = m.body.split('\n\n');
  return (
    <Box sx={{ display: 'flex', justifyContent: 'flex-start' }}>
      <Paper variant="outlined" sx={{ p: 1.5, maxWidth: { xs: '100%', md: '78%' }, borderRadius: 2, bgcolor: '#F1F8F4', borderColor: 'success.light' }}>
        <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }} flexWrap="wrap" useFlexGap>
          <AutoAwesomeIcon fontSize="small" color="success" />
          <Typography variant="subtitle2">{m.authorName}</Typography>
          <Chip size="small" label="Suggested answer" color="success" variant="outlined" />
          <Typography variant="caption" color="text.secondary">{formatDateTime(m.createdAt)}</Typography>
        </Stack>
        <Typography variant="body2" fontWeight={600}>{title}</Typography>
        {rest.length > 0 && <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', mt: 0.5 }}>{rest.join('\n\n')}</Typography>}
      </Paper>
    </Box>
  );
}

const MAX_FILES = 3;
const MAX_BYTES = 5 * 1024 * 1024;
const TYPES = ['application/pdf', 'image/jpeg', 'image/png'];

/** Files picked for a message: at most three PDF, JPEG or PNG files of up to 5 MB each. */
export function FilePicker({ files, onChange }: { files: File[]; onChange: (f: File[]) => void }) {
  const input = useRef<HTMLInputElement>(null);
  const [problem, setProblem] = useState<string>();
  const add = (list: FileList | null) => {
    if (!list) return;
    const picked = [...files];
    for (const f of Array.from(list)) {
      if (!TYPES.includes(f.type)) { setProblem(`${f.name}: attach PDF, JPEG or PNG files only.`); continue; }
      if (f.size > MAX_BYTES) { setProblem(`${f.name} is larger than 5 MB.`); continue; }
      if (picked.length >= MAX_FILES) { setProblem(`Attach at most ${MAX_FILES} files.`); break; }
      picked.push(f);
    }
    onChange(picked);
    if (input.current) input.current.value = '';
  };
  return (
    <Box>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <Button size="small" startIcon={<AttachFileIcon />} onClick={() => { setProblem(undefined); input.current?.click(); }} disabled={files.length >= MAX_FILES}>
          Attach a file
        </Button>
        {files.map((f, i) => (
          <Chip key={i} size="small" label={`${f.name} (${size(f.size)})`} onDelete={() => onChange(files.filter((_, j) => j !== i))} />
        ))}
      </Stack>
      <input ref={input} type="file" hidden multiple accept=".pdf,.jpg,.jpeg,.png,application/pdf,image/jpeg,image/png" onChange={(e) => add(e.target.files)} />
      {problem && <Alert severity="warning" sx={{ mt: 1 }} onClose={() => setProblem(undefined)}>{problem}</Alert>}
      <Typography variant="caption" color="text.secondary" display="block">Up to 3 files: PDF, JPEG or PNG, 5 MB each.</Typography>
    </Box>
  );
}

/**
 * Writing a message. Staff can mark it a note only staff see and choose the status after sending (in progress, waiting for the
 * requester, resolved).
 */
export function ReplyBox({ onSend, sending, staff, placeholder, statuses, draft }: {
  onSend: (body: string, files: File[], options: { internal: boolean; statusAfter?: string }) => Promise<unknown>;
  sending?: boolean; staff?: boolean; placeholder?: string; statuses?: { value: string; label: string }[];
  /** Text put into the box from outside (a suggested reply); a new {@code n} replaces what is there. */
  draft?: { text: string; n: number };
}) {
  const [body, setBody] = useState('');
  const box = useRef<HTMLTextAreaElement>(null);
  useEffect(() => {
    if (!draft) return;
    setBody(draft.text);
    setInternal(false);
    box.current?.focus();
  }, [draft]);
  const [files, setFiles] = useState<File[]>([]);
  const [internal, setInternal] = useState(false);
  const [statusAfter, setStatusAfter] = useState('');
  const send = () => onSend(body, files, { internal, statusAfter: internal ? undefined : statusAfter || undefined })
    .then(() => { setBody(''); setFiles([]); setStatusAfter(''); setInternal(false); }).catch(() => { /* the error is shown by the caller */ });
  return (
    <Paper variant="outlined" sx={{ p: 1.5, borderRadius: 2, ...(internal ? { backgroundColor: '#FFF8E1', borderColor: 'warning.light' } : {}) }}>
      <TextField multiline minRows={3} fullWidth value={body} onChange={(e) => setBody(e.target.value)}
        placeholder={internal ? 'A note only staff can see' : placeholder ?? 'Write your message'}
        inputRef={box} inputProps={{ maxLength: 5000, 'aria-label': tr(internal ? 'Note for staff' : 'Message') }} />
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }} justifyContent="space-between" sx={{ mt: 1 }}>
        <FilePicker files={files} onChange={setFiles} />
        <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap justifyContent="flex-end">
          {staff && <FormControlLabel control={<Switch checked={internal} onChange={(e) => setInternal(e.target.checked)} />} label="Note for staff only" />}
          {staff && !internal && statuses && (
            <TextField select size="small" label="Then" value={statusAfter} onChange={(e) => setStatusAfter(e.target.value)} sx={{ minWidth: 200 }}>
              <MenuItem value="">Keep working on it</MenuItem>
              {statuses.map((s) => <MenuItem key={s.value} value={s.value}>{s.label}</MenuItem>)}
            </TextField>
          )}
          <Button variant="contained" onClick={send} disabled={sending || !body.trim()}>{internal ? 'Add note' : 'Send'}</Button>
        </Stack>
      </Stack>
    </Paper>
  );
}

/** Asks whether the answer helped, then closes the request. */
export function CloseRequest({ onClose, busy }: { onClose: (satisfied: boolean | null) => void; busy?: boolean }) {
  const [asking, setAsking] = useState(false);
  if (!asking) return <Button variant="outlined" onClick={() => setAsking(true)}>My problem is solved — close it</Button>;
  return (
    <Paper variant="outlined" sx={{ p: 1.5, borderRadius: 2 }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }}>
        <Typography sx={{ flexGrow: 1 }}>Did the answer help you?</Typography>
        <Button variant="contained" color="success" disabled={busy} onClick={() => onClose(true)}>Yes, it helped</Button>
        <Button variant="outlined" disabled={busy} onClick={() => onClose(false)}>No</Button>
        <Tooltip title="Cancel"><IconButton onClick={() => setAsking(false)} aria-label="Cancel"><CloseIcon /></IconButton></Tooltip>
      </Stack>
    </Paper>
  );
}

/** A message as multipart form data: body, the options, and the files. */
export function messageForm(body: string, files: File[], extra: Record<string, string | undefined> = {}): FormData {
  const f = new FormData();
  f.append('body', body);
  for (const [k, v] of Object.entries(extra)) if (v !== undefined && v !== '') f.append(k, v);
  for (const file of files) f.append('files', file);
  return f;
}
