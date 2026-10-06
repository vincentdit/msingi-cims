import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import CancelIcon from '@mui/icons-material/Cancel';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Collapse, Divider,
  FormControlLabel, IconButton, MenuItem, Stack, Switch, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, formatDateTime, formatTzs } from '../../api/client';
import type { Application, EligibilityResult, Fee, Profile, ProgrammeEligibility, Qualification } from '../../api/types';
import { ErrorAlert, Loading } from '../../components/common';

const SHOW_DEV_TOOLS = import.meta.env.DEV || import.meta.env.VITE_SHOW_DEV_TOOLS === 'true';
const REGIONS = ['Arusha', 'Dar es Salaam', 'Dodoma', 'Geita', 'Iringa', 'Kagera', 'Katavi', 'Kigoma', 'Kilimanjaro', 'Lindi', 'Manyara',
  'Mara', 'Mbeya', 'Morogoro', 'Mtwara', 'Mwanza', 'Njombe', 'Pemba North', 'Pemba South', 'Pwani', 'Rukwa', 'Ruvuma', 'Shinyanga',
  'Simiyu', 'Singida', 'Songwe', 'Tabora', 'Tanga', 'Unguja North', 'Unguja South', 'Unguja Urban West'];

function useInvalidate() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: ['applicant'] });
}

