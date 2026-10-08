import AddIcon from '@mui/icons-material/Add';
import PushPinIcon from '@mui/icons-material/PushPinOutlined';
import SmsIcon from '@mui/icons-material/SmsOutlined';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel,
  MenuItem, Radio, RadioGroup, Stack, Switch, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, formatDateTime } from '../../api/client';
import type { Programme, StaffNotice } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle, humanise } from '../../components/common';

const staff = { realm: 'staff' as const };
const CATEGORIES = ['GENERAL', 'ACADEMIC', 'FINANCE', 'EXAMINATION', 'HOSTEL', 'EVENT'];
const WRITERS = ['INSTITUTION_ADMIN', 'REGISTRAR', 'DEPUTY_PRINCIPAL_ACADEMIC', 'BURSAR', 'EXAMINATIONS_OFFICER', 'HOSTEL_WARDEN'];

type Form = { id?: number; title: string; body: string; category: string; programmeId: string; yearOfStudy: string; pinned: boolean; expiresAt: string };
const EMPTY: Form = { title: '', body: '', category: 'GENERAL', programmeId: '', yearOfStudy: '', pinned: false, expiresAt: '' };

/** Converts an ISO time to the value a datetime-local input expects, in local time. */
const toLocal = (iso?: string) => {
  if (!iso) return '';
  const d = new Date(iso);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
};
const fromLocal = (v: string) => (v ? new Date(v).toISOString() : null);

/** Notices on the student portal (FR-STU-01): drafted, published now or later (optionally with an SMS alert) and withdrawn. */
export default function NoticesPage() {
  const qc = useQueryClient();
  const { user } = useSession();
  const canWrite = (user('staff')?.roles ?? []).some((r) => WRITERS.includes(r));
  const [form, setForm] = useState<Form | null>(null);
  const [publishing, setPublishing] = useState<StaffNotice | null>(null);
  const [filter, setFilter] = useState<'ALL' | 'LIVE' | 'DRAFT' | 'ENDED'>('ALL');
  const list = useQuery({ queryKey: ['staff', 'notices'], queryFn: () => api<StaffNotice[]>('/api/notices', staff) });
  const withdraw = useMutation({
    mutationFn: (id: number) => api<StaffNotice>(`/api/notices/${id}/withdraw`, { ...staff, method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staff', 'notices'] }),
  });
  const shown = (list.data ?? []).filter((n) => filter === 'ALL' || (filter === 'LIVE' && n.live) || (filter === 'DRAFT' && n.status === 'DRAFT')
    || (filter === 'ENDED' && n.status !== 'DRAFT' && !n.live));
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Notices" subtitle="Announcements on the student portal, for every student or a programme and year of study."
        action={canWrite && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setForm(EMPTY)}>New notice</Button>} />
      <Stack direction="row" spacing={1} sx={{ mb: 2 }} useFlexGap flexWrap="wrap">
        {(['ALL', 'LIVE', 'DRAFT', 'ENDED'] as const).map((f) => (
          <Chip key={f} clickable color={filter === f ? 'primary' : 'default'} variant={filter === f ? 'filled' : 'outlined'}
            label={{ ALL: 'All', LIVE: 'On the portal', DRAFT: 'Drafts', ENDED: 'Scheduled, expired or withdrawn' }[f]} onClick={() => setFilter(f)} />
        ))}
      </Stack>
      <ErrorAlert error={list.error ?? withdraw.error} />
      {list.isLoading && <Loading />}
      <Stack spacing={1.5}>
        {shown.map((n) => (
          <Card key={n.id} sx={{ opacity: n.status === 'WITHDRAWN' ? 0.6 : 1 }}>
            <CardContent>
              <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
                <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                  <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }} useFlexGap flexWrap="wrap">
                    <NoticeStatus n={n} />
                    <Chip size="small" variant="outlined" label={humanise(n.category)} />
                    {n.pinned && <Chip size="small" icon={<PushPinIcon />} label="Pinned" />}
                    <Typography variant="body2" color="text.secondary">{n.audience} · {n.audienceCount} student{n.audienceCount === 1 ? '' : 's'}</Typography>
                  </Stack>
                  <Typography variant="h6" sx={{ fontSize: 17 }}>{n.title}</Typography>
                  <Typography variant="body2" color="text.secondary" sx={{ whiteSpace: 'pre-line', display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{n.body}</Typography>
                  <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.75 }}>
                    {n.status === 'DRAFT' ? `Drafted by ${n.createdByName}` : `Published by ${n.publishedByName}, ${formatDateTime(n.publishAt)}`}
                    {n.expiresAt && ` · until ${formatDateTime(n.expiresAt)}`}
                    {n.status !== 'DRAFT' && ` · read by ${n.readCount}`}
                    {n.smsSentCount > 0 && ` · SMS to ${n.smsSentCount}`}
                    {n.withdrawnAt && ` · withdrawn by ${n.withdrawnByName}, ${formatDateTime(n.withdrawnAt)}`}
                  </Typography>
                </Box>
                {canWrite && n.status !== 'WITHDRAWN' && (
                  <Stack direction={{ xs: 'row', md: 'column' }} spacing={1} sx={{ flexShrink: 0, '& .MuiButton-root': { whiteSpace: 'nowrap' } }}>
                    {n.status === 'DRAFT' && <Button variant="contained" onClick={() => setPublishing(n)}>Publish</Button>}
                    <Button variant="outlined" onClick={() => setForm({ id: n.id, title: n.title, body: n.body, category: n.category, programmeId: n.programmeId ? String(n.programmeId) : '',
                      yearOfStudy: n.yearOfStudy ? String(n.yearOfStudy) : '', pinned: n.pinned, expiresAt: toLocal(n.expiresAt) })}>Edit</Button>
                    {n.status === 'PUBLISHED' && <Button color="error" disabled={withdraw.isPending} onClick={() => withdraw.mutate(n.id)}>Withdraw</Button>}
                  </Stack>
                )}
              </Stack>
            </CardContent>
          </Card>
        ))}
        {list.data && !shown.length && <Card><CardContent><Typography color="text.secondary">No notices here yet.</Typography></CardContent></Card>}
      </Stack>
      {form && <NoticeDialog form={form} onClose={() => setForm(null)} />}
      {publishing && <PublishDialog notice={publishing} onClose={() => setPublishing(null)} />}
    </Container>
  );
}

