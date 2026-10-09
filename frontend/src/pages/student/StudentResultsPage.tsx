import LockIcon from '@mui/icons-material/LockOutlined';
import { Alert, Box, Button, Card, CardContent, Chip, Divider, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink } from 'react-router-dom';
import { api, formatDate } from '../../api/client';
import type { StudentSemesterResult } from '../../api/types';
import { ErrorAlert, Loading } from '../../components/common';

const DECISION_COLOUR = { PASS: 'success', SUPPLEMENTARY: 'warning', SPECIAL_EXAM: 'info', INCOMPLETE: 'default', REPEAT: 'error', DISCONTINUE: 'error' } as const;
const fixed = (n?: number) => (n == null ? '—' : Number(n).toFixed(1));

/** Published semester results in the student portal (FR-EXM-10), withheld while results clearance is outstanding. */
export default function StudentResultsPage() {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['student', 'results'], queryFn: () => api<{ semesters: StudentSemesterResult[] }>('/api/student/results', { realm: 'student' }) });
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  const list = q.data.semesters;
  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">{t('results.title')}</Typography>
      {!list.length && <Alert severity="info">{t('results.none')}</Alert>}
      {list.map((r) => (
        <Card key={r.id} sx={{ borderRadius: 3 }}>
          <CardContent>
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
              <Box>
                <Typography variant="h6">{r.semesterName} · {r.academicYear}</Typography>
                <Typography variant="caption" color="text.secondary">{t('results.yearPublished', { year: r.yearOfStudy, date: formatDate(r.publishedAt) })}</Typography>
              </Box>
              {r.decision && <Chip size="small" color={DECISION_COLOUR[r.decision]} label={t(`results.decision${r.decision}`)} />}
            </Stack>
            {r.withheld ? (
              <Alert severity="warning" icon={<LockIcon />} sx={{ mt: 1.5 }}
                action={<Button component={RouterLink} to="/student/fees" color="inherit" size="small">{t('student.navFees')}</Button>}>
                <strong>{t('results.withheld')}</strong> {r.withheldReason}
              </Alert>
            ) : (
              <>
                <Stack direction="row" spacing={3} sx={{ my: 1.5 }}>
                  <Box><Typography variant="caption" color="text.secondary">{t('results.gpa')}</Typography>
                    <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 28, lineHeight: 1.1 }}>{fixed(r.gpa)}</Typography></Box>
                  <Box><Typography variant="caption" color="text.secondary">{t('results.cgpa')}</Typography>
                    <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 28, lineHeight: 1.1 }}>{fixed(r.cgpa)}</Typography></Box>
                  <Box><Typography variant="caption" color="text.secondary">{t('results.credits')}</Typography>
                    <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 28, lineHeight: 1.1 }}>{r.creditsPassed}/{r.creditsRegistered}</Typography></Box>
                </Stack>
                <Stack divider={<Divider flexItem />}>
                  {r.modules.map((m) => (
                    <Stack key={m.code} direction="row" alignItems="center" spacing={1} sx={{ py: 0.75 }}>
                      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>{m.code}</Typography>
                        <Typography variant="body2" color="text.secondary">{m.title}</Typography>
                      </Box>
                      {m.remark !== 'PASS' && <Chip size="small" variant="outlined" color={m.remark === 'FAIL' ? 'error' : 'warning'} label={t(`results.remark${m.remark}`)} />}
                      <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 20, width: 36, textAlign: 'right', color: m.remark === 'FAIL' ? 'error.main' : 'text.primary' }}>
                        {m.grade ?? '—'}
                      </Typography>
                    </Stack>
                  ))}
                </Stack>
              </>
            )}
          </CardContent>
        </Card>
      ))}
      <Typography variant="caption" color="text.secondary">{t('results.provisional')}</Typography>
    </Stack>
  );
}
