import { Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, formatDate, formatDateTime } from '../api/client';
import { ErrorAlert } from './common';

export interface HealthInvoice { id: number; invoiceNo: string; amount: number; balance: number; status: string; controlNo?: string }
export interface HealthCover {
  id: number; studentId: number; registrationNo: string; studentName: string; programmeCode: string; yearOfStudy: number; academicYear: string;
  route?: 'OWN_CARD' | 'INSTITUTION'; status: string; cardNo?: string; holderName?: string; scheme?: string; validTo?: string; reason?: string;
  checkedAt?: string; batchRef?: string; invoice?: HealthInvoice;
}
interface StudentCover {
  studentId: number; enabled: boolean; required: boolean; contribution: number; academicYearId: number; academicYear: string;
  cover?: HealthCover; covered: boolean; message?: string;
}

export const HEALTH_COLOUR: Record<string, 'success' | 'warning' | 'error' | 'info' | 'default'> = {
  ACTIVE: 'success', EXPIRED: 'error', INVALID: 'error', REJECTED: 'error', AWAITING_PAYMENT: 'warning', READY: 'info', SUBMITTED: 'info', NONE: 'default',
};

const tzs = (n: number) => Number(n).toLocaleString('en-GB', { maximumFractionDigits: 0 });

/**
 * A student's health insurance with NHIF for the current academic year: their own NHIF card (checked with NHIF), or registration
 * through the institution after paying NHIF's student contribution. Students use it in the portal; the Registrar on the student
 * record (others on the record see it read-only).
 */
export default function HealthCoverCard({ studentId, canEdit = true }: { studentId?: number; canEdit?: boolean }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const staffSide = studentId != null;
  const path = staffSide ? `/api/registry/students/${studentId}/health-cover` : '/api/student/health-cover';
  const realm = staffSide ? 'staff' as const : 'student' as const;
  const key = ['health-cover', studentId ?? 'me'];
  const q = useQuery({ queryKey: key, retry: false, queryFn: () => api<StudentCover>(path, { realm }) });
  const [card, setCard] = useState('');
  const [editing, setEditing] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [message, setMessage] = useState<string | undefined>();
  const done = (d: StudentCover) => { qc.setQueryData(key, d); setEditing(false); setCard(''); setConfirm(false); setMessage(d.message); };
  const check = useMutation({ mutationFn: () => api<StudentCover>(`${path}/card`, { realm, method: 'PUT', body: { cardNo: card } }), onSuccess: done });
  // the bill shown on the card says what to do next, so the server's confirmation is not repeated
  const institution = useMutation({ mutationFn: () => api<StudentCover>(`${path}/institution`, { realm, method: 'POST' }), onSuccess: (d) => { done(d); setMessage(undefined); } });
  if (!q.data) return null;
  const d = q.data;
  const c = d.cover;
  const title = <Typography variant={staffSide ? 'subtitle1' : 'h6'}>{t('health.title')}</Typography>;
  if (!d.enabled) {
    if (!staffSide) return null;
    return <Card sx={{ mb: 2 }}><CardContent>{title}<Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>{t('health.off')}</Typography></CardContent></Card>;
  }
  const status = c?.status ?? 'NONE';
  const canGiveCard = canEdit && (!c || ['INVALID', 'EXPIRED', 'AWAITING_PAYMENT', 'REJECTED', 'NONE'].includes(status) || (c.route === 'OWN_CARD' && editing));
  const canChooseInstitution = canEdit && (!c || (c.route === 'OWN_CARD' && status !== 'ACTIVE'));
  const unpaid = c?.invoice && c.invoice.balance > 0 && c.invoice.status !== 'CANCELLED' && status === 'AWAITING_PAYMENT';
  return (
    <Card sx={{ borderRadius: staffSide ? undefined : 3, mb: staffSide ? 2 : 0 }}>
      <CardContent>
        <Stack direction="row" spacing={1} alignItems="center" justifyContent="space-between" flexWrap="wrap" useFlexGap>
          {title}
          <Chip size="small" color={HEALTH_COLOUR[status] ?? 'default'} label={t(`health.status.${status}`)} />
        </Stack>
        {!c && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>{t('health.intro', { year: d.academicYear, amount: tzs(d.contribution) })}</Typography>}
        {d.required && !d.covered && <Alert severity="warning" sx={{ mt: 1 }}>{t('health.required')}</Alert>}
        {c && (
          <Box sx={{ mt: 1 }}>
            <Typography variant="body2" color="text.secondary">{c.route && t(`health.route.${c.route}`)} · {c.academicYear}</Typography>
            {c.cardNo && <Typography sx={{ fontFamily: 'monospace', mt: 0.5 }}>{t('health.card', { card: c.cardNo })}</Typography>}
            {c.holderName && <Typography variant="body2">{t('health.holder', { name: c.holderName })}</Typography>}
            {c.scheme && <Typography variant="body2">{t('health.scheme', { scheme: c.scheme })}</Typography>}
            {c.validTo && <Typography variant="body2">{t('health.validTo', { date: formatDate(c.validTo) })}</Typography>}
            {c.checkedAt && <Typography variant="caption" color="text.secondary" display="block">{t('health.checked', { when: formatDateTime(c.checkedAt) })}</Typography>}
            {c.reason && !message && <Alert severity="warning" sx={{ mt: 1 }}>{c.reason}</Alert>}
            {unpaid && (
              <Alert severity="info" sx={{ mt: 1 }}>
                {c.invoice!.controlNo ? t('health.pay', { amount: tzs(c.invoice!.balance), controlNo: c.invoice!.controlNo })
                  : t('health.payNoNumber', { amount: tzs(c.invoice!.balance), invoice: c.invoice!.invoiceNo })}
              </Alert>
            )}
          </Box>
        )}
        {message && <Alert severity={d.covered ? 'success' : 'warning'} sx={{ mt: 1 }}>{message}</Alert>}
        {canGiveCard && (editing || !c || status !== 'ACTIVE') ? (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1.5 }}>
            <TextField size="small" label={t('health.cardLabel')} value={card} onChange={(e) => setCard(e.target.value)} helperText={t('health.cardHelp')}
              sx={{ flex: 1 }} inputProps={{ autoComplete: 'off', maxLength: 40 }} />
            <Box><Button variant="contained" disabled={card.replace(/[^A-Za-z0-9]/g, '').length < 6 || check.isPending} onClick={() => check.mutate()}>{t('health.check')}</Button></Box>
          </Stack>
        ) : canEdit && c?.route === 'OWN_CARD' && (
          <Button size="small" sx={{ mt: 1 }} onClick={() => setEditing(true)}>{t('health.change')}</Button>
        )}
        <Stack direction="row" spacing={1} sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>
          {canChooseInstitution && <Button size="small" variant="outlined" onClick={() => setConfirm(true)}>{t('health.noCard')}</Button>}
          {canEdit && c?.route === 'INSTITUTION' && status === 'REJECTED' && (
            <Button size="small" variant="outlined" disabled={institution.isPending} onClick={() => institution.mutate()}>{t('health.retry')}</Button>
          )}
        </Stack>
        <ErrorAlert error={check.error ?? institution.error} />
      </CardContent>
      <Dialog open={confirm} onClose={() => setConfirm(false)} maxWidth="xs" fullWidth>
        <DialogContent>
          <Typography>{d.contribution > 0 ? t('health.noCardConfirm', { amount: tzs(d.contribution) }) : t('health.noCardFree')}</Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(false)}>{t('health.cancel')}</Button>
          <Button variant="contained" disabled={institution.isPending} onClick={() => institution.mutate()}>{t('health.yes')}</Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}
