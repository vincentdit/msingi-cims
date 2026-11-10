import { Box, Card, CardContent, FormControlLabel, Stack, Switch, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api, formatDate } from '../../api/client';
import { ErrorAlert } from '../../components/common';

const student = { realm: 'student' as const };

interface FamilyLink {
  id: number; phone: string; name?: string; relation?: string; source: 'NEXT_OF_KIN' | 'ADDED'; shareResults: boolean; shareAttendance: boolean;
  active: boolean; lastSeenAt?: string;
}

/**
 * Who follows the student in the family portal and what they see (FR-STU-05): fees always, results and attendance only if the student
 * shares them. Also the student's own choice about fee reminder messages.
 */
export default function StudentFamilyCard() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const links = useQuery({ queryKey: ['student', 'family'], queryFn: () => api<FamilyLink[]>('/api/student/family', student) });
  const prefs = useQuery({ queryKey: ['student', 'preferences'], queryFn: () => api<{ feeRemindersOptOut: boolean }>('/api/student/preferences', student) });
  const share = useMutation({
    mutationFn: (l: FamilyLink) => api<FamilyLink>(`/api/student/family/${l.id}`, { ...student, method: 'PUT', body: { shareResults: l.shareResults, shareAttendance: l.shareAttendance } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['student', 'family'] }),
  });
  const optOut = useMutation({
    mutationFn: (v: boolean) => api<{ feeRemindersOptOut: boolean }>('/api/student/preferences', { ...student, method: 'PUT', body: { feeRemindersOptOut: v } }),
    onSuccess: (d) => qc.setQueryData(['student', 'preferences'], d),
  });
  const active = (links.data ?? []).filter((l) => l.active);
  return (
    <Card sx={{ borderRadius: 3 }}>
      <CardContent>
        <Typography variant="h6" sx={{ mb: 0.5 }}>{t('student.familyTitle')}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>{t('student.familyHint')}</Typography>
        <ErrorAlert error={links.error ?? share.error} />
        {links.data && active.length === 0 && <Typography variant="body2">{t('student.familyNone')}</Typography>}
        <Stack spacing={1}>
          {active.map((l) => (
            <Box key={l.id} sx={{ p: 1.25, border: 1, borderColor: 'divider', borderRadius: 2 }}>
              <Typography sx={{ fontWeight: 600 }}>{l.name ?? l.phone}{l.relation ? ` · ${l.relation}` : ''}</Typography>
              <Typography variant="caption" color="text.secondary">
                {l.phone} · {l.lastSeenAt ? t('student.familyLastSeen', { date: formatDate(l.lastSeenAt) }) : t('student.familyNever')}
              </Typography>
              <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
                <FormControlLabel label={t('student.shareResults')} disabled={share.isPending}
                  control={<Switch checked={l.shareResults} onChange={(e) => share.mutate({ ...l, shareResults: e.target.checked })} />} />
                <FormControlLabel label={t('student.shareAttendance')} disabled={share.isPending}
                  control={<Switch checked={l.shareAttendance} onChange={(e) => share.mutate({ ...l, shareAttendance: e.target.checked })} />} />
              </Stack>
            </Box>
          ))}
        </Stack>
        <Typography variant="subtitle1" sx={{ mt: 2.5 }}>{t('student.remindersTitle')}</Typography>
        <FormControlLabel label={t('student.remindersOptOut')} disabled={!prefs.data || optOut.isPending}
          control={<Switch checked={prefs.data?.feeRemindersOptOut ?? false} onChange={(e) => optOut.mutate(e.target.checked)} />} />
        <Typography variant="body2" color="text.secondary">{t('student.remindersHint')}</Typography>
        <ErrorAlert error={optOut.error} />
      </CardContent>
    </Card>
  );
}
