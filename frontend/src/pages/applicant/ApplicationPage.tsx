import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { Alert, Box, Button, Card, CardContent, Chip, Container, Stack, Step, StepButton, StepContent, Stepper, Typography, useMediaQuery, useTheme } from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type { AdmissionWindow, Application, Profile, Qualification } from '../../api/types';
import { ErrorAlert, Loading, humanise, statusColour } from '../../components/common';
import { DetailsStep, FeeStep, ProgrammesStep, ResultsStep, ReviewStep } from './steps';

/** The applicant's home: start an application, then complete it step by step (BP-02). */
export default function ApplicationPage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const apps = useQuery({ queryKey: ['applicant', 'applications'], queryFn: () => api<Application[]>('/api/applicant/applications', { realm: 'applicant' }) });
  const windows = useQuery({ queryKey: ['public-windows'], queryFn: () => api<AdmissionWindow[]>('/api/public/windows') });
  const start = useMutation({
    mutationFn: (windowId: number) => api<Application>('/api/applicant/applications', { method: 'POST', realm: 'applicant', body: { windowId } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['applicant'] }),
  });

  if (apps.isLoading || windows.isLoading) return <Loading />;
  const current = apps.data?.[0];

  return (
    <Container maxWidth="md" sx={{ py: { xs: 3, md: 5 } }}>
      <ErrorAlert error={apps.error ?? windows.error ?? start.error} />
      {!current && (
        <>
          <Typography variant="h5" component="h1" sx={{ mb: 2 }}>{t('app.title')}</Typography>
          {windows.data?.length ? windows.data.map((w) => (
            <Card key={w.id} sx={{ mb: 2 }}>
              <CardContent>
                <Typography variant="h6">{w.name}</Typography>
                <Typography color="text.secondary">{t('home.closes', { date: formatDate(w.closesAt) })} · {t('home.fee')}: {formatTzs(w.applicationFee)}</Typography>
                <Button sx={{ mt: 2 }} variant="contained" onClick={() => start.mutate(w.id)} disabled={start.isPending}>{t('home.startApplication')}</Button>
              </CardContent>
            </Card>
          )) : <Alert severity="info">{t('home.noWindow')}</Alert>}
        </>
      )}
      {current && (current.status === 'DRAFT' || current.status === 'PAYMENT_PENDING'
        ? <Wizard app={current} />
        : <Submitted app={current} />)}
    </Container>
  );
}

function Wizard({ app }: { app: Application }) {
  const { t } = useTranslation();
  const theme = useTheme();
  const mobile = useMediaQuery(theme.breakpoints.down('sm'));
  const quals = useQuery({ queryKey: ['applicant', 'qualifications'], queryFn: () => api<Qualification[]>('/api/applicant/qualifications', { realm: 'applicant' }) });
  const profile = useQuery({ queryKey: ['applicant', 'profile'], queryFn: () => api<Profile>('/api/applicant/profile', { realm: 'applicant' }) });

  const done = [
    !!quals.data?.some((q) => q.verified),
    profile.data ? profile.data.missingFields.length === 0 : false,
    app.feePaid,
    app.choices.length > 0,
    false,
  ];
  const firstOpen = Math.max(0, done.findIndex((d) => !d));
  const [active, setActive] = useState(firstOpen);
  useEffect(() => { setActive(firstOpen); }, [quals.isSuccess, profile.isSuccess]); // jump to first incomplete step on load

  const steps = [
    { label: t('app.steps.results'), body: <ResultsStep onNext={() => setActive(1)} /> },
    { label: t('app.steps.details'), body: <DetailsStep onNext={() => setActive(2)} /> },
    { label: t('app.steps.fee'), body: <FeeStep app={app} onNext={() => setActive(3)} /> },
    { label: t('app.steps.programmes'), body: <ProgrammesStep app={app} onNext={() => setActive(4)} /> },
    { label: t('app.steps.review'), body: <ReviewStep app={app} /> },
  ];

  return (
    <>
      <Stack direction="row" justifyContent="space-between" alignItems="flex-start" sx={{ mb: 3 }} spacing={2}>
        <Box>
          <Typography variant="h5" component="h1">{t('app.title')}</Typography>
          <Typography color="text.secondary">{app.windowName} · {t('home.closes', { date: formatDateTime(app.windowClosesAt) })}</Typography>
        </Box>
        <Chip color={statusColour(app.status)} label={humanise(app.status)} />
      </Stack>
      <Card sx={{ borderRadius: 3 }}>
        <CardContent sx={{ p: { xs: 2, sm: 3 } }}>
          <Stepper nonLinear activeStep={active} orientation={mobile ? 'vertical' : 'horizontal'} alternativeLabel={!mobile}>
            {steps.map((s, i) => (
              <Step key={s.label} completed={done[i]}>
                <StepButton onClick={() => setActive(i)}>{s.label}</StepButton>
                {mobile && <StepContent>{active === i && s.body}</StepContent>}
              </Step>
            ))}
          </Stepper>
          {!mobile && <Box sx={{ mt: 4 }}>{steps[active].body}</Box>}
        </CardContent>
      </Card>
    </>
  );
}

function Submitted({ app }: { app: Application }) {
  const { t } = useTranslation();
  return (
    <Card sx={{ borderRadius: 3, textAlign: 'center' }}>
      <CardContent sx={{ p: { xs: 3, sm: 5 } }}>
        <CheckCircleIcon color="success" sx={{ fontSize: 64 }} />
        {app.status === 'SELECTED' && app.selectedProgramme ? (
          <>
            <Typography variant="h5" sx={{ mt: 1 }}>{t('app.selectedTitle')}</Typography>
            <Typography sx={{ mt: 1, fontWeight: 700 }}>{app.selectedProgramme}</Typography>
            <Typography color="text.secondary" sx={{ mt: 1 }}>{t('app.selectedText')}</Typography>
          </>
        ) : (
          <>
            <Typography variant="h5" sx={{ mt: 1 }}>{t('app.submitted')}</Typography>
            <Typography color="text.secondary" sx={{ mt: 1 }}>{t('app.submittedText')}</Typography>
          </>
        )}
        <Typography variant="overline" display="block" sx={{ mt: 3 }} color="text.secondary">{t('app.reference')}</Typography>
        <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 26 }}>{app.referenceNo}</Typography>
        <Typography variant="body2" color="text.secondary">{formatDateTime(app.submittedAt)}</Typography>
        <Stack spacing={1} sx={{ mt: 3, textAlign: 'left', maxWidth: 480, mx: 'auto' }}>
          {app.choices.map((c) => (
            <Typography key={c.id}><strong>{t('app.choice', { n: c.order })}:</strong> {c.programmeName}</Typography>
          ))}
        </Stack>
        <Chip sx={{ mt: 3 }} color={statusColour(app.status)} label={humanise(app.status)} />
      </CardContent>
    </Card>
  );
}
