import DownloadIcon from '@mui/icons-material/Download';
import { Alert, Box, Button, Card, CardContent, Chip, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, download, formatDate } from '../../api/client';
import type { IptLogbookEntry, IptPlacementView, MyIpt } from '../../api/types';
import { ErrorAlert } from '../../components/common';
import { IPT_COLOUR, LOG_COLOUR } from '../staff/IptPages';

const student = { realm: 'student' as const };
const blank = (s: string) => (s.trim() ? s.trim() : null);
const addDays = (iso: string, n: number) => { const d = new Date(iso + 'T00:00:00'); d.setDate(d.getDate() + n); return d.toISOString().slice(0, 10); };

/** Industrial practical training on the student portal: placement request, letter, weekly logbook and marks (FR-IPT-02/03, FR-STU-04). */
export default function StudentIptCard() {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['student', 'ipt'], queryFn: () => api<MyIpt>('/api/student/ipt', student) });
  const d = q.data;
  if (q.isLoading || !d) return q.error ? <ErrorAlert error={q.error} /> : null;
  const pl = d.placement;
  const open = d.periods.find((p) => p.requestsOpen);
  if (!pl && !d.periods.length) return null;
  const declined = d.history.find((h) => h.status === 'DECLINED');
  return (
    <Card sx={{ mt: 2 }}>
      <CardContent>
        <Typography variant="h6" gutterBottom>{t('ipt.title')}</Typography>
        <ErrorAlert error={q.error} />
        {pl ? <Placement v={pl} /> : open ? (
          <>
            {declined && <Alert severity="warning" sx={{ mb: 2 }}>{t('ipt.declinedTry', { org: declined.organisation })}</Alert>}
            <RequestForm d={d} periodId={open.id} />
          </>
        ) : <Typography color="text.secondary">{t('ipt.none')}</Typography>}
      </CardContent>
    </Card>
  );
}

function RequestForm({ d, periodId }: { d: MyIpt; periodId: number }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const p = d.periods.find((x) => x.id === periodId)!;
  const [f, setF] = useState({ org: '' as number | 'other' | '', newName: '', region: '', contact: '', phone: '', unit: '', wsName: '', wsPhone: '', note: '' });
  const m = useMutation({
    mutationFn: () => api<MyIpt>('/api/student/ipt/placements', { ...student, method: 'POST', body: {
      periodId, organisationId: typeof f.org === 'number' ? f.org : null, newOrganisationName: f.org === 'other' ? blank(f.newName) : null,
      newOrganisationRegion: blank(f.region), newOrganisationContact: blank(f.contact), newOrganisationPhone: blank(f.phone),
      unit: blank(f.unit), workplaceSupervisorName: blank(f.wsName), workplaceSupervisorPhone: blank(f.wsPhone), note: blank(f.note) } }),
    onSuccess: (r) => qc.setQueryData(['student', 'ipt'], r),
  });
  const tf = (k: keyof typeof f, label: string) => <TextField size="small" fullWidth label={label} value={f[k]} onChange={(e) => setF({ ...f, [k]: e.target.value })} />;
  return (
    <Stack spacing={1.5}>
      <Typography variant="body2">{t('ipt.requestIn', { period: p.name, date: formatDate(p.requestsClose ?? p.endsOn) })} ({formatDate(p.startsOn)} – {formatDate(p.endsOn)})</Typography>
      <TextField select size="small" label={t('ipt.organisation')} value={f.org} onChange={(e) => setF({ ...f, org: e.target.value === 'other' ? 'other' : Number(e.target.value) })}>
        {d.organisations.map((o) => <MenuItem key={o.id} value={o.id}>{o.name}{o.region ? ` (${o.region})` : ''}</MenuItem>)}
        <MenuItem value="other"><em>{t('ipt.other')}</em></MenuItem>
      </TextField>
      {f.org === 'other' && (
        <>
          {tf('newName', t('ipt.newName'))}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>{tf('region', t('ipt.region'))}{tf('contact', t('ipt.contact'))}{tf('phone', t('ipt.phone'))}</Stack>
        </>
      )}
      {tf('unit', t('ipt.unit'))}
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>{tf('wsName', t('ipt.wsName'))}{tf('wsPhone', t('ipt.wsPhone'))}</Stack>
      {tf('note', t('ipt.note'))}
      <ErrorAlert error={m.error} />
      <Box><Button variant="contained" disabled={!f.org || (f.org === 'other' && !f.newName.trim()) || m.isPending} onClick={() => m.mutate()}>{t('ipt.send')}</Button></Box>
    </Stack>
  );
}

