import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import DownloadIcon from '@mui/icons-material/Download';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { api, download, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type { Reconciliation, ReconciliationItem, ReconciliationMonth } from '../../api/types';
import { ErrorAlert, Loading } from '../../components/common';

const staff = { realm: 'staff' as const };

const KIND: Record<string, { label: string; help: string; colour: 'success' | 'warning' | 'error' | 'info' | 'default' }> = {
  MATCHED: { label: 'Matched', help: 'In GePG’s file and in CIMS with the same amount', colour: 'success' },
  MISSING_IN_CIMS: { label: 'Not received by CIMS', help: 'GePG collected it but the payment notice never reached CIMS. Post it to credit the payer.', colour: 'error' },
  MISSING_IN_GEPG: { label: 'Not in GePG’s file', help: 'CIMS recorded it but GePG does not report it for this day. It settles itself if a later file has it.', colour: 'warning' },
  AMOUNT_MISMATCH: { label: 'Amount differs', help: 'GePG and CIMS have different amounts for the same receipt', colour: 'error' },
  DUPLICATE: { label: 'Repeated in file', help: 'The same GePG receipt appears more than once in the file', colour: 'warning' },
  UNKNOWN_BILL: { label: 'Unknown bill', help: 'GePG reports a bill CIMS did not issue (another institution’s or a test bill)', colour: 'info' },
};

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/** Daily GePG reconciliation and the Bursar's monthly sign-off (FR-FIN-05). */
export default function ReconciliationTab({ canWork, isBursar }: { canWork: boolean; isBursar: boolean }) {
  const [month, setMonth] = useState(iso(new Date()).substring(0, 7));
  const [openId, setOpenId] = useState<number | null>(null);
  const [importDate, setImportDate] = useState<string | null>(null);
  if (openId != null) return <DayDetail id={openId} canWork={canWork} onBack={() => setOpenId(null)} onReimport={(d) => setImportDate(d)} importDialog={
    importDate && <ImportDialog date={importDate} onClose={() => setImportDate(null)} onDone={(r) => { setImportDate(null); setOpenId(r.id); }} />} />;
  return (
    <>
      <MonthView month={month} setMonth={setMonth} canWork={canWork} isBursar={isBursar} onOpen={setOpenId} onImport={setImportDate} />
      {importDate && <ImportDialog date={importDate} onClose={() => setImportDate(null)} onDone={(r) => { setImportDate(null); setOpenId(r.id); }} />}
    </>
  );
}

function MonthView({ month, setMonth, canWork, isBursar, onOpen, onImport }: {
  month: string; setMonth: (m: string) => void; canWork: boolean; isBursar: boolean; onOpen: (id: number) => void; onImport: (date: string) => void;
}) {
  const qc = useQueryClient();
  const [signing, setSigning] = useState(false);
  const [note, setNote] = useState('');
  const q = useQuery({ queryKey: ['staff', 'reconciliation-month', month], queryFn: () => api<ReconciliationMonth>(`/api/finance/reconciliations?month=${month}`, staff) });
  const sign = useMutation({
    mutationFn: () => api<ReconciliationMonth>(`/api/finance/reconciliations/months/${month}/sign-off`, { ...staff, method: 'POST', body: { note } }),
    onSuccess: (m) => { qc.setQueryData(['staff', 'reconciliation-month', month], m); setSigning(false); },
  });
  const m = q.data;
  const days = m ? [...m.days].reverse() : [];
  return (
    <>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }} alignItems={{ sm: 'center' }}>
        <TextField size="small" type="month" label="Month" sx={{ width: 190, flexShrink: 0 }} value={month} onChange={(e) => e.target.value && setMonth(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
        <Typography variant="body2" color="text.secondary" sx={{ flex: 1, minWidth: 0 }}>
          Import GePG’s reconciliation file each day. Matched payments need nothing; exceptions are resolved before the day is closed.
        </Typography>
      </Stack>
      <ErrorAlert error={q.error} />
      {q.isLoading && <Loading />}
      {m && (
        <Stack spacing={2.5}>
          <Grid container spacing={1.5}>
            <Tile label="Collected in CIMS" value={formatTzs(m.collected)} />
            <Tile label="Payments" value={m.payments} />
            <Tile label="Days with payments closed" value={`${m.daysClosed} / ${m.daysWithPayments}`} tone={m.daysWithPayments && m.daysClosed >= m.daysWithPayments ? 'success' : undefined} small />
          </Grid>

          {m.signedAt ? (
            <Alert severity="success" icon={<CheckCircleIcon />}>
              Signed off by {m.signedByName} on {formatDateTime(m.signedAt)}.{m.signOffNote ? ` “${m.signOffNote}”` : ''}
            </Alert>
          ) : (
            <Alert severity={m.canSignOff ? 'info' : 'warning'} action={isBursar && (
              <Button color="inherit" size="small" disabled={!m.canSignOff} onClick={() => { setNote(''); setSigning(true); }} sx={{ whiteSpace: 'nowrap' }}>Sign off month</Button>)}>
              {m.canSignOff ? 'Every day is reconciled and closed. The Bursar can sign off the month.' : `Monthly sign-off: ${m.signOffBlocker}.`}
            </Alert>
          )}

          <Card><TableContainer><Table size="small">
            <TableHead><TableRow>
              <TableCell>Day</TableCell><TableCell align="right">CIMS payments</TableCell><TableCell align="right">Amount</TableCell>
              <TableCell align="right">In GePG file</TableCell><TableCell>Reconciliation</TableCell><TableCell align="right" />
            </TableRow></TableHead>
            <TableBody>
              {days.map((d) => (
                <TableRow key={d.date} hover={!!d.reconciliationId} sx={{ cursor: d.reconciliationId ? 'pointer' : undefined }}
                  onClick={() => d.reconciliationId && onOpen(d.reconciliationId)}>
                  <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDate(d.date)}</TableCell>
                  <TableCell align="right">{d.cimsCount || '—'}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{d.cimsCount ? formatTzs(d.cimsTotal) : '—'}</TableCell>
                  <TableCell align="right">{d.gepgCount ?? '—'}</TableCell>
                  <TableCell><DayStatus d={d} /></TableCell>
                  <TableCell align="right" onClick={(e) => e.stopPropagation()}>
                    {canWork && d.status !== 'CLOSED' && (!d.reconciliationId || !d.openExceptions || d.openExceptions === d.exceptionCount) && (
                      <Button size="small" startIcon={<UploadFileIcon />} onClick={() => onImport(d.date)}>{d.reconciliationId ? 'Re-import' : 'Import'}</Button>)}
                  </TableCell>
                </TableRow>
              ))}
              {!days.length && <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 2 }}>Nothing to show for this month.</Typography></TableCell></TableRow>}
            </TableBody>
          </Table></TableContainer></Card>
        </Stack>
      )}
      <Dialog open={signing} onClose={() => setSigning(false)} maxWidth="sm" fullWidth>
        <DialogTitle>Sign off {m && new Date(`${m.month}T00:00`).toLocaleDateString('en-GB', { month: 'long', year: 'numeric' })}</DialogTitle>
        <DialogContent>
          <Typography sx={{ mb: 2 }}>
            You confirm that collections of <strong>{m && formatTzs(m.collected)}</strong> agree with GePG for every day of the month.
            The sign-off is recorded with your name and cannot be undone.
          </Typography>
          <TextField fullWidth multiline minRows={2} label="Note (optional)" value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. Agrees with the bank statement" />
          <ErrorAlert error={sign.error} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setSigning(false)}>Cancel</Button>
          <Button variant="contained" disabled={sign.isPending} onClick={() => sign.mutate()}>Sign off</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

