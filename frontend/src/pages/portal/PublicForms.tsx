import { Alert, Box, Button, Card, CardContent, Container, FormControlLabel, Radio, RadioGroup, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useParams } from 'react-router-dom';
import { api, formatDate } from '../../api/client';
import { ErrorAlert, Loading } from '../../components/common';

interface WpaForm {
  institution: string; studentName: string; registrationNo: string; programme: string; organisation: string; unit?: string; startsOn: string; endsOn: string;
  supervisorName?: string; criteria: Record<string, string>; submitted: boolean; expiresAt: string;
}

/** The workplace supervisor's assessment, opened from the personal link (FR-IPT-03). No sign-in. */
export function WorkplaceAssessmentPage() {
  const { t, i18n } = useTranslation();
  const { token } = useParams();
  const q = useQuery({ queryKey: ['wpa', token], retry: false, queryFn: () => api<WpaForm>(`/api/public/ipt-assessment/${token}`) });
  const [ratings, setRatings] = useState<Record<string, number>>({});
  const [comments, setComments] = useState('');
  const [name, setName] = useState('');
  const send = useMutation({ mutationFn: () => api<WpaForm>(`/api/public/ipt-assessment/${token}`, { method: 'POST', body: { ratings, comments, supervisorName: name || q.data?.supervisorName } }) });
  if (q.isLoading) return <Loading />;
  const f = send.data ?? q.data;
  const label = (code: string, fallback: string) => (i18n.exists(`wpa.criteria.${code}`) ? t(`wpa.criteria.${code}`) : fallback);
  return (
    <Container maxWidth="sm" sx={{ py: { xs: 3, md: 5 } }}>
      <Typography variant="h5" component="h1" sx={{ mb: 2 }}>{t('wpa.title')}</Typography>
      <ErrorAlert error={q.error} />
      {f && (
        <Card sx={{ borderRadius: 3 }}><CardContent>
          {send.data ? <Alert severity="success">{t('wpa.thanks', { institution: f.institution })}</Alert>
            : f.submitted ? <Alert severity="info">{t('wpa.done')}</Alert> : (
              <Stack spacing={2.5}>
                <Typography>{t('wpa.intro', { student: f.studentName, regNo: f.registrationNo, programme: f.programme, organisation: f.organisation, from: formatDate(f.startsOn), to: formatDate(f.endsOn) })}</Typography>
                <Typography variant="caption" color="text.secondary">{t('wpa.scale')}</Typography>
                {Object.entries(f.criteria).map(([code, text]) => (
                  <Box key={code}>
                    <Typography sx={{ fontWeight: 600, mb: 0.5 }}>{label(code, text)}</Typography>
                    <ToggleButtonGroup exclusive size="small" value={ratings[code] ?? null} onChange={(_, v) => v && setRatings({ ...ratings, [code]: v })}>
                      {[1, 2, 3, 4, 5].map((n) => <ToggleButton key={n} value={n} sx={{ width: 48 }} aria-label={`${n}`}>{n}</ToggleButton>)}
                    </ToggleButtonGroup>
                  </Box>
                ))}
                <TextField label={t('wpa.comments')} multiline minRows={3} value={comments} onChange={(e) => setComments(e.target.value)} />
                <TextField label={t('wpa.yourName')} value={name || f.supervisorName || ''} onChange={(e) => setName(e.target.value)} />
                <ErrorAlert error={send.error} />
                <Button variant="contained" size="large" disabled={Object.keys(ratings).length < Object.keys(f.criteria).length || !(name || f.supervisorName) || send.isPending}
                  onClick={() => send.mutate()}>{t('wpa.send')}</Button>
              </Stack>
            )}
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 2 }}>{f.institution}</Typography>
        </CardContent></Card>
      )}
    </Container>
  );
}

interface Survey {
  institution: string; title: string; intro?: string; graduateName: string; programme: string; submitted: boolean; open: boolean;
  questions: { code: string; text: string; type: 'CHOICE' | 'TEXT' | 'RATING'; options: string[]; required: boolean }[];
}

/** A graduate tracer survey, opened from the personal link (FR-GRD-05). No sign-in. */
export function TracerSurveyPage() {
  const { t, i18n } = useTranslation();
  const { token } = useParams();
  const q = useQuery({ queryKey: ['tracer', token], retry: false, queryFn: () => api<Survey>(`/api/public/tracer/${token}`) });
  const [a, setA] = useState<Record<string, string>>({});
  const send = useMutation({ mutationFn: () => api<Survey>(`/api/public/tracer/${token}`, { method: 'POST', body: a }) });
  if (q.isLoading) return <Loading />;
  const s = send.data ?? q.data;
  const qt = (code: string, text: string) => (i18n.exists(`tracer.q.${code}`) ? t(`tracer.q.${code}`) : text);
  const ot = (o: string) => (i18n.exists(`tracer.o.${o}`) ? t(`tracer.o.${o}`) : o);
  return (
    <Container maxWidth="sm" sx={{ py: { xs: 3, md: 5 } }}>
      <ErrorAlert error={q.error} />
      {s && (
        <Card sx={{ borderRadius: 3 }}><CardContent>
          <Typography variant="h5" component="h1">{s.title}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{s.institution}</Typography>
          {send.data ? <Alert severity="success">{t('tracer.thanks')}</Alert> : s.submitted ? <Alert severity="info">{t('tracer.done')}</Alert>
            : !s.open ? <Alert severity="info">{t('tracer.closed')}</Alert> : (
              <Stack spacing={2.5}>
                <Typography>{s.intro || t('tracer.intro', { name: s.graduateName, programme: s.programme })}</Typography>
                {s.questions.map((x) => (
                  <Box key={x.code}>
                    <Typography sx={{ fontWeight: 600, mb: 0.5 }}>{qt(x.code, x.text)}{x.required ? ' *' : ''}</Typography>
                    {x.type === 'TEXT' ? <TextField fullWidth size="small" value={a[x.code] ?? ''} onChange={(e) => setA({ ...a, [x.code]: e.target.value })} />
                      : x.type === 'RATING' ? (
                        <ToggleButtonGroup exclusive size="small" value={a[x.code] ?? null} onChange={(_, v) => v && setA({ ...a, [x.code]: v })}>
                          {x.options.map((o) => <ToggleButton key={o} value={o} sx={{ width: 48 }}>{o}</ToggleButton>)}
                        </ToggleButtonGroup>
                      ) : (
                        <RadioGroup value={a[x.code] ?? ''} onChange={(e) => setA({ ...a, [x.code]: e.target.value })}>
                          {x.options.map((o) => <FormControlLabel key={o} value={o} control={<Radio size="small" />} label={ot(o)} />)}
                        </RadioGroup>
                      )}
                  </Box>
                ))}
                <ErrorAlert error={send.error} />
                <Button variant="contained" size="large" disabled={s.questions.some((x) => x.required && !a[x.code]) || send.isPending} onClick={() => send.mutate()}>{t('tracer.send')}</Button>
              </Stack>
            )}
        </CardContent></Card>
      )}
    </Container>
  );
}
