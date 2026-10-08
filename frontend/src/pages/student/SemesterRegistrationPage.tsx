import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import EventIcon from '@mui/icons-material/EventOutlined';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Divider, FormControlLabel, Link, Stack, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink } from 'react-router-dom';
import { api, formatDate, formatDateTime } from '../../api/client';
import type { ModuleOption, SemesterRegistration, StudentSemesterRegistration } from '../../api/types';
import { ErrorAlert, Loading } from '../../components/common';
import { ClearanceRow } from './StudentPages';

const student = { realm: 'student' as const };
const STATUS_COLOUR = { SUBMITTED: 'info', APPROVED: 'success', REJECTED: 'warning', CANCELLED: 'default' } as const;
const WINDOW_COLOUR = { OPEN: 'success', LATE: 'warning', NOT_OPEN: 'default', CLOSED: 'default' } as const;
const credits = (n: number) => Number(n).toLocaleString('en-GB', { maximumFractionDigits: 1 });

/** Semester registration in the student portal (FR-STU-02, FR-REG-03): core modules, elective choice, fee clearance, submit. */
export default function SemesterRegistrationPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['student', 'semester-registration'], queryFn: () => api<StudentSemesterRegistration>('/api/student/semester-registration', student) });
  const d = q.data;
  const [picked, setPicked] = useState<number[]>([]);
  const [confirmed, setConfirmed] = useState(false);
  const [done, setDone] = useState(false);
  const [editing, setEditing] = useState(false);
  useEffect(() => {
    if (d) setPicked(d.electives.filter((e) => e.selected).map((e) => e.moduleId));
  }, [d]);

  const submit = useMutation({
    mutationFn: () => api<StudentSemesterRegistration>('/api/student/semester-registration', {
      ...student, method: 'POST', body: { semesterId: d!.semester!.id, electiveModuleIds: picked, detailsConfirmed: confirmed },
    }),
    onSuccess: (r) => { qc.setQueryData(['student', 'semester-registration'], r); setDone(true); setEditing(false); setConfirmed(false); window.scrollTo({ top: 0, behavior: 'smooth' }); },
  });
  const cancel = useMutation({
    mutationFn: (id: number) => api<StudentSemesterRegistration>(`/api/student/semester-registration/${id}/cancel`, { ...student, method: 'POST' }),
    onSuccess: (r) => { qc.setQueryData(['student', 'semester-registration'], r); setDone(false); },
  });

  const total = useMemo(() => {
    if (!d) return 0;
    return d.core.reduce((s, m) => s + Number(m.credits), 0)
      + d.electives.filter((e) => picked.includes(e.moduleId)).reduce((s, m) => s + Number(m.credits), 0);
  }, [d, picked]);

  if (q.isLoading) return <Loading />;
  if (!d) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;

  const sem = d.semester;
  const reg = d.registration;
  const canEdit = (d.state === 'OPEN' || d.state === 'LATE') && reg?.status !== 'APPROVED';
  const showForm = canEdit && (reg?.status !== 'SUBMITTED' || editing);
  const history = d.history.filter((h) => h.id !== reg?.id);
  const electivesOk = picked.length >= d.minElectives && picked.length <= d.maxElectives;
  const cleared = !d.clearance || d.clearance.cleared;
  const choose = d.minElectives === d.maxElectives ? t('semReg.chooseExactly', { count: d.minElectives })
    : t('semReg.chooseRange', { min: d.minElectives, max: d.maxElectives });

  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">{t('semReg.title')}</Typography>

      {sem && (
        <Card sx={{ borderRadius: 3 }}>
          <CardContent>
            <Stack direction="row" spacing={1.5} alignItems="flex-start">
              <EventIcon color="primary" sx={{ mt: 0.5 }} />
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Typography variant="h6">{sem.name} · {sem.academicYear}</Typography>
                <Chip size="small" color={WINDOW_COLOUR[sem.window]} label={t(`semReg.window${sem.window}`)} sx={{ my: 0.5 }} />
                <Typography variant="body2" color="text.secondary">
                  {t('semReg.yearSem', { year: d.yearOfStudy, semester: `${formatDate(sem.startDate)} – ${formatDate(sem.endDate)}` })}
                </Typography>
                <Typography variant="body2" sx={{ mt: 0.5 }}>
                  {t('semReg.opens', { date: formatDate(sem.registrationOpens) })} · {t('semReg.closes', { date: formatDate(sem.registrationCloses) })}
                </Typography>
                {sem.lateRegistrationCloses && <Typography variant="body2" color="text.secondary">{t('semReg.lateUntil', { date: formatDate(sem.lateRegistrationCloses) })}</Typography>}
                {d.curriculum && <Typography variant="caption" color="text.secondary">{t('semReg.curriculum')}: {d.curriculum}</Typography>}
              </Box>
            </Stack>
          </CardContent>
        </Card>
      )}

      {d.state !== 'OPEN' && d.state !== 'LATE' && (
        <Alert severity={d.state === 'NOT_OPEN' ? 'info' : 'warning'}>
          {t(`semReg.state${d.state}`, { semester: sem?.name ?? '', date: sem ? formatDate(sem.registrationOpens) : '', defaultValue: d.message })}
        </Alert>
      )}
      {d.state === 'LATE' && canEdit && <Alert severity="warning">{t('semReg.lateNote')}</Alert>}
      {done && reg?.status === 'SUBMITTED' && <Alert severity="success" onClose={() => setDone(false)}>{t('semReg.submitted')}</Alert>}

      {reg && <RegistrationCard reg={reg} canCancel={reg.status === 'SUBMITTED' && canEdit} onCancel={() => {
        if (window.confirm(t('semReg.cancelConfirm', { semester: reg.semesterName }))) cancel.mutate(reg.id);
      }} busy={cancel.isPending} />}
      <ErrorAlert error={cancel.error} />
      {canEdit && reg?.status === 'SUBMITTED' && !editing && (
        <Button variant="outlined" onClick={() => setEditing(true)}>{t('semReg.change')}</Button>
      )}

      {showForm && (
        <>
          <Card sx={{ borderRadius: 3 }}>
            <CardContent>
              <Typography variant="h6">{t('semReg.core')}</Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>{t('semReg.coreHint')}</Typography>
              <Stack divider={<Divider flexItem />}>
                {d.core.map((m) => <ModuleLine key={m.moduleId} m={m} control={<CheckCircleIcon color="success" sx={{ mx: 1.1 }} />} />)}
              </Stack>
            </CardContent>
          </Card>

          {d.electives.length > 0 && (
            <Card sx={{ borderRadius: 3 }}>
              <CardContent>
                <Stack direction="row" justifyContent="space-between" alignItems="baseline">
                  <Typography variant="h6">{t('semReg.electives')}</Typography>
                  <Typography variant="body2" color={electivesOk ? 'success.main' : 'warning.main'}>{choose} · {t('semReg.chosen', { count: picked.length })}</Typography>
                </Stack>
                <Stack divider={<Divider flexItem />} sx={{ mt: 1 }}>
                  {d.electives.map((m) => {
                    const on = picked.includes(m.moduleId);
                    const full = !on && picked.length >= d.maxElectives;
                    return (
                      <ModuleLine key={m.moduleId} m={m} control={
                        <Checkbox checked={on} disabled={full && d.maxElectives > 1}
                          inputProps={{ 'aria-label': `${m.code} ${m.title}` }}
                          onChange={() => setPicked(on ? picked.filter((x) => x !== m.moduleId)
                            : d.maxElectives === 1 ? [m.moduleId] : [...picked, m.moduleId])} />
                      } />
                    );
                  })}
                </Stack>
              </CardContent>
            </Card>
          )}

          {d.clearance && (
            <Card sx={{ borderRadius: 3 }}>
              <CardContent>
                <Typography variant="h6" sx={{ mb: 1 }}>{t('semReg.feeClearance')}</Typography>
                <ClearanceRow c={d.clearance} />
                {!cleared && (
                  <Typography variant="body2" sx={{ mt: 1 }}>
                    {t('semReg.payFirst')} <Link component={RouterLink} to="/student/fees">{t('student.navFees')}</Link>
                  </Typography>
                )}
              </CardContent>
            </Card>
          )}

          <Card sx={{ borderRadius: 3 }}>
            <CardContent>
              <Stack direction="row" justifyContent="space-between" sx={{ mb: 1 }}>
                <Typography color="text.secondary">{t('semReg.totalCredits')}</Typography>
                <Typography sx={{ fontFamily: 'Sora', fontWeight: 700 }}>{credits(total)}</Typography>
              </Stack>
              <FormControlLabel control={<Checkbox checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />}
                label={<Typography variant="body2">{t('semReg.confirmDetails')} <Link component={RouterLink} to="/student/profile">{t('semReg.checkProfile')}</Link></Typography>} />
              <ErrorAlert error={submit.error} />
              <Button fullWidth variant="contained" size="large" sx={{ mt: 1 }} disabled={!confirmed || !electivesOk || !cleared || submit.isPending}
                onClick={() => submit.mutate()}>
                {reg?.status === 'SUBMITTED' ? t('semReg.update') : t('semReg.submit')}
              </Button>
              {!cleared && <Typography variant="body2" color="warning.main" sx={{ mt: 1 }}>{t('semReg.payFirst')}</Typography>}
              {reg?.status === 'SUBMITTED' && <Typography variant="caption" color="text.secondary">{t('semReg.changeHint')}</Typography>}
            </CardContent>
          </Card>
        </>
      )}

      {history.length > 0 && (
        <Card sx={{ borderRadius: 3 }}>
          <CardContent>
            <Typography variant="h6" sx={{ mb: 1 }}>{t('semReg.history')}</Typography>
            <Stack divider={<Divider flexItem />} spacing={1}>
              {history.map((h) => (
                <Stack key={h.id} direction="row" spacing={1} alignItems="center">
                  <Box sx={{ flexGrow: 1 }}>
                    <Typography>{h.semesterName}</Typography>
                    <Typography variant="caption" color="text.secondary">{t('semReg.modulesCount', { n: h.modules.length })} · {t('semReg.credits', { n: credits(h.totalCredits) })}</Typography>
                  </Box>
                  <Chip size="small" color={STATUS_COLOUR[h.status]} label={t(`semReg.status${h.status}`)} />
                </Stack>
              ))}
            </Stack>
          </CardContent>
        </Card>
      )}
    </Stack>
  );
}

