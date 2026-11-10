import AddIcon from '@mui/icons-material/Add';
import UploadIcon from '@mui/icons-material/UploadFile';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, Tab, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, formatDate, formatTzs, uploadForm } from '../../api/client';
import { useRoles } from './MarksPages';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';

const staff = { realm: 'staff' as const };

interface Settings { studentLoanDays: number; staffLoanDays: number; studentMaxLoans: number; staffMaxLoans: number; maxRenewals: number; finePerDay: number; maxFine: number }
interface Item { id: number; title: string; authors?: string; isbn?: string; publisher?: string; year?: number; edition?: string; subject?: string; callNumber?: string; kind: string; copies: number; available: number }
interface Copy { id: number; barcode: string; campus?: string; status: string; price?: number; acquiredOn?: string; note?: string; borrower?: string; dueOn?: string }
interface Loan {
  id: number; copyId: number; barcode: string; title: string; callNumber?: string; borrowerType: 'STUDENT' | 'STAFF'; borrowerId: number; borrowerName: string;
  borrowerRef: string; issuedAt: string; dueOn: string; renewals: number; returnedAt?: string; lost: boolean; daysLate: number; fine: number; fineStatus: string;
  fineNote?: string; controlNo?: string; issuedByName: string; returnedByName?: string;
}
interface Borrower { type: 'STUDENT' | 'STAFF'; id: number; name: string; reference: string; status: string; canBorrow: boolean; reason?: string; onLoan: number; maxLoans: number; overdue: number; unpaidFines: number; loans: Loan[] }
interface Overview { titles: number; copies: number; onLoan: number; overdue: number; lost: number; finesBilledThisYear: number; finesUnpaid: number }

const KINDS = [['BOOK', 'Book'], ['REFERENCE', 'Reference (library use only)'], ['JOURNAL', 'Journal'], ['REPORT', 'Report'], ['MEDIA', 'Media'], ['OTHER', 'Other']];
const FINE: Record<string, string> = { BILLED: 'On the student account', TO_PAY: 'To pay at the cashier', PAID: 'Paid', WAIVED: 'Waived' };
const COPY_STATUS: Record<string, string> = { AVAILABLE: 'Available', ON_LOAN: 'On loan', IN_LIBRARY: 'Library use only', LOST: 'Lost', WITHDRAWN: 'Withdrawn' };

/** The library (FR-LIB-01..05): the lending desk, the catalogue, loans and fines, and the rules. */
export default function LibraryPage() {
  const [tab, setTab] = useState('desk');
  const canWrite = useRoles()(['INSTITUTION_ADMIN', 'LIBRARIAN']);
  const ov = useQuery({ queryKey: ['staff', 'library', 'overview'], queryFn: () => api<Overview>('/api/library/overview', staff) });
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Library" subtitle="Lend and take back books, keep the catalogue, and follow loans and fines." />
      {ov.data && (
        <Grid container spacing={1.5} sx={{ mb: 2 }}>
          {[['Titles', ov.data.titles], ['Copies', ov.data.copies], ['On loan', ov.data.onLoan], ['Overdue', ov.data.overdue], ['Fines unpaid', formatTzs(ov.data.finesUnpaid)]].map(([l, v]) => (
            <Grid key={l as string} size={{ xs: 6, sm: 4, md: 2.4 }}>
              <Card><CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
                <Typography variant="caption" color="text.secondary">{l}</Typography>
                <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 22 }} color={l === 'Overdue' && Number(v) > 0 ? 'warning.main' : undefined}>{typeof v === 'number' ? v.toLocaleString() : v}</Typography>
              </CardContent></Card>
            </Grid>
          ))}
        </Grid>
      )}
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }} variant="scrollable">
        <Tab value="desk" label="Lending desk" />
        <Tab value="catalogue" label="Catalogue" />
        <Tab value="loans" label="Loans and fines" />
        <Tab value="settings" label="Rules" />
      </Tabs>
      {tab === 'desk' && <Desk canWrite={canWrite} />}
      {tab === 'catalogue' && <Catalogue canWrite={canWrite} />}
      {tab === 'loans' && <Loans canWrite={canWrite} />}
      {tab === 'settings' && <SettingsCard canWrite={canWrite} />}
    </Container>
  );
}

