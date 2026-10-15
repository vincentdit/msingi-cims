import AddIcon from '@mui/icons-material/Add';
import EditIcon from '@mui/icons-material/EditOutlined';
import ReplayIcon from '@mui/icons-material/Replay';
import SendIcon from '@mui/icons-material/Send';
import WarningIcon from '@mui/icons-material/WarningAmber';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, Divider,
  FormControlLabel, InputAdornment, LinearProgress, ListItemText, MenuItem, Radio, RadioGroup, Stack, Switch, Tab, Table, TableBody,
  TableCell, TableContainer, TableHead, TablePagination, TableRow, Tabs, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type ReactNode } from 'react';
import { api, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type {
  AdmissionWindow, AudienceFilter, CampaignPreview, CampaignView, MessageTemplateView, MessageView, MessagingOverview, Page, Programme,
} from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle, humanise } from '../../components/common';
import { EmailTab } from './EmailPages';

const staff = { realm: 'staff' as const };
type TabKey = 'overview' | 'send' | 'sent' | 'log' | 'templates' | 'email';
const STUDENT_STATUSES = ['ADMITTED', 'ACTIVE', 'POSTPONED', 'SUSPENDED', 'COMPLETED', 'GRADUATED'];
const APPLICATION_STATUSES = ['SUBMITTED', 'UNDER_REVIEW', 'SELECTED', 'NOT_SELECTED', 'PENDING_VERIFICATION', 'QUERIED', 'NACTVET_REJECTED',
  'OFFERED', 'ACCEPTED', 'DECLINED', 'OFFER_LAPSED', 'PAYMENT_PENDING'];
const STATUS_COLOUR: Record<string, 'success' | 'error' | 'info' | 'default'> = { DELIVERED: 'success', FAILED: 'error', SENT: 'info', QUEUED: 'default' };

function useRoles() {
  const roles = useSession().user('staff')?.roles ?? [];
  const has = (...r: string[]) => roles.some((x) => r.includes(x));
  return {
    canSend: has('INSTITUTION_ADMIN', 'REGISTRAR', 'ADMISSIONS_OFFICER', 'BURSAR', 'DEPUTY_PRINCIPAL_ACADEMIC'),
    isAdmin: has('INSTITUTION_ADMIN'),
    canTopUp: has('INSTITUTION_ADMIN', 'BURSAR'),
  };
}

/** Client-side estimate of SMS parts while typing (the server's count is authoritative). */
function smsParts(text: string) {
  const plain = text.replace(/[–—−]/g, '-').replace(/[‘’]/g, "'").replace(/[“”]/g, '"').replace(/…/g, '...');
  const gsm = /^[@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !"#¤%&'()*+,\-./0-9:;<=>?¡A-ZÄÖÑÜ§¿a-zäöñüà^{}\\[~\]|€]*$/.test(plain);
  if (gsm) {
    const len = [...plain].reduce((n, c) => n + ('^{}\\[~]|€'.includes(c) ? 2 : 1), 0);
    return { unicode: false, length: len, parts: len <= 160 ? 1 : Math.ceil(len / 153) };
  }
  const len = [...plain].length;
  return { unicode: true, length: len, parts: len <= 70 ? 1 : Math.ceil(len / 67) };
}

/** Messaging (FR-COM-01 to 03): SMS bundle, bulk messages, delivery reports and event message templates. */
export default function MessagingPage() {
  const [tab, setTab] = useState<TabKey>('overview');
  const { canSend } = useRoles();
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Messages" subtitle="SMS and email to applicants and students: automatic event messages, bulk messages to groups, delivery and cost." />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" sx={{ mb: 2 }}>
        <Tab value="overview" label="Overview" />
        {canSend && <Tab value="send" label="Send a message" />}
        <Tab value="sent" label="Sent bulk messages" />
        <Tab value="log" label="Message log" />
        <Tab value="templates" label="Event messages" />
        <Tab value="email" label="Email" />
      </Tabs>
      {tab === 'overview' && <OverviewTab />}
      {tab === 'send' && <SendTab onSent={() => setTab('sent')} />}
      {tab === 'sent' && <SentTab />}
      {tab === 'log' && <LogTab />}
      {tab === 'templates' && <TemplatesTab />}
      {tab === 'email' && <EmailTab />}
    </Container>
  );
}

function useOverview() {
  return useQuery({ queryKey: ['staff', 'messaging', 'overview'], queryFn: () => api<MessagingOverview>('/api/messaging/overview', staff) });
}

// ============================================================ overview, bundle and settings

