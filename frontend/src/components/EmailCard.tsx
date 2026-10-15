import MarkEmailReadIcon from '@mui/icons-material/MarkEmailRead';
import { Alert, Box, Button, Card, CardContent, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import { ErrorAlert } from './common';

interface EmailStatus { email?: string; verified: boolean; verifiedAt?: string; pending?: string; devCode?: string }

/**
 * The signed-in person's email: add or change it, and confirm it with the code sent to it (FR-ADM-02). Letters, notices and (for staff)
 * scheduled reports are emailed only to a confirmed address.
 */
export default function EmailCard({ realm }: { realm: 'applicant' | 'student' | 'staff' }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const key = ['account-email', realm];
  const q = useQuery({ queryKey: key, queryFn: () => api<EmailStatus>('/api/account/email', { realm }) });
  const [address, setAddress] = useState('');
  const [code, setCode] = useState('');
  const [editing, setEditing] = useState(false);
  const [dev, setDev] = useState<string | undefined>();
  const request = useMutation({
    mutationFn: () => api<EmailStatus>('/api/account/email', { realm, method: 'POST', body: { email: address.trim() } }),
    onSuccess: (s) => { qc.setQueryData(key, s); setDev(s.devCode); setCode(''); },
  });
  const verify = useMutation({
    mutationFn: () => api<EmailStatus>('/api/account/email/verify', { realm, method: 'POST', body: { code: code.trim() } }),
    onSuccess: (s) => { qc.setQueryData(key, s); setEditing(false); setDev(undefined); setAddress(''); },
  });
  const s = q.data;
  if (!s) return null;
  const valid = /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(address.trim());
  return (
    <Card sx={{ borderRadius: realm === 'staff' ? undefined : 3, textAlign: 'left' }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 0.5 }}>{t('email.title')}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>{t(realm === 'staff' ? 'email.introStaff' : 'email.intro')}</Typography>
        {s.email && s.verified && !editing && (
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <MarkEmailReadIcon color="success" fontSize="small" />
            <Typography sx={{ fontWeight: 600 }}>{s.email}</Typography>
            <Typography variant="body2" color="success.main">{t('email.verified')}</Typography>
            <Button size="small" onClick={() => setEditing(true)}>{t('email.change')}</Button>
          </Stack>
        )}
        {(!s.email || !s.verified || editing) && !s.pending && (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
            <TextField size="small" type="email" label={t('email.address')} value={address} onChange={(e) => setAddress(e.target.value)} sx={{ flex: 1 }}
              helperText={s.email && !s.verified ? t('email.notVerified', { email: s.email }) : undefined} />
            <Box><Button variant="contained" disabled={!valid || request.isPending} onClick={() => request.mutate()}>{t('email.send')}</Button></Box>
          </Stack>
        )}
        {s.pending && (
          <Stack spacing={1}>
            <Typography variant="body2">{t('email.sent', { email: s.pending })}</Typography>
            {dev && <Alert severity="warning" sx={{ py: 0 }}>{t('email.devCode', { code: dev })}</Alert>}
            <Stack direction="row" spacing={1}>
              <TextField size="small" label={t('email.code')} value={code} onChange={(e) => setCode(e.target.value)} inputProps={{ inputMode: 'numeric', maxLength: 6 }} />
              <Button variant="contained" disabled={code.trim().length !== 6 || verify.isPending} onClick={() => verify.mutate()}>{t('email.confirm')}</Button>
              <Button onClick={() => { setAddress(s.pending ?? ''); request.mutate(); }} disabled={request.isPending}>{t('email.resend')}</Button>
            </Stack>
          </Stack>
        )}
        <ErrorAlert error={request.error ?? verify.error} />
      </CardContent>
    </Card>
  );
}
