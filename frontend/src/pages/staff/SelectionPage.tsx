import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import DownloadIcon from '@mui/icons-material/Download';
import GavelIcon from '@mui/icons-material/Gavel';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, LinearProgress,
  Stack, Tab, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { api, download, formatDateTime } from '../../api/client';
import type { AdmissionWindow, SelectionRoundDetail, SelectionRoundSummary, SelectionRow } from '../../api/types';
import { ErrorAlert, Loading, PageTitle, humanise } from '../../components/common';

const staff = { realm: 'staff' as const };
const ROUND_COLOUR: Record<string, 'warning' | 'success' | 'default'> = { PROPOSED: 'warning', APPROVED: 'success', CANCELLED: 'default' };
const sexLabel = (s?: string) => (s === 'F' ? 'F' : s === 'M' ? 'M' : '—');

/** BP-03: run a selection proposal, review it as a committee, approve it. */
export default function SelectionPage() {
  const { id } = useParams();
  const windowId = Number(id);
  const qc = useQueryClient();
  const win = useQuery({ queryKey: ['staff', 'window', id], queryFn: () => api<AdmissionWindow>(`/api/admission/windows/${id}`, staff) });
  const rounds = useQuery({ queryKey: ['staff', 'rounds', id], queryFn: () => api<SelectionRoundSummary[]>(`/api/admission/windows/${id}/selection-rounds`, staff) });
  const [roundId, setRoundId] = useState<number | null>(null);
  useEffect(() => { if (rounds.data?.length && roundId == null) setRoundId(rounds.data[0].id); }, [rounds.data, roundId]);
  const detail = useQuery({
    queryKey: ['staff', 'round', roundId], enabled: roundId != null,
    queryFn: () => api<SelectionRoundDetail>(`/api/admission/selection-rounds/${roundId}`, staff),
  });

  const refresh = (d?: SelectionRoundDetail) => {
    if (d) { qc.setQueryData(['staff', 'round', d.round.id], d); setRoundId(d.round.id); }
    qc.invalidateQueries({ queryKey: ['staff', 'rounds', id] });
    qc.invalidateQueries({ queryKey: ['staff', 'applications'] });
  };
  const run = useMutation({ mutationFn: () => api<SelectionRoundDetail>(`/api/admission/windows/${windowId}/selection-rounds`, { method: 'POST', ...staff }), onSuccess: refresh });
  const [excluding, setExcluding] = useState<SelectionRow | null>(null);
  const [reason, setReason] = useState('');
  const exclude = useMutation({
    mutationFn: () => api<SelectionRoundDetail>(`/api/admission/selection-rounds/${roundId}/exclusions`, { method: 'POST', ...staff, body: { applicationId: excluding!.applicationId, reason } }),
    onSuccess: (d) => { setExcluding(null); setReason(''); refresh(d); },
  });
  const reinstate = useMutation({
    mutationFn: (appId: number) => api<SelectionRoundDetail>(`/api/admission/selection-rounds/${roundId}/exclusions/${appId}`, { method: 'DELETE', ...staff }),
    onSuccess: refresh,
  });
  const [approving, setApproving] = useState(false);
  const [minute, setMinute] = useState('');
  const [notes, setNotes] = useState('');
  const approve = useMutation({
    mutationFn: () => api<SelectionRoundDetail>(`/api/admission/selection-rounds/${roundId}/approve`, { method: 'POST', ...staff, body: { committeeMinuteRef: minute, notes } }),
    onSuccess: (d) => { setApproving(false); refresh(d); },
  });
  const cancel = useMutation({ mutationFn: () => api(`/api/admission/selection-rounds/${roundId}/cancel`, { method: 'POST', ...staff }), onSuccess: () => { setRoundId(null); refresh(); } });
  const [dlError, setDlError] = useState<unknown>(null);

  if (win.isLoading || rounds.isLoading) return <Loading />;
  const d = detail.data;
  const proposed = d?.round.status === 'PROPOSED';
  const hasOpenRound = rounds.data?.some((r) => r.status === 'PROPOSED');
  const busy = run.isPending || exclude.isPending || reinstate.isPending || approve.isPending || cancel.isPending;

  return (
    <Container maxWidth="lg" disableGutters>
      <Button component={RouterLink} to={`/staff/windows/${id}`} startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>{win.data?.name}</Button>
      <PageTitle
        title="Selection"
        subtitle="Rank eligible applicants by merit, allocate places within capacity, and record the Admissions Committee decision."
        action={
          <Stack direction="row" spacing={1}>
            <Button variant="contained" startIcon={<PlayArrowIcon />} onClick={() => run.mutate()} disabled={busy}>
              {hasOpenRound ? 'Re-run proposal' : 'Run selection'}
            </Button>
          </Stack>
        }
      />
      {busy && <LinearProgress sx={{ mb: 2 }} />}
      <ErrorAlert error={run.error ?? exclude.error ?? reinstate.error ?? approve.error ?? cancel.error ?? dlError ?? rounds.error ?? detail.error} />

      {!rounds.data?.length && (
        <Alert severity="info">No selection has been run for this window yet. <strong>Run selection</strong> creates a proposal for the Admissions Committee — nothing is final until it is approved.</Alert>
      )}

      {!!rounds.data?.length && (
        <Tabs value={roundId ?? false} onChange={(_, v) => setRoundId(v)} sx={{ mb: 2 }} variant="scrollable">
          {rounds.data.map((r) => (
            <Tab key={r.id} value={r.id} label={<Stack direction="row" spacing={1} alignItems="center"><span>Round {r.roundNo}</span><Chip size="small" color={ROUND_COLOUR[r.status]} label={humanise(r.status)} /></Stack>} />
          ))}
        </Tabs>
      )}

      {detail.isLoading && <Loading />}
      {d && (
        <Stack spacing={3}>
          <Card>
            <CardContent>
              <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" spacing={2}>
                <Box>
                  <Typography variant="body2" color="text.secondary">
                    Proposed by {d.round.proposedByName} · {formatDateTime(d.round.proposedAt)}
                    {d.round.approvedByName && <> · Approved by {d.round.approvedByName} · {formatDateTime(d.round.approvedAt)} · Minute {d.round.committeeMinuteRef}</>}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">Merit: {d.meritFormula}. Ties: earlier submission first.</Typography>
                </Box>
                <Stack direction="row" spacing={1} alignItems="flex-start">
                  <Button startIcon={<DownloadIcon />} onClick={() => download(`/api/admission/selection-rounds/${d.round.id}/export`, 'staff', `selection-round-${d.round.roundNo}.csv`).catch(setDlError)}>CSV</Button>
                  {proposed && <Button color="error" sx={{ whiteSpace: 'nowrap' }} onClick={() => cancel.mutate()} disabled={busy}>Cancel round</Button>}
                  {proposed && (
                    <Tooltip title={d.canApprove ? '' : 'The person who ran the proposal cannot approve it. The Admissions Committee chair approves.'}>
                      <span><Button variant="contained" color="secondary" startIcon={<GavelIcon />} disabled={!d.canApprove || busy} onClick={() => setApproving(true)}>Approve</Button></span>
                    </Tooltip>
                  )}
                </Stack>
              </Stack>
              <Grid container spacing={2} sx={{ mt: 1 }}>
                {[['Candidates', d.totals.candidates], ['Selected', d.totals.selected], ['Waiting', d.totals.notSelected], ['Excluded', d.totals.excluded]].map(([label, value]) => (
                  <Grid key={label} size={{ xs: 6, md: 3 }}>
                    <Typography variant="body2" color="text.secondary">{label}</Typography>
                    <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 28 }}>{value}</Typography>
                  </Grid>
                ))}
              </Grid>
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
                {Object.entries(d.selectedBySex).map(([k, v]) => <Chip key={k} size="small" label={`${k}: ${v}`} />)}
                {Object.entries(d.selectedByRegion).map(([k, v]) => <Chip key={k} size="small" variant="outlined" label={`${k}: ${v}`} />)}
              </Stack>
            </CardContent>
          </Card>

          {d.programmes.map((p) => (
            <Card key={p.windowProgrammeId}>
              <CardContent>
                <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={1}>
                  <Box>
                    <Typography variant="h6">{p.programmeName}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {p.selectedCount} selected of {p.availableThisRound} places available this round
                      {p.filledInEarlierRounds > 0 && ` (${p.filledInEarlierRounds} of ${p.capacity} filled in earlier rounds)`} · {p.applicants} applicant(s) listed it
                    </Typography>
                  </Box>
                  <Stack direction="row" spacing={1}>{Object.entries(p.selectedBySex).map(([k, v]) => <Chip key={k} size="small" label={`${k}: ${v}`} />)}</Stack>
                </Stack>
                <LinearProgress variant="determinate" value={p.availableThisRound ? Math.min(100, (p.selectedCount / p.availableThisRound) * 100) : 0} sx={{ my: 1.5, height: 6, borderRadius: 3 }} />
                <RowsTable rows={p.selected} showRank action={proposed ? (r) => <Button size="small" color="error" onClick={() => setExcluding(r)}>Exclude</Button> : undefined} />
              </CardContent>
            </Card>
          ))}

          {d.notSelected.length > 0 && (
            <Card><CardContent>
              <Typography variant="h6">Waiting list</Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>Eligible but not placed in this round (by merit). They remain available for the next round.</Typography>
              <RowsTable rows={d.notSelected} />
            </CardContent></Card>
          )}

          {d.excluded.length > 0 && (
            <Card><CardContent>
              <Typography variant="h6">Excluded by the committee</Typography>
              <TableContainer><Table size="small">
                <TableHead><TableRow><TableCell>Reference</TableCell><TableCell>Applicant</TableCell><TableCell>Reason</TableCell><TableCell>By</TableCell><TableCell /></TableRow></TableHead>
                <TableBody>
                  {d.excluded.map((r) => (
                    <TableRow key={r.applicationId}>
                      <TableCell>{r.referenceNo}</TableCell><TableCell>{r.applicantName}</TableCell><TableCell>{r.exclusionReason}</TableCell><TableCell>{r.excludedByName}</TableCell>
                      <TableCell align="right">{proposed && <Button size="small" onClick={() => reinstate.mutate(r.applicationId)}>Reinstate</Button>}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table></TableContainer>
            </CardContent></Card>
          )}
        </Stack>
      )}

      <Dialog open={!!excluding} onClose={() => setExcluding(null)} fullWidth maxWidth="sm">
        <DialogTitle>Exclude {excluding?.applicantName}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>The reason is recorded in the audit trail. Places are re-allocated immediately.</Typography>
          <TextField label="Reason" value={reason} onChange={(e) => setReason(e.target.value)} multiline minRows={2} autoFocus />
        </DialogContent>
        <DialogActions><Button onClick={() => setExcluding(null)}>Cancel</Button><Button color="error" variant="contained" disabled={!reason.trim() || exclude.isPending} onClick={() => exclude.mutate()}>Exclude</Button></DialogActions>
      </Dialog>

      <Dialog open={approving} onClose={() => setApproving(false)} fullWidth maxWidth="sm">
        <DialogTitle>Approve round {d?.round.roundNo}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {d?.totals.selected} applicant(s) will be marked as selected. They are then submitted to NACTVET for verification before admission letters are issued.
          </Typography>
          <Stack spacing={2}>
            <TextField label="Admissions Committee minute reference" placeholder="e.g. AC/2027/04" value={minute} onChange={(e) => setMinute(e.target.value)} required />
            <TextField label="Notes (optional)" value={notes} onChange={(e) => setNotes(e.target.value)} multiline minRows={2} />
          </Stack>
        </DialogContent>
        <DialogActions><Button onClick={() => setApproving(false)}>Cancel</Button><Button variant="contained" color="secondary" disabled={!minute.trim() || approve.isPending} onClick={() => approve.mutate()}>Approve selection</Button></DialogActions>
      </Dialog>
    </Container>
  );
}

function RowsTable({ rows, showRank, action }: { rows: SelectionRow[]; showRank?: boolean; action?: (r: SelectionRow) => React.ReactNode }) {
  if (!rows.length) return <Typography color="text.secondary" variant="body2">None.</Typography>;
  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          <TableRow>
            {showRank && <TableCell>#</TableCell>}
            <TableCell>Reference</TableCell><TableCell>Applicant</TableCell><TableCell>Sex</TableCell><TableCell>Region</TableCell>
            <TableCell align="right">Merit</TableCell><TableCell align="center">Choice</TableCell>{action && <TableCell />}
          </TableRow>
        </TableHead>
        <TableBody>
          {rows.map((r) => (
            <TableRow key={r.applicationId} hover>
              {showRank && <TableCell>{r.rank}</TableCell>}
              <TableCell>{r.referenceNo}</TableCell>
              <TableCell>{r.applicantName}</TableCell>
              <TableCell>{sexLabel(r.sex)}</TableCell>
              <TableCell>{r.region ?? '—'}</TableCell>
              <TableCell align="right">{Number(r.meritScore).toFixed(1)}</TableCell>
              <TableCell align="center">{r.choiceOrder ? <Chip size="small" variant="outlined" label={r.choiceOrder} /> : '—'}</TableCell>
              {action && <TableCell align="right">{action(r)}</TableCell>}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
