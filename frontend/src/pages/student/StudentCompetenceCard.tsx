import { Box, Card, CardContent, Chip, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api, formatDate } from '../../api/client';

interface MyUnit { code: string; title: string; semesterName: string; outcome: 'COMPETENT' | 'NOT_YET_COMPETENT' | 'INCOMPLETE';
  tasks: { name: string; kind: string; outcome: string; attempt: number; assessedOn?: string }[] }

/** An NVA trainee's verified competence outcomes, unit by unit (FR-STU-03, BP-09). */
export default function StudentCompetenceCard() {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['student', 'competence'], queryFn: () => api<MyUnit[]>('/api/student/competence', { realm: 'student' }) });
  if (!q.data?.length) return null;
  const colour = (o: string) => (o === 'COMPETENT' ? 'success' : o === 'NOT_YET_COMPETENT' ? 'error' : 'default') as 'success' | 'error' | 'default';
  return (
    <Card sx={{ borderRadius: 3 }}>
      <CardContent>
        <Typography variant="h6">{t('competence.title')}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>{t('competence.intro')}</Typography>
        <Stack spacing={1.5}>
          {q.data.map((u) => (
            <Box key={u.code + u.semesterName} sx={{ borderTop: 1, borderColor: 'divider', pt: 1.25 }}>
              <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={1}>
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{u.code}</Typography>
                  <Typography variant="caption" color="text.secondary" noWrap component="div">{u.title} · {u.semesterName}</Typography>
                </Box>
                <Chip size="small" color={colour(u.outcome)} label={t(`competence.${u.outcome}`)} />
              </Stack>
              {u.tasks.map((x) => (
                <Typography key={x.name} variant="caption" display="block" color={x.outcome === 'NOT_YET_COMPETENT' ? 'error.main' : 'text.secondary'}>
                  {x.name}: {t(`competence.${x.outcome}`)}{x.attempt > 1 ? ` · ${t('competence.attempt', { n: x.attempt })}` : ''}{x.assessedOn ? ` · ${formatDate(x.assessedOn)}` : ''}
                </Typography>
              ))}
            </Box>
          ))}
        </Stack>
      </CardContent>
    </Card>
  );
}
