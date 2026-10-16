import { Alert, Box, Card, CardContent, Chip, Divider, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, formatDate, formatDateTime } from '../../api/client';
import type { StudentTimetable } from '../../api/types';
import { ErrorAlert, Loading } from '../../components/common';

const hm = (t: string) => t.slice(0, 5);

/** The student's own timetable (FR-TCH-05, FR-EXM-15): classes by day and examination papers, once each is published. */
export default function StudentTimetablePage() {
  const { t } = useTranslation();
  const [semesterId] = useState<number>();
  const q = useQuery({ queryKey: ['student', 'timetable', semesterId ?? 0],
    queryFn: () => api<StudentTimetable>(`/api/student/timetable${semesterId ? `?semesterId=${semesterId}` : ''}`, { realm: 'student' }) });
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  const d = q.data;
  const days = [1, 2, 3, 4, 5, 6, 7].filter((n) => d.slots.some((s) => s.dayOfWeek === n));
  return (
    <Stack spacing={2} sx={{ pb: 10 }}>
      <Box>
        <Typography variant="h5" component="h1">{t('timetable.title')}</Typography>
        {d.semesterName && <Typography color="text.secondary">{d.semesterName}</Typography>}
      </Box>
      {!d.semesterId && <Alert severity="info">{t('timetable.notRegistered')}</Alert>}
      {d.semesterId && (
        <>
          <Stack direction="row" justifyContent="space-between" alignItems="baseline">
            <Typography variant="h6">{t('timetable.classes')}</Typography>
            {d.classPublishedAt && <Typography variant="caption" color="text.secondary">{t('timetable.publishedOn', { date: formatDateTime(d.classPublishedAt) })}</Typography>}
          </Stack>
          {!d.classPublished && <Alert severity="info">{t('timetable.classesNotPublished')}</Alert>}
          {d.classPublished && days.map((n) => (
            <Card key={n} sx={{ borderRadius: 3 }}>
              <CardContent sx={{ pb: '12px !important' }}>
                <Typography variant="subtitle1" fontWeight={700}>{t(`timetable.day${n}`)}</Typography>
                {d.slots.filter((s) => s.dayOfWeek === n).sort((a, b) => a.startTime.localeCompare(b.startTime)).map((s, i) => (
                  <Box key={s.id}>
                    {i > 0 && <Divider sx={{ my: 1 }} />}
                    <Stack direction="row" spacing={1.5} alignItems="flex-start" sx={{ mt: i === 0 ? 1 : 0 }}>
                      <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, width: 100, flexShrink: 0, fontSize: 15 }}>{hm(s.startTime)}–{hm(s.endTime)}</Typography>
                      <Box sx={{ flex: 1, minWidth: 0 }}>
                        <Typography fontWeight={600}>{s.moduleCode} · {s.moduleTitle}</Typography>
                        <Typography variant="body2" color="text.secondary">
                          {t(`timetable.kind${s.kind}`)}{s.groupLabel ? ` (${s.groupLabel})` : ''} · {s.venueCode} {s.venueName}
                          {s.lecturerName ? ` · ${s.lecturerName}` : ''}
                        </Typography>
                        {s.note && <Typography variant="caption" color="text.secondary">{s.note}</Typography>}
                      </Box>
                      {s.changedSincePublished && <Chip size="small" color="warning" label={t('timetable.changed')} />}
                    </Stack>
                  </Box>
                ))}
              </CardContent>
            </Card>
          ))}
          {d.classPublished && d.unscheduled.length > 0 && <Alert severity="warning">{t('timetable.noClass', { modules: d.unscheduled.join(', ') })}</Alert>}

          <Stack direction="row" justifyContent="space-between" alignItems="baseline" sx={{ pt: 1 }}>
            <Typography variant="h6">{t('timetable.exams')}</Typography>
            {d.examPublishedAt && <Typography variant="caption" color="text.secondary">{t('timetable.publishedOn', { date: formatDateTime(d.examPublishedAt) })}</Typography>}
          </Stack>
          {!d.examPublished && <Alert severity="info">{t('timetable.examsNotPublished')}</Alert>}
          {d.examPublished && !d.exams.length && <Alert severity="info">{t('timetable.noExams')}</Alert>}
          {d.examPublished && d.exams.map((x) => (
            <Card key={x.id} sx={{ borderRadius: 3 }}>
              <CardContent sx={{ pb: '12px !important' }}>
                <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
                  <Box>
                    <Typography fontWeight={700}>{formatDate(x.date)} · {hm(x.startTime)}–{hm(x.endTime)}</Typography>
                    <Typography fontWeight={600}>{x.moduleCode} · {x.moduleTitle}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {t('timetable.paper')}: {x.paper} · {t('timetable.venues')}: {x.venues.map((v) => `${v.code} ${v.name}`).join(', ')}
                    </Typography>
                  </Box>
                  {x.changedSincePublished && <Chip size="small" color="warning" label={t('timetable.changed')} />}
                </Stack>
              </CardContent>
            </Card>
          ))}
          {d.examPublished && d.exams.length > 0 && <Typography variant="caption" color="text.secondary">{t('timetable.eligibilityNote')}</Typography>}
        </>
      )}
    </Stack>
  );
}


