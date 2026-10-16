import DoneAllIcon from '@mui/icons-material/DoneAll';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, Divider, MenuItem, Stack, Table,
  TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { api, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type { Semester, SemesterRegistration, SemesterRegistrationSummary } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';

const staff = { realm: 'staff' as const };
const REGISTRY = ['INSTITUTION_ADMIN', 'REGISTRAR'];
const STATUS_LABEL: Record<string, string> = { SUBMITTED: 'Waiting for approval', APPROVED: 'Approved', REJECTED: 'Returned to student', CANCELLED: 'Withdrawn by student' };
const STATUS_COLOUR = { SUBMITTED: 'info', APPROVED: 'success', REJECTED: 'warning', CANCELLED: 'default' } as const;
const WINDOW_LABEL = { OPEN: 'Registration open', LATE: 'Late registration', NOT_OPEN: 'Not open yet', CLOSED: 'Registration closed' } as const;
const num = (n: number) => Number(n).toLocaleString('en-GB', { maximumFractionDigits: 1 });

/** The semester to show first: one whose registration is running, otherwise the most recent. */
function pickSemester(list: Semester[]) {
  return (list.find((s) => s.window === 'OPEN' || s.window === 'LATE') ?? list.find((s) => s.window === 'CLOSED') ?? list[0])?.id;
}

/** Registry's view of semester registration (FR-REG-03): who has registered, approval one by one or in bulk, return for correction. */
export default function SemesterRegistrationsPage() {
  const qc = useQueryClient();
  const { user } = useSession();
  const canDecide = (user('staff')?.roles ?? []).some((r) => REGISTRY.includes(r));
  const semesters = useQuery({ queryKey: ['staff', 'semesters'], queryFn: () => api<Semester[]>('/api/academic/semesters', staff) });
  const [semesterId, setSemesterId] = useState<number>();
  const [status, setStatus] = useState('SUBMITTED');
  const [open, setOpen] = useState<SemesterRegistration | null>(null);
  const [result, setResult] = useState<{ approved: number; skipped: string[] } | null>(null);
  useEffect(() => { if (!semesterId && semesters.data?.length) setSemesterId(pickSemester(semesters.data)); }, [semesters.data, semesterId]);
  const summary = useQuery({
    queryKey: ['staff', 'semreg', 'summary', semesterId], enabled: !!semesterId,
    queryFn: () => api<SemesterRegistrationSummary>(`/api/registry/semester-registrations/summary?semesterId=${semesterId}`, staff),
  });
  const list = useQuery({
    queryKey: ['staff', 'semreg', 'list', semesterId, status], enabled: !!semesterId,
    queryFn: () => api<SemesterRegistration[]>(`/api/registry/semester-registrations?semesterId=${semesterId}${status ? `&status=${status}` : ''}`, staff),
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff', 'semreg'] });
  const approveAll = useMutation({
    mutationFn: () => api<{ approved: number; skipped: string[] }>('/api/registry/semester-registrations/approve-all', { ...staff, method: 'POST', body: { semesterId } }),
    onSuccess: (r) => { setResult(r); refresh(); },
  });
  const s = summary.data;
  const waiting = s?.byStatus.SUBMITTED ?? 0;

  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Semester registration" subtitle="Students choose their electives and submit in the portal; Registry checks and approves."
        action={
          <TextField select size="small" label="Semester" value={semesterId ?? ''} onChange={(e) => { setSemesterId(Number(e.target.value)); setResult(null); }} sx={{ minWidth: 240 }}>
            {(semesters.data ?? []).map((x) => <MenuItem key={x.id} value={x.id}>{x.name} · {x.academicYear}</MenuItem>)}
          </TextField>
        } />
      <ErrorAlert error={semesters.error ?? summary.error ?? list.error ?? approveAll.error} />
      {semesters.data && !semesters.data.length && (
        <Alert severity="info">No semesters yet. Set them up in the <RouterLink to="/staff/calendar">academic calendar</RouterLink>.</Alert>
      )}
      {s && (
        <Grid container spacing={2} sx={{ mb: 2 }}>
          {[
            { label: 'Current students', value: s.currentStudents, hint: WINDOW_LABEL[s.window] },
            { label: 'Waiting for approval', value: waiting, hint: 'Submitted in the portal', key: 'SUBMITTED' },
            { label: 'Approved', value: s.byStatus.APPROVED ?? 0, hint: 'Registered for the semester', key: 'APPROVED' },
            { label: 'Not registered', value: s.notRegistered, hint: `Returned: ${s.byStatus.REJECTED ?? 0} · withdrawn: ${s.byStatus.CANCELLED ?? 0}` },
          ].map((k) => (
            <Grid key={k.label} size={{ xs: 6, md: 3 }}>
              <Card sx={{ cursor: k.key ? 'pointer' : undefined, borderColor: k.key && status === k.key ? 'primary.main' : undefined }} onClick={() => k.key && setStatus(k.key)}>
                <CardContent>
                  <Typography variant="body2" color="text.secondary">{k.label}</Typography>
                  <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 28 }}>{k.value}</Typography>
                  <Typography variant="caption" color="text.secondary">{k.hint}</Typography>
                </CardContent>
              </Card>
            </Grid>
          ))}
        </Grid>
      )}
      {result && (
        <Alert severity={result.skipped.length ? 'warning' : 'success'} sx={{ mb: 2 }} onClose={() => setResult(null)}>
          Approved {result.approved} registration{result.approved === 1 ? '' : 's'}.
          {result.skipped.length > 0 && <> Not approved (fees outstanding or no longer eligible):
            <Box component="ul" sx={{ m: 0, pl: 2 }}>{result.skipped.map((x) => <li key={x}>{x}</li>)}</Box></>}
        </Alert>
      )}
      {semesterId && (
        <>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} justifyContent="space-between" sx={{ mb: 1.5 }}>
            <TextField select size="small" label="Show" value={status} onChange={(e) => setStatus(e.target.value)} sx={{ minWidth: 220 }}>
              <MenuItem value="">All registrations</MenuItem>
              {Object.entries(STATUS_LABEL).map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}
            </TextField>
            {canDecide && waiting > 0 && (
              <Button variant="contained" startIcon={<DoneAllIcon />} disabled={approveAll.isPending} sx={{ whiteSpace: 'nowrap', flexShrink: 0 }}
                onClick={() => { if (window.confirm(`Approve all ${waiting} waiting registration${waiting === 1 ? '' : 's'}? Students who are not cleared for fees are skipped.`)) approveAll.mutate(); }}>
                Approve all waiting ({waiting})
              </Button>
            )}
          </Stack>
          {list.isLoading && <Loading />}
          {list.data && (
            <TableContainer component={Card}>
              <Table size="small">
                <TableHead><TableRow>
                  <TableCell>Registration no.</TableCell><TableCell>Student</TableCell><TableCell>Programme</TableCell><TableCell>Year</TableCell>
                  <TableCell align="right">Modules</TableCell><TableCell align="right">Credits</TableCell><TableCell>Submitted</TableCell><TableCell>Status</TableCell>
                </TableRow></TableHead>
                <TableBody>
                  {list.data.map((r) => (
                    <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(r)}>
                      <TableCell sx={{ whiteSpace: 'nowrap', fontWeight: 600 }}>{r.registrationNo}</TableCell>
                      <TableCell>{r.studentName}</TableCell>
                      <TableCell>{r.programmeCode}</TableCell>
                      <TableCell>{r.yearOfStudy}</TableCell>
                      <TableCell align="right">{r.modules.length}</TableCell>
                      <TableCell align="right">{num(r.totalCredits)}</TableCell>
                      <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDate(r.submittedAt)}{r.late && <Chip size="small" color="warning" variant="outlined" label="Late" sx={{ ml: 1 }} />}
                        {r.lateFee && r.lateFee.status !== 'CANCELLED' && <Chip size="small" variant="outlined" sx={{ ml: 0.5 }}
                          color={r.lateFee.balance > 0 ? 'warning' : 'success'} label={r.lateFee.balance > 0 ? 'Late fee unpaid' : 'Late fee paid'} />}</TableCell>
                      <TableCell><Chip size="small" color={STATUS_COLOUR[r.status]} label={STATUS_LABEL[r.status]} /></TableCell>
                    </TableRow>
                  ))}
                  {!list.data.length && <TableRow><TableCell colSpan={8}><Typography color="text.secondary" sx={{ py: 1 }}>No registrations {status ? `${STATUS_LABEL[status].toLowerCase()}` : 'yet'}.</Typography></TableCell></TableRow>}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </>
      )}
      {open && <RegistrationDialog reg={open} canDecide={canDecide} onClose={() => setOpen(null)} onDone={() => { setOpen(null); refresh(); }} />}
    </Container>
  );
}

