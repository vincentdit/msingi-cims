import DownloadIcon from '@mui/icons-material/Download';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, download, formatDate, type Realm } from '../api/client';
import { ErrorAlert } from './common';

export interface PrivacyRequest {
  id: number; requestNo: string; userId: number; subjectName: string; userType: string; kind: 'ACCESS' | 'CORRECTION' | 'ERASURE' | 'OBJECTION';
  details: string; status: 'OPEN' | 'COMPLETED' | 'REJECTED'; dueOn: string; overdue: boolean; response?: string; handledByName?: string;
  handledAt?: string; createdAt: string; erasable: boolean;
}

const KINDS = ['ACCESS', 'CORRECTION', 'ERASURE', 'OBJECTION'] as const;

/** The signed-in person's rights over their personal data (Personal Data Protection Act, 2022): download it, or ask the college. */
export default function PrivacyCard({ realm }: { realm: Exclude<Realm, 'platform'> }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const key = ['privacy-requests', realm];
  const mine = useQuery({ queryKey: key, queryFn: () => api<PrivacyRequest[]>('/api/account/privacy/requests', { realm }) });
  const [open, setOpen] = useState(false);
  const [kind, setKind] = useState<(typeof KINDS)[number]>('CORRECTION');
  const [details, setDetails] = useState('');
  const send = useMutation({
    mutationFn: () => api<PrivacyRequest>('/api/account/privacy/requests', { realm, method: 'POST', body: { kind, details: details.trim() } }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: key }); setOpen(false); setDetails(''); },
  });
  const dl = useMutation({ mutationFn: () => download('/api/account/privacy/export', realm, 'my-data.json') });
  return (
    <Card sx={{ borderRadius: realm === 'staff' ? undefined : 3, textAlign: 'left' }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 0.5 }}>{t('privacy.title')}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>{t('privacy.intro')}</Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
          <Button variant="outlined" startIcon={<DownloadIcon />} onClick={() => dl.mutate()} disabled={dl.isPending}>{t('privacy.download')}</Button>
          <Button onClick={() => setOpen(true)}>{t('privacy.ask')}</Button>
        </Stack>
        <ErrorAlert error={dl.error} />
        {(mine.data ?? []).length > 0 && (
          <Stack spacing={1} sx={{ mt: 2 }}>
            {mine.data!.map((r) => (
              <Box key={r.id} sx={{ p: 1.25, border: 1, borderColor: 'divider', borderRadius: 2 }}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Typography sx={{ fontWeight: 600 }}>{t(`privacy.kinds.${r.kind}`)}</Typography>
                  <Chip size="small" label={t(`privacy.status.${r.status}`)} color={r.status === 'COMPLETED' ? 'success' : r.status === 'REJECTED' ? 'default' : 'warning'} />
                  <Typography variant="caption" color="text.secondary">{r.requestNo}{r.status === 'OPEN' ? ` · ${t('privacy.due', { date: formatDate(r.dueOn) })}` : ''}</Typography>
                </Stack>
                {r.response && <Typography variant="body2" sx={{ mt: 0.5 }}>{r.response}</Typography>}
              </Box>
            ))}
          </Stack>
        )}
      </CardContent>
      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>{t('privacy.ask')}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField select label={t('privacy.kind')} value={kind} onChange={(e) => setKind(e.target.value as typeof kind)}>
              {KINDS.map((k) => <MenuItem key={k} value={k}>{t(`privacy.kinds.${k}`)}</MenuItem>)}
            </TextField>
            {kind === 'ERASURE' && <Alert severity="info">{t('privacy.erasureNote')}</Alert>}
            <TextField label={t('privacy.details')} value={details} onChange={(e) => setDetails(e.target.value)} multiline minRows={3} inputProps={{ maxLength: 2000 }} />
            <ErrorAlert error={send.error} />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>{t('privacy.close')}</Button>
          <Button variant="contained" disabled={!details.trim() || send.isPending} onClick={() => send.mutate()}>{t('privacy.send')}</Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}