function OverviewTab() {
  const { isAdmin, canTopUp } = useRoles();
  const q = useOverview();
  const [topup, setTopup] = useState(false);
  const [settings, setSettings] = useState(false);
  const o = q.data;
  if (q.isLoading) return <Loading />;
  if (!o) return <ErrorAlert error={q.error} />;
  const count = (rows: { key: string; messages: number }[], key: string) => rows.find((r) => r.key === key)?.messages ?? 0;
  const monthMessages = o.thisMonthByStatus.reduce((n, r) => n + r.messages, 0);
  return (
    <Stack spacing={2.5}>
      {o.gatewayMode === 'simulated' && <Alert severity="info">Development mode: messages are written to the server log instead of being sent. Numbers ending in 000 simulate an unreachable phone; 999 a number the gateway refuses.</Alert>}
      {o.lowBalance && <Alert severity="warning" icon={<WarningIcon />}>The SMS bundle is low ({o.balance.toLocaleString()} SMS left). Event messages keep going out, but bulk messages need enough balance.</Alert>}
      <Grid container spacing={1.5}>
        <Tile label="SMS bundle balance" value={o.balance.toLocaleString()} tone={o.balance <= 0 ? 'error' : o.lowBalance ? 'warning' : 'success'}
          action={canTopUp && <Button size="small" startIcon={<AddIcon />} onClick={() => setTopup(true)}>Record top-up</Button>} />
        <Tile label="Messages this month" value={monthMessages.toLocaleString()} />
        <Tile label="Cost this month" value={formatTzs(o.thisMonthCost)} />
        <Tile label="Delivered" value={o.deliveryRate != null ? `${o.deliveryRate}%` : '—'} />
      </Grid>
      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card sx={{ height: '100%' }}><CardContent>
            <Typography variant="h6" sx={{ mb: 1 }}>This month</Typography>
            <Table size="small"><TableBody>
              {[['EVENT', 'Event messages (automatic)'], ['BULK', 'Bulk messages'], ['SECURITY', 'Verification codes']].map(([k, label]) => {
                const r = o.thisMonthByKind.find((x) => x.key === k);
                return <TableRow key={k}><TableCell>{label}</TableCell><TableCell align="right">{r?.messages ?? 0}</TableCell><TableCell align="right" sx={{ color: 'text.secondary' }}>{r?.parts ?? 0} SMS</TableCell></TableRow>;
              })}
            </TableBody></Table>
            <Stack direction="row" spacing={1} sx={{ mt: 1.5 }} useFlexGap flexWrap="wrap">
              {['DELIVERED', 'SENT', 'QUEUED', 'FAILED'].map((s) => <Chip key={s} size="small" color={STATUS_COLOUR[s]} variant={s === 'QUEUED' ? 'outlined' : 'filled'} label={`${humanise(s)}: ${count(o.thisMonthByStatus, s)}`} />)}
            </Stack>
          </CardContent></Card>
        </Grid>
        <Grid size={{ xs: 12, md: 6 }}>
          <Card sx={{ height: '100%' }}><CardContent>
            <Stack direction="row" alignItems="center" sx={{ mb: 1 }}>
              <Typography variant="h6" sx={{ flexGrow: 1 }}>Settings</Typography>
              {isAdmin && <Button size="small" startIcon={<EditIcon />} onClick={() => setSettings(true)}>Change</Button>}
            </Stack>
            <Table size="small"><TableBody>
              <TableRow><TableCell>Language of event messages</TableCell><TableCell>{o.defaultLanguage === 'SW' ? 'Kiswahili' : 'English'}</TableCell></TableRow>
              <TableRow><TableCell>Sender name</TableCell><TableCell>{o.senderId ?? 'Aggregator default'}</TableCell></TableRow>
              <TableRow><TableCell>Price per SMS</TableCell><TableCell>{formatTzs(o.pricePerSms)}</TableCell></TableRow>
              <TableRow><TableCell>Warn when the bundle is below</TableCell><TableCell>{o.lowBalanceWarning.toLocaleString()} SMS</TableCell></TableRow>
              <TableRow><TableCell>Bundle: bought / used</TableCell><TableCell>{o.purchased.toLocaleString()} / {o.used.toLocaleString()}</TableCell></TableRow>
            </TableBody></Table>
          </CardContent></Card>
        </Grid>
      </Grid>
      <Card><CardContent>
        <Typography variant="h6" sx={{ mb: 1 }}>Bundle top-ups</Typography>
        {!o.topups.length ? <Typography color="text.secondary">No top-ups recorded yet.</Typography> : (
          <TableContainer><Table size="small">
            <TableHead><TableRow><TableCell>Date</TableCell><TableCell align="right">SMS</TableCell><TableCell align="right">Paid</TableCell><TableCell>Reference</TableCell><TableCell>Recorded by</TableCell></TableRow></TableHead>
            <TableBody>{o.topups.map((t) => (
              <TableRow key={t.id}><TableCell>{formatDate(t.recordedAt)}</TableCell><TableCell align="right">{t.units.toLocaleString()}</TableCell>
                <TableCell align="right">{formatTzs(t.amountPaid)}</TableCell><TableCell>{t.reference}</TableCell><TableCell>{t.recordedByName}</TableCell></TableRow>
            ))}</TableBody>
          </Table></TableContainer>
        )}
      </CardContent></Card>
      {topup && <TopupDialog onClose={() => setTopup(false)} />}
      {settings && <SettingsDialog o={o} onClose={() => setSettings(false)} />}
    </Stack>
  );
}

