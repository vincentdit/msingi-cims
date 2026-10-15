import { Alert, Box, Button, Card, CardContent, Checkbox, FormControlLabel, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import { ErrorAlert } from './common';

interface Mine { phone?: string; email?: string; employmentStatus?: string; employer?: string; jobTitle?: string; sector?: string; region?: string; consentContact: boolean }
const STATUSES = ['EMPLOYED', 'SELF_EMPLOYED', 'FURTHER_STUDIES', 'SEEKING', 'NOT_SEEKING'];

/** A graduate keeps the institution up to date (BR-31). Shown only to graduates. */
export default function AlumniCard() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['student', 'alumni'], retry: false, queryFn: () => api<Mine>('/api/student/alumni', { realm: 'student' }) });
  const [f, setF] = useState<Mine | null>(null);
  useEffect(() => { if (q.data) setF(q.data); }, [q.data]);
  const save = useMutation({
    mutationFn: () => api<Mine>('/api/student/alumni', { realm: 'student', method: 'PUT', body: { ...f, employmentStatus: f?.employmentStatus || null } }),
    onSuccess: (d) => qc.setQueryData(['student', 'alumni'], d),
  });
  if (!f) return null;
  const tf = (k: keyof Mine, label: string) => <TextField size="small" fullWidth label={label} value={(f[k] as string) ?? ''} onChange={(e) => setF({ ...f, [k]: e.target.value })} />;
  return (
    <Card sx={{ borderRadius: 3 }}>
      <CardContent>
        <Typography variant="h6">{t('alumniCard.title')}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>{t('alumniCard.intro')}</Typography>
        <Stack spacing={1.5}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>{tf('phone', t('alumniCard.phone'))}{tf('email', t('alumniCard.email'))}</Stack>
          <TextField select size="small" label={t('tracer.q.employment')} value={f.employmentStatus ?? ''} onChange={(e) => setF({ ...f, employmentStatus: e.target.value })}>
            <MenuItem value="">—</MenuItem>{STATUSES.map((s) => <MenuItem key={s} value={s}>{t(`tracer.o.${s}`)}</MenuItem>)}
          </TextField>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>{tf('employer', t('tracer.q.employer'))}{tf('jobTitle', t('tracer.q.jobTitle'))}</Stack>
          <FormControlLabel control={<Checkbox checked={f.consentContact} onChange={(e) => setF({ ...f, consentContact: e.target.checked })} />} label={t('alumniCard.consent')} />
          {save.isSuccess && <Alert severity="success">{t('alumniCard.saved')}</Alert>}
          <ErrorAlert error={save.error} />
          <Box><Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending}>{t('alumniCard.save')}</Button></Box>
        </Stack>
      </CardContent>
    </Card>
  );
}
