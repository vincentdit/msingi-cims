import CheckCircleIcon from '@mui/icons-material/CheckCircleOutline';
import DoneIcon from '@mui/icons-material/Check';
import PlaceIcon from '@mui/icons-material/PlaceOutlined';
import EventIcon from '@mui/icons-material/Event';
import PaymentsIcon from '@mui/icons-material/Payments';
import { Alert, Box, Button, Card, CardContent, Chip, Container, Stack, Typography, useTheme } from '@mui/material';
import Grid from '@mui/material/Grid2';
import { alpha } from '@mui/material/styles';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink } from 'react-router-dom';
import { api, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type { AdmissionWindow, Application, Profile, Qualification } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading } from '../../components/common';
import { CampusIllustration } from '../../components/CampusIllustration';
import { useInstitution } from '../../components/InstitutionContext';
import { usePublicImage } from '../../components/usePublicImage';

/** A woven-mat (mkeka) texture laid over the banner. */
const MKEKA = `url("data:image/svg+xml,${encodeURIComponent("<svg xmlns='http://www.w3.org/2000/svg' width='32' height='32'><path d='M0 16L16 0L32 16L16 32Z' fill='none' stroke='white' stroke-opacity='0.07' stroke-width='1.5'/><path d='M8 16L16 8L24 16L16 24Z' fill='white' fill-opacity='0.025'/></svg>")}")`;

