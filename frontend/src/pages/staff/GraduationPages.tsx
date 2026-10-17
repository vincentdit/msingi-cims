import AddIcon from '@mui/icons-material/Add';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import DownloadIcon from '@mui/icons-material/Download';
import PrintIcon from '@mui/icons-material/Print';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, Step, StepLabel, Stepper,
  Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime } from '../../api/client';
import type { Graduand, GraduandGenerateResult, GraduationDetail, GraduationStatus, GraduationSummary } from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };
const REGISTRY = ['INSTITUTION_ADMIN', 'REGISTRAR'];
export const GRAD_STAGE: Record<GraduationStatus, string> = { DRAFT: 'Being prepared', SUBMITTED: 'With the Governing Board', APPROVED: 'Approved by the Board' };
const STAGE_COLOUR = { DRAFT: 'default', SUBMITTED: 'warning', APPROVED: 'success' } as const;

export function GraduationsPage() {
  const navigate = useNavigate();
  const can = useRoles();
  const [creating, setCreating] = useState(false);
  const q = useQuery({ queryKey: ['staff', 'graduations'], queryFn: () => api<GraduationSummary[]>('/api/graduations', staff) });
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Graduation" subtitle="Graduand lists from finalists with their award and full clearance, Governing Board approval, NACTVET submission and certificates."
        action={can(REGISTRY) && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setCreating(true)} sx={{ whiteSpace: 'nowrap' }}>New graduation</Button>} />
      <ErrorAlert error={q.error} />
      {q.isLoading && <Loading />}
      {q.data && (
        <TableContainer component={Card}>
          <Table size="small">
            <TableHead><TableRow>
              <TableCell>Graduation</TableCell><TableCell>Ceremony</TableCell><TableCell align="right">Graduands</TableCell>
              <TableCell align="right">Certificates</TableCell><TableCell>Stage</TableCell><TableCell>NACTVET</TableCell>
            </TableRow></TableHead>
            <TableBody>
              {q.data.map((g) => (
                <TableRow key={g.id} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/graduations/${g.id}`)}>
                  <TableCell sx={{ fontWeight: 600 }}>{g.name}</TableCell>
                  <TableCell>{g.ceremonyDate ? formatDate(g.ceremonyDate) : '—'}</TableCell>
                  <TableCell align="right">{g.graduands}</TableCell>
                  <TableCell align="right">{g.certificates}</TableCell>
                  <TableCell><Chip size="small" color={STAGE_COLOUR[g.status]} label={GRAD_STAGE[g.status]} /></TableCell>
                  <TableCell>{g.nactvetStatus ? g.nactvetStatus.charAt(0) + g.nactvetStatus.slice(1).toLowerCase() : '—'}</TableCell>
                </TableRow>
              ))}
              {!q.data.length && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 1 }}>No graduations yet.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {creating && <GraduationDialog onClose={() => setCreating(false)} onDone={(id) => navigate(`/staff/graduations/${id}`)} />}
    </Container>
  );
}

function GraduationDialog({ existing, onClose, onDone }: { existing?: GraduationDetail; onClose: () => void; onDone: (id: number) => void }) {
  const [name, setName] = useState(existing?.name ?? '');
  const [date, setDate] = useState(existing?.ceremonyDate ?? '');
  const m = useMutation({
    mutationFn: () => api<GraduationDetail>(existing ? `/api/graduations/${existing.id}` : '/api/graduations', {
      ...staff, method: existing ? 'PUT' : 'POST', body: { name: name.trim(), ceremonyDate: date || null },
    }),
    onSuccess: (g) => onDone(g.id),
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>{existing ? 'Graduation details' : 'New graduation'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. 12th Graduation Ceremony 2028" slotProps={{ htmlInput: { maxLength: 120 } }} />
          <TextField type="date" label="Ceremony date" value={date} onChange={(e) => setDate(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <ErrorAlert error={m.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!name.trim() || m.isPending} onClick={() => m.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

// ============================================================ one graduation

export function GraduationPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'graduation', id], queryFn: () => api<GraduationDetail>(`/api/graduations/${id}`, staff) });
  const [result, setResult] = useState<GraduandGenerateResult | null>(null);
  const [dialog, setDialog] = useState<'edit' | 'approve' | 'return' | 'nactvet' | 'reprint' | null>(null);
  const [reprintOf, setReprintOf] = useState<Graduand | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff', 'graduation'] });
  const act = useMutation({
    mutationFn: (what: 'generate' | 'submit' | 'certificates') => api<unknown>(`/api/graduations/${id}/${what}`, { ...staff, method: 'POST' }),
    onSuccess: (r, what) => {
      if (what === 'generate') setResult(r as GraduandGenerateResult);
      if (what === 'certificates') {
        const x = r as { issued: number; alreadyIssued: number };
        setNotice(x.issued ? `${x.issued} certificate${x.issued === 1 ? '' : 's'} numbered and ready to print.` : 'Every graduand already has a certificate.');
      }
      if (what === 'submit') setNotice('Sent to the Governing Board.');
      refresh();
    },
  });
  const print = useMutation({
    mutationFn: (p: { graduand?: Graduand; reason?: string }) => download(`/api/graduations/${id}/certificates/print${p.graduand ? `?graduandId=${p.graduand.id}` : ''}`, 'staff',
      p.graduand ? `certificate-${p.graduand.registrationNo.replace(/\W+/g, '-')}.pdf` : `certificates-${id}.pdf`, 'POST', p.reason ? { reason: p.reason } : undefined),
    onSuccess: () => { setDialog(null); setReprintOf(null); refresh(); },
  });
  const g = q.data;
  const steps = ['Graduand list', 'Governing Board', 'NACTVET and certificates'];
  const active = !g ? 0 : g.status === 'DRAFT' ? 0 : g.status === 'SUBMITTED' ? 1 : 2;
  const printAll = () => {
    if (g!.graduands.some((x) => x.certificateNo && x.printCount > 0)) { setReprintOf(null); setDialog('reprint'); } else print.mutate({});
  };

  return (
    <Container maxWidth="lg" disableGutters>
      <Button component={StaffLink} to="/staff/graduations" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>Graduation</Button>
      <ErrorAlert error={q.error ?? act.error ?? print.error} />
      {q.isLoading && <Loading />}
      {g && (
        <>
          <PageTitle title={g.name} subtitle={`${g.ceremonyDate ? `Ceremony ${formatDate(g.ceremonyDate)}` : 'Ceremony date not set'} · created by ${g.createdByName}`}
            action={g.can.edit && <Button onClick={() => setDialog('edit')}>Details</Button>} />
          <Card sx={{ mb: 2 }}><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
            <Stepper activeStep={g.status === 'APPROVED' && g.nactvetStatus ? 3 : active} alternativeLabel>
              {steps.map((s, i) => (
                <Step key={s}><StepLabel optional={<Typography variant="caption" color="text.secondary">
                  {i === 0 && g.submittedAt ? `${g.submittedByName}, ${formatDate(g.submittedAt)}` : i === 1 && g.approvedAt ? `${g.boardMinuteRef} · ${formatDate(g.approvedAt)}`
                    : i === 2 && g.nactvetStatus ? `NACTVET: ${g.nactvetStatus.toLowerCase()}` : ''}
                </Typography>}>{s}</StepLabel></Step>
              ))}
            </Stepper>
          </CardContent></Card>
          {notice && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>{notice}</Alert>}
          {g.returnedNote && g.status === 'DRAFT' && <Alert severity="warning" sx={{ mb: 2 }}>Returned by {g.returnedByName}: {g.returnedNote}</Alert>}
          {g.status === 'SUBMITTED' && !g.can.approve && <Alert severity="info" sx={{ mb: 2 }}>With the Governing Board since {formatDateTime(g.submittedAt!)}. Another Board member records the approval.</Alert>}

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mb: 2 }} useFlexGap flexWrap="wrap">
            {g.can.generate && <Button variant="contained" onClick={() => act.mutate('generate')} disabled={act.isPending}>{g.generatedAt ? 'Refresh the list' : 'Generate the graduand list'}</Button>}
            {g.can.submit && <Button variant="outlined" color="success" onClick={() => act.mutate('submit')} disabled={act.isPending}>Send to the Governing Board</Button>}
            {g.can.approve && <>
              <Button variant="contained" color="success" onClick={() => setDialog('approve')}>Record Board approval</Button>
              <Button color="warning" onClick={() => setDialog('return')}>Return to Registry</Button>
            </>}
            {g.can.issue && <>
              <Button variant="contained" onClick={() => act.mutate('certificates')} disabled={act.isPending}>Issue certificates</Button>
              <Button startIcon={<PrintIcon />} onClick={printAll} disabled={print.isPending || !g.graduands.some((x) => x.certificateNo)}>Print all certificates</Button>
            </>}
            {g.can.nactvet && <>
              <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/graduations/${g.id}/nactvet-file`, 'staff', `nactvet-graduands-${g.id}.xlsx`)}>NACTVET graduand file</Button>
              <Button onClick={() => setDialog('nactvet')}>Record NACTVET submission</Button>
            </>}
          </Stack>
          {g.generatedAt && g.status === 'DRAFT' && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 1 }}>List generated {formatDateTime(g.generatedAt)} by {g.generatedByName}.</Typography>}

          {result && (
            <Alert severity={result.notReady.length ? 'warning' : 'success'} sx={{ mb: 2 }} onClose={() => setResult(null)}>
              {result.total} graduand{result.total === 1 ? '' : 's'} listed ({result.added} added, {result.removed} removed).
              {result.notReady.length > 0 && <>
                <Box sx={{ mt: 1, fontWeight: 600 }}>Finalists not ready yet:</Box>
                <Box component="ul" sx={{ m: 0, pl: 2 }}>{result.notReady.map((n) => <li key={n.studentId}>{n.registrationNo} {n.studentName} ({n.programmeCode}): {n.reason}</li>)}</Box>
              </>}
            </Alert>
          )}

          {g.nactvetStatus && (
            <Alert severity={g.nactvetStatus === 'ACCEPTED' ? 'success' : g.nactvetStatus === 'QUERIED' ? 'warning' : 'info'} sx={{ mb: 2 }}>
              NACTVET: {g.nactvetStatus.toLowerCase()}{g.nactvetReference && ` · ${g.nactvetReference}`}{g.nactvetSubmittedAt && ` · sent ${formatDate(g.nactvetSubmittedAt)}`}{g.nactvetNote && ` · ${g.nactvetNote}`}
            </Alert>
          )}

          <Grid container spacing={2} sx={{ mb: 2 }}>
            {Object.entries(g.graduands.reduce<Record<string, number>>((m, x) => ({ ...m, [x.classification ?? 'Unclassified']: (m[x.classification ?? 'Unclassified'] ?? 0) + 1 }), {}))
              .map(([k, n]) => (
                <Grid key={k} size={{ xs: 6, md: 3 }}><Card><CardContent>
                  <Typography variant="caption" color="text.secondary">{k}</Typography>
                  <Typography variant="h5" sx={{ fontWeight: 700 }}>{n}</Typography>
                </CardContent></Card></Grid>
              ))}
          </Grid>

          <TableContainer component={Card}>
            <Table size="small">
              <TableHead><TableRow>
                <TableCell>Graduand</TableCell><TableCell>Award</TableCell><TableCell align="right">GPA</TableCell>
                <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>Completed</TableCell><TableCell>Certificate</TableCell>
              </TableRow></TableHead>
              <TableBody>
                {g.graduands.map((x) => (
                  <TableRow key={x.id}>
                    <TableCell>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{x.registrationNo}</Typography>
                      <Typography variant="caption" color="text.secondary">{x.studentName} · {x.programmeCode}</Typography>
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2">{x.awardName}</Typography>
                      <Typography variant="caption" color="text.secondary">{x.classification ?? '—'}</Typography>
                    </TableCell>
                    <TableCell align="right">{x.cgpa ?? '—'}</TableCell>
                    <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>{x.completedSemester}</TableCell>
                    <TableCell>
                      {x.certificateNo ? (
                        <Stack direction="row" spacing={1} alignItems="center">
                          <Box>
                            <Typography variant="body2" sx={{ fontFamily: 'monospace' }}>{x.certificateNo}</Typography>
                            <Typography variant="caption" color="text.secondary">
                              {x.printCount ? `Printed ${x.printCount}×, last ${formatDate(x.lastPrintedAt!)}` : 'Not printed yet'}
                            </Typography>
                          </Box>
                          {g.can.issue && <Tooltip title={x.printCount ? 'Reprint (needs a reason)' : 'Print'}>
                            <Button size="small" onClick={() => (x.printCount ? (setReprintOf(x), setDialog('reprint')) : print.mutate({ graduand: x }))}>
                              {x.printCount ? 'Reprint' : 'Print'}
                            </Button>
                          </Tooltip>}
                        </Stack>
                      ) : <Typography variant="body2" color="text.secondary">—</Typography>}
                    </TableCell>
                  </TableRow>
                ))}
                {!g.graduands.length && <TableRow><TableCell colSpan={5}><Typography color="text.secondary" sx={{ py: 1 }}>
                  {g.status === 'DRAFT' ? 'Generate the list: finalists with the award and full clearance are added.' : 'No graduands.'}
                </Typography></TableCell></TableRow>}
              </TableBody>
            </Table>
          </TableContainer>

          {dialog === 'edit' && <GraduationDialog existing={g} onClose={() => setDialog(null)} onDone={() => { setDialog(null); refresh(); }} />}
          {dialog === 'approve' && <TextDialog title="Record the Governing Board's approval" label="Board minute reference" max={100} confirm="Approve graduands"
            help="Every graduand becomes a graduate and is told by SMS." onClose={() => setDialog(null)}
            send={(v) => api(`/api/graduations/${g.id}/approve`, { ...staff, method: 'POST', body: { boardMinuteRef: v } })} onDone={() => { setDialog(null); setNotice('Graduands approved.'); refresh(); }} />}
          {dialog === 'return' && <TextDialog title="Return the list to Registry" label="What needs changing" max={1000} confirm="Return" multiline onClose={() => setDialog(null)}
            send={(v) => api(`/api/graduations/${g.id}/return`, { ...staff, method: 'POST', body: { note: v } })} onDone={() => { setDialog(null); refresh(); }} />}
          {dialog === 'reprint' && <TextDialog title={reprintOf ? `Reprint ${reprintOf.certificateNo}` : 'Print all certificates again'} label="Reason for the reprint" max={300} minLength={5}
            confirm="Print" help="Every print is logged with this reason; the reprint is marked as such." onClose={() => { setDialog(null); setReprintOf(null); }}
            send={(v) => print.mutateAsync({ graduand: reprintOf ?? undefined, reason: v })} onDone={() => undefined} />}
          {dialog === 'nactvet' && <NactvetDialog g={g} onClose={() => setDialog(null)} onDone={() => { setDialog(null); refresh(); }} />}
        </>
      )}
    </Container>
  );
}

