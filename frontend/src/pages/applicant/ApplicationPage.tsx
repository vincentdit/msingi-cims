import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import DownloadIcon from '@mui/icons-material/Download';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import SchoolIcon from '@mui/icons-material/School';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Step, StepButton,
  StepContent, Stepper, TextField, Typography, useMediaQuery, useTheme,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, download, formatDate, formatDateTime, formatTzs } from '../../api/client';
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

const AWAITING_NACTVET = ['SELECTED', 'PENDING_VERIFICATION', 'QUERIED'];

function Submitted({ app }: { app: Application }) {
  const { t } = useTranslation();
  const s = app.status;
  const icon = s === 'NACTVET_REJECTED' || s === 'OFFER_LAPSED' || s === 'DECLINED'
    ? <InfoOutlinedIcon color="action" sx={{ fontSize: 64 }} />
    : s === 'OFFERED' ? <SchoolIcon color="primary" sx={{ fontSize: 64 }} /> : <CheckCircleIcon color="success" sx={{ fontSize: 64 }} />;
  // the applicant sees one "awaiting verification" status while the office deals with NACTVET
  const chipLabel = AWAITING_NACTVET.includes(s) ? t('offer.awaitingVerification') : humanise(s);
  return (
    <Card sx={{ borderRadius: 3, textAlign: 'center' }}>
      <CardContent sx={{ p: { xs: 3, sm: 5 } }}>
        {icon}
        {s === 'OFFERED' && app.offer ? <OfferPanel app={app} /> : <Outcome app={app} />}
        <Typography variant="overline" display="block" sx={{ mt: 3 }} color="text.secondary">{t('app.reference')}</Typography>
        <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 26 }}>{app.referenceNo}</Typography>
        <Typography variant="body2" color="text.secondary">{formatDateTime(app.submittedAt)}</Typography>
        <Stack spacing={1} sx={{ mt: 3, textAlign: 'left', maxWidth: 480, mx: 'auto' }}>
          {app.choices.map((c) => (
            <Typography key={c.id}><strong>{t('app.choice', { n: c.order })}:</strong> {c.programmeName}</Typography>
          ))}
        </Stack>
        <Chip sx={{ mt: 3 }} color={AWAITING_NACTVET.includes(s) ? 'success' : statusColour(s)} label={chipLabel} />
      </CardContent>
    </Card>
  );
}

/** The message for every status except an open offer. */
function Outcome({ app }: { app: Application }) {
  const { t } = useTranslation();
  const programme = app.selectedProgramme;
  const block = (title: string, text: string, bold?: string) => (
    <>
      <Typography variant="h5" sx={{ mt: 1 }}>{title}</Typography>
      {bold && <Typography sx={{ mt: 1, fontWeight: 700 }}>{bold}</Typography>}
      <Typography color="text.secondary" sx={{ mt: 1 }}>{text}</Typography>
    </>
  );
  switch (app.status) {
    case 'SELECTED': case 'PENDING_VERIFICATION': case 'QUERIED':
      return block(t('app.selectedTitle'), t('app.selectedText'), programme);
    case 'ACCEPTED':
      return <>
        {block(t('offer.acceptedTitle'), t('offer.acceptedText'), programme)}
        {app.offer?.verificationNo && <Typography variant="body2" sx={{ mt: 2 }}>{t('offer.verificationNo')}: <strong>{app.offer.verificationNo}</strong></Typography>}
        <LetterDownload app={app} />
      </>;
    case 'DECLINED':
      return block(t('offer.declinedTitle'), t('offer.declinedText'), programme);
    case 'OFFER_LAPSED':
      return block(t('offer.lapsedTitle'), t('offer.lapsedText', { programme }));
    case 'NACTVET_REJECTED':
      return <>
        {block(t('offer.rejectedTitle'), t('offer.rejectedText'), programme)}
        {app.offer?.rejectionReason && <Alert severity="warning" sx={{ mt: 2, textAlign: 'left' }}>{app.offer.rejectionReason}</Alert>}
      </>;
    default:
      return block(t('app.submitted'), t('app.submittedText'));
  }
}

/** FR-ADM-13: the admission letter with joining instructions, once issued. */
function LetterDownload({ app }: { app: Application }) {
  const { t } = useTranslation();
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  if (!app.letter) return <Alert severity="info" sx={{ mt: 3, textAlign: 'left' }}>{t('offer.letterPreparing')}</Alert>;
  const get = () => {
    setBusy(true); setError(null);
    download(`/api/applicant/applications/${app.id}/admission-letter`, 'applicant', `admission-letter-${app.letter!.letterNo.replace(/\W+/g, '-')}.pdf`)
      .catch(setError).finally(() => setBusy(false));
  };
  return (
    <Box sx={{ mt: 3 }}>
      <Button variant="contained" size="large" startIcon={<DownloadIcon />} onClick={get} disabled={busy}>{t('offer.downloadLetter')}</Button>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
        {app.letter.letterNo} · {t('offer.letterCode')}: <strong style={{ fontFamily: 'monospace' }}>{app.letter.verificationCode}</strong>
      </Typography>
      <Box sx={{ mt: 1 }}><ErrorAlert error={error} /></Box>
    </Box>
  );
}

