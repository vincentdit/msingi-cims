import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import CheckCircleIcon from '@mui/icons-material/CheckCircleOutline';
import EventIcon from '@mui/icons-material/Event';
import ShareIcon from '@mui/icons-material/Share';
import StarIcon from '@mui/icons-material/AutoAwesome';
import { Alert, Box, Button, Card, CardContent, Chip, Container, Divider, Snackbar, Stack, Typography } from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { api, formatDate, formatTzs } from '../../api/client';
import type { ProgrammeAdvert } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading } from '../../components/common';
import { useInstitution } from '../../components/InstitutionContext';
import { setLanguage } from '../../i18n';
import { rememberChoice } from './applyFor';

/**
 * A programme's own page, which the links shared on Facebook and WhatsApp open: the year's fee and its instalments, the entry
 * requirements in the language on screen, the selling points, the dates, and Apply now (which keeps the programme ready to choose).
 */
export default function ProgrammePage() {
  const { code = '' } = useParams();
  const [params] = useSearchParams();
  const { t, i18n } = useTranslation();
  const inst = useInstitution();
  const navigate = useNavigate();
  const { user } = useSession();
  const [copied, setCopied] = useState(false);
  const lang = params.get('lang');

  useEffect(() => {
    if ((lang === 'sw' || lang === 'en') && i18n.language !== lang) void setLanguage(lang);
  }, [lang]);

  const q = useQuery({ queryKey: ['public-programme', code], retry: false,
    queryFn: () => api<ProgrammeAdvert>(`/api/public/programmes/${encodeURIComponent(code)}`) });
  const a = q.data;
  const sw = i18n.language === 'sw';

  useEffect(() => {
    if (a) document.title = `${a.name} — ${inst.name}`;
  }, [a, inst.name]);

  if (q.isLoading) return <Container sx={{ py: 6 }}><Loading /></Container>;
  if (!a) {
    return (
      <Container maxWidth="md" sx={{ py: 6 }}>
        {(q.error as { status?: number } | null)?.status === 404 || q.isError
          ? <Alert severity="info" action={<Button component={RouterLink} to="/">{t('programme.back')}</Button>}>{t('programme.notFound')}</Alert>
          : <ErrorAlert error={q.error} onRetry={() => q.refetch()} />}
      </Container>
    );
  }

  const routes = sw ? a.routesSw : a.routesEn;
  const years = a.durationSemesters % 2 === 0 ? a.durationSemesters / 2 : null;
  const share = `${window.location.origin}${a.path}${sw ? '?lang=sw' : ''}`;
  const apply = () => {
    rememberChoice({ code: a.code, windowProgrammeId: a.windowProgrammeId, windowId: a.windowId });
    navigate(user('applicant') ? '/application' : '/register');
  };
  const doShare = async () => {
    const nav = window.navigator as Navigator & { share?: (d: ShareData) => Promise<void> };
    if (nav.share) {
      try {
        await nav.share({ title: a.name, url: share });
        return;
      } catch {
        /* cancelled: copy instead */
      }
    }
    try {
      await nav.clipboard.writeText(share);
      setCopied(true);
    } catch {
      /* clipboard unavailable */
    }
  };

  return (
    <>
      <Box sx={{ bgcolor: 'primary.dark', color: 'primary.contrastText', py: { xs: 4, md: 6 } }}>
        <Container maxWidth="lg">
          <Button component={RouterLink} to="/" startIcon={<ArrowBackIcon />} sx={{ color: 'inherit', opacity: 0.85, mb: 2, ml: -1 }}>{t('programme.back')}</Button>
          <Stack direction="row" spacing={1} sx={{ mb: 1.5 }} flexWrap="wrap" useFlexGap>
            <Chip size="small" color="warning" label={a.awardFamily === 'NTA' ? `NTA ${a.awardLevel}` : `NVA ${'I'.repeat(a.awardLevel)}`} sx={{ color: '#0F1B2D', fontWeight: 700 }} />
            <Chip size="small" icon={<EventIcon />} label={a.open ? `${t('programme.closes')}: ${formatDate(a.closesAt)}` : t('programme.notOpenYet', { date: formatDate(a.opensAt) })}
              sx={{ bgcolor: 'rgba(255,255,255,0.16)', color: 'inherit', '& .MuiChip-icon': { color: 'inherit' } }} />
          </Stack>
          <Typography variant="h3" component="h1" sx={{ fontSize: { xs: 28, md: 42 }, maxWidth: 820 }}>{a.name}</Typography>
          <Typography sx={{ mt: 1, opacity: 0.9, fontSize: 17 }}>{a.awardName} · {a.department}</Typography>
          <Box sx={{ mt: 3 }}>
            {a.fee?.total ? (
              <>
                <Typography sx={{ fontSize: { xs: 22, md: 28 }, fontWeight: 800 }}>{t('programme.feeYear', { amount: formatTzs(a.fee.total) })}</Typography>
                {a.fee.instalments > 1 && <Typography sx={{ opacity: 0.9 }}>
                  {t('programme.instalments', { n: a.fee.instalments })}{a.fee.firstInstalment ? ` — ${t('programme.firstInstalment', { amount: formatTzs(a.fee.firstInstalment) })}` : ''}
                </Typography>}
              </>
            ) : <Typography sx={{ opacity: 0.9 }}>{t('programme.noFee')}</Typography>}
          </Box>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ mt: 3.5 }}>
            <Button size="large" variant="contained" color="warning" onClick={apply} disabled={!a.open} sx={{ color: '#0F1B2D', py: 1.3 }}>{t('programme.apply')}</Button>
            <Button size="large" variant="outlined" startIcon={<ShareIcon />} onClick={doShare} sx={{ color: 'inherit', borderColor: 'rgba(255,255,255,0.5)' }}>{t('programme.share')}</Button>
          </Stack>
          {a.open && <Typography variant="body2" sx={{ mt: 1.5, opacity: 0.8, maxWidth: 560 }}>{t('programme.applyNote')}</Typography>}
        </Container>
      </Box>

      <Container maxWidth="lg" sx={{ py: 5 }}>
        <Grid container spacing={4}>
          <Grid size={{ xs: 12, md: 7 }}>
            <Typography variant="h6" sx={{ mb: 1.5 }}>{t('programme.requirements')}</Typography>
            <Card variant="outlined">
              <CardContent>
                {routes.length === 0 ? <Typography>{a.requirementSummary || t('programme.openEntry')}</Typography> : (
                  <Stack spacing={1.5} divider={<Divider flexItem><Typography variant="caption" color="text.secondary">{t('programme.or')}</Typography></Divider>}>
                    {routes.map((r, i) => (
                      <Stack key={i} direction="row" spacing={1.25} alignItems="flex-start">
                        <CheckCircleIcon color="secondary" fontSize="small" sx={{ mt: '3px' }} />
                        <Typography>{r.charAt(0).toUpperCase() + r.slice(1)}</Typography>
                      </Stack>
                    ))}
                  </Stack>
                )}
              </CardContent>
            </Card>
            {a.highlights.length > 0 && (
              <>
                <Typography variant="h6" sx={{ mt: 4, mb: 1.5 }}>{t('programme.why')}</Typography>
                <Stack spacing={1}>
                  {a.highlights.map((h) => (
                    <Stack key={h} direction="row" spacing={1.25} alignItems="center">
                      <StarIcon color="warning" fontSize="small" />
                      <Typography>{h}</Typography>
                    </Stack>
                  ))}
                </Stack>
              </>
            )}
          </Grid>
          <Grid size={{ xs: 12, md: 5 }}>
            <Card variant="outlined">
              <CardContent>
                <Stack spacing={1.5}>
                  {([
                    [t('programme.duration'), years ? t('programme.years', { count: years }) : t('programme.semesters', { n: a.durationSemesters })],
                    [t('programme.places'), String(a.capacity)],
                    [t('programme.intake'), `${a.intake} ${a.academicYear}`],
                    [t('programme.department'), a.department],
                    [t('programme.applicationFee'), formatTzs(a.applicationFee)],
                    [t('programme.opens'), formatDate(a.opensAt)],
                    [t('programme.closes'), formatDate(a.closesAt)],
                  ] as const).map(([k, v]) => (
                    <Stack key={k} direction="row" justifyContent="space-between" spacing={2}>
                      <Typography color="text.secondary">{k}</Typography>
                      <Typography sx={{ fontWeight: 600, textAlign: 'right' }}>{v}</Typography>
                    </Stack>
                  ))}
                </Stack>
                <Button fullWidth size="large" variant="contained" sx={{ mt: 3 }} onClick={apply} disabled={!a.open}>{t('programme.apply')}</Button>
              </CardContent>
            </Card>
          </Grid>
        </Grid>
      </Container>
      <Snackbar open={copied} autoHideDuration={2500} onClose={() => setCopied(false)} message={t('programme.copied')} />
    </>
  );
}