function TextDialog({ title, label, help, confirm, max, minLength = 1, multiline, onClose, send, onDone }: {
  title: string; label: string; help?: string; confirm: string; max: number; minLength?: number; multiline?: boolean; onClose: () => void;
  send: (v: string) => Promise<unknown>; onDone: () => void;
}) {
  const [v, setV] = useState('');
  const m = useMutation({ mutationFn: () => send(v.trim()), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <TextField fullWidth sx={{ mt: 1 }} label={label} value={v} onChange={(e) => setV(e.target.value)} multiline={multiline} minRows={multiline ? 2 : undefined}
          helperText={help} slotProps={{ htmlInput: { maxLength: max } }} />
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={v.trim().length < minLength || m.isPending} onClick={() => m.mutate()}>{confirm}</Button></DialogActions>
    </Dialog>
  );
}

function NactvetDialog({ g, onClose, onDone }: { g: GraduationDetail; onClose: () => void; onDone: () => void }) {
  const [status, setStatus] = useState<'SUBMITTED' | 'ACCEPTED' | 'QUERIED'>(g.nactvetStatus ? 'ACCEPTED' : 'SUBMITTED');
  const [reference, setReference] = useState(g.nactvetReference ?? '');
  const [note, setNote] = useState('');
  const m = useMutation({
    mutationFn: () => api(`/api/graduations/${g.id}/nactvet`, { ...staff, method: 'PUT', body: { status, reference: reference.trim() || null, note: note.trim() || null } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>NACTVET graduand submission</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select label="Status" value={status} onChange={(e) => setStatus(e.target.value as typeof status)}>
            <MenuItem value="SUBMITTED">Sent to NACTVET</MenuItem>
            <MenuItem value="ACCEPTED" disabled={!g.nactvetStatus}>Accepted by NACTVET</MenuItem>
            <MenuItem value="QUERIED" disabled={!g.nactvetStatus}>Queried by NACTVET</MenuItem>
          </TextField>
          <TextField label="NACTVET reference" value={reference} onChange={(e) => setReference(e.target.value)} slotProps={{ htmlInput: { maxLength: 100 } }} />
          <TextField label="Note" value={note} onChange={(e) => setNote(e.target.value)} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 1000 } }} />
          <ErrorAlert error={m.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={m.isPending} onClick={() => m.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}
