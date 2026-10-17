import DownloadIcon from '@mui/icons-material/Download';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, download, formatDate, formatDateTime } from '../../api/client';
import type { AcademicDocumentView, Amendment, StudentModuleRecord } from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { useRoles } from './MarksPages';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };
const num = (n?: number | null) => (n == null ? '—' : Number(n).toLocaleString('en-GB', { maximumFractionDigits: 2 }));
const STATUS_COLOUR = { PENDING: 'warning', APPROVED: 'success', REJECTED: 'default' } as const;

// ============================================================ amendments to confirmed results

/** Requests to correct confirmed results: requested by the Examinations Officer, approved by the administrator (someone else). */
export function AmendmentsPage() {
  const qc = useQueryClient();
  const list = useQuery({ queryKey: ['staff', 'amendments'], queryFn: () => api<Amendment[]>('/api/exams/amendments', staff) });
  const [deciding, setDeciding] = useState<{ a: Amendment; approve: boolean } | null>(null);
  const pending = (list.data ?? []).filter((a) => a.status === 'PENDING');
  const done = (list.data ?? []).filter((a) => a.status !== 'PENDING');
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Result amendments" subtitle="Confirmed results change only through an amendment: requested with a reason from the student's record, approved by someone else, and fully audited." />
      <ErrorAlert error={list.error} />
      {list.isLoading && <Loading />}
      {list.data && (
        <Stack spacing={3}>
          <Box>
            <Typography variant="h6" sx={{ mb: 1 }}>Waiting for approval ({pending.length})</Typography>
            {!pending.length && <Alert severity="info">No amendments are waiting. The Examinations Officer requests one from the Results card of a student's record.</Alert>}
            <Stack spacing={1.5}>
              {pending.map((a) => <AmendmentCard key={a.id} a={a} onDecide={(approve) => setDeciding({ a, approve })} />)}
            </Stack>
          </Box>
          {done.length > 0 && (
            <Box>
              <Typography variant="h6" sx={{ mb: 1 }}>Decided</Typography>
              <TableContainer component={Card}>
                <Table size="small">
                  <TableHead><TableRow><TableCell>Student</TableCell><TableCell>Module</TableCell><TableCell>Change</TableCell><TableCell>Reason</TableCell><TableCell>Decision</TableCell></TableRow></TableHead>
                  <TableBody>
                    {done.map((a) => (
                      <TableRow key={a.id}>
                        <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{a.registrationNo}</Typography><Typography variant="caption" color="text.secondary">{a.studentName}</Typography></TableCell>
                        <TableCell>{a.moduleCode}<br /><Typography variant="caption" color="text.secondary">{a.semester}</Typography></TableCell>
                        <TableCell>{a.oldGrade ?? '—'} ({num(a.oldTotal)}) → {a.newGrade} ({num(a.newTotal)})</TableCell>
                        <TableCell sx={{ maxWidth: 280 }}>{a.reason}</TableCell>
                        <TableCell><Chip size="small" color={STATUS_COLOUR[a.status]} label={a.status === 'APPROVED' ? 'Applied' : 'Rejected'} />
                          <Typography variant="caption" color="text.secondary" display="block">{a.decidedByName}, {formatDate(a.decidedAt)}</Typography></TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            </Box>
          )}
        </Stack>
      )}
      {deciding && <DecideDialog a={deciding.a} approve={deciding.approve} onClose={() => setDeciding(null)}
        onDone={() => { setDeciding(null); qc.invalidateQueries({ queryKey: ['staff', 'amendments'] }); }} />}
    </Container>
  );
}

function AmendmentCard({ a, onDecide }: { a: Amendment; onDecide: (approve: boolean) => void }) {
  return (
    <Card>
      <CardContent>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} justifyContent="space-between">
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="h6" sx={{ fontSize: 17 }}>{a.moduleCode} · {a.moduleTitle}</Typography>
            <Typography variant="body2" color="text.secondary">
              <StaffLink to={`/staff/students/${a.studentId}`}>{a.registrationNo}</StaffLink> {a.studentName} · {a.semester}
            </Typography>
            <Table size="small" sx={{ my: 1, maxWidth: 420 }}>
              <TableHead><TableRow><TableCell /><TableCell align="right">CA</TableCell><TableCell align="right">SE</TableCell><TableCell align="right">Total</TableCell><TableCell>Grade</TableCell></TableRow></TableHead>
              <TableBody>
                <TableRow><TableCell>Confirmed</TableCell><TableCell align="right">{num(a.oldCaTotal)}</TableCell><TableCell align="right">{num(a.oldSeTotal)}</TableCell>
                  <TableCell align="right">{num(a.oldTotal)}</TableCell><TableCell>{a.oldGrade ?? a.oldRemark}</TableCell></TableRow>
                <TableRow sx={{ '& td': { fontWeight: 700 } }}><TableCell>Corrected</TableCell><TableCell align="right">{num(a.newCaTotal)}</TableCell><TableCell align="right">{num(a.newSeTotal)}</TableCell>
                  <TableCell align="right">{num(a.newTotal)}</TableCell><TableCell>{a.newGrade} {a.newRemark === 'FAIL' && <Chip size="small" color="error" label="Fail" />}</TableCell></TableRow>
              </TableBody>
            </Table>
            <Typography variant="body2"><strong>Reason:</strong> {a.reason}{a.evidenceRef && ` (evidence: ${a.evidenceRef})`}</Typography>
            <Typography variant="caption" color="text.secondary">Requested by {a.requestedByName}, {formatDateTime(a.requestedAt)}</Typography>
          </Box>
          {a.canDecide ? (
            <Stack spacing={1} sx={{ flexShrink: 0 }}>
              <Button variant="contained" color="success" onClick={() => onDecide(true)}>Approve and apply</Button>
              <Button color="inherit" onClick={() => onDecide(false)}>Reject</Button>
            </Stack>
          ) : <Typography variant="body2" color="text.secondary" sx={{ flexShrink: 0 }}>Waiting for the administrator</Typography>}
        </Stack>
      </CardContent>
    </Card>
  );
}

function DecideDialog({ a, approve, onClose, onDone }: { a: Amendment; approve: boolean; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('');
  const m = useMutation({ mutationFn: () => api(`/api/exams/amendments/${a.id}/${approve ? 'approve' : 'reject'}`, { ...staff, method: 'POST', body: { note } }), onSuccess: onDone });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{approve ? 'Apply this amendment?' : 'Reject this amendment'}</DialogTitle>
      <DialogContent>
        <Typography sx={{ mb: 2 }}>{a.registrationNo} · {a.moduleCode}: {a.oldGrade ?? '—'} → <strong>{a.newGrade}</strong>.
          {approve && ' The confirmed result is changed, GPA and decisions are recomputed and the student is told by SMS.'}</Typography>
        <TextField fullWidth multiline minRows={2} label={approve ? 'Note (optional)' : 'Why is it rejected?'} value={note} onChange={(e) => setNote(e.target.value)} />
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color={approve ? 'success' : 'warning'} disabled={(!approve && !note.trim()) || m.isPending} onClick={() => m.mutate()}>{approve ? 'Approve and apply' : 'Reject'}</Button></DialogActions>
    </Dialog>
  );
}

// ============================================================ the Results card of a student's record

/** Statements of results (FR-EXM-13) and, for the Examinations Officer, corrections to confirmed results. */
export function StudentExamCard({ studentId, regNo }: { studentId: number; regNo: string }) {
  const can = useRoles();
  const issuer = can(['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER', 'REGISTRAR']);
  const office = can(['INSTITUTION_ADMIN', 'EXAMINATIONS_OFFICER']);
  const qc = useQueryClient();
  const docs = useQuery({ queryKey: ['staff', 'student', studentId, 'transcripts'], enabled: issuer,
    queryFn: () => api<AcademicDocumentView[]>(`/api/exams/students/${studentId}/transcripts`, staff) });
  const records = useQuery({ queryKey: ['staff', 'student', studentId, 'module-results'], enabled: office,
    queryFn: () => api<StudentModuleRecord[]>(`/api/exams/students/${studentId}/module-results`, staff) });
  const issue = useMutation({
    mutationFn: () => download(`/api/exams/students/${studentId}/transcript`, 'staff', `statement-of-results-${regNo.replace(/\W+/g, '-')}.pdf`, 'POST'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staff', 'student', studentId, 'transcripts'] }),
  });
  const [amending, setAmending] = useState<StudentModuleRecord | null>(null);
  const [requested, setRequested] = useState(false);
  if (!issuer) return null;
  return (
    <Card sx={{ mb: 2.5 }}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
          <Typography variant="h6">Results</Typography>
          <Button size="small" startIcon={<DownloadIcon />} onClick={() => issue.mutate()} disabled={issue.isPending}>Statement of results</Button>
        </Stack>
        <ErrorAlert error={issue.error ?? docs.error ?? records.error} />
        {docs.data && docs.data.length > 0 && (
          <Stack spacing={0.5} sx={{ mb: 1.5 }}>
            {docs.data.slice(0, 4).map((d) => (
              <Stack key={d.id} direction="row" spacing={1} alignItems="center">
                <Typography variant="body2" sx={{ flexGrow: 1 }}>{d.documentNo} · {formatDate(d.issuedAt)} · CGPA {d.cgpa ?? '—'}</Typography>
                <Chip size="small" color={d.status === 'VALID' ? 'success' : 'default'} label={d.status === 'VALID' ? 'Valid' : d.status === 'SUPERSEDED' ? 'Superseded' : 'Revoked'} />
                <Button size="small" onClick={() => download(`/api/exams/transcripts/${d.id}/pdf`, 'staff', `${d.documentNo.replace(/\W+/g, '-')}.pdf`)}>PDF</Button>
              </Stack>
            ))}
          </Stack>
        )}
        {docs.data && !docs.data.length && <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>No statement issued yet. One can be issued once results are published.</Typography>}
        {requested && <Alert severity="success" sx={{ mb: 1 }} onClose={() => setRequested(false)}>Amendment requested: the administrator approves it under Result amendments.</Alert>}
        {office && records.data && records.data.length > 0 && (
          <Table size="small">
            <TableHead><TableRow><TableCell>Module</TableCell><TableCell align="right">Total</TableCell><TableCell>Grade</TableCell><TableCell /></TableRow></TableHead>
            <TableBody>
              {records.data.map((r) => (
                <TableRow key={r.moduleResultId}>
                  <TableCell><strong>{r.code}</strong><Typography variant="caption" color="text.secondary" display="block">{r.semester}</Typography></TableCell>
                  <TableCell align="right">{num(r.total)}</TableCell>
                  <TableCell>{r.grade ?? r.remark.toLowerCase()}
                    {r.resitKind && <Typography variant="caption" color="text.secondary" display="block">
                      {r.resitKind === 'SPECIAL' ? 'Special exam' : 'Supplementary'}: {r.resitGrade ?? 'not marked yet'}</Typography>}</TableCell>
                  <TableCell align="right">
                    {r.pendingAmendment ? <Chip size="small" color="warning" label="Amendment pending" />
                      : r.locked ? <Button size="small" onClick={() => setAmending(r)}>Correct</Button>
                        : <Typography variant="caption" color="text.secondary">Not confirmed yet</Typography>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
      {amending && <AmendDialog r={amending} onClose={() => setAmending(null)} onDone={() => {
        setAmending(null); setRequested(true); qc.invalidateQueries({ queryKey: ['staff', 'student', studentId, 'module-results'] });
      }} />}
    </Card>
  );
}

function AmendDialog({ r, onClose, onDone }: { r: StudentModuleRecord; onClose: () => void; onDone: () => void }) {
  const [f, setF] = useState({ caTotal: String(r.caTotal ?? ''), seTotal: String(r.seTotal ?? ''), reason: '', evidenceRef: '' });
  const m = useMutation({
    mutationFn: () => api('/api/exams/amendments', { ...staff, method: 'POST', body: { moduleResultId: r.moduleResultId, caTotal: Number(f.caTotal),
      seTotal: Number(f.seTotal), reason: f.reason, evidenceRef: f.evidenceRef || null } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Correct a confirmed result · {r.code}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Confirmed: CA {num(r.caTotal)}, SE {num(r.seTotal)}, total {num(r.total)}, grade {r.grade ?? r.remark.toLowerCase()}. Enter the corrected totals; the administrator approves before anything changes.
        </Typography>
        <Grid container spacing={2}>
          <Grid size={6}><TextField fullWidth type="number" label="Corrected CA total" value={f.caTotal} onChange={(e) => setF({ ...f, caTotal: e.target.value })} /></Grid>
          <Grid size={6}><TextField fullWidth type="number" label="Corrected SE total" value={f.seTotal} onChange={(e) => setF({ ...f, seTotal: e.target.value })} /></Grid>
          <Grid size={12}><TextField fullWidth multiline minRows={2} label="Reason" value={f.reason} onChange={(e) => setF({ ...f, reason: e.target.value })}
            placeholder="e.g. Marks of the second script were not added" /></Grid>
          <Grid size={12}><TextField fullWidth label="Evidence reference (optional)" value={f.evidenceRef} onChange={(e) => setF({ ...f, evidenceRef: e.target.value })} placeholder="e.g. script number, minute" /></Grid>
        </Grid>
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Cancel</Button><Button variant="contained" disabled={!f.reason.trim() || f.caTotal === '' || f.seTotal === '' || m.isPending} onClick={() => m.mutate()}>Request amendment</Button></DialogActions>
    </Dialog>
  );
}
