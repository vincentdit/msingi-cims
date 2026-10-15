import { Alert, Box, Button, Card, CardContent, Chip, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, formatDateTime } from '../api/client';
import { ErrorAlert } from './common';

interface Nin { studentId: number; last4?: string; status?: string; checkedAt?: string; nidaEnabled: boolean; message?: string }

const COLOUR: Record<string, 'success' | 'warning' | 'error' | 'default'> = { VALID: 'success', UNVERIFIED: 'default', MISMATCH: 'warning', NOT_FOUND: 'error', INVALID: 'error' };

/**
 * The National Identification Number (FR-INT-06): shown only as its last four digits; checked with NIDA when the institution
 * uses it. Students enter their own (student portal); the Registrar can enter or re-check it (student record).
 */
export default function NinCard({ studentId, canEdit = true }: { studentId?: number; canEdit?: boolean }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const staffSide = studentId != null;
  const path = staffSide ? `/api/registry/students/${studentId}/nin` : '/api/student/nin';
  const realm = staffSide ? 'staff' as const : 'student' as const;
  const key = ['nin', studentId ?? 'me'];
  const q = useQuery({ queryKey: key, retry: false, queryFn: () => api<Nin>(path, { realm }) });
  const [value, setValue] = useState('');
  const [editing, setEditing] = useState(false);
  const save = useMutation({
    mutationFn: () => api<Nin>(path, { realm, method: 'PUT', body: { nin: value } }),
    onSuccess: (d) => { qc.setQueryData(key, d); setEditing(false); setValue(''); },
  });
  const verify = useMutation({ mutationFn: () => api<Nin>(`${path}/verify`, { realm, method: 'POST' }), onSuccess: (d) => qc.setQueryData(key, d) });
  if (!q.data) return null;
  const n = save.data ?? verify.data ?? q.data;
  return (
    <Card sx={{ borderRadius: staffSide ? undefined : 3, mb: staffSide ? 2 : 0 }}>
      <CardContent>
        <Typography variant={staffSide ? 'subtitle1' : 'h6'}>{t('nin.title')}</Typography>
        {n.last4 ? (
          <Stack direction="row" spacing={1} alignItems="center" sx={{ my: 1 }}>
            <Typography sx={{ fontFamily: 'monospace' }}>•••• •••• •••• •••• {n.last4}</Typography>
            <Chip size="small" color={COLOUR[n.status ?? 'UNVERIFIED']} label={t(`nin.status.${n.status ?? 'UNVERIFIED'}`)} />
          </Stack>
        ) : <Typography variant="body2" color="text.secondary" sx={{ my: 1 }}>{t('nin.none')}</Typography>}
        {n.checkedAt && <Typography variant="caption" color="text.secondary" display="block">{t('nin.checked', { when: formatDateTime(n.checkedAt) })}</Typography>}
        {(save.data?.message ?? verify.data?.message) && <Alert severity={n.status === 'VALID' ? 'success' : 'warning'} sx={{ my: 1 }}>{save.data?.message ?? verify.data?.message}</Alert>}
        {canEdit && (editing || !n.last4 ? (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1 }}>
            <TextField size="small" label={t('nin.label')} placeholder="19990101-12345-00001-12" value={value} onChange={(e) => setValue(e.target.value)}
              helperText={n.nidaEnabled ? t('nin.helpNida') : t('nin.help')} sx={{ flex: 1 }} inputProps={{ inputMode: 'numeric', autoComplete: 'off' }} />
            <Box><Button variant="contained" disabled={value.replace(/\D/g, '').length !== 20 || save.isPending} onClick={() => save.mutate()}>{t('nin.save')}</Button></Box>
          </Stack>
        ) : (
          <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
            <Button size="small" onClick={() => setEditing(true)}>{t('nin.change')}</Button>
            {staffSide && n.nidaEnabled && n.status !== 'VALID' && <Button size="small" onClick={() => verify.mutate()} disabled={verify.isPending}>{t('nin.verify')}</Button>}
          </Stack>
        ))}
        <ErrorAlert error={save.error ?? verify.error} />
      </CardContent>
    </Card>
  );
}
