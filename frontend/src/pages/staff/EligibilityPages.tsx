import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import CheckIcon from '@mui/icons-material/CheckCircle';
import CrossIcon from '@mui/icons-material/Cancel';
import RefreshIcon from '@mui/icons-material/Refresh';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, Link, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { api, formatDateTime } from '../../api/client';
import type { EligibilityLine, EligibilityOverview, EligibilityRules, ExemptionView, GenerateEligibilityResult, OfferingEligibilityView } from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { OFFERING_STAGE, useRoles, useSemesterChoice } from './MarksPages';

const staff = { realm: 'staff' as const };
const pct = (n?: number | null) => (n == null ? '—' : `${Number(n).toLocaleString('en-GB', { maximumFractionDigits: 1 })}%`);

function RulesLine({ rules }: { rules: EligibilityRules }) {
  const parts = [`attendance of at least ${pct(rules.minAttendancePercent)}`];
  if (rules.requiresCa) parts.push('every coursework item marked');
  if (rules.requiresClearance) parts.push('examination fees clearance');
  return (
    <Typography variant="body2" color="text.secondary">
      To sit an examination a student needs {parts.join(', ').replace(/, ([^,]*)$/, ' and $1')}, or an approved exemption.
      {' '}<Link component={RouterLink} to="/staff/regulations">Change the rules</Link>
    </Typography>
  );
}

// ============================================================ semester overview

export function EligibilityPage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const { semesterId, semesters, picker } = useSemesterChoice();
  const [result, setResult] = useState<GenerateEligibilityResult | null>(null);
  const overview = useQuery({
    queryKey: ['staff', 'eligibility', semesterId], enabled: !!semesterId,
    queryFn: () => api<EligibilityOverview>(`/api/exams/eligibility?semesterId=${semesterId}`, staff),
  });
  const generate = useMutation({
    mutationFn: () => api<GenerateEligibilityResult>(`/api/exams/semesters/${semesterId}/eligibility`, { ...staff, method: 'POST' }),
    onSuccess: (r) => { setResult(r); qc.invalidateQueries({ queryKey: ['staff', 'eligibility'] }); },
  });
  const o = overview.data;
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Examination eligibility" subtitle="Who may sit each semester examination: attendance, coursework and fees clearance, with exemptions."
        action={picker} />
      <ErrorAlert error={semesters.error ?? overview.error ?? generate.error} />
      {overview.isLoading && <Loading />}
      {o && (
        <>
          <Card sx={{ mb: 2 }}>
            <CardContent>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'center' }}>
                <RulesLine rules={o.rules} />
                {o.canGenerate && (
                  <Button variant="contained" startIcon={<RefreshIcon />} onClick={() => generate.mutate()} disabled={generate.isPending} sx={{ whiteSpace: 'nowrap', flexShrink: 0 }}>
                    {o.offerings.some((x) => x.generatedAt) ? 'Regenerate lists' : 'Generate lists'}
                  </Button>
                )}
              </Stack>
            </CardContent>
          </Card>
          {result && (
            <Alert severity={result.notEligible ? 'warning' : 'success'} sx={{ mb: 2 }} onClose={() => setResult(null)}>
              {result.students} students in {result.offerings} module{result.offerings === 1 ? '' : 's'}: {result.eligible} eligible, {result.notEligible} not eligible.
              {result.notEligible > 0 && ' Students who have just become ineligible were told by SMS.'}
              {result.notes.length > 0 && <Box component="ul" sx={{ m: 0, pl: 2 }}>{result.notes.map((n) => <li key={n}>{n}</li>)}</Box>}
            </Alert>
          )}
          <PendingExemptions />
          <TableContainer component={Card}>
            <Table size="small">
              <TableHead><TableRow>
                <TableCell>Module</TableCell><TableCell align="right">Students</TableCell><TableCell align="right">Eligible</TableCell>
                <TableCell align="right">Not eligible</TableCell><TableCell align="right">Exempted</TableCell>
                <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>List generated</TableCell><TableCell>Results</TableCell>
              </TableRow></TableHead>
              <TableBody>
                {o.offerings.map((x) => (
                  <TableRow key={x.offeringId} hover sx={{ cursor: 'pointer' }} onClick={() => navigate(`/staff/eligibility/${x.offeringId}`)}>
                    <TableCell>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{x.code}</Typography>
                      <Typography variant="body2" color="text.secondary">{x.title}</Typography>
                    </TableCell>
                    <TableCell align="right">{x.students}</TableCell>
                    <TableCell align="right">{x.generatedAt ? x.eligible : '—'}</TableCell>
                    <TableCell align="right">{x.generatedAt ? (x.notEligible ? <Chip size="small" color="error" label={x.notEligible} /> : 0) : '—'}</TableCell>
                    <TableCell align="right">
                      {x.exempted || '—'}
                      {x.pendingExemptions > 0 && <Chip size="small" color="warning" variant="outlined" label={`${x.pendingExemptions} pending`} sx={{ ml: 0.5 }} />}
                    </TableCell>
                    <TableCell sx={{ display: { xs: 'none', md: 'table-cell' } }}>
                      {x.generatedAt ? <><Typography variant="body2">{formatDateTime(x.generatedAt)}</Typography>
                        <Typography variant="caption" color="text.secondary">{x.generatedByName}</Typography></> : <Typography variant="body2" color="text.secondary">Not yet</Typography>}
                    </TableCell>
                    <TableCell><Typography variant="body2" color="text.secondary">{OFFERING_STAGE[x.status]}</Typography></TableCell>
                  </TableRow>
                ))}
                {!o.offerings.length && (
                  <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 1 }}>No modules set up for this semester yet.</Typography></TableCell></TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
            Generate the lists before opening examination marks entry, and again as attendance, coursework and payments come in. Lists of modules whose results
            have been submitted are kept as they were.
          </Typography>
        </>
      )}
    </Container>
  );
}

