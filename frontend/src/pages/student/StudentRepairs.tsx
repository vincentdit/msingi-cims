import { Alert, Box, Button, Chip, MenuItem, Stack, Switch, FormControlLabel, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, formatDate } from '../../api/client';
import type { HostelRepair } from '../../api/types';
import { ErrorAlert } from '../../components/common';

const student = { realm: 'student' as const };
const CATEGORIES = ['ELECTRICAL', 'PLUMBING', 'FURNITURE', 'STRUCTURE', 'CLEANING', 'SECURITY', 'OTHER'];

/** Repairs in the student's hostel room: report one, and follow what the warden does (FR-HST-06). */
export default function StudentRepairs() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [open, setOpen] = useState(false);
  const [category, setCategory] = useState('ELECTRICAL');
  const [urgent, setUrgent] = useState(false);
  const [description, setDescription] = useState('');
  const q = useQuery({ queryKey: ['student', 'repairs'], queryFn: () => api<HostelRepair[]>('/api/student/hostel/repairs', student) });
  const save = useMutation({
    mutationFn: () => api('/api/student/hostel/repairs', { ...student, method: 'POST', body: { category, priority: urgent ? 'URGENT' : 'NORMAL', description } }),
    onSuccess: () => { setOpen(false); setDescription(''); setUrgent(false); qc.invalidateQueries({ queryKey: ['student', 'repairs'] }); },
  });
  const colour = (s: string) => (s === 'DONE' ? 'success' : s === 'IN_PROGRESS' ? 'info' : s === 'OPEN' ? 'warning' : 'default') as 'success' | 'info' | 'warning' | 'default';
  return (
    <Box sx={{ mt: 2, pt: 1.5, borderTop: 1, borderColor: 'divider' }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="subtitle2">{t('repairs.title')}</Typography>
        {!open && <Button size="small" onClick={() => setOpen(true)}>{t('repairs.report')}</Button>}
      </Stack>
      {open && (
        <Stack spacing={1.5} sx={{ mt: 1 }}>
          <ErrorAlert error={save.error} />
          <TextField select size="small" label={t('repairs.kind')} value={category} onChange={(e) => setCategory(e.target.value)}>
            {CATEGORIES.map((c) => <MenuItem key={c} value={c}>{t(`repairs.${c}`)}</MenuItem>)}
          </TextField>
          <TextField size="small" multiline minRows={2} label={t('repairs.what')} value={description} onChange={(e) => setDescription(e.target.value)} />
          <FormControlLabel control={<Switch checked={urgent} onChange={(e) => setUrgent(e.target.checked)} />} label={t('repairs.urgent')} />
          <Stack direction="row" spacing={1}>
            <Button variant="contained" size="small" onClick={() => save.mutate()} disabled={description.trim().length < 5 || save.isPending}>{t('repairs.send')}</Button>
            <Button size="small" onClick={() => setOpen(false)}>{t('repairs.cancel')}</Button>
          </Stack>
        </Stack>
      )}
      {save.isSuccess && !open && <Alert severity="success" sx={{ mt: 1 }}>{t('repairs.sent')}</Alert>}
      {(q.data ?? []).slice(0, 5).map((r) => (
        <Stack key={r.id} direction="row" spacing={1} alignItems="flex-start" sx={{ mt: 1 }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="body2">{t(`repairs.${r.category}`)}: {r.description}</Typography>
            <Typography variant="caption" color="text.secondary">{formatDate(r.reportedAt)}{r.note ? ` · ${r.note}` : ''}</Typography>
          </Box>
          <Chip size="small" color={colour(r.status)} label={t(`repairs.status${r.status}`)} />
        </Stack>
      ))}
    </Box>
  );
}
