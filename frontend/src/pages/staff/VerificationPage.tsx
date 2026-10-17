import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import AutorenewIcon from '@mui/icons-material/Autorenew';
import BlockIcon from '@mui/icons-material/Block';
import CloudSyncIcon from '@mui/icons-material/CloudSync';
import DownloadIcon from '@mui/icons-material/Download';
import SendIcon from '@mui/icons-material/Send';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton,
  LinearProgress, Radio, RadioGroup, Stack, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField,
  Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { useParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime } from '../../api/client';
import type {
  AdmissionWindow, NactvetSubmission, OutcomeResult, VerificationApplicantRow, VerificationOverview,
} from '../../api/types';
import { ErrorAlert, Loading, PageTitle, humanise, statusColour } from '../../components/common';
import { ROLES } from '../../auth/access';
import { useSession } from '../../auth/session';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };
const OFFER_STATUSES = ['OFFERED', 'ACCEPTED', 'DECLINED', 'OFFER_LAPSED'];
const MAX_FILE_BYTES = 2_000_000;

type TabKey = 'ready' | 'submissions' | 'queries' | 'offers' | 'rejected';

/** BP-04: submit selected applicants to NACTVET, record outcomes, resolve queries and follow offers to acceptance. */
export default function VerificationPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  // the API decides; these keep read-only roles (Deputy Principal, Registrar, Principal) away from actions they cannot take
  const roles = useSession().user('staff')?.roles ?? [];
  const office = roles.some((r) => r === ROLES.ADMIN || r === ROLES.ADMISSIONS);          // submit, outcomes, queries, letters
  const revoker = roles.some((r) => r === ROLES.ADMIN || r === ROLES.ACADEMIC);           // revoke a letter
  const win = useQuery({ queryKey: ['staff', 'window', id], queryFn: () => api<AdmissionWindow>(`/api/admission/windows/${id}`, staff) });
  const ov = useQuery({ queryKey: ['staff', 'verification', id], queryFn: () => api<VerificationOverview>(`/api/admission/windows/${id}/verification`, staff) });
  const [tab, setTab] = useState<TabKey>('ready');
  const [notice, setNotice] = useState<string | null>(null);
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['staff', 'verification', id] });
    qc.invalidateQueries({ queryKey: ['staff', 'applications'] });
  };
  const outcomeNotice = (r: OutcomeResult) => {
    setNotice(r.applied === 0
      ? `No new outcomes yet for batch ${r.submission.batchNo}. NACTVET is still processing ${r.submission.pending} applicant(s).`
      : `Batch ${r.submission.batchNo}: ${r.verified} verified, ${r.queried} queried, ${r.rejected} not verified.`
        + (r.submission.pending ? ` ${r.submission.pending} still at NACTVET.` : ''));
    refresh();
  };

  const [submitOpen, setSubmitOpen] = useState(false);
  const [channel, setChannel] = useState<'API' | 'FILE'>('API');
  const submit = useMutation({
    mutationFn: () => api<NactvetSubmission>(`/api/admission/windows/${id}/nactvet-submissions`, { method: 'POST', ...staff, body: { channel } }),
    onSuccess: (s) => {
      setSubmitOpen(false);
      setNotice(`Batch ${s.batchNo} submitted with ${s.total} applicant(s)` + (s.channel === 'FILE'
        ? '. Download the file and upload it to NACTVET, then import their outcome file here.'
        : `. NACTVET reference ${s.externalRef}. Fetch outcomes when NACTVET has processed it.`));
      setTab('submissions');
      refresh();
    },
  });
  const fetchOutcomes = useMutation({
    mutationFn: (sid: number) => api<OutcomeResult>(`/api/admission/nactvet-submissions/${sid}/fetch-outcomes`, { method: 'POST', ...staff }),
    onSuccess: outcomeNotice,
  });
  const [importing, setImporting] = useState<NactvetSubmission | null>(null);
  const [csv, setCsv] = useState<{ name: string; text: string } | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const importOutcomes = useMutation({
    mutationFn: () => api<OutcomeResult>(`/api/admission/nactvet-submissions/${importing!.id}/outcomes`, { method: 'POST', ...staff, body: { csv: csv!.text } }),
    onSuccess: (r) => { setImporting(null); setCsv(null); outcomeNotice(r); },
  });
  const [resolving, setResolving] = useState<VerificationApplicantRow | null>(null);
  const [remarks, setRemarks] = useState('');
  const resolve = useMutation({
    mutationFn: () => api(`/api/admission/applications/${resolving!.applicationId}/resolve-query`, { method: 'POST', ...staff, body: { remarks } }),
    onSuccess: () => { setResolving(null); setRemarks(''); setNotice('Query resolved. The applicant will be included in the next submission.'); refresh(); },
  });
  const [dlError, setDlError] = useState<unknown>(null);
  const issueLetters = useMutation({
    mutationFn: () => api<{ issued: number }>(`/api/admission/windows/${id}/admission-letters`, { method: 'POST', ...staff }),
    onSuccess: (r) => { setNotice(`${r.issued} admission letter(s) issued. Applicants were told by SMS that their letter is ready.`); refresh(); },
  });
  const [letterAction, setLetterAction] = useState<{ kind: 'reissue' | 'revoke'; row: VerificationApplicantRow } | null>(null);
  const [letterReason, setLetterReason] = useState('');
  const changeLetter = useMutation({
    mutationFn: () => api<{ letterNo: string; edition: number }>(
      `/api/admission/applications/${letterAction!.row.applicationId}/admission-letter/${letterAction!.kind}`, { method: 'POST', ...staff, body: { reason: letterReason } }),
    onSuccess: (r) => {
      setNotice(letterAction!.kind === 'reissue'
        ? `Letter ${r.letterNo} reissued as edition ${r.edition}. The previous edition now verifies as superseded.`
        : `Letter ${r.letterNo} revoked. Verification now reports it as revoked.`);
      setLetterAction(null); setLetterReason(''); refresh();
    },
  });

  if (win.isLoading || ov.isLoading) return <Loading />;
  const d = ov.data;
  if (!d) return <ErrorAlert error={ov.error ?? win.error} />;

  const readyCount = d.ready.filter((r) => r.issues.length === 0).length;
  const blockedCount = d.ready.length - readyCount;
  const queries = d.applicants.filter((a) => a.status === 'QUERIED');
  const offers = d.applicants.filter((a) => OFFER_STATUSES.includes(a.status));
  const rejected = d.applicants.filter((a) => a.status === 'NACTVET_REJECTED');
  const atNactvet = d.applicants.filter((a) => a.status === 'PENDING_VERIFICATION');
  const busy = submit.isPending || fetchOutcomes.isPending || importOutcomes.isPending || resolve.isPending || issueLetters.isPending;

  const onFile = (f?: File) => {
    setFileError(null);
    setCsv(null);
    if (!f) return;
    if (f.size > MAX_FILE_BYTES) { setFileError('The file is larger than 2 MB.'); return; }
    f.text().then((text) => setCsv({ name: f.name, text })).catch(() => setFileError('The file could not be read.'));
  };
  const dl = (path: string, name: string) => download(path, 'staff', name).catch(setDlError);

  return (
    <Container maxWidth="lg" disableGutters>
      <Button component={StaffLink} to={`/staff/windows/${id}`} startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>{win.data?.name}</Button>
      <PageTitle
        title="NACTVET verification & offers"
        subtitle={<>Submit approved selections to NACTVET, record outcomes, and follow offers to acceptance. Offers stay open for <strong>{d.offerAcceptanceDays} days</strong> after verification.</>}
        action={office ? (
          <Tooltip title={readyCount ? '' : 'No selected applicant is ready to submit'}>
            <span>
              <Button variant="contained" startIcon={<SendIcon />} disabled={!readyCount || busy} onClick={() => setSubmitOpen(true)} sx={{ whiteSpace: 'nowrap' }}>
                Submit to NACTVET{readyCount ? ` (${readyCount})` : ''}
              </Button>
            </span>
          </Tooltip>
        ) : <Chip label="View only: the admissions office submits to NACTVET" variant="outlined" />}
      />
      {busy && <LinearProgress sx={{ mb: 2 }} />}
      <ErrorAlert error={submit.error ?? fetchOutcomes.error ?? resolve.error ?? issueLetters.error ?? dlError ?? ov.error} />
      {notice && <Alert severity="success" onClose={() => setNotice(null)} sx={{ mb: 2 }}>{notice}</Alert>}
      {!d.institutionNactvetNo && <Alert severity="warning" sx={{ mb: 2 }}>The institution's NACTVET registration number is not set, so nothing can be submitted. Ask the system administrator to set it.</Alert>}

      <Grid container spacing={1.5} sx={{ mb: 3 }}>
        <Stat label="Awaiting submission" value={d.counts.SELECTED} hint={blockedCount ? `${blockedCount} need fixing` : undefined} />
        <Stat label="At NACTVET" value={d.counts.PENDING_VERIFICATION} />
        <Stat label="Queried" value={d.counts.QUERIED} tone={d.counts.QUERIED ? 'warning' : undefined} />
        <Stat label="Not verified" value={d.counts.NACTVET_REJECTED} />
        <Stat label="Offers open" value={d.counts.OFFERED} />
        <Stat label="Accepted" value={d.counts.ACCEPTED} tone="success" />
        <Stat label="Declined / lapsed" value={d.counts.DECLINED + d.counts.OFFER_LAPSED} />
        <Stat label="Acceptance rate" value={d.acceptanceRate == null ? '—' : `${d.acceptanceRate}%`} hint="of answered offers" />
      </Grid>

      <Tabs value={tab} onChange={(_, v) => setTab(v)} variant="scrollable" sx={{ mb: 2 }}>
        <Tab value="ready" label={`Ready to submit (${d.ready.length})`} />
        <Tab value="submissions" label={`Submissions (${d.submissions.length})`} />
        <Tab value="queries" label={<Stack direction="row" spacing={1} alignItems="center"><span>Queries</span>{!!queries.length && <Chip size="small" color="warning" label={queries.length} />}</Stack>} />
        <Tab value="offers" label={`Offers (${offers.length})`} />
        <Tab value="rejected" label={`Not verified (${rejected.length})`} />
      </Tabs>

      {tab === 'ready' && (
        <Section empty={!d.ready.length} emptyText="No approved selections are waiting. Applicants appear here after the Admissions Committee approves a selection round."
          intro="Records are checked before submission for what NACTVET would query. Applicants with issues are held back until they are fixed.">
          <Table size="small">
            <TableHead><TableRow><TableCell>Reference</TableCell><TableCell>Applicant</TableCell><TableCell>Programme</TableCell><TableCell>CSEE index</TableCell><TableCell>Check</TableCell></TableRow></TableHead>
            <TableBody>
              {d.ready.map((r) => (
                <TableRow key={r.applicationId}>
                  <TableCell>{r.referenceNo}</TableCell>
                  <TableCell>{r.applicantName}{r.remarks && <Typography variant="caption" display="block" color="text.secondary">Resubmission: {r.remarks}</Typography>}</TableCell>
                  <TableCell>{r.programmeName}</TableCell>
                  <TableCell>{r.indexNumber ?? '—'}</TableCell>
                  <TableCell>
                    {r.issues.length === 0
                      ? <Chip size="small" color="success" label="Ready" />
                      : <Stack direction="row" spacing={0.5} useFlexGap flexWrap="wrap">{r.issues.map((i) => <Chip key={i} size="small" color="warning" variant="outlined" label={i} />)}</Stack>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Section>
      )}

      {tab === 'submissions' && (
        <Stack spacing={3}>
          <Section empty={!d.submissions.length} emptyText="Nothing has been submitted to NACTVET for this window yet.">
            <Table size="small">
              <TableHead><TableRow><TableCell>Batch</TableCell><TableCell>Submitted</TableCell><TableCell>Outcomes</TableCell><TableCell>Status</TableCell><TableCell align="right">Actions</TableCell></TableRow></TableHead>
              <TableBody>
                {d.submissions.map((s) => (
                  <TableRow key={s.id}>
                    <TableCell>
                      <strong>#{s.batchNo}</strong> · {s.channel === 'API' ? 'API' : 'File'}
                      {s.externalRef && <Typography variant="caption" display="block" color="text.secondary">{s.externalRef}</Typography>}
                    </TableCell>
                    <TableCell>{formatDateTime(s.submittedAt)}<Typography variant="caption" display="block" color="text.secondary">{s.submittedByName}</Typography></TableCell>
                    <TableCell>
                      <Stack direction="row" spacing={0.5} useFlexGap flexWrap="wrap">
                        <Chip size="small" label={`${s.total} sent`} variant="outlined" />
                        {!!s.verified && <Chip size="small" color="success" label={`${s.verified} verified`} />}
                        {!!s.queried && <Chip size="small" color="warning" label={`${s.queried} queried`} />}
                        {!!s.rejected && <Chip size="small" color="error" label={`${s.rejected} not verified`} />}
                        {!!s.pending && <Chip size="small" color="info" label={`${s.pending} pending`} />}
                      </Stack>
                    </TableCell>
                    <TableCell><Chip size="small" color={s.status === 'COMPLETED' ? 'success' : 'info'} label={s.status === 'COMPLETED' ? 'Completed' : 'Awaiting NACTVET'} /></TableCell>
                    <TableCell align="right">
                      <Stack direction="row" spacing={0.5} justifyContent="flex-end" useFlexGap flexWrap="wrap">
                        {office && s.channel === 'API' && s.pending > 0 && <Button size="small" startIcon={<CloudSyncIcon />} disabled={busy} onClick={() => fetchOutcomes.mutate(s.id)}>Fetch outcomes</Button>}
                        {office && s.pending > 0 && <Button size="small" startIcon={<UploadFileIcon />} disabled={busy} onClick={() => { setImporting(s); setCsv(null); setFileError(null); }}>Import outcomes</Button>}
                        <Button size="small" startIcon={<DownloadIcon />} onClick={() => dl(`/api/admission/nactvet-submissions/${s.id}/file`, `nactvet-batch-${s.batchNo}.csv`)}>File</Button>
                      </Stack>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Section>
          {!!atNactvet.length && (
            <Section title={`At NACTVET (${atNactvet.length})`}>
              <ApplicantTable rows={atNactvet} columns={['ref', 'name', 'programme', 'index']} />
            </Section>
          )}
        </Stack>
      )}

      {tab === 'queries' && (
        <Section empty={!queries.length} emptyText="No open NACTVET queries."
          intro="Correct the record (for example the applicant's name or index number), then mark the query resolved with a note. The applicant goes into the next submission with your note.">
          <ApplicantTable rows={queries} columns={['ref', 'name', 'programme', 'index', 'reason']}
            action={office ? (r) => <Button size="small" onClick={() => { setResolving(r); setRemarks(''); }}>Resolve</Button> : undefined} />
        </Section>
      )}

      {tab === 'offers' && (
        <Stack spacing={2}>
          {office && d.lettersPending > 0 && (d.joiningInstructionsSet
            ? <Alert severity="info" action={<Button color="inherit" size="small" disabled={busy} onClick={() => issueLetters.mutate()}>Issue {d.lettersPending} letter(s)</Button>}>
                {d.lettersPending} accepted applicant(s) do not have an admission letter yet.
              </Alert>
            : <Alert severity="warning" action={<Button color="inherit" size="small" component={StaffLink} to={`/staff/windows/${id}`}>Set up</Button>}>
                {d.lettersPending} accepted applicant(s) are waiting for their admission letter. Set the joining instructions for this window first.
              </Alert>)}
          <Section empty={!offers.length} emptyText="No offers yet. Offers are issued automatically when NACTVET verifies an applicant.">
            <ApplicantTable rows={offers} columns={['ref', 'name', 'programme', 'verification', 'expires', 'status', 'letter']} actionInColumn="letter"
              action={(r) => r.letterNo ? (
                <Stack direction="row" spacing={0} sx={{ ml: -0.75 }}>
                  <Tooltip title="Download letter (PDF)"><IconButton size="small" aria-label="Download letter"
                    onClick={() => dl(`/api/admission/applications/${r.applicationId}/admission-letter`, `admission-letter-${r.referenceNo.replace(/\W+/g, '-')}.pdf`)}><DownloadIcon fontSize="small" /></IconButton></Tooltip>
                  {office && <Tooltip title="Reissue (corrected edition)"><IconButton size="small" aria-label="Reissue letter"
                    onClick={() => { setLetterAction({ kind: 'reissue', row: r }); setLetterReason(''); }}><AutorenewIcon fontSize="small" /></IconButton></Tooltip>}
                  {revoker && <Tooltip title="Revoke"><IconButton size="small" color="error" aria-label="Revoke letter"
                    onClick={() => { setLetterAction({ kind: 'revoke', row: r }); setLetterReason(''); }}><BlockIcon fontSize="small" /></IconButton></Tooltip>}
                </Stack>
              ) : office && r.status === 'ACCEPTED' && d.joiningInstructionsSet ? (
                <Button size="small" onClick={() => setLetterAction({ kind: 'reissue', row: r })}>Issue letter</Button>
              ) : null} />
          </Section>
        </Stack>
      )}

      {tab === 'rejected' && (
        <Section empty={!rejected.length} emptyText="No applicant has been rejected by NACTVET."
          intro="These admissions are withdrawn and the applicants were told the reason by SMS. Their places return to the next selection round.">
          <ApplicantTable rows={rejected} columns={['ref', 'name', 'programme', 'index', 'reason']} />
        </Section>
      )}

      {/* submit */}
      <Dialog open={submitOpen} onClose={() => setSubmitOpen(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Submit to NACTVET</DialogTitle>
        <DialogContent>
          <Typography sx={{ mb: 2 }}>
            <strong>{readyCount}</strong> applicant(s) will be submitted under {d.institutionNactvetNo}.
            {blockedCount > 0 && <> {blockedCount} with issues will be held back.</>}
          </Typography>
          <RadioGroup value={channel} onChange={(e) => setChannel(e.target.value as 'API' | 'FILE')}>
            <FormControlLabel value="API" control={<Radio />} label={<Box><Typography>Send through the NACTVET API</Typography><Typography variant="body2" color="text.secondary">Outcomes are fetched from NACTVET from the Submissions tab.</Typography></Box>} sx={{ mb: 1, alignItems: 'flex-start' }} />
            <FormControlLabel value="FILE" control={<Radio />} label={<Box><Typography>Prepare a file to upload to NACTVET</Typography><Typography variant="body2" color="text.secondary">Download the file in NACTVET's template, upload it on the NACTVET portal, then import their outcome file.</Typography></Box>} sx={{ alignItems: 'flex-start' }} />
          </RadioGroup>
          <ErrorAlert error={submit.error} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSubmitOpen(false)}>Cancel</Button>
          <Button variant="contained" startIcon={<SendIcon />} disabled={submit.isPending} onClick={() => submit.mutate()}>Submit {readyCount}</Button>
        </DialogActions>
      </Dialog>

      {/* import outcomes */}
      <Dialog open={!!importing} onClose={() => setImporting(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Import NACTVET outcomes — batch {importing?.batchNo}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            A CSV file with the columns <code>reference, outcome, reason, verification_no, multiple_admission</code>. Outcome is VERIFIED, QUERIED or
            REJECTED; leave it empty for applicants NACTVET has not decided yet. A verification number is required for VERIFIED and a reason for the others.
            The file is checked in full first — nothing is recorded if any row is wrong.
          </Typography>
          <Button size="small" startIcon={<DownloadIcon />} sx={{ mb: 2 }}
            onClick={() => importing && dl(`/api/admission/nactvet-submissions/${importing.id}/file?kind=outcomes`, `nactvet-outcomes-batch-${importing.batchNo}.csv`)}>
            Download a pre-filled outcome template
          </Button>
          <Box>
            <Button variant="outlined" component="label" startIcon={<UploadFileIcon />}>
              Choose CSV file
              <input hidden type="file" accept=".csv,text/csv" onChange={(e) => onFile(e.target.files?.[0])} />
            </Button>
            {csv && <Typography variant="body2" sx={{ mt: 1 }}>{csv.name} · {csv.text.split(/\r?\n/).filter((l) => l.trim()).length - 1} row(s)</Typography>}
            {fileError && <Alert severity="error" sx={{ mt: 1 }}>{fileError}</Alert>}
          </Box>
          <Box sx={{ mt: 2 }}><ErrorAlert error={importOutcomes.error} /></Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setImporting(null)}>Cancel</Button>
          <Button variant="contained" disabled={!csv || importOutcomes.isPending} onClick={() => importOutcomes.mutate()}>Record outcomes</Button>
        </DialogActions>
      </Dialog>

      {/* reissue / revoke letter */}
      <Dialog open={!!letterAction} onClose={() => setLetterAction(null)} maxWidth="sm" fullWidth>
        <DialogTitle>{letterAction?.kind === 'revoke' ? 'Revoke admission letter' : letterAction?.row.letterNo ? 'Reissue admission letter' : 'Issue admission letter'}</DialogTitle>
        <DialogContent>
          <Typography sx={{ mb: 1 }}><strong>{letterAction?.row.applicantName}</strong> · {letterAction?.row.referenceNo}{letterAction?.row.letterNo && <> · {letterAction.row.letterNo}</>}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {letterAction?.kind === 'revoke'
              ? 'The letter will verify as revoked and the applicant will no longer be able to download it. Only the Principal or Deputy Principal (Academic) can revoke.'
              : letterAction?.row.letterNo
                ? 'A new edition with a new verification code is issued from the current applicant record and joining instructions. The current edition will verify as superseded.'
                : 'The letter is issued from the current applicant record and joining instructions.'}
          </Typography>
          <TextField label="Reason (recorded in the audit trail)" fullWidth multiline minRows={2} value={letterReason}
            onChange={(e) => setLetterReason(e.target.value)} inputProps={{ maxLength: 300 }} autoFocus />
          <ErrorAlert error={changeLetter.error} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setLetterAction(null)}>Cancel</Button>
          <Button variant="contained" color={letterAction?.kind === 'revoke' ? 'error' : 'primary'} disabled={!letterReason.trim() || changeLetter.isPending}
            onClick={() => changeLetter.mutate()}>{letterAction?.kind === 'revoke' ? 'Revoke' : 'Issue'}</Button>
        </DialogActions>
      </Dialog>

      {/* resolve query */}
      <Dialog open={!!resolving} onClose={() => setResolving(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Resolve NACTVET query</DialogTitle>
        <DialogContent>
          <Typography sx={{ mb: 1 }}><strong>{resolving?.applicantName}</strong> · {resolving?.referenceNo}</Typography>
          <Alert severity="warning" sx={{ mb: 2 }}>{resolving?.reason}</Alert>
          <TextField label="What was corrected (sent to NACTVET with the resubmission)" fullWidth multiline minRows={2} value={remarks}
            onChange={(e) => setRemarks(e.target.value)} inputProps={{ maxLength: 300 }} helperText={`${remarks.length}/300`} autoFocus />
          <ErrorAlert error={resolve.error} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setResolving(null)}>Cancel</Button>
          <Button variant="contained" disabled={!remarks.trim() || resolve.isPending} onClick={() => resolve.mutate()}>Mark resolved</Button>
        </DialogActions>
      </Dialog>
    </Container>
  );
}

function Stat({ label, value, hint, tone }: { label: string; value: number | string; hint?: string; tone?: 'success' | 'warning' }) {
  return (
    <Grid size={{ xs: 6, sm: 3 }}>
      <Card sx={{ height: '100%' }}>
        <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
          <Typography variant="body2" color="text.secondary">{label}</Typography>
          <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 26, color: tone ? `${tone}.main` : undefined }}>{value}</Typography>
          {hint && <Typography variant="caption" color="text.secondary">{hint}</Typography>}
        </CardContent>
      </Card>
    </Grid>
  );
}

function Section({ title, intro, empty, emptyText, children }: { title?: string; intro?: string; empty?: boolean; emptyText?: string; children: ReactNode }) {
  return (
    <Card>
      <CardContent>
        {title && <Typography variant="h6" sx={{ mb: 1 }}>{title}</Typography>}
        {intro && !empty && <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{intro}</Typography>}
        {empty ? <Typography color="text.secondary">{emptyText}</Typography> : <TableContainer>{children}</TableContainer>}
      </CardContent>
    </Card>
  );
}

type Column = 'ref' | 'name' | 'phone' | 'programme' | 'index' | 'reason' | 'verification' | 'expires' | 'status' | 'letter';

function ApplicantTable({ rows, columns, action, actionInColumn }: {
  rows: VerificationApplicantRow[]; columns: Column[]; action?: (r: VerificationApplicantRow) => ReactNode; actionInColumn?: Column;
}) {
  const head: Record<Column, string> = {
    ref: 'Reference', name: 'Applicant', phone: 'Phone', programme: 'Programme', index: 'CSEE index', reason: 'NACTVET reason',
    verification: 'Verification no.', expires: 'Accept by', status: 'Status', letter: 'Admission letter',
  };
  const cell = (r: VerificationApplicantRow, c: Column): ReactNode => {
    switch (c) {
      case 'ref': return r.referenceNo;
      case 'name': return r.applicantName;
      case 'phone': return r.phone;
      case 'programme': return r.programmeName;
      case 'index': return r.indexNumber ?? '—';
      case 'reason': return r.reason;
      case 'verification':
        return <>{r.verificationNo}{r.multipleAdmission && <Tooltip title="Also admitted by another institution: must confirm with NACTVET's code"><Chip size="small" sx={{ ml: 1 }} label="Multiple" color="info" variant="outlined" /></Tooltip>}</>;
      case 'expires':
        return r.status === 'OFFERED'
          ? <Typography variant="body2" color={r.offerExpired ? 'error' : undefined}>{formatDate(r.offerExpiresAt)}{r.offerExpired && ' (expired)'}</Typography>
          : r.offerRespondedAt ? <Typography variant="body2" color="text.secondary">Answered {formatDate(r.offerRespondedAt)}</Typography> : '—';
      case 'letter':
        return r.letterNo
          ? <><Typography variant="body2">{r.letterNo}{r.letterEdition && r.letterEdition > 1 ? ` (ed. ${r.letterEdition})` : ''}</Typography><Typography variant="caption" color="text.secondary">Issued {formatDate(r.letterIssuedAt)}</Typography></>
          : r.status === 'ACCEPTED' ? <Chip size="small" color="warning" variant="outlined" label="Not issued" /> : '—';
      case 'status':
        return <>
          <Chip size="small" color={statusColour(r.status)} label={humanise(r.status)} />
          {r.declineReason && <Typography variant="caption" display="block" color="text.secondary">{r.declineReason}</Typography>}
        </>;
    }
  };
  return (
    <Table size="small">
      <TableHead><TableRow>{columns.map((c) => <TableCell key={c}>{head[c]}</TableCell>)}{action && !actionInColumn && <TableCell />}</TableRow></TableHead>
      <TableBody>
        {rows.map((r) => (
          <TableRow key={r.applicationId}>
            {columns.map((c) => <TableCell key={c}>{cell(r, c)}{action && actionInColumn === c && action(r)}</TableCell>)}
            {action && !actionInColumn && <TableCell align="right">{action(r)}</TableCell>}
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
