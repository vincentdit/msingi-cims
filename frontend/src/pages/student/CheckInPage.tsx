import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import QrIcon from '@mui/icons-material/QrCode2';
import { Alert, Box, Button, Card, CardContent, Container, Stack, TextField, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink, useSearchParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { CheckInDone } from '../../api/types';
import { ErrorAlert } from '../../components/common';

const DEVICE = 'cims.device';

/** A random id kept on this phone, so the lecturer can see when one phone checks in several students. */
function deviceId(): string | undefined {
  try {
    let id = localStorage.getItem(DEVICE);
    if (!id) {
      id = typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
      localStorage.setItem(DEVICE, id);
    }
    return id;
  } catch {
    return undefined;
  }
}

/** Self check-in (FR-TCH-03): the student types the code on the lecturer's screen, or arrives here from its QR code. */
export default function CheckInPage() {
  const { t } = useTranslation();
  const [params, setParams] = useSearchParams();
  const [code, setCode] = useState((params.get('code') ?? '').replace(/\D/g, '').slice(0, 6));
  const tried = useRef(false);
  const go = useMutation({
    mutationFn: (c: string) => api<CheckInDone>('/api/student/check-in', { realm: 'student', method: 'POST', body: { code: c, deviceId: deviceId() } }),
  });
  useEffect(() => {
    // arrived from the QR code: check in straight away, once
    if (!tried.current && code.length === 6 && params.get('code')) {
      tried.current = true;
      go.mutate(code);
      setParams({}, { replace: true });
    }
  }, [code, params, setParams, go]);
  const d = go.data;
  const time = d ? new Date(d.checkedInAt).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' }) : '';

  return (
    <Container maxWidth="sm" sx={{ pt: 3 }}>
      <Card sx={{ borderRadius: 3 }}>
        <CardContent>
          {d ? (
            <Stack spacing={2} alignItems="center" sx={{ textAlign: 'center', py: 2 }}>
              <CheckCircleIcon color={d.status === 'LATE' ? 'info' : 'success'} sx={{ fontSize: 72 }} />
              <Typography variant="h5" data-testid="checkin-done">
                {t(d.already ? 'checkIn.already' : d.status === 'LATE' ? 'checkIn.doneLate' : 'checkIn.done', { module: d.moduleCode })}
              </Typography>
              <Typography color="text.secondary">{d.moduleTitle} · {t('checkIn.at', { time })}</Typography>
              <Stack direction="row" spacing={1}>
                <Button onClick={() => { go.reset(); setCode(''); }}>{t('checkIn.another')}</Button>
                <Button variant="contained" component={RouterLink} to="/student">{t('checkIn.back')}</Button>
              </Stack>
            </Stack>
          ) : (
            <Box component="form" onSubmit={(e) => { e.preventDefault(); if (code.length === 6) go.mutate(code); }}>
              <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
                <QrIcon color="primary" />
                <Typography variant="h6">{t('checkIn.title')}</Typography>
              </Stack>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{t('checkIn.intro')}</Typography>
              <ErrorAlert error={go.error} />
              <TextField fullWidth autoFocus label={t('checkIn.code')} value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                slotProps={{ htmlInput: { inputMode: 'numeric', pattern: '[0-9]*', autoComplete: 'one-time-code', 'aria-label': t('checkIn.code'),
                  style: { fontSize: 32, letterSpacing: 12, textAlign: 'center', fontFamily: 'ui-monospace, Menlo, Consolas, monospace' } } }} />
              <Button type="submit" fullWidth size="large" variant="contained" sx={{ mt: 2 }} disabled={code.length !== 6 || go.isPending}>
                {go.isPending ? t('checkIn.checking') : t('checkIn.submit')}
              </Button>
              <Alert severity="info" sx={{ mt: 2 }}>{t('checkIn.changes')}</Alert>
            </Box>
          )}
        </CardContent>
      </Card>
    </Container>
  );
}
