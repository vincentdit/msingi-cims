import StudentRepairs from './StudentRepairs';
import { Alert, Box, Button, Card, CardContent, Checkbox, Chip, FormControlLabel, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, formatDate, formatTzs } from '../../api/client';
import type { MyHostel, RoomType } from '../../api/types';
import { ErrorAlert } from '../../components/common';

const student = { realm: 'student' as const };
const TYPES: RoomType[] = ['SINGLE', 'DOUBLE', 'TRIPLE', 'QUAD', 'DORMITORY'];

/** Hostel application and allocation on the student portal (FR-HST-02, FR-STU-04). */
export default function StudentHostelCard() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['student', 'hostel'], queryFn: () => api<MyHostel>('/api/student/hostel', student) });
  const d = q.data;
  const app = d?.application;
  const window = d?.openWindows[0];
  const editable = !!window && (!app || ((app.status === 'SUBMITTED' || app.status === 'WAITLISTED') && app.windowId === window.id) || app.windowId !== window.id);
  const [editing, setEditing] = useState(false);
  const [prefs, setPrefs] = useState<(number | '')[]>(['', '', '']);
  const [roomType, setRoomType] = useState<RoomType | ''>('');
  const [special, setSpecial] = useState(false);
  const [note, setNote] = useState('');
  useEffect(() => {
    if (!d) return;
    const names = d.hostels;
    const ids = (app?.preferences ?? []).map((n) => names.find((h) => h.name === n)?.id ?? '');
    setPrefs([ids[0] ?? '', ids[1] ?? '', ids[2] ?? '']);
    setRoomType(app?.roomType ?? '');
    setSpecial(app?.specialNeeds ?? false);
    setNote(app?.specialNeedsNote ?? '');
  }, [d, app]);
  const save = useMutation({
    mutationFn: () => api<MyHostel>('/api/student/hostel/applications', { ...student, method: 'POST', body: {
      windowId: window!.id, preference1Id: prefs[0] || null, preference2Id: prefs[1] || null, preference3Id: prefs[2] || null,
      roomType: roomType || null, specialNeeds: special, specialNeedsNote: special ? note : null } }),
    onSuccess: (r) => { qc.setQueryData(['student', 'hostel'], r); setEditing(false); },
  });
  const withdraw = useMutation({
    mutationFn: () => api<MyHostel>(`/api/student/hostel/applications/${app!.id}`, { ...student, method: 'DELETE' }),
    onSuccess: (r) => qc.setQueryData(['student', 'hostel'], r),
  });
  if (!d) return null;
  const current = app && (!window || app.windowId === window.id || app.status === 'ALLOCATED') ? app : null;
  const al = current?.allocation;
  if (!window && !current) return null;
  const showForm = window && !d.blocker && (editing || !current) && editable;
  return (
    <Card sx={{ borderRadius: 3 }}>
      <CardContent>
        <Typography variant="h6">{t('hostel.title')}</Typography>
        {current && !showForm && (
          <Box sx={{ mt: 1 }}>
            <Chip size="small" color={current.status === 'ALLOCATED' ? 'success' : current.status === 'WAITLISTED' ? 'warning' : current.status === 'RELEASED' ? 'error' : 'default'}
              label={t(`hostel.status${current.status}`)} />
            {al && current.status === 'ALLOCATED' && (
              <Box sx={{ mt: 1.5, p: 1.5, borderRadius: 2, bgcolor: 'action.hover' }}>
                <Typography sx={{ fontWeight: 700 }}>{al.hostel}</Typography>
                <Typography variant="body2">{al.room}, bed {al.bed}</Typography>
                {al.status === 'ALLOCATED' && al.invoiceStatus !== 'PAID' && al.fee > 0 && (
                  <>
                    <Typography variant="body2" sx={{ mt: 1 }}>{t('hostel.payBy', { amount: formatTzs(al.balance), date: formatDate(al.payBy) })}</Typography>
                    {al.controlNo && <Typography variant="body2">{t('hostel.controlNo')}: <strong style={{ fontFamily: 'monospace', fontSize: 15 }}>{al.controlNo}</strong></Typography>}
                  </>
                )}
                {al.invoiceStatus === 'PAID' && al.status === 'ALLOCATED' && <Typography variant="body2" color="success.main" sx={{ mt: 1 }}>{t('hostel.paid')}</Typography>}
                {al.checkedInAt && <Typography variant="caption" color="text.secondary">{t('hostel.checkedIn', { date: formatDate(al.checkedInAt) })}</Typography>}
                {(al.status === 'ALLOCATED' || al.status === 'CHECKED_IN') && <StudentRepairs />}
              </Box>
            )}
            {current.status === 'SUBMITTED' && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>{t('hostel.submittedHint')}</Typography>}
            {current.status === 'WAITLISTED' && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>{t('hostel.waitHint')}</Typography>}
            {current.status === 'RELEASED' && al?.releaseReason && <Typography variant="body2" color="error.main" sx={{ mt: 1 }}>{al.releaseReason}</Typography>}
            {editable && (current.status === 'SUBMITTED' || current.status === 'WAITLISTED') && (
              <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
                <Button size="small" onClick={() => setEditing(true)}>{t('hostel.update')}</Button>
                <Button size="small" color="warning" onClick={() => withdraw.mutate()} disabled={withdraw.isPending}>{t('hostel.withdraw')}</Button>
              </Stack>
            )}
          </Box>
        )}
        {window && d.blocker && !current && <Alert severity="info" sx={{ mt: 1 }}>{d.blocker}</Alert>}
        {showForm && (
          <Stack spacing={1.5} sx={{ mt: 1 }}>
            <Typography variant="body2" color="text.secondary">{t('hostel.applyIn', { window: window.name, date: formatDate(window.closesOn) })}</Typography>
            {[0, 1, 2].map((i) => (
              <TextField key={i} select size="small" label={t('hostel.pref', { n: i + 1 })} value={prefs[i]}
                onChange={(e) => setPrefs(prefs.map((p, j) => (j === i ? (e.target.value === '' ? '' : Number(e.target.value)) : p)))}>
                <MenuItem value="">{t('hostel.any')}</MenuItem>
                {d.hostels.filter((h) => !prefs.includes(h.id) || prefs[i] === h.id).map((h) => <MenuItem key={h.id} value={h.id}>{h.name}</MenuItem>)}
              </TextField>
            ))}
            <TextField select size="small" label={t('hostel.roomType')} value={roomType} onChange={(e) => setRoomType(e.target.value as RoomType | '')}>
              <MenuItem value="">{t('hostel.anyType')}</MenuItem>
              {TYPES.map((x) => <MenuItem key={x} value={x}>{t(`hostel.type${x}`)}</MenuItem>)}
            </TextField>
            <FormControlLabel control={<Checkbox checked={special} onChange={(e) => setSpecial(e.target.checked)} />} label={t('hostel.special')} />
            {special && <TextField size="small" label={t('hostel.specialNote')} value={note} onChange={(e) => setNote(e.target.value)} multiline />}
            <ErrorAlert error={save.error} />
            <Button variant="contained" onClick={() => save.mutate()} disabled={save.isPending}>{current ? t('hostel.update') : t('hostel.submit')}</Button>
          </Stack>
        )}
        <ErrorAlert error={withdraw.error} />
      </CardContent>
    </Card>
  );
}