/** Exemptions waiting for a decision, for academic management. */
function PendingExemptions() {
  const can = useRoles();
  const pending = useQuery({
    queryKey: ['staff', 'exemptions', 'pending'], enabled: can(['INSTITUTION_ADMIN', 'DEPUTY_PRINCIPAL_ACADEMIC', 'EXAMINATIONS_OFFICER', 'HEAD_OF_DEPARTMENT']),
    queryFn: () => api<ExemptionView[]>('/api/exams/exemptions?pending=true', staff),
  });
  if (!pending.data?.length) return null;
  return (
    <Card sx={{ mb: 2, borderLeft: 4, borderColor: 'warning.main' }}>
      <CardContent>
        <Typography variant="h6" gutterBottom>Exemptions awaiting a decision</Typography>
        <Stack spacing={1.5}>
          {pending.data.map((x) => <ExemptionItem key={x.id} x={x} showModule />)}
        </Stack>
      </CardContent>
    </Card>
  );
}

function ExemptionItem({ x, showModule }: { x: ExemptionView; showModule?: boolean }) {
  const qc = useQueryClient();
  const [deciding, setDeciding] = useState<'approve' | 'reject' | null>(null);
  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between" alignItems={{ sm: 'flex-start' }}>
        <Box>
          {showModule && <Typography variant="body2" sx={{ fontWeight: 600 }}>{x.moduleCode} · {x.registrationNo} {x.studentName}</Typography>}
          <Typography variant="body2">“{x.reason}”</Typography>
          <Typography variant="caption" color="text.secondary">
            Asked by {x.requestedByName}, {formatDateTime(x.requestedAt)}{x.reasonsNotMet.length > 0 && ` · not met: ${x.reasonsNotMet.join('; ')}`}
          </Typography>
          {x.status !== 'PENDING' && (
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
              {x.status === 'APPROVED' ? 'Approved' : 'Rejected'} by {x.decidedByName}{x.decidedAt && `, ${formatDateTime(x.decidedAt)}`}{x.decisionNote && `: ${x.decisionNote}`}
            </Typography>
          )}
        </Box>
        {x.canDecide ? (
          <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
            <Button size="small" color="warning" onClick={() => setDeciding('reject')}>Reject</Button>
            <Button size="small" variant="contained" color="success" onClick={() => setDeciding('approve')}>Approve</Button>
          </Stack>
        ) : x.status === 'PENDING' && <Chip size="small" color="warning" variant="outlined" label="Awaiting decision" sx={{ flexShrink: 0 }} />}
      </Stack>
      {deciding && <DecideDialog x={x} approve={deciding === 'approve'} onClose={() => setDeciding(null)}
        onDone={() => { setDeciding(null); qc.invalidateQueries({ queryKey: ['staff', 'exemptions'] }); qc.invalidateQueries({ queryKey: ['staff', 'eligibility'] });
          qc.invalidateQueries({ queryKey: ['staff', 'offering-eligibility'] }); }} />}
    </Box>
  );
}