function useRefresh() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: ['staff', 'library'] });
}

// ------------------------------------------------------------------ the lending desk

function Desk({ canWrite }: { canWrite: boolean }) {
  const refresh = useRefresh();
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [picked, setPicked] = useState<Borrower | null>(null);
  const [barcode, setBarcode] = useState('');
  const [back, setBack] = useState('');
  const [last, setLast] = useState<{ kind: 'issued' | 'returned'; loan: Loan } | null>(null);
  const found = useQuery({ queryKey: ['staff', 'library', 'borrowers', search], enabled: search.length >= 2,
    queryFn: () => api<Borrower[]>(`/api/library/borrowers?q=${encodeURIComponent(search)}`, staff) });
  const borrower = useQuery({ queryKey: ['staff', 'library', 'borrower', picked?.type, picked?.id], enabled: !!picked,
    queryFn: () => api<Borrower>(`/api/library/borrowers/${picked!.type}/${picked!.id}`, staff), initialData: picked ?? undefined });
  const issue = useMutation({
    mutationFn: () => api<Loan>('/api/library/issue', { ...staff, method: 'POST', body: { barcode, borrowerType: picked!.type, borrowerId: picked!.id } }),
    onSuccess: (l) => { setLast({ kind: 'issued', loan: l }); setBarcode(''); refresh(); },
  });
  const giveBack = useMutation({
    mutationFn: () => api<Loan>('/api/library/return', { ...staff, method: 'POST', body: { barcode: back } }),
    onSuccess: (l) => { setLast({ kind: 'returned', loan: l }); setBack(''); refresh(); },
  });
  const b = borrower.data;
  return (
    <Grid container spacing={2}>
      <Grid size={{ xs: 12, md: 7 }}>
        <Card><CardContent>
          <Typography variant="h6" sx={{ mb: 1 }}>Lend</Typography>
          <form onSubmit={(e) => { e.preventDefault(); setSearch(q.trim()); setPicked(null); }}>
            <Stack direction="row" spacing={1}>
              <TextField size="small" fullWidth label="Registration number, staff number or name" value={q} onChange={(e) => setQ(e.target.value)} />
              <Button type="submit" variant="outlined">Find</Button>
            </Stack>
          </form>
          <ErrorAlert error={found.error} />
          {!picked && found.data && (found.data.length === 0
            ? <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>No student or member of staff found. Staff must have a staff record (Human resources).</Typography>
            : <Stack spacing={1} sx={{ mt: 1.5 }}>{found.data.map((x) => (
              <Button key={`${x.type}${x.id}`} variant="outlined" sx={{ justifyContent: 'flex-start', textTransform: 'none' }} onClick={() => setPicked(x)}>
                {`${x.name} · ${x.reference} · ${x.type === 'STUDENT' ? 'Student' : 'Staff'}`}
              </Button>))}</Stack>)}
          {b && (
            <Box sx={{ mt: 2 }}>
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Typography sx={{ fontWeight: 700 }}>{b.name}</Typography>
                <Chip size="small" label={`${b.type === 'STUDENT' ? 'Student' : 'Staff'} · ${b.reference}`} />
                <Chip size="small" label={`${b.onLoan} of ${b.maxLoans} on loan`} />
                {b.overdue > 0 && <Chip size="small" color="warning" label={`${b.overdue} overdue`} />}
                {b.unpaidFines > 0 && <Chip size="small" color="error" variant="outlined" label={`Fines ${formatTzs(b.unpaidFines)}`} />}
                <Button size="small" onClick={() => { setPicked(null); setSearch(''); setQ(''); }}>Change</Button>
              </Stack>
              {!b.canBorrow && <Alert severity="warning" sx={{ mt: 1.5 }}>{b.reason}</Alert>}
              {canWrite && b.canBorrow && (
                <form onSubmit={(e) => { e.preventDefault(); issue.mutate(); }}>
                  <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
                    <TextField size="small" fullWidth label="Copy number (scan the barcode)" value={barcode} onChange={(e) => setBarcode(e.target.value)} autoFocus />
                    <Button type="submit" variant="contained" disabled={!barcode.trim() || issue.isPending}>Lend</Button>
                  </Stack>
                </form>
              )}
              <ErrorAlert error={issue.error} />
              {b.loans.length > 0 && <LoanTable loans={b.loans} canWrite={canWrite} compact />}
            </Box>
          )}
        </CardContent></Card>
      </Grid>
      <Grid size={{ xs: 12, md: 5 }}>
        <Card><CardContent>
          <Typography variant="h6" sx={{ mb: 1 }}>Take back</Typography>
          {canWrite ? (
            <form onSubmit={(e) => { e.preventDefault(); giveBack.mutate(); }}>
              <Stack direction="row" spacing={1}>
                <TextField size="small" fullWidth label="Copy number" value={back} onChange={(e) => setBack(e.target.value)} />
                <Button type="submit" variant="contained" disabled={!back.trim() || giveBack.isPending}>Take back</Button>
              </Stack>
            </form>
          ) : <Typography variant="body2" color="text.secondary">Only the library staff lend and take back.</Typography>}
          <ErrorAlert error={giveBack.error} />
          {last && (
            <Alert severity={last.kind === 'returned' && last.loan.fine > 0 ? 'warning' : 'success'} sx={{ mt: 2 }}>
              {last.kind === 'issued'
                ? `${last.loan.title} (${last.loan.barcode}) lent to ${last.loan.borrowerName}, due ${formatDate(last.loan.dueOn)}.`
                : `${last.loan.title} (${last.loan.barcode}) back from ${last.loan.borrowerName}.`
                  + (last.loan.fine > 0 ? ` ${last.loan.daysLate} days late: fine ${formatTzs(last.loan.fine)} (${FINE[last.loan.fineStatus] ?? ''}${last.loan.controlNo ? `, control number ${last.loan.controlNo}` : ''}).` : '')}
            </Alert>
          )}
        </CardContent></Card>
      </Grid>
    </Grid>
  );
}

