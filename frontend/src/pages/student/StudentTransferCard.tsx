import { Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, formatDateTime } from '../../api/client';
import type { Transfer, TransferOptions } from '../../api/types';
import { ErrorAlert } from '../../components/common';

const student = { realm: 'student' as const };
const COLOUR: Record<Transfer['state'], 'default' | 'warning' | 'info' | 'success' | 'error'> = {
  REQUESTED: 'warning', RECOMMENDED: 'info', APPROVED: 'success', REJECTED: 'error', CANCELLED: 'default',
};

/** The student's campus and transfers: ask to move to another programme or campus, and follow the request (FR-REG-05). */
export default function StudentTransferCard() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const options = useQuery({ queryKey: ['student', 'transfer-options'], queryFn: () => api<TransferOptions>('/api/student/transfer-options', student) });
  const list = useQuery({ queryKey: ['student', 'transfers'], queryFn: () => api<Transfer[]>('/api/student/transfers', student) });
  const [open, setOpen] = useState(false);
  const refresh = () => qc.invalidateQueries({ queryKey: ['student'] });
  const cancel = useMutation({ mutationFn: (id: number) => api(`/api/student/transfers/${id}/cancel`, { ...student, method: 'POST' }), onSuccess: refresh });
  const o = options.data;
  if (!o) return null;
  return (
    <Card sx={{ borderRadius: 3 }}>
      <CardContent>
        <Stack direction="row" alignItems="center" spacing={1} sx={{ mb: 1 }}>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>{t('transfer.title')}</Typography>
          {['ADMITTED', 'ACTIVE', 'POSTPONED'].includes(o.status) && <Button variant="outlined" size="small" disabled={o.openTransfer} onClick={() => setOpen(true)}>{t('transfer.ask')}</Button>}
        </Stack>
        {o.campus && <Typography variant="body2">{t('transfer.campus')}: <strong>{o.campus}</strong></Typography>}
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>{t('transfer.intro')}</Typography>
        <Stack spacing={1.5}>
          {list.data?.map((x) => (
            <Box key={x.id} sx={{ border: 1, borderColor: 'divider', borderRadius: 2, p: 1.5 }}>
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
                <Chip size="small" color={COLOUR[x.state]} label={t(`transfer.state.${x.state}`)} />
                <Typography variant="caption" color="text.secondary">{formatDateTime(x.requestedAt)}</Typography>
              </Stack>
              {x.fromProgramme !== x.toProgramme && <Typography variant="body2">{x.fromProgramme} → <strong>{x.toProgramme}</strong></Typography>}
              {x.fromCampus !== x.toCampus && <Typography variant="body2">{x.fromCampus} → <strong>{x.toCampus}</strong></Typography>}
              {x.decisionNote && <Typography variant="body2" color="text.secondary">“{x.decisionNote}” — {x.decidedByName}</Typography>}
              {x.state === 'REQUESTED' && x.requestedByStudent && (
                <Button size="small" color="inherit" sx={{ px: 0, mt: 0.5 }} disabled={cancel.isPending} onClick={() => cancel.mutate(x.id)}>{t('student.cancelRequest')}</Button>
              )}
            </Box>
          ))}
        </Stack>
        <ErrorAlert error={cancel.error} />
      </CardContent>
      {open && <AskDialog o={o} onClose={() => setOpen(false)} onDone={() => { setOpen(false); refresh(); }} />}
    </Card>
  );
}

function AskDialog({ o, onClose, onDone }: { o: TransferOptions; onClose: () => void; onDone: () => void }) {
  const { t } = useTranslation();
  const [programmeId, setProgrammeId] = useState(String(o.programmeId));
  const [campusId, setCampusId] = useState(o.campusId ? String(o.campusId) : '');
  const [reason, setReason] = useState('');
  const p = o.programmes.find((x) => String(x.id) === programmeId);
  const campuses = o.campuses.filter((c) => !p || p.campusIds.length === 0 || p.campusIds.includes(c.id));
  const submit = useMutation({
    mutationFn: () => api('/api/student/transfers', { ...student, method: 'POST', body: { toProgrammeId: Number(programmeId), toCampusId: campusId ? Number(campusId) : null, reason } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{t('transfer.ask')}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField select label={t('transfer.programme')} value={programmeId} onChange={(e) => setProgrammeId(e.target.value)}>
            {o.programmes.map((x) => <MenuItem key={x.id} value={String(x.id)}>{x.name}</MenuItem>)}
          </TextField>
          {o.campuses.length > 1 && (
            <TextField select label={t('transfer.campus')} value={campuses.some((c) => String(c.id) === campusId) ? campusId : ''} onChange={(e) => setCampusId(e.target.value)}
              slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
              <MenuItem value="">{t('transfer.usualCampus')}</MenuItem>
              {campuses.map((c) => <MenuItem key={c.id} value={String(c.id)}>{c.name}</MenuItem>)}
            </TextField>
          )}
          <TextField label={t('transfer.reason')} required multiline minRows={3} value={reason} onChange={(e) => setReason(e.target.value)} inputProps={{ maxLength: 1000 }} />
          <Alert severity="info">{t('transfer.howDecided')}</Alert>
          <ErrorAlert error={submit.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('transfer.close')}</Button>
        <Button variant="contained" disabled={!reason.trim() || submit.isPending} onClick={() => submit.mutate()}>{t('transfer.send')}</Button>
      </DialogActions>
    </Dialog>
  );
}