function ModuleLine({ m, control }: { m: ModuleOption; control: React.ReactNode }) {
  const { t } = useTranslation();
  return (
    <Stack direction="row" alignItems="center" sx={{ py: 0.75 }}>
      {control}
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{m.code}</Typography>
        <Typography variant="body2" color="text.secondary">{m.title}</Typography>
      </Box>
      <Typography variant="body2" sx={{ whiteSpace: 'nowrap', ml: 1 }}>{t('semReg.credits', { n: credits(m.credits) })}</Typography>
    </Stack>
  );
}

function RegistrationCard({ reg, canCancel, onCancel, busy }: { reg: SemesterRegistration; canCancel: boolean; onCancel: () => void; busy: boolean }) {
  const { t } = useTranslation();
  return (
    <Card sx={{ borderRadius: 3, borderColor: reg.status === 'APPROVED' ? 'success.main' : reg.status === 'REJECTED' ? 'warning.main' : undefined }}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1}>
          <Typography variant="h6">{t('semReg.yours')}</Typography>
          <Stack direction="row" spacing={0.5}>
            {reg.late && <Chip size="small" variant="outlined" color="warning" label={t('semReg.late')} />}
            <Chip size="small" color={STATUS_COLOUR[reg.status]} label={t(`semReg.status${reg.status}`)} />
          </Stack>
        </Stack>
        <Typography variant="caption" color="text.secondary">
          {t('semReg.submittedOn', { date: formatDateTime(reg.submittedAt) })}
          {reg.decidedAt && reg.decidedByName && <> · {t(`semReg.status${reg.status}`)} {t('semReg.decided', { date: formatDateTime(reg.decidedAt), name: reg.decidedByName })}</>}
        </Typography>
        {reg.status === 'REJECTED' && reg.decisionNote && (
          <Alert severity="warning" sx={{ mt: 1 }}><strong>{t('semReg.registryNote')}:</strong> {reg.decisionNote}</Alert>
        )}
        {reg.status !== 'CANCELLED' && reg.status !== 'REJECTED' && (
          <Stack divider={<Divider flexItem />} sx={{ mt: 1 }}>
            {reg.modules.map((m) => (
              <Stack key={m.moduleId} direction="row" alignItems="center" spacing={1} sx={{ py: 0.5 }}>
                <Box sx={{ flexGrow: 1, minWidth: 0, display: { sm: 'flex' }, gap: 1.5 }}>
                  <Typography variant="body2" sx={{ fontWeight: 600, minWidth: 82 }}>{m.code}</Typography>
                  <Typography variant="body2" color="text.secondary">{m.title}</Typography>
                </Box>
                <Chip size="small" variant="outlined" label={t(`semReg.category${m.category}`)} />
                <Typography variant="body2" sx={{ whiteSpace: 'nowrap', minWidth: 28, textAlign: 'right' }}>{credits(m.credits)}</Typography>
              </Stack>
            ))}
            <Stack direction="row" justifyContent="space-between" sx={{ pt: 1 }}>
              <Typography variant="body2" color="text.secondary">{t('semReg.totalCredits')}</Typography>
              <Typography variant="body2" sx={{ fontWeight: 700 }}>{credits(reg.totalCredits)}</Typography>
            </Stack>
          </Stack>
        )}
        {reg.status === 'APPROVED' && <Typography variant="body2" color="success.main" sx={{ mt: 1 }}>{t('semReg.approvedHint')}</Typography>}
        {canCancel && <Button color="inherit" size="small" sx={{ mt: 1 }} onClick={onCancel} disabled={busy}>{t('semReg.cancel')}</Button>}
      </CardContent>
    </Card>
  );
}