function RegistrationDialog({ reg, canDecide, onClose, onDone }: { reg: SemesterRegistration; canDecide: boolean; onClose: () => void; onDone: () => void }) {
  const [rejecting, setRejecting] = useState(false);
  const [note, setNote] = useState('');
  const approve = useMutation({ mutationFn: () => api(`/api/registry/semester-registrations/${reg.id}/approve`, { ...staff, method: 'POST' }), onSuccess: onDone });
  const reject = useMutation({ mutationFn: () => api(`/api/registry/semester-registrations/${reg.id}/reject`, { ...staff, method: 'POST', body: { note } }), onSuccess: onDone });
  const c = reg.clearance as { enabled?: boolean; cleared?: boolean; amountNeeded?: number; reason?: string } | undefined;
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>
        {reg.studentName}
        <Typography variant="body2" color="text.secondary">{reg.registrationNo} · {reg.programmeCode} year {reg.yearOfStudy} · {reg.semesterName}</Typography>
      </DialogTitle>
      <DialogContent dividers>
        <Stack direction="row" spacing={1} sx={{ mb: 1.5 }} useFlexGap flexWrap="wrap">
          <Chip size="small" color={STATUS_COLOUR[reg.status]} label={STATUS_LABEL[reg.status]} />
          {reg.late && <Chip size="small" color="warning" variant="outlined" label="Late registration" />}
          {reg.curriculum && <Chip size="small" variant="outlined" label={reg.curriculum} />}
        </Stack>
        <Stack divider={<Divider flexItem />}>
          {reg.modules.map((m) => (
            <Stack key={m.moduleId} direction="row" spacing={1} alignItems="center" sx={{ py: 0.5 }}>
              <Typography variant="body2" sx={{ fontWeight: 600, minWidth: 86 }}>{m.code}</Typography>
              <Typography variant="body2" sx={{ flexGrow: 1 }}>{m.title}</Typography>
              <Chip size="small" variant="outlined" label={m.category === 'CORE' ? 'Core' : 'Elective'} />
              <Typography variant="body2" sx={{ minWidth: 28, textAlign: 'right' }}>{num(m.credits)}</Typography>
            </Stack>
          ))}
          <Stack direction="row" justifyContent="space-between" sx={{ pt: 1 }}>
            <Typography variant="body2" color="text.secondary">Total credits</Typography>
            <Typography variant="body2" sx={{ fontWeight: 700 }}>{num(reg.totalCredits)}</Typography>
          </Stack>
        </Stack>
        {reg.lateFee && reg.lateFee.status !== 'CANCELLED' && (
          <Typography variant="body2" sx={{ mt: 2 }}>
            <strong>Late registration fee:</strong> {formatTzs(reg.lateFee.amount)} on invoice {reg.lateFee.invoiceNo}
            {reg.lateFee.balance > 0 ? ` · ${formatTzs(reg.lateFee.balance)} unpaid` : ' · paid'}
          </Typography>
        )}
        <Typography variant="body2" sx={{ mt: 2 }}>
          <strong>Fee clearance when submitted:</strong>{' '}
          {!c || c.enabled === false ? 'not required' : c.cleared ? 'cleared' : `not cleared${c.amountNeeded ? ` (${formatTzs(c.amountNeeded)} to pay)` : ''}`}
          {canDecide && reg.status === 'SUBMITTED' && ' · checked again on approval'}
        </Typography>
        <Typography variant="body2"><strong>Details confirmed by the student:</strong> {reg.detailsConfirmed ? 'yes' : 'no'}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          Submitted {formatDateTime(reg.submittedAt)}{reg.submittedByName && ` by ${reg.submittedByName}`}
          {reg.decidedAt && ` · ${STATUS_LABEL[reg.status].toLowerCase()} ${formatDateTime(reg.decidedAt)} by ${reg.decidedByName}`}
        </Typography>
        {reg.decisionNote && <Alert severity="warning" sx={{ mt: 1 }}><strong>Note to student:</strong> {reg.decisionNote}</Alert>}
        <Button component={RouterLink} to={`/staff/students/${reg.studentId}`} size="small" sx={{ mt: 1, px: 0 }}>Open student record</Button>
        {rejecting && (
          <TextField fullWidth multiline minRows={2} sx={{ mt: 2 }} label="What should the student correct?" value={note} onChange={(e) => setNote(e.target.value)}
            helperText="Sent to the student by SMS and shown in the portal." autoFocus />
        )}
        <ErrorAlert error={approve.error ?? reject.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        {canDecide && reg.status === 'SUBMITTED' && (rejecting ? (
          <Button color="warning" variant="contained" disabled={!note.trim() || reject.isPending} onClick={() => reject.mutate()}>Return to student</Button>
        ) : (
          <>
            <Button color="warning" onClick={() => setRejecting(true)}>Return for correction</Button>
            <Button variant="contained" color="success" disabled={approve.isPending} onClick={() => approve.mutate()}>Approve</Button>
          </>
        ))}
      </DialogActions>
    </Dialog>
  );
}
