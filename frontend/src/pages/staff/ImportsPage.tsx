import DownloadIcon from '@mui/icons-material/Download';
import UploadIcon from '@mui/icons-material/UploadFile';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, Tab, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState } from 'react';
import { api, download, formatDateTime, uploadForm } from '../../api/client';
import type { AdmissionWindow } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';

const staff = { realm: 'staff' as const };
type Kind = 'STUDENTS' | 'RESULTS' | 'BALANCES' | 'SELECTIONS';
interface Batch {
  id: number; batchNo: string; kind: Kind; fileName: string; status: 'VALIDATED' | 'APPLIED' | 'DISCARDED'; totalRows: number; errorRows: number;
  errors: { row: number; field: string; message: string }[]; notes: string[]; windowId?: number; externalBody?: string; uploadedByName: string;
  uploadedAt: string; decidedByName?: string; decidedAt?: string; signOffNote?: string; summary?: Record<string, unknown>; preview: Record<string, string>[];
}

const KINDS: { kind: Kind; label: string; roles: string[]; intro: string }[] = [
  { kind: 'STUDENTS', label: 'Students', roles: ['INSTITUTION_ADMIN', 'REGISTRAR'],
    intro: 'Students already at the institution before CIMS. Each gets a student portal sign-in by registration number; they set a password with "Forgot password" and their phone.' },
  { kind: 'RESULTS', label: 'Earlier results', roles: ['INSTITUTION_ADMIN', 'REGISTRAR', 'EXAMINATIONS_OFFICER'],
    intro: 'Results from before CIMS, one row per module, with the semester GPA and decision repeated on each row of the semester. Shown with the student\'s record and on the student portal.' },
  { kind: 'BALANCES', label: 'Opening balances', roles: ['INSTITUTION_ADMIN', 'BURSAR', 'ACCOUNTANT'],
    intro: 'What each student owed (positive) or had paid in excess (negative) when the institution moved to CIMS. A balance owed becomes an invoice; a credit is used against the next invoice.' },
  { kind: 'SELECTIONS', label: 'External selections', roles: ['INSTITUTION_ADMIN', 'ADMISSIONS_OFFICER'],
    intro: 'Students selected for the institution by TAMISEMI or another body. They join the admission window as selected, go to NACTVET verification and receive offers like other applicants; they are told by SMS to sign up with their index number.' },
];

/** Bringing data in from files: checked first, applied after another person signs off (BR-39, BR-08). */
export default function ImportsPage() {
  const { user } = useSession();
  const roles = user('staff')?.roles ?? [];
  const mine = KINDS.filter((k) => k.roles.some((r) => roles.includes(r)));
  const [kind, setKind] = useState<Kind>(mine[0]?.kind ?? 'STUDENTS');
  const k = mine.find((x) => x.kind === kind) ?? mine[0];
  if (!k) return <Alert severity="info">Imports are not part of your role.</Alert>;
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Data import" subtitle="Bring records in from a CSV or Excel file. Every row is checked first; nothing changes until someone else signs the file off." />
      <Tabs value={k.kind} onChange={(_, v) => setKind(v)} sx={{ mb: 2.5 }} variant="scrollable" allowScrollButtonsMobile>
        {mine.map((x) => <Tab key={x.kind} value={x.kind} label={x.label} />)}
      </Tabs>
      <KindPanel key={k.kind} k={k} />
    </Container>
  );
}