function DecideDialog({ x, approve, onClose, onDone }: { x: ExemptionView; approve: boolean; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('');
  const m = useMutation({
    mutationFn: () => api(`/api/exams/exemptions/${x.id}/${approve ? 'approve' : 'reject'}`, { ...staff, method: 'POST', body: { note: note.trim() || null } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{approve ? 'Approve' : 'Reject'} exemption · {x.moduleCode} · {x.registrationNo}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 2 }}>“{x.reason}” — {x.requestedByName}</Typography>
        <TextField fullWidth multiline minRows={2} label={approve ? 'Note (optional)' : 'Why is it rejected?'} value={note} onChange={(e) => setNote(e.target.value)}
          required={!approve} slotProps={{ htmlInput: { maxLength: 1000 } }} />
        {approve && <Typography variant="caption" color="text.secondary">The student may then sit the examination and is told by SMS.</Typography>}
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color={approve ? 'success' : 'warning'} disabled={m.isPending || (!approve && !note.trim())} onClick={() => m.mutate()}>
          {approve ? 'Approve' : 'Reject'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ============================================================ one module's list

export function OfferingEligibilityPage() {
  const { id } = useParams();
  const [asking, setAsking] = useState<EligibilityLine | null>(null);
  const [only, setOnly] = useState(false);
  const v = useQuery({ queryKey: ['staff', 'offering-eligibility', id], queryFn: () => api<OfferingEligibilityView>(`/api/exams/offerings/${id}/eligibility`, staff) });
  const qc = useQueryClient();
  const e = v.data;
  const rows = (e?.students ?? []).filter((s) => !only || !s.eligible);
  return (
    <Container maxWidth="lg" disableGutters>
      <Button component={RouterLink} to="/staff/eligibility" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>Examination eligibility</Button>
      <ErrorAlert error={v.error} />
      {v.isLoading && <Loading />}
      {e && (
        <>
          <PageTitle title={`${e.code} · ${e.title}`} subtitle={`${e.semesterName}${e.departmentName ? ` · ${e.departmentName}` : ''} · results ${OFFERING_STAGE[e.status].toLowerCase()}`}
            action={<Button component={RouterLink} to={`/staff/teaching/${e.offeringId}`}>Attendance</Button>} />
          <Card sx={{ mb: 2 }}><CardContent><RulesLine rules={e.rules} />
            <Typography variant="body2" sx={{ mt: 1 }}>
              {e.generatedAt ? <>List generated {formatDateTime(e.generatedAt)} by {e.generatedByName}.</> : 'The Examinations Officer has not generated this list yet.'}
            </Typography>
          </CardContent></Card>
          {e.generatedAt && (
            <Stack direction="row" spacing={1} sx={{ mb: 1.5 }}>
              <Chip clickable label="Everyone" color={!only ? 'primary' : 'default'} variant={!only ? 'filled' : 'outlined'} onClick={() => setOnly(false)} />
              <Chip clickable label={`Not eligible (${e.students.filter((s) => !s.eligible).length})`} color={only ? 'primary' : 'default'}
                variant={only ? 'filled' : 'outlined'} onClick={() => setOnly(true)} />
            </Stack>
          )}
          <TableContainer component={Card}>
            <Table size="small">
              <TableHead><TableRow>
                <TableCell>Student</TableCell><TableCell align="center">Attendance</TableCell>
                {e.rules.requiresCa && <TableCell align="center">Coursework</TableCell>}
                {e.rules.requiresClearance && <TableCell align="center">Fees</TableCell>}
                <TableCell>Eligibility</TableCell><TableCell />
              </TableRow></TableHead>
              <TableBody>
                {rows.map((s) => (
                  <TableRow key={s.moduleRegistrationId} sx={{ verticalAlign: 'top' }}>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{s.registrationNo}</Typography>
                      <Typography variant="caption" color="text.secondary">{s.name} · {s.programmeCode}</Typography>
                    </TableCell>
                    <TableCell align="center">
                      {e.generatedAt ? <Tick ok={s.attendanceMet} label={`${pct(s.attendancePercent)} (${s.sessionsAttended}/${s.sessionsCounted})`} /> : '—'}
                    </TableCell>
                    {e.rules.requiresCa && <TableCell align="center">{e.generatedAt ? <Tick ok={s.caMet} /> : '—'}</TableCell>}
                    {e.rules.requiresClearance && <TableCell align="center">{e.generatedAt ? <Tick ok={s.clearanceMet} /> : '—'}</TableCell>}
                    <TableCell>
                      {!e.generatedAt ? '—' : s.eligible
                        ? <Chip size="small" color="success" variant={s.exempted ? 'outlined' : 'filled'} label={s.exempted ? 'Eligible (exempted)' : 'Eligible'} />
                        : <Chip size="small" color="error" label="Not eligible" />}
                      {!s.eligible && s.reasons.map((r) => <Typography key={r} variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>{r}</Typography>)}
                      {s.exemption && (s.exemption.status !== 'APPROVED' || !s.rulesMet) && (
                        <Box sx={{ mt: 1, p: 1, bgcolor: 'action.hover', borderRadius: 1, maxWidth: 420 }}>
                          <Typography variant="caption" sx={{ fontWeight: 600 }}>Exemption {s.exemption.status.toLowerCase()}</Typography>
                          <ExemptionItem x={s.exemption} />
                        </Box>
                      )}
                    </TableCell>
                    <TableCell align="right">
                      {e.canRequest && !s.rulesMet && (!s.exemption || s.exemption.status === 'REJECTED') && (
                        <Button size="small" onClick={() => setAsking(s)} sx={{ whiteSpace: 'nowrap' }}>Ask for exemption</Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
                {!rows.length && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 1 }}>{only ? 'Every student is eligible.' : 'No students registered for this module.'}</Typography></TableCell></TableRow>}
              </TableBody>
            </Table>
          </TableContainer>
          {asking && <AskDialog offeringId={e.offeringId} line={asking} onClose={() => setAsking(null)}
            onDone={() => { setAsking(null); qc.invalidateQueries({ queryKey: ['staff', 'offering-eligibility'] }); qc.invalidateQueries({ queryKey: ['staff', 'exemptions'] }); }} />}
        </>
      )}
    </Container>
  );
}

function Tick({ ok, label }: { ok: boolean; label?: string }) {
  return (
    <Tooltip title={ok ? 'Met' : 'Not met'}>
      <Stack direction="row" spacing={0.5} alignItems="center" justifyContent="center">
        {ok ? <CheckIcon fontSize="small" color="success" /> : <CrossIcon fontSize="small" color="error" />}
        {label && <Typography variant="body2" color={ok ? undefined : 'error.main'} sx={{ whiteSpace: 'nowrap' }}>{label}</Typography>}
      </Stack>
    </Tooltip>
  );
}

function AskDialog({ offeringId, line, onClose, onDone }: { offeringId: number; line: EligibilityLine; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState('');
  const m = useMutation({
    mutationFn: () => api(`/api/exams/offerings/${offeringId}/exemptions`, { ...staff, method: 'POST', body: { moduleRegistrationId: line.moduleRegistrationId, reason: reason.trim() } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Exemption for {line.registrationNo} · {line.name}</DialogTitle>
      <DialogContent>
        <Box component="ul" sx={{ mt: 0, pl: 2 }}>{line.reasons.map((r) => <li key={r}><Typography variant="body2">{r}</Typography></li>)}</Box>
        <TextField fullWidth multiline minRows={3} label="Reason" value={reason} onChange={(e) => setReason(e.target.value)}
          helperText="E.g. the medical or other evidence seen. Someone else in academic management decides." slotProps={{ htmlInput: { maxLength: 1000 } }} />
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={reason.trim().length < 10 || m.isPending} onClick={() => m.mutate()}>Ask for exemption</Button>
      </DialogActions>
    </Dialog>
  );
}