function Tile({ label, value, tone, action }: { label: string; value: ReactNode; tone?: 'success' | 'warning' | 'error'; action?: ReactNode }) {
  return (
    <Grid size={{ xs: 6, md: 3 }}>
      <Card sx={{ height: '100%' }}><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
        <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 22, color: tone ? `${tone}.main` : undefined }}>{value}</Typography>
        {action}
      </CardContent></Card>
    </Grid>
  );
}

function TopupDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const [units, setUnits] = useState('');
  const [amount, setAmount] = useState('');
  const [reference, setReference] = useState('');
  const m = useMutation({
    mutationFn: () => api<MessagingOverview>('/api/messaging/topups', { ...staff, method: 'POST', body: { units: Number(units), amountPaid: Number(amount || 0), reference } }),
    onSuccess: (o) => { qc.setQueryData(['staff', 'messaging', 'overview'], o); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Record an SMS bundle top-up</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField label="SMS bought" type="number" value={units} onChange={(e) => setUnits(e.target.value)} />
          <TextField label="Amount paid" type="number" value={amount} onChange={(e) => setAmount(e.target.value)}
            slotProps={{ input: { startAdornment: <InputAdornment position="start">TZS</InputAdornment> } }} />
          <TextField label="Invoice or receipt reference" value={reference} onChange={(e) => setReference(e.target.value)} />
          <ErrorAlert error={m.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!Number(units) || !reference.trim() || m.isPending} onClick={() => m.mutate()}>Record</Button>
      </DialogActions>
    </Dialog>
  );
}

function SettingsDialog({ o, onClose }: { o: MessagingOverview; onClose: () => void }) {
  const qc = useQueryClient();
  const [f, setF] = useState({ defaultLanguage: o.defaultLanguage, senderId: o.senderId ?? '', pricePerSms: String(o.pricePerSms), lowBalanceWarning: String(o.lowBalanceWarning) });
  const m = useMutation({
    mutationFn: () => api<MessagingOverview>('/api/messaging/settings', { ...staff, method: 'PUT',
      body: { defaultLanguage: f.defaultLanguage, senderId: f.senderId, pricePerSms: Number(f.pricePerSms), lowBalanceWarning: Number(f.lowBalanceWarning) } }),
    onSuccess: (x) => { qc.setQueryData(['staff', 'messaging', 'overview'], x); qc.invalidateQueries({ queryKey: ['staff', 'messaging', 'templates'] }); onClose(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="xs" fullWidth>
      <DialogTitle>Messaging settings</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select label="Language of event messages" value={f.defaultLanguage} onChange={(e) => setF({ ...f, defaultLanguage: e.target.value as 'EN' | 'SW' })}>
            <MenuItem value="EN">English</MenuItem><MenuItem value="SW">Kiswahili</MenuItem>
          </TextField>
          <TextField label="Sender name (up to 11 characters)" value={f.senderId} onChange={(e) => setF({ ...f, senderId: e.target.value.slice(0, 11) })}
            helperText="As registered with your SMS aggregator; leave empty for its default." />
          <TextField label="Price per SMS" type="number" value={f.pricePerSms} onChange={(e) => setF({ ...f, pricePerSms: e.target.value })}
            slotProps={{ input: { startAdornment: <InputAdornment position="start">TZS</InputAdornment> } }} />
          <TextField label="Warn when the bundle falls below (SMS)" type="number" value={f.lowBalanceWarning} onChange={(e) => setF({ ...f, lowBalanceWarning: e.target.value })} />
          <ErrorAlert error={m.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={m.isPending} onClick={() => m.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

// ============================================================ send a bulk message (FR-COM-02)

function SendTab({ onSent }: { onSent: () => void }) {
  const qc = useQueryClient();
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [aud, setAud] = useState<AudienceFilter>({ group: 'STUDENTS', studentStatuses: ['ADMITTED', 'ACTIVE'], withFeeBalance: false });
  const [preview, setPreview] = useState<CampaignPreview | null>(null);
  const [confirm, setConfirm] = useState(false);
  const ref = useRef<HTMLTextAreaElement>(null);
  const programmes = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const windows = useQuery({ queryKey: ['staff', 'windows'], queryFn: () => api<AdmissionWindow[]>('/api/admission/windows', staff) });
  const request = () => ({ title, body, audience: aud });
  const pv = useMutation({ mutationFn: () => api<CampaignPreview>('/api/messaging/campaigns/preview', { ...staff, method: 'POST', body: request() }), onSuccess: setPreview });
  const send = useMutation({
    mutationFn: () => api<CampaignView>('/api/messaging/campaigns', { ...staff, method: 'POST', body: request() }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['staff', 'messaging'] }); setConfirm(false); onSent(); },
  });
  const change = (a: Partial<AudienceFilter>) => { setAud({ ...aud, ...a }); setPreview(null); };
  const placeholders = { STUDENTS: ['firstName', 'fullName', 'registrationNo', 'programme', 'balance'], APPLICANTS: ['firstName', 'fullName', 'referenceNo', 'programme'], PHONES: [] }[aud.group];
  const insert = (p: string) => {
    const el = ref.current;
    const at = el?.selectionStart ?? body.length;
    setBody(body.slice(0, at) + `{${p}}` + body.slice(el?.selectionEnd ?? at));
    setPreview(null);
  };
  const est = smsParts(body);
  return (
    <Grid container spacing={2.5}>
      <Grid size={{ xs: 12, md: 7 }}>
        <Card><CardContent>
          <Stack spacing={2}>
            <Typography variant="h6">Who</Typography>
            <RadioGroup row value={aud.group} onChange={(e) => change({ group: e.target.value as AudienceFilter['group'] })}>
              <FormControlLabel value="STUDENTS" control={<Radio />} label="Students" />
              <FormControlLabel value="APPLICANTS" control={<Radio />} label="Applicants" />
              <FormControlLabel value="PHONES" control={<Radio />} label="A list of numbers" />
            </RadioGroup>
            {aud.group === 'STUDENTS' && (
              <Grid container spacing={1.5}>
                <Grid size={{ xs: 12, sm: 6 }}>
                  <TextField select size="small" label="Status" value={aud.studentStatuses ?? []}
                    slotProps={{ select: { multiple: true, renderValue: (v) => (v as string[]).map(humanise).join(', ') } }}
                    onChange={(e) => change({ studentStatuses: e.target.value as unknown as string[] })}>
                    {STUDENT_STATUSES.map((s) => <MenuItem key={s} value={s}><Checkbox size="small" checked={aud.studentStatuses?.includes(s) ?? false} /><ListItemText primary={humanise(s)} /></MenuItem>)}
                  </TextField>
                </Grid>
                <Grid size={{ xs: 12, sm: 6 }}>
                  <TextField select size="small" label="Year of study" value={aud.yearOfStudy ?? ''} onChange={(e) => change({ yearOfStudy: e.target.value ? Number(e.target.value) : null })}>
                    <MenuItem value="">All years</MenuItem>{[1, 2, 3, 4].map((y) => <MenuItem key={y} value={y}>Year {y}</MenuItem>)}
                  </TextField>
                </Grid>
                <Grid size={12}>
                  <TextField select size="small" label="Programme" value={aud.programmeId ?? ''} onChange={(e) => change({ programmeId: e.target.value ? Number(e.target.value) : null })}>
                    <MenuItem value="">All programmes</MenuItem>{programmes.data?.map((p) => <MenuItem key={p.id} value={p.id}>{p.name}</MenuItem>)}
                  </TextField>
                </Grid>
                <Grid size={12}>
                  <FormControlLabel control={<Switch checked={!!aud.withFeeBalance} onChange={(e) => change({ withFeeBalance: e.target.checked })} />} label="Only students who owe fees" />
                </Grid>
              </Grid>
            )}
            {aud.group === 'APPLICANTS' && (
              <Grid container spacing={1.5}>
                <Grid size={{ xs: 12, sm: 6 }}>
                  <TextField select size="small" label="Admission window" value={aud.windowId ?? ''} onChange={(e) => change({ windowId: e.target.value ? Number(e.target.value) : null })}>
                    {windows.data?.map((w) => <MenuItem key={w.id} value={w.id}>{w.name}</MenuItem>)}
                  </TextField>
                </Grid>
                <Grid size={{ xs: 12, sm: 6 }}>
                  <TextField select size="small" label="Application status (all if empty)" value={aud.applicationStatuses ?? []}
                    slotProps={{ select: { multiple: true, renderValue: (v) => (v as string[]).map(humanise).join(', ') } }}
                    onChange={(e) => change({ applicationStatuses: e.target.value as unknown as string[] })}>
                    {APPLICATION_STATUSES.map((s) => <MenuItem key={s} value={s}><Checkbox size="small" checked={aud.applicationStatuses?.includes(s) ?? false} /><ListItemText primary={humanise(s)} /></MenuItem>)}
                  </TextField>
                </Grid>
              </Grid>
            )}
            {aud.group === 'PHONES' && (
              <TextField multiline minRows={4} label="Mobile numbers" placeholder={'0712 345 678\n0754 123 456'} value={aud.phones ?? ''}
                onChange={(e) => change({ phones: e.target.value })} helperText="One per line, or separated by commas. Duplicates are sent once." />
            )}
            <Divider />
            <Typography variant="h6">Message</Typography>
            <TextField size="small" label="Title (for your records, not sent)" value={title} onChange={(e) => setTitle(e.target.value)} inputProps={{ maxLength: 150 }} />
            <TextField inputRef={ref} multiline minRows={4} label="Message" value={body} onChange={(e) => { setBody(e.target.value); setPreview(null); }}
              inputProps={{ maxLength: 700 }}
              helperText={`${est.length} characters · ${est.parts} SMS per person${est.unicode ? ' · special characters make each SMS shorter (70 characters)' : ''}`}
              FormHelperTextProps={{ sx: { color: est.unicode ? 'warning.main' : undefined } }} />
            {!!placeholders.length && (
              <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" alignItems="center">
                <Typography variant="body2" color="text.secondary">Insert:</Typography>
                {placeholders.map((p) => <Chip key={p} size="small" variant="outlined" label={`{${p}}`} onClick={() => insert(p)} />)}
              </Stack>
            )}
            <ErrorAlert error={pv.error} />
            <Box><Button variant="outlined" disabled={!body.trim() || !title.trim() || pv.isPending} onClick={() => pv.mutate()}>Preview recipients and cost</Button></Box>
          </Stack>
        </CardContent></Card>
      </Grid>
      <Grid size={{ xs: 12, md: 5 }}>
        <Card sx={{ position: { md: 'sticky' }, top: { md: 80 } }}><CardContent>
          <Typography variant="h6" sx={{ mb: 1 }}>Before sending</Typography>
          {pv.isPending && <LinearProgress />}
          {!preview ? <Typography color="text.secondary">Preview to see who will receive the message, what it costs and how it reads.</Typography> : (
            <Stack spacing={1.5}>
              <Typography variant="body2" color="text.secondary">{preview.audienceLabel}</Typography>
              <Grid container spacing={1}>
                <Fig label="Recipients" value={preview.recipients.toLocaleString()} />
                <Fig label="SMS" value={preview.totalParts.toLocaleString()} />
                <Fig label="Cost" value={formatTzs(preview.estimatedCost)} />
                <Fig label="Bundle left" value={preview.balance.toLocaleString()} tone={preview.enoughBalance ? undefined : 'error.main'} />
              </Grid>
              {(preview.skippedNoPhone > 0 || preview.skippedDuplicates > 0) && (
                <Typography variant="body2" color="text.secondary">
                  {preview.skippedNoPhone > 0 && `${preview.skippedNoPhone} without a mobile number. `}{preview.skippedDuplicates > 0 && `${preview.skippedDuplicates} duplicate number(s) sent once.`}
                </Typography>
              )}
              {!!preview.invalidPhones.length && <Alert severity="warning">Not valid mobile numbers (skipped): {preview.invalidPhones.join(', ')}</Alert>}
              {preview.unicode && <Alert severity="warning">Special characters or emoji make each SMS hold only 70 characters, which costs more.</Alert>}
              {!preview.enoughBalance && <Alert severity="error">Not enough SMS in the bundle. Record a top-up first.</Alert>}
              {preview.samples.map((s, i) => (
                <Box key={i} sx={{ p: 1.5, borderRadius: 2, bgcolor: 'action.hover' }}>
                  <Typography variant="caption" color="text.secondary">{s.name ? `${s.name} · ` : ''}{s.phone} · {s.parts} SMS</Typography>
                  <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{s.text}</Typography>
                </Box>
              ))}
              <Button variant="contained" startIcon={<SendIcon />} disabled={!preview.recipients || !preview.enoughBalance} onClick={() => setConfirm(true)}>
                Send to {preview.recipients.toLocaleString()} {preview.recipients === 1 ? 'person' : 'people'}
              </Button>
            </Stack>
          )}
        </CardContent></Card>
      </Grid>
      <Dialog open={confirm} onClose={() => setConfirm(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Send “{title}”?</DialogTitle>
        <DialogContent>
          <Typography>{preview?.recipients.toLocaleString()} people will receive it ({preview?.totalParts.toLocaleString()} SMS, about {formatTzs(preview?.estimatedCost)}). Sent messages cannot be recalled.</Typography>
          <ErrorAlert error={send.error} />
        </DialogContent>
        <DialogActions><Button onClick={() => setConfirm(false)}>Cancel</Button><Button variant="contained" disabled={send.isPending} onClick={() => send.mutate()}>Send now</Button></DialogActions>
      </Dialog>
    </Grid>
  );
}

function Fig({ label, value, tone }: { label: string; value: ReactNode; tone?: string }) {
  return (
    <Grid size={6}>
      <Typography variant="caption" color="text.secondary">{label}</Typography>
      <Typography sx={{ fontWeight: 700, fontSize: 18, color: tone }}>{value}</Typography>
    </Grid>
  );
}

// ============================================================ sent bulk messages and delivery

function SentTab() {
  const [open, setOpen] = useState<number | null>(null);
  const q = useQuery({ queryKey: ['staff', 'messaging', 'campaigns'], queryFn: () => api<CampaignView[]>('/api/messaging/campaigns', staff) });
  if (q.isLoading) return <Loading />;
  return (
    <>
      <ErrorAlert error={q.error} />
      <Card><TableContainer><Table size="small">
        <TableHead><TableRow><TableCell>Sent</TableCell><TableCell>Message</TableCell><TableCell>To</TableCell><TableCell align="right">People</TableCell>
          <TableCell>Delivery</TableCell><TableCell align="right">Cost</TableCell></TableRow></TableHead>
        <TableBody>
          {q.data?.map((c) => (
            <TableRow key={c.id} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(c.id)}>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(c.sentAt)}<Typography variant="caption" display="block" color="text.secondary">{c.sentByName}</Typography></TableCell>
              <TableCell sx={{ maxWidth: 320 }}><Typography sx={{ fontWeight: 600 }}>{c.title}</Typography><Typography variant="caption" color="text.secondary" noWrap display="block">{c.body}</Typography></TableCell>
              <TableCell sx={{ maxWidth: 220 }}><Typography variant="body2">{c.audienceLabel}</Typography></TableCell>
              <TableCell align="right">{c.recipients}</TableCell>
              <TableCell><DeliveryBar byStatus={c.byStatus} total={c.recipients} /></TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatTzs(c.estimatedCost)}</TableCell>
            </TableRow>
          ))}
          {q.data && !q.data.length && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 2 }}>No bulk messages sent yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table></TableContainer></Card>
      {open != null && <CampaignDialog id={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function DeliveryBar({ byStatus, total }: { byStatus: Record<string, number>; total: number }) {
  const d = byStatus.DELIVERED ?? 0, f = byStatus.FAILED ?? 0, p = (byStatus.SENT ?? 0) + (byStatus.QUEUED ?? 0);
  const pct = (n: number) => `${total ? (100 * n) / total : 0}%`;
  return (
    <Tooltip title={`Delivered ${d} · Failed ${f} · Pending ${p}`}>
      <Box sx={{ minWidth: 120 }}>
        <Box sx={{ display: 'flex', height: 8, borderRadius: 4, overflow: 'hidden', bgcolor: 'action.hover' }}>
          <Box sx={{ width: pct(d), bgcolor: 'success.main' }} /><Box sx={{ width: pct(f), bgcolor: 'error.main' }} />
        </Box>
        <Typography variant="caption" color="text.secondary">{d} delivered{f ? ` · ${f} failed` : ''}{p ? ` · ${p} pending` : ''}</Typography>
      </Box>
    </Tooltip>
  );
}

function CampaignDialog({ id, onClose }: { id: number; onClose: () => void }) {
  const qc = useQueryClient();
  const { canSend } = useRoles();
  const q = useQuery({ queryKey: ['staff', 'messaging', 'campaign', id], queryFn: () => api<{ campaign: CampaignView; messages: MessageView[] }>(`/api/messaging/campaigns/${id}`, staff) });
  const resend = useMutation({
    mutationFn: () => api(`/api/messaging/campaigns/${id}/resend-failed`, { ...staff, method: 'POST' }),
    onSuccess: () => setTimeout(() => qc.invalidateQueries({ queryKey: ['staff', 'messaging'] }), 600),
  });
  const d = q.data;
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{d?.campaign.title ?? 'Bulk message'}</DialogTitle>
      <DialogContent>
        {!d ? <Loading /> : (
          <Stack spacing={2}>
            <Typography variant="body2" color="text.secondary">{d.campaign.audienceLabel} · sent by {d.campaign.sentByName}, {formatDateTime(d.campaign.sentAt)}</Typography>
            <Box sx={{ p: 1.5, borderRadius: 2, bgcolor: 'action.hover' }}><Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{d.campaign.body}</Typography></Box>
            <DeliveryBar byStatus={d.campaign.byStatus} total={d.campaign.recipients} />
            <MessagesTable rows={d.messages} />
            <ErrorAlert error={resend.error} />
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        {canSend && !!d?.campaign.byStatus.FAILED && (
          <Button startIcon={<ReplayIcon />} disabled={resend.isPending} onClick={() => resend.mutate()}>Resend {d.campaign.byStatus.FAILED} failed</Button>
        )}
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}

function MessagesTable({ rows }: { rows: MessageView[] }) {
  return (
    <TableContainer><Table size="small">
      <TableHead><TableRow><TableCell>To</TableCell><TableCell>Message</TableCell><TableCell>Status</TableCell><TableCell>When</TableCell></TableRow></TableHead>
      <TableBody>
        {rows.map((m) => (
          <TableRow key={m.id} sx={{ verticalAlign: 'top' }}>
            <TableCell sx={{ whiteSpace: 'nowrap' }}>{m.phone}<Typography variant="caption" display="block" color="text.secondary">{m.recipientName ?? (m.templateCode ? humanise(m.templateCode) : '')}</Typography></TableCell>
            <TableCell sx={{ minWidth: 240 }}><Typography variant="body2">{m.body}</Typography>
              <Typography variant="caption" color="text.secondary">{m.parts} SMS · {m.language === 'SW' ? 'Kiswahili' : 'English'}{m.encoding === 'UCS2' ? ' · Unicode' : ''}</Typography></TableCell>
            <TableCell><Chip size="small" color={STATUS_COLOUR[m.status]} variant={m.status === 'QUEUED' ? 'outlined' : 'filled'} label={humanise(m.status)} />
              {m.error && <Typography variant="caption" display="block" color="error.main">{m.error}</Typography>}
              {m.attempts > 1 && <Typography variant="caption" display="block" color="text.secondary">{m.attempts} attempts</Typography>}</TableCell>
            <TableCell sx={{ whiteSpace: 'nowrap' }}><Typography variant="caption" display="block">{formatDateTime(m.queuedAt)}</Typography>
              {m.deliveredAt && <Typography variant="caption" display="block" color="text.secondary">delivered {formatDateTime(m.deliveredAt)}</Typography>}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table></TableContainer>
  );
}

// ============================================================ message log

function LogTab() {
  const [status, setStatus] = useState('');
  const [kind, setKind] = useState('');
  const [phone, setPhone] = useState('');
  const [page, setPage] = useState(0);
  const params = new URLSearchParams({ page: String(page), size: '25' });
  if (status) params.set('status', status);
  if (kind) params.set('kind', kind);
  if (phone.replace(/\D/g, '').length >= 3) params.set('phone', phone);
  const q = useQuery({ queryKey: ['staff', 'messaging', 'log', params.toString()], queryFn: () => api<Page<MessageView>>(`/api/messaging/messages?${params}`, staff) });
  return (
    <Card><CardContent>
      <Grid container spacing={1.5} sx={{ mb: 1.5 }}>
        <Grid size={{ xs: 12, md: 4 }}><TextField size="small" label="Mobile number" value={phone} onChange={(e) => { setPhone(e.target.value); setPage(0); }} /></Grid>
        <Grid size={{ xs: 6, md: 4 }}>
          <TextField select size="small" label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
            <MenuItem value="">All</MenuItem>{['QUEUED', 'SENT', 'DELIVERED', 'FAILED'].map((s) => <MenuItem key={s} value={s}>{humanise(s)}</MenuItem>)}
          </TextField>
        </Grid>
        <Grid size={{ xs: 6, md: 4 }}>
          <TextField select size="small" label="Type" value={kind} onChange={(e) => { setKind(e.target.value); setPage(0); }}>
            <MenuItem value="">All</MenuItem><MenuItem value="EVENT">Event messages</MenuItem><MenuItem value="BULK">Bulk messages</MenuItem><MenuItem value="SECURITY">Verification codes</MenuItem>
          </TextField>
        </Grid>
      </Grid>
      <ErrorAlert error={q.error} />
      {q.isLoading ? <Loading /> : <MessagesTable rows={q.data?.content ?? []} />}
      {q.data && !q.data.content.length && <Typography color="text.secondary" sx={{ py: 2 }}>No messages match.</Typography>}
      {q.data && <TablePagination component="div" count={q.data.totalElements} page={page} rowsPerPage={25} rowsPerPageOptions={[25]} onPageChange={(_, p) => setPage(p)} />}
    </CardContent></Card>
  );
}

// ============================================================ event message templates (FR-COM-01, 03)

function TemplatesTab() {
  const { isAdmin } = useRoles();
  const [editing, setEditing] = useState<MessageTemplateView | null>(null);
  const q = useQuery({ queryKey: ['staff', 'messaging', 'templates'], queryFn: () => api<MessageTemplateView[]>('/api/messaging/templates', staff) });
  const overview = useOverview();
  if (q.isLoading) return <Loading />;
  const groups = [...new Set(q.data?.map((t) => t.group) ?? [])];
  const lang = overview.data?.defaultLanguage ?? 'EN';
  return (
    <Stack spacing={2}>
      <Alert severity="info">These messages go out automatically when something happens. They are sent in {lang === 'SW' ? 'Kiswahili' : 'English'} (change it under Overview → Settings); both wordings are kept so you can switch at any time.</Alert>
      <ErrorAlert error={q.error} />
      {groups.map((g) => (
        <Card key={g}><CardContent>
          <Typography variant="h6" sx={{ mb: 1 }}>{g}</Typography>
          <Stack divider={<Divider flexItem />}>
            {q.data!.filter((t) => t.group === g).map((t) => (
              <Stack key={t.code} direction={{ xs: 'column', md: 'row' }} spacing={{ xs: 0.5, md: 2 }} sx={{ py: 1.25, opacity: t.enabled ? 1 : 0.55 }}>
                <Box sx={{ width: { md: 240 }, flexShrink: 0 }}>
                  <Typography sx={{ fontWeight: 600 }}>{t.name}</Typography>
                  <Typography variant="caption" color="text.secondary" display="block">{t.when}</Typography>
                  <Stack direction="row" spacing={0.5} sx={{ mt: 0.5 }}>
                    {t.customised && <Chip size="small" color="primary" variant="outlined" label="Reworded" />}
                    {!t.enabled && <Chip size="small" label="Switched off" />}
                  </Stack>
                </Box>
                <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                  <Typography variant="body2">{lang === 'SW' ? t.sampleSw : t.sampleEn}</Typography>
                  <Typography variant="caption" color="text.secondary">{lang === 'SW' ? t.partsSw : t.partsEn} SMS with example values</Typography>
                </Box>
                {isAdmin && <Box><Button size="small" startIcon={<EditIcon />} onClick={() => setEditing(t)}>Edit</Button></Box>}
              </Stack>
            ))}
          </Stack>
        </CardContent></Card>
      ))}
      {editing && <TemplateDialog t={editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

function TemplateDialog({ t, onClose }: { t: MessageTemplateView; onClose: () => void }) {
  const qc = useQueryClient();
  const [en, setEn] = useState(t.bodyEn);
  const [sw, setSw] = useState(t.bodySw);
  const [enabled, setEnabled] = useState(t.enabled);
  const done = () => { qc.invalidateQueries({ queryKey: ['staff', 'messaging', 'templates'] }); onClose(); };
  const save = useMutation({ mutationFn: () => api(`/api/messaging/templates/${t.code}`, { ...staff, method: 'PUT', body: { bodyEn: en, bodySw: sw, enabled } }), onSuccess: done });
  const reset = useMutation({ mutationFn: () => api(`/api/messaging/templates/${t.code}`, { ...staff, method: 'DELETE' }), onSuccess: done });
  const field = (label: string, value: string, set: (v: string) => void) => {
    const est = smsParts(value.replace(/\{[A-Za-z]+\}/g, 'xxxxxxxxxx'));
    return (
      <TextField multiline minRows={3} label={label} value={value} onChange={(e) => set(e.target.value)} inputProps={{ maxLength: 700 }}
        helperText={`About ${est.parts} SMS${est.unicode ? ' · special characters make each SMS shorter' : ''}`} />
    );
  };
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{t.name}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Sent when: {t.when.toLowerCase()}.</Typography>
        <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" alignItems="center" sx={{ mb: 2 }}>
          <Typography variant="body2" color="text.secondary">Can include:</Typography>
          {t.variables.map((v) => <Chip key={v} size="small" variant={t.required.includes(v) ? 'filled' : 'outlined'} label={`{${v}}`} />)}
          {!!t.required.length && <Typography variant="caption" color="text.secondary">(filled = must be included)</Typography>}
        </Stack>
        <Stack spacing={2}>
          {field('English', en, setEn)}
          {field('Kiswahili', sw, setSw)}
          <FormControlLabel control={<Switch checked={enabled} disabled={t.code === 'OTP_CODE'} onChange={(e) => setEnabled(e.target.checked)} />}
            label={t.code === 'OTP_CODE' ? 'Always sent (applicants need it to sign up)' : 'Send this message'} />
          <ErrorAlert error={save.error ?? reset.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        {t.customised && <Button color="inherit" sx={{ mr: 'auto' }} disabled={reset.isPending} onClick={() => reset.mutate()}>Restore the original wording</Button>}
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!en.trim() || !sw.trim() || save.isPending} onClick={() => save.mutate()}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}
