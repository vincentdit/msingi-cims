import AddIcon from '@mui/icons-material/Add';
import { Alert, Box, Button, Container, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, formatDateTime } from '../../api/client';
import type { AcademicCalendar, Almanac, AlmanacYear } from '../../api/types';
import { useSession } from '../../auth/session';
import { AlmanacView } from '../../components/Almanac';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { StaffLink } from '../../components/StaffLink';
import { EventDialog, eventForm, newEvent, type EventForm } from './AcademicPages';

const staff = { realm: 'staff' as const };
const EDITORS = ['INSTITUTION_ADMIN', 'DEPUTY_PRINCIPAL_ACADEMIC'];

/**
 * The institution's almanac (stakeholders' comments): the academic calendar's dates together with meetings, events, deadlines and
 * holidays, month by month. Every member of staff reads it; the administrator and the Deputy Principal (Academic) keep it and
 * publish it to students and the public site.
 */
export default function AlmanacPage() {
  const qc = useQueryClient();
  const { user } = useSession();
  const canEdit = (user('staff')?.roles ?? []).some((r) => EDITORS.includes(r));
  const years = useQuery({ queryKey: ['staff', 'almanac-years'], queryFn: () => api<AlmanacYear[]>('/api/academic/almanac/years', staff) });
  const [yearId, setYearId] = useState<number>();
  useEffect(() => { if (!yearId && years.data?.length) setYearId((years.data.find((y) => y.current) ?? years.data[0]).id); }, [years.data, yearId]);
  const almanac = useQuery({ queryKey: ['staff', 'almanac', yearId], enabled: !!yearId,
    queryFn: () => api<Almanac>(`/api/academic/almanac?academicYearId=${yearId}`, staff) });
  const cal = useQuery({ queryKey: ['staff', 'calendar', yearId], enabled: !!yearId && canEdit,
    queryFn: () => api<AcademicCalendar>(`/api/academic/calendar?academicYearId=${yearId}`, staff) });
  const [ev, setEv] = useState<EventForm | null>(null);
  const [confirm, setConfirm] = useState<boolean | null>(null);
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['staff', 'almanac'] });
    qc.invalidateQueries({ queryKey: ['staff', 'almanac-years'] });
    qc.invalidateQueries({ queryKey: ['staff', 'calendar'] });
  };
  const publish = useMutation({
    mutationFn: (on: boolean) => api<Almanac>(`/api/academic/almanac/${yearId}/publish?publish=${on}`, { ...staff, method: 'POST' }),
    onSuccess: () => { setConfirm(null); refresh(); },
  });
  const a = almanac.data;

  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Almanac" subtitle="The year of the whole institution: teaching and examinations, meetings, events, deadlines and holidays."
        action={
          <Stack direction="row" spacing={1} sx={{ '@media print': { display: 'none' } }}>
            <TextField select size="small" label="Academic year" value={yearId ?? ''} onChange={(e) => setYearId(Number(e.target.value))} sx={{ minWidth: 170 }}>
              {(years.data ?? []).map((y) => <MenuItem key={y.id} value={y.id}>{y.code}{y.current ? ' (current)' : ''}</MenuItem>)}
            </TextField>
            {canEdit && yearId && <Button variant="contained" startIcon={<AddIcon />} sx={{ whiteSpace: 'nowrap' }} onClick={() => setEv(newEvent(yearId, 'MEETING'))}>Add entry</Button>}
          </Stack>
        } />
      <ErrorAlert error={years.error ?? almanac.error} />
      {years.data && !years.data.length && (
        <Alert severity="info" action={<Button color="inherit" size="small" component={StaffLink} to="/staff/calendar">Academic calendar</Button>}>
          There is no academic year yet. Add one under Academic calendar → Academic years and intakes.
        </Alert>
      )}
      {almanac.isLoading && <Loading />}
      {a && (
        <>
          <Alert severity={a.published ? 'success' : 'info'} sx={{ mb: 2, '@media print': { display: 'none' } }}
            action={canEdit && <Button color="inherit" size="small" onClick={() => setConfirm(!a.published)}>{a.published ? 'Withdraw' : 'Publish'}</Button>}>
            {a.published
              ? `Published${a.publishedAt ? ` on ${formatDateTime(a.publishedAt)}` : ''}${a.publishedBy ? ` by ${a.publishedBy}` : ''}: students see the entries for them, and the public site the entries for everyone.`
              : 'Not published: only staff see this almanac. Publish it when it is ready for students and the public site.'}
          </Alert>
          {canEdit && (
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2, '@media print': { display: 'none' } }}>
              Teaching, registration and marks-entry dates come from the semesters; change them on the Academic calendar.
            </Typography>
          )}
          <AlmanacView almanac={a} showAudience title={`Almanac ${a.academicYear}`}
            onEdit={canEdit ? (e) => e.eventId && setEv(eventForm({ ...e, id: e.eventId, academicYearId: a.academicYearId, semesterId: undefined,
              semesterName: e.semesterName ?? undefined })) : undefined} />
        </>
      )}
      {ev && cal.data && <EventDialog form={ev} semesters={cal.data.semesters} onClose={() => setEv(null)} onSaved={() => { setEv(null); refresh(); }}
        onDeleted={() => { setEv(null); refresh(); }} />}
      {confirm !== null && (
        <Dialog open onClose={() => setConfirm(null)} maxWidth="xs" fullWidth>
          <DialogTitle>{confirm ? `Publish the ${a?.academicYear} almanac?` : `Withdraw the ${a?.academicYear} almanac?`}</DialogTitle>
          <DialogContent>
            <Typography>{confirm
              ? 'Students will see the entries for everyone and for students on their portal, and the public site will show the entries for everyone. Entries for staff only stay inside.'
              : 'Students and the public site will no longer see it. Staff still do.'}</Typography>
            <ErrorAlert error={publish.error} />
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setConfirm(null)}>Cancel</Button>
            <Button variant="contained" disabled={publish.isPending} onClick={() => publish.mutate(confirm)}>{confirm ? 'Publish' : 'Withdraw'}</Button>
          </DialogActions>
        </Dialog>
      )}
      <Box sx={{ height: 24 }} />
    </Container>
  );
}