function NoticeStatus({ n }: { n: StaffNotice }) {
  if (n.status === 'DRAFT') return <Chip size="small" color="warning" label="Draft" />;
  if (n.status === 'WITHDRAWN') return <Chip size="small" label="Withdrawn" />;
  if (n.live) return <Chip size="small" color="success" label="On the portal" />;
  if (n.publishAt && new Date(n.publishAt) > new Date()) return <Chip size="small" color="info" label={`Scheduled ${formatDateTime(n.publishAt)}`} />;
  return <Chip size="small" label="Expired" />;
}

function NoticeDialog({ form: initial, onClose }: { form: Form; onClose: () => void }) {
  const qc = useQueryClient();
  const [f, setF] = useState<Form>(initial);
  const programmes = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const save = useMutation({
    mutationFn: () => api<StaffNotice>(f.id ? `/api/notices/${f.id}` : '/api/notices', {
      ...staff, method: f.id ? 'PUT' : 'POST',
      body: { title: f.title, body: f.body, category: f.category, programmeId: f.programmeId ? Number(f.programmeId) : null,
        yearOfStudy: f.yearOfStudy ? Number(f.yearOfStudy) : null, pinned: f.pinned, expiresAt: fromLocal(f.expiresAt) },
    }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['staff', 'notices'] }); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{f.id ? 'Edit notice' : 'New notice'}</DialogTitle>
      <DialogContent>
        <Grid container spacing={2} sx={{ mt: 0.5 }}>
          <Grid size={{ xs: 12, md: 8 }}>
            <TextField label="Title" value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} inputProps={{ maxLength: 150 }} />
          </Grid>
          <Grid size={{ xs: 12, md: 4 }}>
            <TextField select label="Category" value={f.category} onChange={(e) => setF({ ...f, category: e.target.value })}>
              {CATEGORIES.map((c) => <MenuItem key={c} value={c}>{humanise(c)}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid size={{ xs: 12, md: 8 }}>
            <TextField select label="Programme" value={f.programmeId} onChange={(e) => setF({ ...f, programmeId: e.target.value })}>
              <MenuItem value="">All programmes</MenuItem>
              {programmes.data?.map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.name}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid size={{ xs: 12, md: 4 }}>
            <TextField select label="Year of study" value={f.yearOfStudy} onChange={(e) => setF({ ...f, yearOfStudy: e.target.value })}>
              <MenuItem value="">All years</MenuItem>
              {[1, 2, 3, 4].map((y) => <MenuItem key={y} value={String(y)}>Year {y}</MenuItem>)}
            </TextField>
          </Grid>
          <Grid size={12}>
            <TextField multiline minRows={6} label="Notice" value={f.body} onChange={(e) => setF({ ...f, body: e.target.value })}
              inputProps={{ maxLength: 4000 }} helperText={`${f.body.length} / 4000`} />
          </Grid>
          <Grid size={{ xs: 12, md: 6 }}>
            <TextField type="datetime-local" label="Remove from the portal on (optional)" value={f.expiresAt}
              onChange={(e) => setF({ ...f, expiresAt: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} />
          </Grid>
          <Grid size={{ xs: 12, md: 6 }} sx={{ display: 'flex', alignItems: 'center' }}>
            <FormControlLabel control={<Switch checked={f.pinned} onChange={(e) => setF({ ...f, pinned: e.target.checked })} />} label="Pin to the top" />
          </Grid>
        </Grid>
        <ErrorAlert error={save.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!f.title.trim() || !f.body.trim() || save.isPending} onClick={() => save.mutate()}>
          {f.id ? 'Save' : 'Save as draft'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function PublishDialog({ notice, onClose }: { notice: StaffNotice; onClose: () => void }) {
  const qc = useQueryClient();
  const [when, setWhen] = useState<'now' | 'later'>('now');
  const [at, setAt] = useState('');
  const [sms, setSms] = useState(false);
  const m = useMutation({
    mutationFn: () => api<StaffNotice>(`/api/notices/${notice.id}/publish`, {
      ...staff, method: 'POST', body: { publishAt: when === 'later' ? fromLocal(at) : null, sendSms: when === 'now' && sms },
    }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['staff', 'notices'] }); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Publish “{notice.title}”</DialogTitle>
      <DialogContent>
        <Typography sx={{ mb: 1.5 }}>For <strong>{notice.audience}</strong> ({notice.audienceCount} current student{notice.audienceCount === 1 ? '' : 's'}).</Typography>
        <RadioGroup value={when} onChange={(e) => setWhen(e.target.value as 'now' | 'later')}>
          <FormControlLabel value="now" control={<Radio />} label="Publish now" />
          <FormControlLabel value="later" control={<Radio />} label="Schedule for later" />
        </RadioGroup>
        {when === 'later' && (
          <TextField type="datetime-local" label="Show on the portal from" value={at} onChange={(e) => setAt(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }} sx={{ mt: 1 }} />
        )}
        <FormControlLabel sx={{ mt: 1.5 }} disabled={when === 'later' || !notice.audienceCount}
          control={<Checkbox checked={when === 'now' && sms} onChange={(e) => setSms(e.target.checked)} />}
          label={<Stack direction="row" spacing={0.75} alignItems="center"><SmsIcon fontSize="small" /><span>Also alert {notice.audienceCount} student{notice.audienceCount === 1 ? '' : 's'} by SMS</span></Stack>} />
        {when === 'later' && <Alert severity="info" sx={{ mt: 1 }}>SMS alerts go out only with notices published now.</Alert>}
        {when === 'now' && sms && <Alert severity="warning" sx={{ mt: 1 }}>SMS is charged from the institution's SMS bundle.</Alert>}
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={(when === 'later' && !at) || m.isPending} onClick={() => m.mutate()}>{when === 'now' ? 'Publish' : 'Schedule'}</Button>
      </DialogActions>
    </Dialog>
  );
}
