import { Alert, Box, Button, Card, CardContent, Chip, LinearProgress, Stack, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api, formatDate } from '../../api/client';
import type { MyClearance } from '../../api/types';
import { ErrorAlert } from '../../components/common';

const COLOUR = { PENDING: 'default', CLEARED: 'success', HELD: 'error' } as const;

/** Finalists' clearance across the offices and their graduation (UC-18); hidden for students who are not finalists yet. */
export default function StudentClearanceCard() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['student', 'clearance'], queryFn: () => api<MyClearance>('/api/student/clearance', { realm: 'student' }) });
  const start = useMutation({
    mutationFn: () => api<MyClearance>('/api/student/clearance', { realm: 'student', method: 'POST' }),
    onSuccess: (d) => qc.setQueryData(['student', 'clearance'], d),
  });
  const d = q.data;
  if (!d || (!d.award.finalist && !d.clearance && !d.graduation)) return null;
  const c = d.clearance;
  return (
    <Card sx={{ borderRadius: 3 }}>
      <CardContent>
        <Typography variant="h6">{t('clearance.title')}</Typography>
        <Box sx={{ mt: 1, p: 1.5, borderRadius: 2, bgcolor: 'action.hover' }}>
          <Typography variant="caption" color="text.secondary">{t('clearance.award')}</Typography>
          <Typography sx={{ fontWeight: 700 }}>{d.graduation?.awardName ?? d.award.awardName}</Typography>
          {(d.graduation?.classification ?? d.award.classification) && (
            <Typography variant="body2">{d.graduation?.classification ?? d.award.classification}{d.award.cgpa != null && ` · GPA ${d.award.cgpa}`}</Typography>
          )}
        </Box>
        {d.graduation && (
          <Alert severity={d.graduation.status === 'APPROVED' ? 'success' : 'info'} sx={{ mt: 1.5 }}>
            <strong>{d.graduation.name}</strong> · {t(`clearance.grad${d.graduation.status}`)}
            {d.graduation.ceremonyDate && <> · {t('clearance.ceremony', { date: formatDate(d.graduation.ceremonyDate) })}</>}
          </Alert>
        )}
        {!c && !d.award.qualified && <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>{t('clearance.notQualified', { reason: d.award.reason })}</Typography>}
        {!c && d.award.qualified && (
          <Stack spacing={1} sx={{ mt: 1.5 }}>
            <Typography variant="body2" color="text.secondary">{t('clearance.startHint')}</Typography>
            <Button variant="contained" onClick={() => start.mutate()} disabled={start.isPending}>{t('clearance.start')}</Button>
            <ErrorAlert error={start.error} />
          </Stack>
        )}
        {c && !d.graduation && (
          <>
            <Typography variant="body2" sx={{ mt: 1.5 }}>{c.status === 'CLEARED' ? t('clearance.complete') : t('clearance.cleared', { n: c.cleared, total: c.total })}</Typography>
            <LinearProgress variant="determinate" value={(c.cleared / Math.max(1, c.total)) * 100} color={c.status === 'CLEARED' ? 'success' : 'primary'}
              sx={{ my: 1, height: 8, borderRadius: 4 }} />
            {c.items.map((i) => (
              <Box key={i.id} sx={{ py: 1, borderBottom: 1, borderColor: 'divider' }}>
                <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{i.officeName}</Typography>
                  <Chip size="small" color={COLOUR[i.status]} variant={i.status === 'PENDING' ? 'outlined' : 'filled'} label={t(`clearance.status${i.status}`)} />
                </Stack>
                {i.status === 'HELD' && i.note && <Typography variant="body2" color="error.main">{i.note}</Typography>}
                {i.status === 'PENDING' && i.instructions && <Typography variant="caption" color="text.secondary">{i.instructions}</Typography>}
              </Box>
            ))}
          </>
        )}
      </CardContent>
    </Card>
  );
}
