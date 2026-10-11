import { Alert, Box, Card, CardContent, Chip, LinearProgress, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import type { MyAttendance } from '../../api/types';

const pct = (n?: number | null) => (n == null ? '—' : `${Number(n).toLocaleString('en-GB', { maximumFractionDigits: 1 })}%`);

/** The student's attendance in each module this semester and, once the lists are out, whether they may sit each examination. */
export default function StudentAttendanceCard() {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['student', 'attendance'], queryFn: () => api<MyAttendance>('/api/student/attendance', { realm: 'student' }) });
  const a = q.data;
  if (!a || !a.semesterId) return null;
  const threshold = pct(a.threshold);
  const recorded = a.modules.some((m) => m.counted > 0) || a.listPublished;
  return (
    <Card sx={{ borderRadius: 3 }}>
      <CardContent>
        <Typography variant="h6">{t('attendance.title')}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>{t('attendance.semester', { semester: a.semesterName, threshold })}</Typography>
        {!recorded && <Typography variant="body2" color="text.secondary">{t('attendance.none')}</Typography>}
        {recorded && (
          <Stack spacing={1.5}>
            {a.modules.map((m) => (
              <Box key={m.code}>
                <Stack direction="row" spacing={1} justifyContent="space-between" alignItems="baseline">
                  <Box sx={{ minWidth: 0 }}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{m.code}</Typography>
                    <Typography variant="caption" color="text.secondary" noWrap component="div">{m.title}</Typography>
                  </Box>
                  <Typography sx={{ fontWeight: 700, flexShrink: 0 }} color={m.below ? 'warning.main' : undefined}>{pct(m.percent)}</Typography>
                </Stack>
                <LinearProgress variant="determinate" value={Math.min(100, Number(m.percent ?? 0))} color={m.below ? 'warning' : 'success'}
                  sx={{ my: 0.5, height: 6, borderRadius: 3, opacity: m.percent == null ? 0.3 : 1 }} />
                <Stack direction="row" spacing={1} justifyContent="space-between" alignItems="center">
                  <Typography variant="caption" color="text.secondary">
                    {m.counted ? t('attendance.attended', { attended: m.attended, counted: m.counted }) : t('attendance.notYet')}
                    {m.excused > 0 && ` · ${t('attendance.excused', { n: m.excused })}`}
                  </Typography>
                  {m.eligible != null && (
                    <Chip size="small" color={m.eligible ? 'success' : 'error'} variant={m.eligible ? 'outlined' : 'filled'}
                      label={m.eligible ? (m.exempted ? t('attendance.exempted') : t('attendance.eligible')) : t('attendance.notEligible')} />
                  )}
                </Stack>
                {m.eligible === false && (
                  <Alert severity="error" sx={{ mt: 0.5, py: 0 }}>{m.reasons.join('; ')}. {t('attendance.contact')}</Alert>
                )}
                {m.eligible == null && m.below && <Typography variant="caption" color="warning.main">{t('attendance.below', { threshold })}</Typography>}
              </Box>
            ))}
          </Stack>
        )}
      </CardContent>
    </Card>
  );
}