export default function HomePage() {
  const inst = useInstitution();
  const { t } = useTranslation();
  const { user } = useSession();
  const q = useQuery({ queryKey: ['public-windows'], queryFn: () => api<AdmissionWindow[]>('/api/public/windows') });
  const window = q.data?.[0];
  const upcoming = useQuery({ queryKey: ['public-windows', 'upcoming'], enabled: q.isSuccess && !window,
    queryFn: () => api<AdmissionWindow[]>('/api/public/windows/upcoming') }).data?.[0];
  const signedIn = !!user('applicant');
  const cta = signedIn ? '/application' : '/register';
  const theme = useTheme();
  const photo = usePublicImage(inst.campusPhoto?.url);
  const caption = inst.campusPhoto?.caption ?? inst.name;
  const chip = { bgcolor: 'rgba(255,255,255,0.14)', color: 'inherit', '& .MuiChip-icon': { color: 'inherit' } };

  // the applicant's own progress, when signed in
  const realm = { realm: 'applicant' as const };
  const apps = useQuery({ queryKey: ['applicant', 'applications'], enabled: signedIn, queryFn: () => api<Application[]>('/api/applicant/applications', realm) });
  const profile = useQuery({ queryKey: ['applicant', 'profile'], enabled: signedIn, queryFn: () => api<Profile>('/api/applicant/profile', realm) });
  const quals = useQuery({ queryKey: ['applicant', 'qualifications'], enabled: signedIn, queryFn: () => api<Qualification[]>('/api/applicant/qualifications', realm) });
  const mine = window ? apps.data?.find((a) => a.windowId === window.id) : undefined;
  const submitted = !!mine && !['DRAFT', 'PAYMENT_PENDING'].includes(mine.status);
  const done = signedIn ? [
    true,
    true,
    !!profile.data && profile.data.missingFields.length === 0,
    !!mine?.feePaid,
    !!mine?.feePaid && (!!quals.data?.some((x) => !x.registration) || mine.choices.length > 0),
    submitted,
  ] : [];

  return (
    <>
      <Box sx={{ bgcolor: 'primary.main', color: 'primary.contrastText', position: 'relative', overflow: 'hidden' }}>
        {photo && (
          <Box component="img" src={photo} alt="" aria-hidden sx={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'cover',
            filter: 'grayscale(1) blur(3px)', opacity: 0.22, transform: 'scale(1.05)' }} />
        )}
        <Box sx={{ position: 'absolute', inset: 0, background: (th) => `linear-gradient(100deg, ${th.palette.primary.dark} 0%, ${th.palette.primary.main} 55%, ${alpha(th.palette.primary.main, 0.75)} 100%)`, opacity: photo ? 0.9 : 1 }} />
        <Box sx={{ position: 'absolute', inset: 0, backgroundImage: MKEKA }} />
        <Container maxWidth="lg" sx={{ position: 'relative', py: { xs: 5, md: 8 } }}>
          <Grid container spacing={{ xs: 4, md: 6 }} alignItems="center">
            <Grid size={{ xs: 12, md: 7 }}>
              <Typography sx={{ opacity: 0.85, fontWeight: 600, fontSize: 15 }}>{inst.branding.tagline ?? inst.name}</Typography>
              <Typography variant="h3" component="h1" sx={{ mt: 1, maxWidth: 720, fontSize: { xs: 30, md: 44 } }}>
                {t('home.heroTitle', { year: window?.academicYear ?? '' })}
              </Typography>
              <Typography sx={{ mt: 2, maxWidth: 560, opacity: 0.9, fontSize: 18 }}>{t('home.heroText')}</Typography>
              {window ? (
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mt: 4 }} alignItems={{ sm: 'center' }}>
                  <Button size="large" variant="contained" color="warning" component={RouterLink} to={cta} sx={{ color: '#0F1B2D', py: 1.4, flexShrink: 0 }}>
                    {submitted ? t('home.viewApplication') : mine ? t('home.continueApplication') : t('home.startApplication')}
                  </Button>
                  <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                    <Chip icon={<EventIcon />} label={t('home.closes', { date: formatDate(window.closesAt) })} sx={chip} />
                    <Chip icon={<PaymentsIcon />} label={`${t('home.fee')}: ${formatTzs(window.applicationFee)}`} sx={chip} />
                  </Stack>
                </Stack>
              ) : (
                q.isSuccess && <Alert severity="info" sx={{ mt: 4, maxWidth: 560 }}>
                  {upcoming ? t('home.opensOn', { window: upcoming.name, date: formatDateTime(upcoming.opensAt) }) : t('home.noWindow')}
                </Alert>
              )}
            </Grid>
            <Grid size={{ xs: 12, md: 5 }}>
              <Box component="figure" sx={{ m: 0, position: 'relative', borderRadius: 3, overflow: 'hidden', aspectRatio: { xs: '16 / 9', md: '4 / 3' },
                border: '6px solid rgba(255,255,255,0.92)', boxShadow: '0 24px 48px -16px rgba(0,0,0,0.45)', transform: { md: 'rotate(1.2deg)' }, bgcolor: 'primary.dark' }}>
                {photo
                  ? <Box component="img" src={photo} alt={caption} sx={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
                  : <CampusIllustration primary={theme.palette.primary.main} accent={theme.palette.warning.main} name={inst.name} />}
                <Box component="figcaption" sx={{ position: 'absolute', left: 0, right: 0, bottom: 0, px: 2, py: 1.25, display: 'flex', alignItems: 'center', gap: 0.75,
                  color: '#fff', fontSize: 14, fontWeight: 600, background: 'linear-gradient(to top, rgba(10,20,35,0.78), rgba(10,20,35,0))' }}>
                  <PlaceIcon sx={{ fontSize: 18 }} />{caption}
                </Box>
              </Box>
            </Grid>
          </Grid>
        </Container>
      </Box>

      <Container maxWidth="lg" sx={{ py: 6 }}>
        {q.isLoading && <Loading />}
        <ErrorAlert error={q.error} onRetry={() => q.refetch()} />
        {window && (
          <Grid container spacing={4}>
            <Grid size={{ xs: 12, md: 4 }}>
              <Typography variant="h6" sx={{ mb: 2 }}>{signedIn ? t('home.progressTitle') : t('home.howTitle')}</Typography>
              <Box component="ol" sx={{ listStyle: 'none', p: 0, m: 0, display: 'grid', gap: 2 }}>
                {[t('home.how1'), t('home.how2'), t('home.how3'), t('home.how4', { fee: formatTzs(window.applicationFee) }), t('home.how5'),
                  t('home.how6', { n: window.maxChoices })].map((step, i) => (
                  <Stack component="li" key={i} direction="row" spacing={1.5} alignItems="flex-start">
                    <Box sx={{ width: 28, height: 28, borderRadius: '50%', display: 'grid', placeItems: 'center', fontWeight: 700, flexShrink: 0, fontSize: 14,
                      ...(done[i] ? { bgcolor: 'success.main', color: '#fff' } : { bgcolor: 'secondary.main', color: '#fff' }) }}>
                      {done[i] ? <DoneIcon sx={{ fontSize: 18 }} aria-label={t('home.stepDone')} /> : i + 1}
                    </Box>
                    <Typography sx={{ pt: 0.3, color: done[i] ? 'text.secondary' : 'text.primary' }}>{step}</Typography>
                  </Stack>
                ))}
              </Box>
              {submitted && mine && <Alert severity="success" sx={{ mt: 3 }}>{t('home.submittedNote', { ref: mine.referenceNo, date: formatDateTime(window.closesAt) })}</Alert>}
              <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>{t('home.levelsNote')}</Typography>
              {!submitted && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>{t('home.changeNote', { date: formatDateTime(window.closesAt) })}</Typography>}
              {window.instructions && <Alert severity="info" sx={{ mt: 3 }}>{window.instructions}</Alert>}
            </Grid>
            <Grid size={{ xs: 12, md: 8 }}>
              <Typography variant="h6" sx={{ mb: 2 }}>{t('home.programmes')}</Typography>
              <Stack spacing={1.5}>
                {window.programmes?.map((p) => (
                  <Card key={p.id}>
                    <CardContent>
                      <Stack direction="row" justifyContent="space-between" spacing={2} alignItems="flex-start">
                        <Box>
                          <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{p.programmeName}</Typography>
                          <Typography variant="body2" color="text.secondary">{p.awardName} · {p.department}</Typography>
                        </Box>
                        <Chip size="small" color={p.awardFamily === 'NTA' ? 'primary' : 'secondary'} label={p.awardFamily === 'NTA' ? `NTA ${p.awardLevel}` : `NVA ${'I'.repeat(p.awardLevel)}`} />
                      </Stack>
                      <Stack direction="row" spacing={1} alignItems="flex-start" sx={{ mt: 1.5 }}>
                        <CheckCircleIcon fontSize="small" color="secondary" sx={{ mt: '2px' }} />
                        <Typography variant="body2"><strong>{t('home.requirements')}:</strong> {p.requirementSummary}</Typography>
                      </Stack>
                      <Typography variant="caption" color="text.secondary">{t('home.places', { n: p.capacity })}</Typography>
                    </CardContent>
                  </Card>
                ))}
              </Stack>
            </Grid>
          </Grid>
        )}
      </Container>
    </>
  );
}