function LoanTable({ loans, canWrite, compact }: { loans: Loan[]; canWrite: boolean; compact?: boolean }) {
  const refresh = useRefresh();
  const [dialog, setDialog] = useState<{ loan: Loan; action: 'lost' | 'waive' | 'fine-paid' } | null>(null);
  const renew = useMutation({ mutationFn: (l: Loan) => api<Loan>(`/api/library/loans/${l.id}/renew`, { ...staff, method: 'POST' }), onSuccess: refresh });
  return (
    <>
      <ErrorAlert error={renew.error} />
      <TableContainer sx={{ mt: 1.5 }}>
        <Table size="small" sx={{ minWidth: compact ? 520 : 820 }}>
          <TableHead><TableRow>
            <TableCell>Item</TableCell>{!compact && <TableCell>Borrower</TableCell>}<TableCell>Due</TableCell><TableCell>Fine</TableCell><TableCell />
          </TableRow></TableHead>
          <TableBody>
            {loans.map((l) => (
              <TableRow key={l.id}>
                <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{l.title}</Typography><Typography variant="caption" color="text.secondary">{l.barcode}{l.callNumber ? ` · ${l.callNumber}` : ''}</Typography></TableCell>
                {!compact && <TableCell><Typography variant="body2">{l.borrowerName}</Typography><Typography variant="caption" color="text.secondary">{l.borrowerRef}</Typography></TableCell>}
                <TableCell>
                  {l.returnedAt ? <Typography variant="body2" color="text.secondary">{l.lost ? 'Lost' : `Back ${formatDate(l.returnedAt)}`}</Typography> : formatDate(l.dueOn)}
                  {!l.returnedAt && l.daysLate > 0 && <Chip size="small" color="warning" label={`${l.daysLate} days late`} sx={{ ml: 1 }} />}
                </TableCell>
                <TableCell>{l.fine > 0 ? <><Typography variant="body2">{formatTzs(l.fine)}</Typography><Typography variant="caption" color="text.secondary">{FINE[l.fineStatus]}{l.controlNo ? ` · ${l.controlNo}` : ''}</Typography></> : '—'}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {canWrite && !l.returnedAt && <Button size="small" disabled={renew.isPending || l.daysLate > 0} onClick={() => renew.mutate(l)}>Renew</Button>}
                  {canWrite && !l.returnedAt && <Button size="small" color="error" onClick={() => setDialog({ loan: l, action: 'lost' })}>Lost</Button>}
                  {canWrite && ['BILLED', 'TO_PAY'].includes(l.fineStatus) && <Button size="small" onClick={() => setDialog({ loan: l, action: 'waive' })}>Waive</Button>}
                  {canWrite && l.fineStatus === 'TO_PAY' && <Button size="small" onClick={() => setDialog({ loan: l, action: 'fine-paid' })}>Paid</Button>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
      {dialog && <LoanActionDialog {...dialog} onClose={() => setDialog(null)} onDone={() => { setDialog(null); refresh(); }} />}
    </>
  );
}

function LoanActionDialog({ loan, action, onClose, onDone }: { loan: Loan; action: 'lost' | 'waive' | 'fine-paid'; onClose: () => void; onDone: () => void }) {
  const [note, setNote] = useState('');
  const go = useMutation({ mutationFn: () => api(`/api/library/loans/${loan.id}/${action}`, { ...staff, method: 'POST', body: { note } }), onSuccess: onDone });
  const words = {
    lost: { title: 'Mark as lost', label: 'What happened (optional)', help: `${loan.borrowerName} is charged the price of the copy plus any late fine${loan.borrowerType === 'STUDENT' ? ' on their account, with a control number' : ', to pay at the cashier'}.` },
    waive: { title: 'Waive the fine', label: 'Reason', help: `The fine of ${formatTzs(loan.fine)} is cancelled${loan.fineStatus === 'BILLED' ? ' (the unpaid invoice is cancelled)' : ''}.` },
    'fine-paid': { title: 'Fine paid at the cashier', label: 'Receipt number', help: `${loan.borrowerName} paid ${formatTzs(loan.fine)}.` },
  }[action];
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>{words.title}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 2 }}>{`${loan.title} (${loan.barcode}). ${words.help}`}</Typography>
        <TextField fullWidth label={words.label} value={note} onChange={(e) => setNote(e.target.value)} />
        <ErrorAlert error={go.error} />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color={action === 'lost' ? 'error' : 'primary'} disabled={go.isPending || (action !== 'lost' && !note.trim())} onClick={() => go.mutate()}>{words.title}</Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ the catalogue

function Catalogue({ canWrite }: { canWrite: boolean }) {
  const refresh = useRefresh();
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [open, setOpen] = useState<number | 'new' | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const list = useQuery({ queryKey: ['staff', 'library', 'items', search], queryFn: () => api<Item[]>(`/api/library/items?q=${encodeURIComponent(search)}&limit=100`, staff) });
  return (
    <Card><CardContent>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mb: 2 }}>
        <form onSubmit={(e) => { e.preventDefault(); setSearch(q); }} style={{ flexGrow: 1, display: 'flex', gap: 8 }}>
          <TextField size="small" fullWidth label="Title, author, ISBN, subject, call number or copy number" value={q} onChange={(e) => setQ(e.target.value)} />
          <Button type="submit" variant="outlined">Search</Button>
        </form>
        {canWrite && <Button startIcon={<AddIcon />} variant="contained" onClick={() => setOpen('new')}>Add a title</Button>}
        {canWrite && <Button startIcon={<UploadIcon />} onClick={() => setImportOpen(true)}>Import</Button>}
      </Stack>
      <ErrorAlert error={list.error} />
      {list.isLoading && <Loading />}
      {list.data?.length === 0 && <Typography color="text.secondary">Nothing found.</Typography>}
      {!!list.data?.length && (
        <TableContainer>
          <Table size="small" sx={{ minWidth: 640 }}>
            <TableHead><TableRow><TableCell>Title</TableCell><TableCell>Call number</TableCell><TableCell align="right">Copies</TableCell><TableCell align="right">Available</TableCell></TableRow></TableHead>
            <TableBody>
              {list.data.map((i) => (
                <TableRow key={i.id} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(i.id)}>
                  <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{i.title}</Typography><Typography variant="caption" color="text.secondary">{[i.authors, i.year, i.isbn].filter(Boolean).join(' · ')}</Typography></TableCell>
                  <TableCell>{i.callNumber ?? '—'}</TableCell>
                  <TableCell align="right">{i.copies}</TableCell>
                  <TableCell align="right">{i.available}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      {open !== null && <ItemDialog id={open === 'new' ? null : open} canWrite={canWrite} onClose={() => { setOpen(null); refresh(); }} />}
      {importOpen && <ImportDialog onClose={() => { setImportOpen(false); refresh(); }} />}
    </CardContent></Card>
  );
}

function ItemDialog({ id, canWrite, onClose }: { id: number | null; canWrite: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const [itemId, setItemId] = useState(id);
  const q = useQuery({ queryKey: ['staff', 'library', 'item', itemId], enabled: itemId !== null,
    queryFn: () => api<{ item: Item; copies: Copy[] }>(`/api/library/items/${itemId}`, staff) });
  const [form, setForm] = useState<Partial<Item> | null>(id === null ? { kind: 'BOOK' } : null);
  const [copy, setCopy] = useState({ barcode: '', price: '' });
  const f = form ?? q.data?.item ?? null;
  const save = useMutation({
    mutationFn: () => api<{ item: Item }>(itemId === null ? '/api/library/items' : `/api/library/items/${itemId}`, { ...staff, method: itemId === null ? 'POST' : 'PUT', body: f }),
    onSuccess: (d) => { setItemId(d.item.id); setForm(null); qc.invalidateQueries({ queryKey: ['staff', 'library'] }); },
  });
  const addCopy = useMutation({
    mutationFn: () => api(`/api/library/items/${itemId}/copies`, { ...staff, method: 'POST', body: { barcode: copy.barcode, price: copy.price === '' ? null : Number(copy.price) } }),
    onSuccess: () => { setCopy({ barcode: '', price: '' }); qc.invalidateQueries({ queryKey: ['staff', 'library'] }); },
  });
  const status = useMutation({
    mutationFn: ({ c, s }: { c: Copy; s: string }) => api(`/api/library/copies/${c.id}/status`, { ...staff, method: 'PUT', body: { status: s } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staff', 'library'] }),
  });
  const set = (k: keyof Item, v: string) => setForm({ ...(f ?? {}), [k]: k === 'year' ? (v === '' ? undefined : Number(v)) : v });
  const editing = canWrite && (form !== null);
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{itemId === null ? 'Add a title' : f?.title}</DialogTitle>
      <DialogContent>
        {itemId !== null && q.isLoading && <Loading />}
        {f && (editing ? (
          <Grid container spacing={2} sx={{ mt: 0.5 }}>
            <Grid size={12}><TextField fullWidth label="Title" value={f.title ?? ''} onChange={(e) => set('title', e.target.value)} required /></Grid>
            <Grid size={{ xs: 12, sm: 8 }}><TextField fullWidth label="Authors" value={f.authors ?? ''} onChange={(e) => set('authors', e.target.value)} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField select fullWidth label="Kind" value={f.kind ?? 'BOOK'} onChange={(e) => set('kind', e.target.value)}>
              {KINDS.map(([v, l]) => <MenuItem key={v} value={v}>{l}</MenuItem>)}</TextField></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="ISBN" value={f.isbn ?? ''} onChange={(e) => set('isbn', e.target.value)} /></Grid>
            <Grid size={{ xs: 12, sm: 5 }}><TextField fullWidth label="Publisher" value={f.publisher ?? ''} onChange={(e) => set('publisher', e.target.value)} /></Grid>
            <Grid size={{ xs: 6, sm: 3 }}><TextField fullWidth type="number" label="Year" value={f.year ?? ''} onChange={(e) => set('year', e.target.value)} /></Grid>
            <Grid size={{ xs: 6, sm: 4 }}><TextField fullWidth label="Edition" value={f.edition ?? ''} onChange={(e) => set('edition', e.target.value)} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="Call number" value={f.callNumber ?? ''} onChange={(e) => set('callNumber', e.target.value)} /></Grid>
            <Grid size={{ xs: 12, sm: 4 }}><TextField fullWidth label="Subject" value={f.subject ?? ''} onChange={(e) => set('subject', e.target.value)} /></Grid>
          </Grid>
        ) : (
          <Box>
            <Typography variant="body2" color="text.secondary">{[f.authors, f.publisher, f.year, f.edition, f.isbn && `ISBN ${f.isbn}`, f.callNumber, KINDS.find(([v]) => v === f.kind)?.[1]].filter(Boolean).join(' · ')}</Typography>
            {canWrite && <Button size="small" sx={{ mt: 1, px: 0 }} onClick={() => setForm(f)}>Edit the details</Button>}
          </Box>
        ))}
        <ErrorAlert error={save.error} />
        {itemId !== null && q.data && (
          <Box sx={{ mt: 2.5 }}>
            <Typography variant="subtitle1">Copies</Typography>
            <TableContainer>
              <Table size="small">
                <TableHead><TableRow><TableCell>Number</TableCell><TableCell>Status</TableCell><TableCell align="right">Price</TableCell><TableCell /></TableRow></TableHead>
                <TableBody>
                  {q.data.copies.map((c) => (
                    <TableRow key={c.id}>
                      <TableCell sx={{ fontFamily: 'monospace' }}>{c.barcode}</TableCell>
                      <TableCell>{COPY_STATUS[c.status] ?? c.status}{c.borrower ? ` · ${c.borrower}, due ${formatDate(c.dueOn)}` : ''}</TableCell>
                      <TableCell align="right">{c.price != null ? formatTzs(c.price) : '—'}</TableCell>
                      <TableCell align="right">
                        {canWrite && c.status === 'AVAILABLE' && <Button size="small" onClick={() => status.mutate({ c, s: 'WITHDRAWN' })}>Withdraw</Button>}
                        {canWrite && ['WITHDRAWN', 'IN_LIBRARY'].includes(c.status) && <Button size="small" onClick={() => status.mutate({ c, s: 'AVAILABLE' })}>Make available</Button>}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
            {canWrite && (
              <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
                <TextField size="small" label="New copy number" value={copy.barcode} onChange={(e) => setCopy({ ...copy, barcode: e.target.value })} />
                <TextField size="small" type="number" label="Price (TZS)" value={copy.price} onChange={(e) => setCopy({ ...copy, price: e.target.value })} sx={{ width: 140 }} />
                <Button variant="outlined" disabled={!copy.barcode.trim() || addCopy.isPending} onClick={() => addCopy.mutate()}>Add copy</Button>
              </Stack>
            )}
            <ErrorAlert error={addCopy.error ?? status.error} />
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        {editing && <Button variant="contained" disabled={save.isPending || !f?.title?.trim()} onClick={() => save.mutate()}>Save</Button>}
      </DialogActions>
    </Dialog>
  );
}

function ImportDialog({ onClose }: { onClose: () => void }) {
  const [file, setFile] = useState<File | null>(null);
  const go = useMutation({ mutationFn: () => { const f = new FormData(); f.append('file', file!); return uploadForm<{ titlesAdded: number; copiesAdded: number; skipped: number; problems: string[] }>('/api/library/import', 'staff', f, 'POST'); } });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Import the catalogue</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 2 }}>
          A CSV or Excel file with the columns Title, Authors, ISBN, Publisher, Year, Edition, Subject, Call number, Kind, Barcode and Price. Each row with a barcode
          is one copy; rows with the same ISBN (or the same title and authors) are copies of one title. Copies already in the catalogue are skipped.
        </Typography>
        <Button component="label" variant="outlined" startIcon={<UploadIcon />}>{file ? file.name : 'Choose the file'}
          <input hidden type="file" accept=".csv,.xlsx" onChange={(e) => setFile(e.target.files?.[0] ?? null)} /></Button>
        <ErrorAlert error={go.error} />
        {go.data && (
          <Alert severity={go.data.problems.length ? 'warning' : 'success'} sx={{ mt: 2 }}>
            {`${go.data.titlesAdded} titles and ${go.data.copiesAdded} copies added, ${go.data.skipped} rows skipped.`}
            {go.data.problems.slice(0, 12).map((p) => <div key={p}>{p}</div>)}
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={!file || go.isPending || !!go.data} onClick={() => go.mutate()}>Import</Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ loans and fines, rules

function Loans({ canWrite }: { canWrite: boolean }) {
  const [which, setWhich] = useState('OVERDUE');
  const [q, setQ] = useState('');
  const list = useQuery({ queryKey: ['staff', 'library', 'loans', which, q], queryFn: () => api<Loan[]>(`/api/library/loans?which=${which}&q=${encodeURIComponent(q)}`, staff) });
  return (
    <Card><CardContent>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
        <TextField select size="small" label="Show" value={which} onChange={(e) => setWhich(e.target.value)} sx={{ minWidth: 200 }}>
          <MenuItem value="OVERDUE">Overdue</MenuItem><MenuItem value="OPEN">On loan</MenuItem><MenuItem value="FINES">Fines not paid</MenuItem><MenuItem value="ALL">All (latest 500)</MenuItem>
        </TextField>
        <TextField size="small" fullWidth label="Borrower, copy number or title" value={q} onChange={(e) => setQ(e.target.value)} />
      </Stack>
      <ErrorAlert error={list.error} />
      {list.isLoading && <Loading />}
      {list.data?.length === 0 && <Typography color="text.secondary" sx={{ mt: 2 }}>Nothing to show.</Typography>}
      {!!list.data?.length && <LoanTable loans={list.data} canWrite={canWrite} />}
    </CardContent></Card>
  );
}

function SettingsCard({ canWrite }: { canWrite: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'library', 'settings'], queryFn: () => api<Settings>('/api/library/settings', staff) });
  const [s, setS] = useState<Settings | null>(null);
  const v = s ?? q.data;
  const save = useMutation({ mutationFn: () => api<Settings>('/api/library/settings', { ...staff, method: 'PUT', body: v }),
    onSuccess: (d) => { setS(null); qc.setQueryData(['staff', 'library', 'settings'], d); } });
  if (!v) return <Loading />;
  const field = (k: keyof Settings, label: string) => (
    <Grid size={{ xs: 12, sm: 6, md: 4 }}><TextField fullWidth type="number" label={label} value={v[k]} disabled={!canWrite}
      onChange={(e) => setS({ ...v, [k]: Number(e.target.value) })} /></Grid>
  );
  return (
    <Card><CardContent>
      <Grid container spacing={2}>
        {field('studentLoanDays', 'Days a student may keep an item')}
        {field('staffLoanDays', 'Days staff may keep an item')}
        {field('maxRenewals', 'Renewals allowed')}
        {field('studentMaxLoans', 'Items a student may have at once')}
        {field('staffMaxLoans', 'Items staff may have at once')}
        <Grid size={{ xs: 12, sm: 6, md: 4 }} />
        {field('finePerDay', 'Fine per day late (TZS)')}
        {field('maxFine', 'Most a late return can cost (TZS)')}
      </Grid>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
        A student's fine goes on their account with a control number, and the library clearance office cannot clear a finalist who still has items on loan.
        Borrowers get an SMS the day before an item is due and when it becomes overdue.
      </Typography>
      <ErrorAlert error={save.error} />
      {canWrite && <Button variant="contained" sx={{ mt: 2 }} disabled={!s || save.isPending} onClick={() => save.mutate()}>Save</Button>}
    </CardContent></Card>
  );
}
