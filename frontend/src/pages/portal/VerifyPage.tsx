import CancelIcon from '@mui/icons-material/Cancel';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline';
import VerifiedIcon from '@mui/icons-material/Verified';
import { Alert, Box, Button, Card, CardContent, Container, Stack, TextField, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router-dom';
import { ApiError, api, formatDate } from '../../api/client';
import type { LetterVerification } from '../../api/types';
import { ErrorAlert, Loading } from '../../components/common';

/** Public verification of admission letters by code or QR (BP-16). No sign-in. */
export default function VerifyPage() {
  const { t } = useTranslation();
  const { code } = useParams();
  const navigate = useNavigate();
  const [input, setInput] = useState(code ?? '');
  const result = useQuery({
    queryKey: ['verify', code], enabled: !!code, retry: false,
    queryFn: () => api<LetterVerification>(`/api/public/verify/${encodeURIComponent(code!)}`),
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    const c = input.trim();
    if (c) navigate(`/verify/${encodeURIComponent(c)}`);
  };
  const notFound = result.error instanceof ApiError && result.error.problem.status === 404;

  return (
    <Container maxWidth="sm" sx={{ py: { xs: 3, md: 6 } }}>
      <Typography variant="h5" component="h1">{t('verify.title')}</Typography>
      <Typography color="text.secondary" sx={{ mt: 1, mb: 3 }}>{t('verify.intro')}</Typography>
      <Box component="form" onSubmit={submit}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
          <TextField label={t('verify.code')} placeholder="XXXX-XXXX-XXXX-XXXX" fullWidth value={input} onChange={(e) => setInput(e.target.value)}
            inputProps={{ autoCapitalize: 'characters', spellCheck: false, style: { fontFamily: 'monospace', letterSpacing: 1 } }} />
          <Button type="submit" variant="contained" size="large" disabled={!input.trim()}>{t('verify.check')}</Button>
        </Stack>
      </Box>

      <Box sx={{ mt: 3 }}>
        {result.isLoading && <Loading />}
        {notFound && <Alert severity="error" icon={<ErrorOutlineIcon />}>{t('verify.notFound')}</Alert>}
        {result.error && !notFound && <ErrorAlert error={result.error} />}
        {result.data && <Result v={result.data} />}
      </Box>
    </Container>
  );
}

function Result({ v }: { v: LetterVerification }) {
  const { t } = useTranslation();
  const tone = v.status === 'VALID' ? 'success' : v.status === 'REVOKED' ? 'error' : 'warning';
  const icon = v.status === 'VALID' ? <VerifiedIcon color="success" sx={{ fontSize: 40 }} /> : v.status === 'REVOKED'
    ? <CancelIcon color="error" sx={{ fontSize: 40 }} /> : <ErrorOutlineIcon color="warning" sx={{ fontSize: 40 }} />;
  const title = v.status === 'VALID' ? t('verify.valid') : v.status === 'REVOKED' ? t('verify.revoked') : t('verify.superseded');
  const text = v.status === 'VALID' ? t('verify.validText') : v.status === 'REVOKED' ? t('verify.revokedText')
    : t('verify.supersededText', { n: v.currentEdition ?? '' });
  const rows: [string, string | undefined][] = [
    [t('verify.holder'), v.holderName],
    [t('verify.programme'), v.programme],
    [t('verify.award'), v.award],
    [t('verify.intake'), `${v.academicYear}, ${v.intake}`],
    [t('verify.reporting'), formatDate(v.reportingDate)],
    [t('verify.letterNo'), v.edition > 1 ? `${v.letterNo} (${t('verify.edition', { n: v.edition })})` : v.letterNo],
    [t('verify.issued'), formatDate(v.issuedAt)],
    [t('verify.nactvet'), v.nactvetVerificationNo],
    [t('verify.institution'), v.institutionNactvetNo ? `${v.institution} (${v.institutionNactvetNo})` : v.institution],
  ];
  return (
    <Card sx={{ borderRadius: 3, borderTop: 4, borderColor: `${tone}.main` }}>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Stack direction="row" spacing={1.5} alignItems="center" sx={{ mb: 1 }}>
          {icon}
          <Typography variant="h6" color={`${tone}.main`}>{title}</Typography>
        </Stack>
        <Typography color="text.secondary" sx={{ mb: 2 }}>{text}</Typography>
        <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: { xs: '1fr', sm: '42% 58%' }, rowGap: 1 }}>
          {rows.filter(([, val]) => val).map(([label, val]) => (
            <Box key={label} sx={{ display: 'contents' }}>
              <Typography component="dt" variant="body2" color="text.secondary">{label}</Typography>
              <Typography component="dd" sx={{ m: 0, fontWeight: 600, mb: { xs: 1, sm: 0 } }}>{val}</Typography>
            </Box>
          ))}
        </Box>
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 2 }}>{t('verify.privacy')}</Typography>
      </CardContent>
    </Card>
  );
}
