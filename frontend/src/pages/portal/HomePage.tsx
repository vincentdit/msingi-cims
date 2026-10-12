import CheckCircleIcon from '@mui/icons-material/CheckCircleOutline';
import EventIcon from '@mui/icons-material/Event';
import PaymentsIcon from '@mui/icons-material/Payments';
import { Alert, Box, Button, Card, CardContent, Chip, Container, Stack, Typography } from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink } from 'react-router-dom';
import { api, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type { AdmissionWindow } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading } from '../../components/common';
import { useInstitution } from '../../components/InstitutionContext';

export default function HomePage() {
  const inst = useInstitution();
  const { t } = useTranslation();
  const { user } = useSession();
  const q = useQuery({ queryKey: ['public-windows'], queryFn: () => api<AdmissionWindow[]>('/api/public/windows') });
  const window = q.data?.[0];
  const upcoming = useQuery({ queryKey: ['public-windows', 'upcoming'], enabled: q.isSuccess && !window,
    queryFn: () => api<AdmissionWindow[]>('/api/public/windows/upcoming') }).data?.[0];
  const cta = user('applicant') ? '/application' : '/register';

  return (
    <>
      <Box sx={{ bgcolor: 'primary.main', color: 'primary.contrastText', py: { xs: 6, md: 9 }, position: 'relative', overflow: 'hidden' }}>
        <Box sx={{ position: 'absolute', right: -80, top: -80, width: 320, height: 320, borderRadius: '50%', bgcolor: 'rgba(255,255,255,0.06)' }} />
        <Container maxWidth="lg" sx={{ position: 'relative' }}>
          <Typography variant="overline" sx={{ opacity: 0.85, letterSpacing: 2 }}>{inst.branding.tagline ?? inst.name}</Typography>
          <Typography variant="h3" component="h1" sx={{ mt: 1, maxWidth: 720, fontSize: { xs: 30, md: 44 } }}>
            {t('home.heroTitle', { year: window?.academicYear ?? '' })}
          </Typography>
          <Typography sx={{ mt: 2, maxWidth: 620, opacity: 0.9, fontSize: 18 }}>{t('home.heroText')}</Typography>
          {window ? (
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mt: 4 }} alignItems={{ sm: 'center' }}>
              <Button size="large" variant="contained" color="warning" component={RouterLink} to={cta} sx={{ color: '#0F1B2D', py: 1.4 }}>
                {t('home.startApplication')}
              </Button>
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                <Chip icon={<EventIcon />} label={t('home.closes', { date: formatDate(window.closesAt) })} sx={{ bgcolor: 'rgba(255,255,255,0.14)', color: 'inherit', '& .MuiChip-icon': { color: 'inherit' } }} />
                <Chip icon={<PaymentsIcon />} label={`${t('home.fee')}: ${formatTzs(window.applicationFee)}`} sx={{ bgcolor: 'rgba(255,255,255,0.14)', color: 'inherit', '& .MuiChip-icon': { color: 'inherit' } }} />
              </Stack>
            </Stack>
          ) : (
            q.isSuccess && <Alert severity="info" sx={{ mt: 4, maxWidth: 560 }}>
              {upcoming ? t('home.opensOn', { window: upcoming.name, date: formatDateTime(upcoming.opensAt) }) : t('home.noWindow')}
            </Alert>
          )}
        </Container>
      </Box>

      <Container maxWidth="lg" sx={{ py: 6 }}>
        {q.isLoading && <Loading />}
        <ErrorAlert error={q.error} onRetry={() => q.refetch()} />
        {window && (
          <Grid container spacing={4}>
            <Grid size={{ xs: 12, md: 4 }}>
              <Typography variant="h6" sx={{ mb: 2 }}>{t('home.howTitle')}</Typography>
              <Stack spacing={2}>
                {[t('home.how1'), t('home.how2'), t('home.how3'), t('home.how4', { n: window.maxChoices })].map((s, i) => (
                  <Stack key={i} direction="row" spacing={1.5} alignItems="flex-start">
                    <Box sx={{ width: 28, height: 28, borderRadius: '50%', bgcolor: 'secondary.main', color: '#fff', display: 'grid', placeItems: 'center', fontWeight: 700, flexShrink: 0, fontSize: 14 }}>{i + 1}</Box>
                    <Typography sx={{ pt: 0.3 }}>{s}</Typography>
                  </Stack>
                ))}
              </Stack>
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