function KindPanel({ k }: { k: (typeof KINDS)[number] }) {
  const qc = useQueryClient();
  const { user } = useSession();
  const list = useQuery({ queryKey: ['imports', k.kind], queryFn: () => api<Batch[]>(`/api/imports?kind=${k.kind}`, staff) });
  const windows = useQuery({ queryKey: ['staff', 'windows'], enabled: k.kind === 'SELECTIONS', queryFn: () => api<AdmissionWindow[]>('/api/admission/windows', staff) });
  const bodies = useQuery({ queryKey: ['imports', 'bodies'], enabled: k.kind === 'SELECTIONS', queryFn: () => api<string[]>('/api/imports/external-bodies', staff) });
  const [windowId, setWindowId] = useState('');
  const [body, setBody] = useState('TAMISEMI');
  const [open, setOpen] = useState<Batch | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const up = useMutation({
    mutationFn: (f: File) => {
      const form = new FormData(); form.append('file', f);
      const q = k.kind === 'SELECTIONS' ? `?windowId=${windowId}&externalBody=${body}` : '';
      return uploadForm<Batch>(`/api/imports/${k.kind}${q}`, 'staff', form, 'POST');
    },
    onSuccess: (b) => { qc.invalidateQueries({ queryKey: ['imports', k.kind] }); setOpen(b); },
  });
  const ready = k.kind !== 'SELECTIONS' || !!windowId;
  return (
    <Stack spacing={2.5}>
      <Card><CardContent>
        <Typography variant="body2" sx={{ mb: 2 }}>{k.intro}</Typography>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }}>
          <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/imports/templates/${k.kind}`, 'staff', `${k.kind.toLowerCase()}-template.csv`)}>Template</Button>
          {k.kind === 'SELECTIONS' && (
            <>
              <TextField select size="small" label="Admission window" value={windowId} onChange={(e) => setWindowId(e.target.value)} sx={{ minWidth: 260 }}>
                {(windows.data ?? []).filter((w) => w.state !== 'DRAFT').map((w) => <MenuItem key={w.id} value={w.id}>{w.name}</MenuItem>)}
              </TextField>
              <TextField select size="small" label="Selected by" value={body} onChange={(e) => setBody(e.target.value)} sx={{ minWidth: 200 }}>
                {(bodies.data ?? ['TAMISEMI']).map((b) => <MenuItem key={b} value={b}>{b.replace(/_/g, ' ')}</MenuItem>)}
              </TextField>
            </>
          )}
          <Button variant="contained" startIcon={<UploadIcon />} disabled={!ready || up.isPending} onClick={() => input.current?.click()}>
            {up.isPending ? 'Checking…' : 'Upload and check'}
          </Button>
          <input ref={input} type="file" hidden accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            onChange={(e) => { const f = e.target.files?.[0]; if (f) up.mutate(f); e.target.value = ''; }} />
        </Stack>
        <ErrorAlert error={up.error} />
      </CardContent></Card>
      <Card>
        {list.isLoading ? <Loading /> : (
          <TableContainer>
            <Table size="small">
              <TableHead><TableRow><TableCell>File</TableCell><TableCell>Rows</TableCell><TableCell>Uploaded</TableCell><TableCell>Status</TableCell><TableCell /></TableRow></TableHead>
              <TableBody>
                {(list.data ?? []).map((b) => (
                  <TableRow key={b.id} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(b)}>
                    <TableCell><b>{b.batchNo}</b> · {b.fileName}{b.externalBody ? ` · ${b.externalBody}` : ''}</TableCell>
                    <TableCell>{b.totalRows}{b.errorRows ? <Chip size="small" color="error" label={`${b.errorRows} with problems`} sx={{ ml: 1 }} /> : null}</TableCell>
                    <TableCell>{b.uploadedByName}, {formatDateTime(b.uploadedAt)}</TableCell>
                    <TableCell><StatusChip b={b} /></TableCell>
                    <TableCell align="right"><Button size="small">Open</Button></TableCell>
                  </TableRow>
                ))}
                {list.data?.length === 0 && <TableRow><TableCell colSpan={5}><Typography color="text.secondary" sx={{ py: 2 }}>Nothing imported yet.</Typography></TableCell></TableRow>}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </Card>
      {open && <BatchDialog batch={open} me={user('staff')?.fullName} onClose={() => setOpen(null)} />}
    </Stack>
  );
}

function StatusChip({ b }: { b: Batch }) {
  if (b.status === 'APPLIED') return <Chip size="small" color="success" label="Applied" />;
  if (b.status === 'DISCARDED') return <Chip size="small" label="Discarded" />;
  return b.errorRows ? <Chip size="small" color="error" label="Has problems" /> : <Chip size="small" color="warning" label="Waiting for sign-off" />;
}

function BatchDialog({ batch, me, onClose }: { batch: Batch; me?: string; onClose: () => void }) {
  const qc = useQueryClient();
  const [note, setNote] = useState('');
  const done = () => { qc.invalidateQueries({ queryKey: ['imports', batch.kind] }); onClose(); };
  const apply = useMutation({ mutationFn: () => api(`/api/imports/batches/${batch.id}/apply`, { ...staff, method: 'POST', body: { note } }), onSuccess: done });
  const discard = useMutation({ mutationFn: () => api(`/api/imports/batches/${batch.id}/discard`, { ...staff, method: 'POST' }), onSuccess: done });
  const cols = batch.preview.length ? Object.keys(batch.preview[0]).filter((c) => c !== '_row') : [];
  const own = me === batch.uploadedByName;
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="lg">
      <DialogTitle>{batch.batchNo} · {batch.fileName} <StatusChip b={batch} /></DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          {batch.totalRows} rows · uploaded by {batch.uploadedByName}, {formatDateTime(batch.uploadedAt)}
          {batch.decidedByName ? ` · ${batch.status === 'APPLIED' ? 'signed off' : 'discarded'} by ${batch.decidedByName}, ${formatDateTime(batch.decidedAt)}` : ''}
        </Typography>
        {batch.signOffNote && <Alert severity="info" sx={{ mb: 1.5 }}>{batch.signOffNote}</Alert>}
        {batch.summary && <Alert severity="success" sx={{ mb: 1.5 }}>{Object.entries(batch.summary).map(([k, v]) => `${k.replace(/([A-Z])/g, ' $1').toLowerCase()}: ${Array.isArray(v) ? (v.length ? v.join(', ') : 'none') : v}`).join(' · ')}</Alert>}
        {batch.notes.map((n) => <Alert key={n} severity="info" sx={{ mb: 1 }}>{n}</Alert>)}
        {batch.errors.length > 0 && (
          <Box sx={{ mb: 2 }}>
            <Stack direction="row" justifyContent="space-between" alignItems="center">
              <Typography variant="subtitle1" color="error.main">{batch.errorRows} row{batch.errorRows === 1 ? "" : "s"} with problems — correct the file and upload it again</Typography>
              <Button size="small" startIcon={<DownloadIcon />} onClick={() => download(`/api/imports/batches/${batch.id}/errors.csv`, 'staff', `problems-${batch.batchNo}.csv`)}>Problems (CSV)</Button>
            </Stack>
            <Table size="small"><TableHead><TableRow><TableCell>Row</TableCell><TableCell>Column</TableCell><TableCell>Problem</TableCell></TableRow></TableHead>
              <TableBody>{batch.errors.slice(0, 100).map((e, i) => <TableRow key={i}><TableCell>{e.row}</TableCell><TableCell>{e.field}</TableCell><TableCell>{e.message}</TableCell></TableRow>)}</TableBody>
            </Table>
          </Box>
        )}
        <Typography variant="subtitle2" sx={{ mb: 0.5 }}>First rows of the file</Typography>
        <TableContainer sx={{ maxHeight: 280 }}>
          <Table size="small" stickyHeader>
            <TableHead><TableRow><TableCell>Row</TableCell>{cols.map((c) => <TableCell key={c}>{c}</TableCell>)}</TableRow></TableHead>
            <TableBody>{batch.preview.map((r) => <TableRow key={r._row}><TableCell>{r._row}</TableCell>{cols.map((c) => <TableCell key={c}>{r[c] ?? ''}</TableCell>)}</TableRow>)}</TableBody>
          </Table>
        </TableContainer>
        {batch.status === 'VALIDATED' && batch.errorRows === 0 && (
          own ? <Alert severity="warning" sx={{ mt: 2 }}>Someone other than you must sign this file off.</Alert>
            : <TextField fullWidth sx={{ mt: 2 }} label="Sign-off note (optional)" value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. Checked against the 2025 class register" />
        )}
        <ErrorAlert error={apply.error ?? discard.error} />
      </DialogContent>
      <DialogActions>
        {batch.status === 'VALIDATED' && <Button color="inherit" onClick={() => discard.mutate()} disabled={discard.isPending}>Discard</Button>}
        <Box sx={{ flex: 1 }} />
        <Button onClick={onClose}>Close</Button>
        {batch.status === 'VALIDATED' && batch.errorRows === 0 && !own && <Button variant="contained" onClick={() => apply.mutate()} disabled={apply.isPending}>Sign off and apply</Button>}
      </DialogActions>
    </Dialog>
  );
}