/** BP-04 step 5: accept (with NACTVET's confirmation code if admitted elsewhere) or decline before the deadline. */
function OfferPanel({ app }: { app: Application }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const offer = app.offer!;
  const [code, setCode] = useState('');
  const [declining, setDeclining] = useState(false);
  const [reason, setReason] = useState('');
  const done = (a: Application) => {
    qc.setQueryData(['applicant', 'applications'], [a]);
    qc.invalidateQueries({ queryKey: ['applicant'] });
  };
  const accept = useMutation({
    mutationFn: () => api<Application>(`/api/applicant/applications/${app.id}/offer/accept`, { method: 'POST', realm: 'applicant', body: { confirmationCode: code || undefined } }),
    onSuccess: done,
    onError: () => qc.invalidateQueries({ queryKey: ['applicant', 'applications'] }), // refresh attempts left
  });
  const decline = useMutation({
    mutationFn: () => api<Application>(`/api/applicant/applications/${app.id}/offer/decline`, { method: 'POST', realm: 'applicant', body: { reason: reason || undefined } }),
    onSuccess: (a) => { setDeclining(false); done(a); },
  });
  const locked = offer.multipleAdmission && offer.confirmationAttemptsLeft === 0;

  return (
    <Box>
      <Typography variant="h5" sx={{ mt: 1 }}>{t('offer.title')}</Typography>
      <Typography color="text.secondary" sx={{ mt: 1 }}>{t('offer.text')}</Typography>
      <Typography sx={{ mt: 0.5, fontWeight: 700 }}>{app.selectedProgramme}</Typography>
      <Typography variant="body2" sx={{ mt: 1 }}>{t('offer.verificationNo')}: <strong>{offer.verificationNo}</strong></Typography>
      {offer.open
        ? <Chip sx={{ mt: 2 }} color="warning" variant="outlined" label={t('offer.acceptBy', { date: formatDate(offer.expiresAt) })} />
        : <Alert severity="error" sx={{ mt: 2, textAlign: 'left' }}>{t('offer.expiredText')}</Alert>}

      {offer.open && (
        <Box sx={{ maxWidth: 440, mx: 'auto', mt: 3, textAlign: 'left' }}>
          {offer.multipleAdmission && (
            <Alert severity="info" sx={{ mb: 2 }}>
              <strong>{t('offer.multipleTitle')}</strong>
              <Box>{t('offer.multipleText')}</Box>
            </Alert>
          )}
          {offer.multipleAdmission && (
            <TextField label={t('offer.confirmationCode')} fullWidth value={code} disabled={locked}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              inputProps={{ inputMode: 'numeric', autoComplete: 'one-time-code' }}
              helperText={offer.confirmationAttemptsLeft < 5 ? t('offer.attemptsLeft', { n: offer.confirmationAttemptsLeft }) : ' '} sx={{ mb: 1 }} />
          )}
          {offer.devConfirmationCode && <Alert severity="warning" sx={{ mb: 2 }}>{t('offer.devCode', { code: offer.devConfirmationCode })}</Alert>}
          <ErrorAlert error={accept.error ?? decline.error} />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <Button variant="contained" size="large" fullWidth disabled={accept.isPending || locked || (offer.multipleAdmission && code.length !== 6)}
              onClick={() => accept.mutate()}>{t('offer.accept')}</Button>
            <Button variant="outlined" color="inherit" size="large" fullWidth disabled={decline.isPending} onClick={() => setDeclining(true)}>{t('offer.decline')}</Button>
          </Stack>
        </Box>
      )}

      <Dialog open={declining} onClose={() => setDeclining(false)} maxWidth="xs" fullWidth>
        <DialogTitle>{t('offer.declineTitle')}</DialogTitle>
        <DialogContent>
          <Typography sx={{ mb: 2 }}>{t('offer.declineText')}</Typography>
          <TextField label={t('offer.declineReason')} fullWidth multiline minRows={2} value={reason} onChange={(e) => setReason(e.target.value)} inputProps={{ maxLength: 300 }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeclining(false)}>{t('offer.cancel')}</Button>
          <Button color="error" variant="contained" disabled={decline.isPending} onClick={() => decline.mutate()}>{t('offer.confirmDecline')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