function Placement({ v }: { v: IptPlacementView }) {
  const { t } = useTranslation();
  const s = v.summary;
  const [editing, setEditing] = useState<number | null>(null);
  const nextWeek = (v.logbook.reduce((m, e) => Math.max(m, e.weekNo), 0) || 0) + 1;
  const nextStart = addDays(s.startsOn, (nextWeek - 1) * 7);
  const canAdd = v.can.writeLogbook && nextStart <= new Date().toISOString().slice(0, 10) && nextStart <= s.endsOn;
  return (
    <Stack spacing={1.5}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap">
        <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>{s.organisation}</Typography>
        <Chip size="small" color={IPT_COLOUR[s.status]} label={t(`ipt.status${s.status}`)} />
      </Stack>
      <Typography variant="body2" color="text.secondary">{[s.region, v.unit].filter(Boolean).join(' · ')} · {formatDate(s.startsOn)} – {formatDate(s.endsOn)}</Typography>
      {s.status === 'REQUESTED' && <Alert severity="info">{t('ipt.requestedHint')}</Alert>}
      {s.institutionSupervisorName && <Typography variant="body2">{t('ipt.supervisor', { name: s.institutionSupervisorName })}</Typography>}
      {s.workplaceSupervisorName && <Typography variant="body2">{t('ipt.workplace', { name: s.workplaceSupervisorName })}</Typography>}
      {s.letterNo && <Box><Button size="small" variant="outlined" startIcon={<DownloadIcon />} onClick={() => download('/api/student/ipt/letter', 'student', 'placement-letter.pdf')}>{t('ipt.letter')}</Button></Box>}
      {(s.status === 'CONFIRMED' || s.status === 'COMPLETED') && (
        <>
          <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ pt: 1 }}>
            <Typography variant="subtitle2">{t('ipt.logbook')} · {t('ipt.approved', { n: s.logbookApproved, total: s.logbookWeeks })}</Typography>
            {s.totalMark != null
              ? <Chip color="success" label={`${t('ipt.mark')}: ${s.totalMark}`} />
              : s.scores > 0 && <Typography variant="caption" color="text.secondary">{t('ipt.marks', { n: s.scores })}</Typography>}
          </Stack>
          {v.logbook.map((e) => editing === e.weekNo
            ? <WeekForm key={e.id} weekNo={e.weekNo} weekStarting={e.weekStarting} entry={e} onDone={() => setEditing(null)} />
            : (
              <Box key={e.id} sx={{ p: 1.25, border: 1, borderColor: 'divider', borderRadius: 1 }}>
                <Stack direction="row" justifyContent="space-between" alignItems="center">
                  <Typography variant="body2" sx={{ fontWeight: 600 }}>{t('ipt.week', { n: e.weekNo })} · {formatDate(e.weekStarting)}</Typography>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <Chip size="small" color={LOG_COLOUR[e.status]} label={t(`ipt.log${e.status}`)} />
                    {v.can.writeLogbook && (e.status === 'DRAFT' || e.status === 'RETURNED') && <Button size="small" onClick={() => setEditing(e.weekNo)}>{t('ipt.edit')}</Button>}
                  </Stack>
                </Stack>
                <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{e.activities}</Typography>
                {e.reviewerComment && <Typography variant="caption" color={e.status === 'RETURNED' ? 'warning.main' : 'text.secondary'}>{e.reviewedByName}: “{e.reviewerComment}”</Typography>}
              </Box>
            ))}
          {editing === nextWeek && <WeekForm weekNo={nextWeek} weekStarting={nextStart} onDone={() => setEditing(null)} />}
          {canAdd && editing == null && <Box><Button size="small" variant="outlined" onClick={() => setEditing(nextWeek)}>{t('ipt.newWeek', { n: nextWeek })}</Button></Box>}
        </>
      )}
    </Stack>
  );
}

function WeekForm({ weekNo, weekStarting, entry, onDone }: { weekNo: number; weekStarting: string; entry?: IptLogbookEntry; onDone: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [f, setF] = useState({ activities: entry?.activities ?? '', skills: entry?.skills ?? '', challenges: entry?.challenges ?? '', hours: entry?.hours == null ? '' : String(entry.hours) });
  const m = useMutation({
    mutationFn: (submit: boolean) => api<IptPlacementView>('/api/student/ipt/logbook', { ...student, method: 'PUT', body: {
      weekNo, weekStarting, activities: f.activities.trim(), skills: blank(f.skills), challenges: blank(f.challenges), hours: f.hours === '' ? null : Number(f.hours), submit } }),
    onSuccess: (v) => { qc.setQueryData<MyIpt>(['student', 'ipt'], (old) => (old ? { ...old, placement: v } : old)); onDone(); },
  });
  return (
    <Box sx={{ p: 1.5, border: 1, borderColor: 'primary.main', borderRadius: 1 }}>
      <Typography variant="body2" sx={{ fontWeight: 600, mb: 1 }}>{t('ipt.week', { n: weekNo })} · {formatDate(weekStarting)}</Typography>
      <Stack spacing={1.25}>
        <TextField size="small" multiline minRows={3} label={t('ipt.activities')} value={f.activities} onChange={(e) => setF({ ...f, activities: e.target.value })} />
        <TextField size="small" label={t('ipt.skills')} value={f.skills} onChange={(e) => setF({ ...f, skills: e.target.value })} />
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.25}>
          <TextField size="small" fullWidth label={t('ipt.challenges')} value={f.challenges} onChange={(e) => setF({ ...f, challenges: e.target.value })} />
          <TextField size="small" type="number" label={t('ipt.hours')} value={f.hours} onChange={(e) => setF({ ...f, hours: e.target.value })} sx={{ width: { sm: 110 } }} />
        </Stack>
        <ErrorAlert error={m.error} />
        <Stack direction="row" spacing={1} justifyContent="flex-end">
          <Button onClick={onDone}>{t('ipt.cancel')}</Button>
          <Button variant="outlined" disabled={!f.activities.trim() || m.isPending} onClick={() => m.mutate(false)}>{t('ipt.saveDraft')}</Button>
          <Button variant="contained" disabled={!f.activities.trim() || m.isPending} onClick={() => m.mutate(true)}>{t('ipt.submitWeek')}</Button>
        </Stack>
      </Stack>
    </Box>
  );
}
