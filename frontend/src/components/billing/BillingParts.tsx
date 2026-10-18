import DownloadIcon from '@mui/icons-material/DownloadOutlined';
import {
  Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow,
  Tooltip, Typography,
} from '@mui/material';
import { Fragment } from 'react';
import { download, formatDateTime } from '../../api/client';
import { Loading } from '../common';
import { tr } from '../../locale/ui';
import { METHOD_LABEL, STATUS_COLOUR, STATUS_LABEL, day, providerLabel, tzs, type Aging, type InvoiceStatus, type PaymentRow, type Statement } from './billing';

/** Pieces of Msingi billing shown both in the platform console and on the institution's own page. */

export function InvoiceStatusChip({ status, overdue }: { status: InvoiceStatus; overdue?: number }) {
  if ((status === 'ISSUED' || status === 'PARTLY_PAID') && overdue && overdue > 0) {
    return <Chip size="small" color="error" label={`${overdue} days overdue`} />;
  }
  return <Chip size="small" color={STATUS_COLOUR[status]} variant={status === 'CANCELLED' || status === 'DRAFT' ? 'outlined' : 'filled'} label={STATUS_LABEL[status]} />;
}

export function AgingBar({ aging }: { aging: Aging }) {
  const parts = [
    { label: 'Not yet due', v: aging.current, c: '#3B82F6' }, { label: '1–30 days', v: aging.days1to30, c: '#F59E0B' },
    { label: '31–60 days', v: aging.days31to60, c: '#F97316' }, { label: '61–90 days', v: aging.days61to90, c: '#EF4444' },
    { label: 'Over 90 days', v: aging.over90, c: '#991B1B' },
  ];
  const total = parts.reduce((s, p) => s + p.v, 0);
  return (
    <Box>
      <Box sx={{ display: 'flex', height: 10, borderRadius: 5, overflow: 'hidden', bgcolor: 'action.hover', mb: 1 }} aria-hidden>
        {total > 0 && parts.map((p) => p.v > 0 && <Box key={p.label} sx={{ width: `${(100 * p.v) / total}%`, bgcolor: p.c }} />)}
      </Box>
      <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
        {parts.map((p) => (
          <Stack key={p.label} direction="row" spacing={0.75} alignItems="center">
            <Box sx={{ width: 10, height: 10, borderRadius: '50%', bgcolor: p.c }} />
            <Typography variant="caption" color="text.secondary">{p.label}</Typography>
            <Typography variant="caption" fontWeight={700}>{tzs(p.v)}</Typography>
          </Stack>
        ))}
      </Stack>
    </Box>
  );
}

export function PaymentTable({ rows, loading, realm, onReverse, showInstitution }: {
  rows?: PaymentRow[]; loading?: boolean; realm: 'platform' | 'staff'; onReverse?: (p: PaymentRow) => void; showInstitution?: boolean;
}) {
  const receipt = (p: PaymentRow) => download(realm === 'platform' ? `/api/platform/billing/payments/${p.id}/receipt` : `/api/msingi-billing/payments/${p.id}/receipt`,
    realm, `receipt-${p.receiptNo}.pdf`);
  if (loading) return <Loading />;
  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          <TableRow>
            <TableCell>Receipt</TableCell>{showInstitution && <TableCell>Institution</TableCell>}<TableCell>Paid</TableCell><TableCell>How</TableCell>
            <TableCell align="right">Amount</TableCell><TableCell>For</TableCell><TableCell />
          </TableRow>
        </TableHead>
        <TableBody>
          {rows?.map((p) => (
            <TableRow key={p.id} sx={p.status === 'REVERSED' ? { '& td': { color: 'text.disabled' } } : undefined}>
              <TableCell sx={{ fontFamily: 'monospace', whiteSpace: 'nowrap' }}>
                {p.receiptNo}
                {p.status === 'REVERSED' && <Tooltip title={p.reversedReason ?? ''}><Chip size="small" label="Reversed" sx={{ ml: 1, height: 18 }} /></Tooltip>}
              </TableCell>
              {showInstitution && <TableCell>{p.institutionName}</TableCell>}
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(p.paidAt)}</TableCell>
              <TableCell>
                {METHOD_LABEL[p.method]}{p.provider ? ` · ${providerLabel(p.provider)}` : ''}
                <Typography variant="caption" color="text.secondary" component="div">
                  {[p.reference, p.source === 'ONLINE' ? tr('reported by the collector') : realm === 'platform' ? p.recordedByName : null].filter(Boolean).join(' · ')}
                </Typography>
              </TableCell>
              <TableCell align="right">{tzs(p.amount)}</TableCell>
              <TableCell sx={{ whiteSpace: 'nowrap', fontFamily: 'monospace', fontSize: 13 }}>
                {p.invoices ? p.invoices.split(', ').map((n) => <div key={n}>{n}</div>) : '—'}
                {p.unallocated > 0 && p.status === 'RECEIVED' && <Typography variant="caption" color="success.main" component="div" sx={{ fontFamily: 'inherit' }}>{`TZS ${tzs(p.unallocated)} credit`}</Typography>}
              </TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                <Tooltip title={tr('Receipt (PDF)')}><IconButton size="small" onClick={() => receipt(p)} aria-label={tr('Receipt (PDF)')}><DownloadIcon fontSize="small" /></IconButton></Tooltip>
                {onReverse && p.status === 'RECEIVED' && <Button size="small" color="error" onClick={() => onReverse(p)}>Reverse</Button>}
              </TableCell>
            </TableRow>
          ))}
          {rows?.length === 0 && <TableRow><TableCell colSpan={7}><Typography color="text.secondary" sx={{ py: 2 }}>No payments yet.</Typography></TableCell></TableRow>}
        </TableBody>
      </Table>
    </TableContainer>
  );
}

