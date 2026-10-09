import DownloadIcon from '@mui/icons-material/Download';
import LockIcon from '@mui/icons-material/LockOutlined';
import { Alert, Box, Button, Card, CardContent, Checkbox, Chip, Divider, Stack, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink } from 'react-router-dom';
import { api, download, formatDate, formatTzs } from '../../api/client';
import type { StudentResits, StudentSemesterResult } from '../../api/types';
import { ErrorAlert, Loading } from '../../components/common';

const DECISION_COLOUR = { PASS: 'success', SUPPLEMENTARY: 'warning', SPECIAL_EXAM: 'info', INCOMPLETE: 'default', REPEAT: 'error', DISCONTINUE: 'error', CARRY_OVER: 'warning' } as const;
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
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="h5" component="h1">{t('results.title')}</Typography>
        {list.some((r) => !r.withheld) && <StatementButton />}
      </Stack>
      {!list.length && <Alert severity="info">{t('results.none')}</Alert>}
      <ResitsCard />
      {list.map((r) => (
        <Card key={r.id} sx={{ borderRadius: 3 }}>
          <CardContent>
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
              <Box>
                <Typography variant="h6">{r.semesterName} · {r.academicYear}</Typography>
                {r.round === 'RESIT' && <Typography variant="body2" color="warning.main">{t('results.afterResits')}</Typography>}
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
                      {m.attempt && <Chip size="small" variant="outlined" label={t(m.attempt === 'SPECIAL' ? 'results.special' : 'results.supplementary')} />}
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

function StatementButton() {
  const { t } = useTranslation();
  const m = useMutation({ mutationFn: () => download('/api/student/transcript', 'student', 'statement-of-results.pdf') });
  return (
    <Box sx={{ textAlign: 'right' }}>
      <Button size="small" startIcon={<DownloadIcon />} onClick={() => m.mutate()} disabled={m.isPending}>{t('results.statement')}</Button>
      {m.error && <Typography variant="caption" color="error" display="block">{(m.error as Error).message}</Typography>}
    </Box>
  );
}

/** Registration for supplementary and special examinations (BP-10): eligible modules, fee and control number. */
function ResitsCard() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['student', 'resits'], queryFn: () => api<StudentResits>('/api/student/resits', { realm: 'student' }) });
  const [picked, setPicked] = useState<number[]>([]);
  const register = useMutation({
    mutationFn: () => api<StudentResits>('/api/student/resits', { realm: 'student', method: 'POST', body: { sessionId: q.data!.sessionId, moduleResultIds: picked } }),
    onSuccess: (r) => { qc.setQueryData(['student', 'resits'], r); setPicked([]); qc.invalidateQueries({ queryKey: ['student', 'dashboard'] }); },
  });
  const r = q.data;
  if (!r || !r.modules.length || r.modules.every((m) => m.resitId && m.grade)) return null; // resit results are out
  const open = r.modules.filter((m) => !m.resitId);
  const fee = open.filter((m) => picked.includes(m.moduleResultId)).reduce((s, m) => s + Number(m.fee), 0);
  return (
    <Card sx={{ borderRadius: 3, borderTop: 4, borderColor: 'warning.main' }}>
      <CardContent>
        <Typography variant="h6">{t('results.resitsTitle')}</Typography>
        <Typography variant="body2" color="text.secondary">
          {r.semesterName} · {r.academicYear}{r.examPeriod && ` · ${r.examPeriod}`} · {r.registrationOpen ? t('results.registerBy', { date: formatDate(r.registrationCloses) })
            : t('results.registrationClosed')}
        </Typography>
        <Stack divider={<Divider flexItem />} sx={{ mt: 1 }}>
          {r.modules.map((m) => (
            <Stack key={m.moduleResultId} direction="row" alignItems="center" spacing={1} sx={{ py: 0.75 }}>
              {!m.resitId && r.registrationOpen
                ? <Checkbox checked={picked.includes(m.moduleResultId)} inputProps={{ 'aria-label': m.code }}
                  onChange={(e) => setPicked(e.target.checked ? [...picked, m.moduleResultId] : picked.filter((x) => x !== m.moduleResultId))} />
                : <Box sx={{ width: 42 }} />}
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>{m.code}</Typography>
                <Typography variant="body2" color="text.secondary">{m.title}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {t(m.kind === 'SPECIAL' ? 'results.special' : 'results.supplementary')} · {Number(m.fee) > 0 ? formatTzs(m.fee) : t('results.free')}
                </Typography>
              </Box>
              {m.resitId && <Chip size="small" color={m.paid ? 'success' : 'warning'} label={m.paid ? t('results.registered') : t('results.awaitingPayment')} />}
            </Stack>
          ))}
        </Stack>
        {r.controlNo && Number(r.amountDue) > 0 && (
          <Alert severity="info" sx={{ mt: 1 }}>{t('results.payResits', { amount: formatTzs(r.amountDue) })} <strong style={{ fontFamily: 'monospace' }}>{r.controlNo}</strong></Alert>
        )}
        {r.registrationOpen && open.length > 0 && (
          <>
            {register.error && <Alert severity="error" sx={{ mt: 1 }}>{(register.error as Error).message}</Alert>}
            <Button fullWidth variant="contained" sx={{ mt: 1.5 }} disabled={!picked.length || register.isPending}
              onClick={() => register.mutate()}>{picked.length ? t('results.registerFor', { n: picked.length, fee: fee > 0 ? formatTzs(fee) : t('results.free') }) : t('results.chooseResits')}</Button>
          </>
        )}
      </CardContent>
    </Card>
  );
}