// ---------------------------------------------------------------- Results
export function ResultsStep({ onNext }: { onNext: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidate();
  const quals = useQuery({ queryKey: ['applicant', 'qualifications'], queryFn: () => api<Qualification[]>('/api/applicant/qualifications', { realm: 'applicant' }) });
  const [type, setType] = useState<'CSEE' | 'ACSEE'>('CSEE');
  const [index, setIndex] = useState('');
  const [year, setYear] = useState(String(new Date().getFullYear() - 1));
  const [prior, setPrior] = useState(false);
  const [award, setAward] = useState({ type: 'NVA', number: '', level: '3', year: '' });

  const fetchNecta = useMutation({
    mutationFn: () => api('/api/applicant/qualifications/necta', { method: 'POST', realm: 'applicant', body: { type, indexNumber: index.trim().toUpperCase(), year: Number(year) } }),
    onSuccess: () => { setIndex(''); invalidate(); },
  });
  const addAward = useMutation({
    mutationFn: () => api('/api/applicant/qualifications/prior-award', { method: 'POST', realm: 'applicant', body: { ...award, level: Number(award.level), year: award.year ? Number(award.year) : null } }),
    onSuccess: () => { setAward({ ...award, number: '' }); invalidate(); },
  });
  const remove = useMutation({
    mutationFn: (id: number) => api(`/api/applicant/qualifications/${id}`, { method: 'DELETE', realm: 'applicant' }),
    onSuccess: invalidate,
  });

  return (
    <Stack spacing={3}>
      <Typography color="text.secondary">{t('app.resultsIntro')}</Typography>
      <form onSubmit={(e) => { e.preventDefault(); fetchNecta.mutate(); }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <TextField select label={t('app.examType')} value={type} onChange={(e) => setType(e.target.value as 'CSEE' | 'ACSEE')} sx={{ minWidth: 170 }}>
            <MenuItem value="CSEE">CSEE (Form Four)</MenuItem>
            <MenuItem value="ACSEE">ACSEE (Form Six)</MenuItem>
          </TextField>
          <TextField label={t('app.indexNumber')} placeholder="S0101/0001" value={index} onChange={(e) => setIndex(e.target.value)} required />
          <TextField label={t('app.year')} value={year} onChange={(e) => setYear(e.target.value.replace(/\D/g, ''))} required sx={{ maxWidth: { sm: 120 } }} inputMode="numeric" />
          <Button type="submit" variant="contained" disabled={fetchNecta.isPending} sx={{ minWidth: 150 }}>{t('app.fetch')}</Button>
        </Stack>
      </form>
      <ErrorAlert error={fetchNecta.error ?? addAward.error ?? remove.error} />

      {quals.isLoading ? <Loading /> : quals.data?.map((q) => (
        <Card key={q.id}>
          <CardContent>
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
              <Box>
                <Typography sx={{ fontWeight: 700 }}>{q.type} · {q.indexNumber}{q.examYear ? ` · ${q.examYear}` : ''}{q.awardLevel ? ` · Level ${q.awardLevel}` : ''}</Typography>
                {q.centreName && <Typography variant="body2" color="text.secondary">{q.centreName}{q.division ? ` · Division ${q.division}` : ''}{q.points ? ` (${q.points} points)` : ''}</Typography>}
              </Box>
              <Stack direction="row" spacing={1} alignItems="center">
                <Chip size="small" color={q.verified ? 'success' : 'warning'} label={q.verified ? t('app.verified') : t('app.pending')} />
                <IconButton size="small" onClick={() => remove.mutate(q.id)} aria-label={t('app.remove')}><DeleteIcon fontSize="small" /></IconButton>
              </Stack>
            </Stack>
            {q.subjects.length > 0 && (
              <Stack direction="row" flexWrap="wrap" useFlexGap spacing={1} sx={{ mt: 1.5 }}>
                {q.subjects.map((s) => (
                  <Chip key={s.code + s.name} variant="outlined" size="small" label={`${s.name} — ${s.grade}`}
                    sx={{ borderColor: s.grade === 'F' ? 'error.light' : undefined }} />
                ))}
              </Stack>
            )}
          </CardContent>
        </Card>
      ))}

      <FormControlLabel control={<Switch checked={prior} onChange={(e) => setPrior(e.target.checked)} />} label={t('app.priorAward')} />
      <Collapse in={prior}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <TextField select label="Award" value={award.type} onChange={(e) => setAward({ ...award, type: e.target.value, level: e.target.value === 'NVA' ? '3' : '5' })} sx={{ minWidth: 110 }}>
            <MenuItem value="NVA">NVA</MenuItem>
            <MenuItem value="NTA">NTA</MenuItem>
          </TextField>
          <TextField label={t('app.awardNumber')} value={award.number} onChange={(e) => setAward({ ...award, number: e.target.value })} />
          <TextField select label={t('app.level')} value={award.level} onChange={(e) => setAward({ ...award, level: e.target.value })} sx={{ minWidth: 100 }}>
            {(award.type === 'NVA' ? [1, 2, 3] : [4, 5, 6, 7, 8]).map((l) => <MenuItem key={l} value={String(l)}>{l}</MenuItem>)}
          </TextField>
          <Button variant="outlined" onClick={() => addAward.mutate()} disabled={!award.number || addAward.isPending}>{t('app.add')}</Button>
        </Stack>
      </Collapse>

      <Box><Button variant="contained" onClick={onNext} disabled={!quals.data?.length}>{t('app.next')}</Button></Box>
    </Stack>
  );
}

// ---------------------------------------------------------------- Personal details
export function DetailsStep({ onNext }: { onNext: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidate();
  const profile = useQuery({ queryKey: ['applicant', 'profile'], queryFn: () => api<Profile>('/api/applicant/profile', { realm: 'applicant' }) });
  const [f, setF] = useState<Partial<Profile> | null>(null);
  // initialise once — later refetches must not overwrite what the applicant is typing
  useEffect(() => { if (profile.data && f === null) setF(profile.data); }, [profile.data, f]);
  const save = useMutation({
    mutationFn: () => api<Profile>('/api/applicant/profile', { method: 'PUT', realm: 'applicant', body: f }),
    onSuccess: () => { invalidate(); onNext(); },
  });
  if (profile.isLoading || f === null) return <Loading />;
  const field = (k: keyof Profile, label: string, props: Record<string, unknown> = {}) => (
    <TextField label={label} value={(f[k] as string) ?? ''} onChange={(e) => setF({ ...f, [k]: e.target.value })} {...props} />
  );

  return (
    <form onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <Stack spacing={2}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          {field('firstName', t('auth.firstName'), { required: true })}
          {field('middleName', t('auth.middleName'))}
          {field('surname', t('auth.surname'), { required: true })}
        </Stack>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <TextField select label="Sex" value={f.sex ?? ''} onChange={(e) => setF({ ...f, sex: e.target.value as 'M' | 'F' })} required>
            <MenuItem value="F">Female</MenuItem>
            <MenuItem value="M">Male</MenuItem>
          </TextField>
          {field('dateOfBirth', 'Date of birth', { type: 'date', required: true, slotProps: { inputLabel: { shrink: true } } })}
          {field('nationality', 'Nationality', { required: true, placeholder: 'Tanzanian' })}
        </Stack>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <TextField select label="Region" value={f.region ?? ''} onChange={(e) => setF({ ...f, region: e.target.value })} required>
            {REGIONS.map((r) => <MenuItem key={r} value={r}>{r}</MenuItem>)}
          </TextField>
          {field('district', 'District')}
          {field('email', t('auth.email'), { type: 'email' })}
        </Stack>
        {field('postalAddress', 'Postal address')}
        {field('disability', 'Disability or special support needed (optional)')}
        <Divider textAlign="left"><Typography variant="caption" color="text.secondary">NEXT OF KIN</Typography></Divider>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          {field('nextOfKinName', 'Full name', { required: true })}
          {field('nextOfKinPhone', 'Mobile number', { required: true, inputMode: 'tel' })}
          {field('nextOfKinRelation', 'Relationship', { placeholder: 'Parent / Guardian' })}
        </Stack>
        <ErrorAlert error={save.error} />
        <Box><Button type="submit" variant="contained" disabled={save.isPending}>{t('app.save')} & {t('app.next').toLowerCase()}</Button></Box>
      </Stack>
    </form>
  );
}

// ---------------------------------------------------------------- Fee
export function FeeStep({ app, onNext }: { app: Application; onNext: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidate();
  const fee = useQuery({ queryKey: ['applicant', 'fee', app.id], queryFn: () => api<Fee>(`/api/applicant/applications/${app.id}/fee`, { realm: 'applicant' }) });
  const request = useMutation({ mutationFn: () => api<Fee>(`/api/applicant/applications/${app.id}/fee`, { method: 'POST', realm: 'applicant' }), onSuccess: invalidate });
  const simulate = useMutation({ mutationFn: () => api<Fee>(`/api/applicant/applications/${app.id}/fee/simulate-payment`, { method: 'POST', realm: 'applicant' }), onSuccess: invalidate });
  if (fee.isLoading) return <Loading />;
  const f = fee.data;

  if (f?.feePaid) {
    return (
      <Stack spacing={2}>
        <Alert severity="success" icon={<CheckCircleIcon />}>{t('app.feePaid')} — {formatTzs(f.amount)}{f.invoiceNo ? ` (${f.invoiceNo})` : ''}</Alert>
        <Box><Button variant="contained" onClick={onNext}>{t('app.next')}</Button></Box>
      </Stack>
    );
  }
  return (
    <Stack spacing={2.5}>
      <Typography color="text.secondary">{t('app.feeIntro', { amount: formatTzs(app.applicationFee) })}</Typography>
      {f?.controlNo ? (
        <Card sx={{ bgcolor: '#FFFBEB', borderColor: '#F3D58A' }}>
          <CardContent>
            <Typography variant="overline" color="text.secondary">{t('app.controlNo')}</Typography>
            <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: { xs: 30, sm: 38 }, letterSpacing: 3 }}>
              {f.controlNo.replace(/(\d{4})(?=\d)/g, '$1 ')}
            </Typography>
            <Typography variant="body2" color="text.secondary">{formatTzs(f.amount - f.paid)} · {t('app.expires', { date: formatDateTime(f.controlNoExpiresAt) })}</Typography>
          </CardContent>
        </Card>
      ) : (
        <Box><Button variant="contained" size="large" onClick={() => request.mutate()} disabled={request.isPending}>{t('app.getControlNo')}</Button></Box>
      )}
      {f?.controlNo && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <Button variant="outlined" onClick={() => fee.refetch()}>{t('app.checkPayment')}</Button>
          {SHOW_DEV_TOOLS && <Button color="warning" variant="contained" onClick={() => simulate.mutate()} disabled={simulate.isPending}>{t('app.simulate')}</Button>}
        </Stack>
      )}
      <ErrorAlert error={request.error ?? simulate.error} />
    </Stack>
  );
}

// ---------------------------------------------------------------- Programmes
export function EligibilityDetail({ result }: { result: EligibilityResult }) {
  return (
    <Stack spacing={1}>
      {result.pathways.length === 0 && <Typography variant="body2">Open entry.</Typography>}
      {result.pathways.map((p) => (
        <Box key={p.label}>
          <Typography variant="body2" sx={{ fontWeight: 700 }}>{p.label} {p.satisfied ? '✓' : ''}</Typography>
          {p.conditions.map((c, i) => (
            <Stack key={i} direction="row" spacing={1} alignItems="flex-start">
              {c.satisfied ? <CheckCircleIcon fontSize="small" color="success" /> : <CancelIcon fontSize="small" color="error" />}
              <Typography variant="body2">{c.requirement} <Typography component="span" variant="body2" color="text.secondary">— {c.found}</Typography></Typography>
            </Stack>
          ))}
        </Box>
      ))}
    </Stack>
  );
}

export function ProgrammesStep({ app, onNext }: { app: Application; onNext: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidate();
  const elig = useQuery({ queryKey: ['applicant', 'eligibility', app.windowId], queryFn: () => api<ProgrammeEligibility[]>(`/api/applicant/eligibility?windowId=${app.windowId}`, { realm: 'applicant' }) });
  const [chosen, setChosen] = useState<number[]>(app.choices.map((c) => c.windowProgrammeId));
  const save = useMutation({
    mutationFn: () => api(`/api/applicant/applications/${app.id}/choices`, { method: 'PUT', realm: 'applicant', body: { windowProgrammeIds: chosen } }),
    onSuccess: () => { invalidate(); onNext(); },
  });

  if (!app.feePaid) return <Alert severity="warning">Pay the application fee first.</Alert>;
  if (elig.isLoading) return <Loading />;
  const byId = new Map(elig.data?.map((e) => [e.windowProgrammeId, e]));
  const toggle = (id: number) => setChosen((c) => (c.includes(id) ? c.filter((x) => x !== id) : c.length < app.maxChoices ? [...c, id] : c));
  const move = (i: number, d: number) => setChosen((c) => { const n = [...c]; [n[i], n[i + d]] = [n[i + d], n[i]]; return n; });

  return (
    <Stack spacing={2.5}>
      <Typography color="text.secondary">{t('app.programmesIntro', { n: app.maxChoices })}</Typography>
      {chosen.length > 0 && (
        <Card sx={{ borderColor: 'secondary.main' }}>
          <CardContent>
            {chosen.map((id, i) => (
              <Stack key={id} direction="row" alignItems="center" spacing={1} sx={{ py: 0.5 }}>
                <Chip size="small" color="secondary" label={t('app.choice', { n: i + 1 })} />
                <Typography sx={{ flexGrow: 1 }}>{byId.get(id)?.programmeName}</Typography>
                <IconButton size="small" disabled={i === 0} onClick={() => move(i, -1)} aria-label="up"><ArrowUpwardIcon fontSize="small" /></IconButton>
                <IconButton size="small" disabled={i === chosen.length - 1} onClick={() => move(i, 1)} aria-label="down"><ArrowDownwardIcon fontSize="small" /></IconButton>
              </Stack>
            ))}
          </CardContent>
        </Card>
      )}
      {elig.data?.map((e) => (
        <ProgrammeOption key={e.windowProgrammeId} e={e} checked={chosen.includes(e.windowProgrammeId)}
          disabled={!e.eligible || (!chosen.includes(e.windowProgrammeId) && chosen.length >= app.maxChoices)}
          onToggle={() => toggle(e.windowProgrammeId)} />
      ))}
      <ErrorAlert error={save.error} />
      <Box><Button variant="contained" onClick={() => save.mutate()} disabled={!chosen.length || save.isPending}>{t('app.saveChoices')}</Button></Box>
    </Stack>
  );
}

function ProgrammeOption({ e, checked, disabled, onToggle }: { e: ProgrammeEligibility; checked: boolean; disabled: boolean; onToggle: () => void }) {
  const { t } = useTranslation();
  const [why, setWhy] = useState(false);
  return (
    <Card sx={{ borderColor: checked ? 'secondary.main' : undefined, opacity: e.eligible ? 1 : 0.85 }}>
      <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
        <Stack direction="row" alignItems="center" spacing={1.5}>
          <Checkbox checked={checked} disabled={disabled} onChange={onToggle} slotProps={{ input: { 'aria-label': e.programmeName } }} />
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Typography sx={{ fontWeight: 600 }}>{e.programmeName}</Typography>
            <Typography variant="body2" color="text.secondary">{e.awardName} · {e.department}</Typography>
          </Box>
          <Stack alignItems="flex-end" spacing={0.5}>
            <Chip size="small" color={e.eligible ? 'success' : 'default'} label={e.eligible ? t('app.eligible') : t('app.notEligible')} />
            <Button size="small" onClick={() => setWhy(!why)}>{t('app.why')}</Button>
          </Stack>
        </Stack>
        <Collapse in={why}><Box sx={{ pl: 7, pt: 1 }}><EligibilityDetail result={e.eligibility} /></Box></Collapse>
      </CardContent>
    </Card>
  );
}

// ---------------------------------------------------------------- Review
export function ReviewStep({ app }: { app: Application }) {
  const { t } = useTranslation();
  const invalidate = useInvalidate();
  const submit = useMutation({ mutationFn: () => api(`/api/applicant/applications/${app.id}/submit`, { method: 'POST', realm: 'applicant' }), onSuccess: invalidate });
  return (
    <Stack spacing={2.5}>
      <Typography color="text.secondary">{t('app.reviewIntro')}</Typography>
      <Card>
        <CardContent>
          <Typography variant="subtitle2" color="text.secondary">{app.windowName}</Typography>
          {app.choices.map((c) => (
            <Typography key={c.id} sx={{ mt: 0.5 }}><strong>{t('app.choice', { n: c.order })}:</strong> {c.programmeName}</Typography>
          ))}
          <Typography sx={{ mt: 1 }}>{t('app.steps.fee')}: {app.feePaid ? '✓' : '—'}</Typography>
        </CardContent>
      </Card>
      {app.blockers.length > 0 && (
        <Alert severity="warning">
          <strong>{t('app.todo')}:</strong>
          <Box component="ul" sx={{ m: 0, pl: 2 }}>{app.blockers.map((b) => <li key={b}>{b}</li>)}</Box>
        </Alert>
      )}
      <ErrorAlert error={submit.error} />
      <Box><Button variant="contained" size="large" color="secondary" disabled={app.blockers.length > 0 || submit.isPending} onClick={() => submit.mutate()}>{t('app.submit')}</Button></Box>
    </Stack>
  );
}