function DayStatus({ d }: { d: ReconciliationMonth['days'][number] }) {
  if (!d.reconciliationId) {
    return d.cimsCount ? <Chip size="small" color="warning" variant="outlined" label="Not reconciled" /> : <Typography variant="body2" color="text.secondary">—</Typography>;
  }
  if (d.status === 'CLOSED') return <Chip size="small" color="success" label="Closed" />;
  return d.openExceptions
    ? <Chip size="small" color="error" label={`${d.openExceptions} exception${d.openExceptions === 1 ? '' : 's'} open`} />
    : <Chip size="small" color="info" label="Ready to close" />;
}

function ImportDialog({ date, onClose, onDone }: { date: string; onClose: () => void; onDone: (r: Reconciliation) => void }) {
  const qc = useQueryClient();
  const [file, setFile] = useState<{ name: string; text: string } | null>(null);
  const [dlError, setDlError] = useState<unknown>(null);
  const run = useMutation({
    mutationFn: () => api<Reconciliation>('/api/finance/reconciliations', { ...staff, method: 'POST', body: { date, fileName: file?.name, csv: file?.text } }),
    onSuccess: (r) => { qc.invalidateQueries({ queryKey: ['staff', 'reconciliation-month'] }); qc.setQueryData(['staff', 'reconciliation', r.id], r); onDone(r); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Import GePG file for {formatDate(date)}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 2 }}>
          Download the day’s reconciliation file from the GePG portal as CSV (columns PayRefId, SpBillId, BillCtrNum, PspTrxId, PspName, PaidAmt, Ccy,
          TrxDtTm, UsdPayChnl, PyrCellNum). Payments are matched by GePG receipt number. A file with any invalid line is rejected as a whole.
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }}>
          <Button variant="outlined" component="label" startIcon={<UploadFileIcon />}>
            Choose CSV file
            <input hidden type="file" accept=".csv,text/csv" onChange={async (e) => {
              const f = e.target.files?.[0];
              if (f) setFile({ name: f.name, text: await f.text() });
              e.target.value = '';
            }} />
          </Button>
          <Typography variant="body2" color={file ? 'text.primary' : 'text.secondary'} sx={{ flex: 1, wordBreak: 'break-all' }}>
            {file ? `${file.name} · ${Math.max(file.text.trim().split(/\r?\n/).length - 1, 0)} line(s)` : 'No file chosen'}
          </Typography>
        </Stack>
        <Button size="small" startIcon={<DownloadIcon />} sx={{ mt: 1.5 }}
          onClick={() => download(`/api/finance/reconciliations/cims-payments.csv?date=${date}`, 'staff', `cims-payments-${date}.csv`).catch(setDlError)}>
          CIMS payments of this day in the same layout
        </Button>
        <ErrorAlert error={run.error ?? dlError} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!file || run.isPending} onClick={() => run.mutate()}>Import and match</Button>
      </DialogActions>
    </Dialog>
  );
}