export function StatementDialog({ st, realm, onClose }: { st: Statement; realm: 'platform' | 'staff'; onClose: () => void }) {
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{`Statement · ${st.institutionName}`}</DialogTitle>
      <DialogContent dividers>
        <StatementTable st={st} />
      </DialogContent>
      <DialogActions>
        <Button startIcon={<DownloadIcon />} onClick={() => download(realm === 'platform' ? `/api/platform/billing/statements/${st.tenantId}/pdf` : '/api/msingi-billing/statement/pdf',
          realm, `statement-${st.institutionCode}.pdf`)}>PDF</Button>
        <Button onClick={onClose}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}

export function StatementTable({ st }: { st: Statement }) {
  return (
    <>
      <TableContainer>
        <Table size="small">
          <TableHead>
            <TableRow><TableCell>Date</TableCell><TableCell>Reference</TableCell><TableCell>Description</TableCell><TableCell align="right">Charged</TableCell>
              <TableCell align="right">Paid</TableCell><TableCell align="right">Balance</TableCell></TableRow>
          </TableHead>
          <TableBody>
            {st.entries.map((e, i) => (
              <TableRow key={i}>
                <TableCell sx={{ whiteSpace: 'nowrap' }}>{day(e.date)}</TableCell>
                <TableCell sx={{ fontFamily: 'monospace', whiteSpace: 'nowrap' }}>{e.reference}</TableCell>
                <TableCell>{e.description}</TableCell>
                <TableCell align="right">{e.debit ? tzs(e.debit) : ''}</TableCell>
                <TableCell align="right">{e.credit ? tzs(e.credit) : ''}</TableCell>
                <TableCell align="right">{tzs(e.balance)}</TableCell>
              </TableRow>
            ))}
            {st.entries.length === 0 && <TableRow><TableCell colSpan={6}><Typography color="text.secondary">No invoices or payments yet.</Typography></TableCell></TableRow>}
          </TableBody>
        </Table>
      </TableContainer>
      <Stack direction="row" spacing={3} sx={{ mt: 2 }} flexWrap="wrap" useFlexGap>
        {[['Invoiced', st.invoiced], ['Paid', st.paid], ['Balance', st.balance]].map(([l, v]) => (
          <Fragment key={l as string}><Box><Typography variant="caption" color="text.secondary">{l}</Typography><Typography fontWeight={700}>{`TZS ${tzs(v as number)}`}</Typography></Box></Fragment>
        ))}
        {st.credit > 0 && <Box><Typography variant="caption" color="text.secondary">Credit</Typography><Typography fontWeight={700} color="success.main">{`TZS ${tzs(st.credit)}`}</Typography></Box>}
      </Stack>
      <Box sx={{ mt: 2 }}><AgingBar aging={st.aging} /></Box>
    </>
  );
}