function DayDetail({ id, canWork, onBack, onReimport, importDialog }: {
  id: number; canWork: boolean; onBack: () => void; onReimport: (date: string) => void; importDialog: ReactNode;
}) {
  const qc = useQueryClient();
  const [resolving, setResolving] = useState<ReconciliationItem | null>(null);
  const [posting, setPosting] = useState<ReconciliationItem | null>(null);
  const [showMatched, setShowMatched] = useState(false);
  const q = useQuery({ queryKey: ['staff', 'reconciliation', id], queryFn: () => api<Reconciliation>(`/api/finance/reconciliations/${id}`, staff) });
  const done = (r: Reconciliation) => {
    qc.setQueryData(['staff', 'reconciliation', id], r);
    qc.invalidateQueries({ queryKey: ['staff', 'reconciliation-month'] });
  };
  const close = useMutation({ mutationFn: () => api<Reconciliation>(`/api/finance/reconciliations/${id}/close`, { ...staff, method: 'POST' }), onSuccess: done });
  const r = q.data;
  if (q.isLoading) return <Loading />;
  if (!r) return <ErrorAlert error={q.error} />;
  const open = r.status === 'OPEN';
  const exceptions = r.items.filter((i) => i.kind !== 'MATCHED');
  const matched = r.items.filter((i) => i.kind === 'MATCHED');
  const anyResolved = r.items.some((i) => i.status === 'RESOLVED');
  return (
    <Stack spacing={2.5}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }}>
        <Button startIcon={<ArrowBackIcon />} onClick={onBack}>Month</Button>
        <Box sx={{ flex: 1 }}>
          <Typography variant="h6">Reconciliation for {formatDate(r.date)}</Typography>
          <Typography variant="body2" color="text.secondary">
            {r.fileName ?? 'GePG file'} imported by {r.importedByName}, {formatDateTime(r.importedAt)}
            {r.closedAt && <> · closed by {r.closedByName}, {formatDateTime(r.closedAt)}</>}
          </Typography>
        </Box>
        {open && canWork && !anyResolved && <Button startIcon={<UploadFileIcon />} onClick={() => onReimport(r.date)}>Re-import</Button>}
        {open && canWork && (
          <Tooltip title={r.openExceptions ? 'Resolve every exception first' : ''}>
            <span><Button variant="contained" disabled={!!r.openExceptions || close.isPending} onClick={() => close.mutate()}>Close day</Button></span>
          </Tooltip>
        )}
        {!open && <Chip color="success" label="Closed" />}
      </Stack>
      <ErrorAlert error={close.error} />

      <Grid container spacing={1.5}>
        <Tile small label={`GePG (${r.gepgCount})`} value={formatTzs(r.gepgTotal)} />
        <Tile small label={`CIMS (${r.cimsCount})`} value={formatTzs(r.cimsTotal)} />
        <Tile small label="Difference" value={formatTzs(r.gepgTotal - r.cimsTotal)} tone={r.gepgTotal !== r.cimsTotal ? 'warning' : 'success'} />
        <Tile small label="Matched" value={r.matchRate != null ? `${r.matchedCount} (${r.matchRate}%)` : r.matchedCount} tone="success" />
        <Tile small label="Open exceptions" value={r.openExceptions} tone={r.openExceptions ? 'error' : 'success'} />
      </Grid>

      <Card><CardContent>
        <Typography variant="h6" sx={{ mb: 1 }}>Exceptions</Typography>
        {!exceptions.length ? <Typography color="text.secondary">None. Every line in GePG’s file matches a CIMS payment.</Typography> : (
          <TableContainer><Table size="small">
            <TableHead><TableRow>
              <TableCell>Exception</TableCell><TableCell>GePG receipt</TableCell><TableCell>Payer / bill</TableCell>
              <TableCell align="right">GePG</TableCell><TableCell align="right">CIMS</TableCell><TableCell>Resolution</TableCell>
            </TableRow></TableHead>
            <TableBody>
              {exceptions.map((i) => (
                <TableRow key={i.id} sx={{ verticalAlign: 'top' }}>
                  <TableCell sx={{ minWidth: 150 }}>
                    <Tooltip title={KIND[i.kind]?.help ?? ''}><Chip size="small" color={KIND[i.kind]?.colour ?? 'default'} label={KIND[i.kind]?.label ?? i.kind} /></Tooltip>
                    {i.lineNo && <Typography variant="caption" color="text.secondary" display="block">Line {i.lineNo}</Typography>}
                  </TableCell>
                  <TableCell sx={{ overflowWrap: 'anywhere', minWidth: 150 }}>{i.gepgReceiptNo ?? '—'}
                    <Typography variant="caption" color="text.secondary" display="block">{[i.pspName, i.channel, i.paidAt && formatDateTime(i.paidAt)].filter(Boolean).join(' · ')}</Typography>
                  </TableCell>
                  <TableCell>{i.payerName ?? (i.payerPhone || '—')}
                    <Typography variant="caption" color="text.secondary" display="block">{i.receiptNo ? `Receipt ${i.receiptNo}` : i.billId ?? i.controlNo}</Typography>
                  </TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{i.gepgAmount != null ? formatTzs(i.gepgAmount) : '—'}</TableCell>
                  <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{i.cimsAmount != null ? formatTzs(i.cimsAmount) : '—'}</TableCell>
                  <TableCell sx={{ minWidth: 200 }}>
                    {i.status === 'RESOLVED' ? (
                      <>
                        <Typography variant="body2"><strong>{i.resolution === 'POSTED' ? 'Posted' : i.resolution === 'AUTO_MATCHED' ? 'Matched later' : 'Explained'}</strong>{i.note ? ` — ${i.note}` : ''}</Typography>
                        <Typography variant="caption" color="text.secondary">{i.resolvedByName}, {i.resolvedAt && formatDateTime(i.resolvedAt)}</Typography>
                      </>
                    ) : open && canWork ? (
                      <Stack direction="row" spacing={1}>
                        {i.kind === 'MISSING_IN_CIMS' && <Button size="small" variant="contained" sx={{ whiteSpace: 'nowrap' }} onClick={() => setPosting(i)}>Post payment</Button>}
                        <Button size="small" variant="outlined" onClick={() => setResolving(i)}>Explain</Button>
                      </Stack>
                    ) : <Typography variant="body2" color="text.secondary">Open</Typography>}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table></TableContainer>
        )}
      </CardContent></Card>

      <Card><CardContent>
        <Stack direction="row" alignItems="center">
          <Typography variant="h6" sx={{ flex: 1 }}>Matched ({matched.length})</Typography>
          {!!matched.length && <Button size="small" onClick={() => setShowMatched(!showMatched)}>{showMatched ? 'Hide' : 'Show'}</Button>}
        </Stack>
        {showMatched && (
          <TableContainer><Table size="small">
            <TableHead><TableRow><TableCell>GePG receipt</TableCell><TableCell>Payer</TableCell><TableCell>CIMS receipt</TableCell><TableCell align="right">Amount</TableCell></TableRow></TableHead>
            <TableBody>
              {matched.map((i) => (
                <TableRow key={i.id}>
                  <TableCell sx={{ overflowWrap: 'anywhere' }}>{i.gepgReceiptNo}{i.paidDateDiffers && <Chip size="small" variant="outlined" label="Recorded on another day" sx={{ ml: 1 }} />}</TableCell>
                  <TableCell>{i.payerName}</TableCell>
                  <TableCell>{i.receiptNo}</TableCell>
                  <TableCell align="right">{formatTzs(i.gepgAmount ?? 0)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table></TableContainer>
        )}
      </CardContent></Card>

      {resolving && <NoteDialog item={resolving} onClose={() => setResolving(null)} onDone={(x) => { done(x); setResolving(null); }} />}
      {posting && <PostDialog item={posting} onClose={() => setPosting(null)} onDone={(x) => { done(x); setPosting(null); }} />}
      {importDialog}
    </Stack>
  );
}

function NoteDialog({ item, onClose, onDone }: { item: ReconciliationItem; onClose: () => void; onDone: (r: Reconciliation) => void }) {
  const [note, setNote] = useState('');
  const m = useMutation({
    mutationFn: () => api<Reconciliation>(`/api/finance/reconciliations/items/${item.id}/resolve`, { ...staff, method: 'POST', body: { note } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Explain: {KIND[item.kind]?.label}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{KIND[item.kind]?.help}. Record what was found; the explanation is kept with your name for the auditors.</Typography>
        <TextField autoFocus fullWidth multiline minRows={2} label="Explanation" value={note} onChange={(e) => setNote(e.target.value)}
          placeholder="e.g. Confirmed with GePG helpdesk, ticket 4471" inputProps={{ maxLength: 500 }} />
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={!note.trim() || m.isPending} onClick={() => m.mutate()}>Resolve</Button>
      </DialogActions>
    </Dialog>
  );
}

function PostDialog({ item, onClose, onDone }: { item: ReconciliationItem; onClose: () => void; onDone: (r: Reconciliation) => void }) {
  const m = useMutation({
    mutationFn: () => api<Reconciliation>(`/api/finance/reconciliations/items/${item.id}/post`, { ...staff, method: 'POST', body: {} }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>Post payment from GePG</DialogTitle>
      <DialogContent>
        <Typography sx={{ mb: 1 }}>
          GePG collected <strong>{formatTzs(item.gepgAmount ?? 0)}</strong> (receipt {item.gepgReceiptNo}) for bill {item.billId ?? item.controlNo}, but the payment notice never reached CIMS.
        </Typography>
        <Typography variant="body2" color="text.secondary">
          Posting records the payment as if the notice had arrived: a receipt is issued, the amount is allocated to the bill and the payer’s balance and clearance are updated.
        </Typography>
        <ErrorAlert error={m.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={m.isPending} onClick={() => m.mutate()}>Post payment</Button>
      </DialogActions>
    </Dialog>
  );
}

function Tile({ label, value, tone, small }: { label: string; value: ReactNode; tone?: 'success' | 'warning' | 'error'; small?: boolean }) {
  return (
    <Grid size={{ xs: 6, md: 'grow' }}>
      <Card sx={{ height: '100%' }}><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
        <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: small ? 17 : 22, color: tone ? `${tone}.main` : undefined }}>{value}</Typography>
      </CardContent></Card>
    </Grid>
  );
}
