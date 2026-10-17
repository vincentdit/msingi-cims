import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import CancelIcon from '@mui/icons-material/Cancel';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import LockIcon from '@mui/icons-material/LockOutlined';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Collapse, Divider,
  IconButton, MenuItem, Stack, TextField, Tooltip, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, formatDateTime, formatTzs } from '../../api/client';
import type { Application, EligibilityResult, Fee, Profile, ProgrammeEligibility, Qualification } from '../../api/types';
import { AreaPicker } from '../../components/AreaPicker';
import { PrimarySchoolPicker } from '../../components/PrimarySchoolPicker';
import { ErrorAlert, Loading } from '../../components/common';
import { HowToPay, usePayWords } from '../../components/HowToPay';

const SHOW_DEV_TOOLS = import.meta.env.DEV || import.meta.env.VITE_SHOW_DEV_TOOLS === 'true';

function useInvalidate() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: ['applicant'] });
}

// ---------------------------------------------------------------- Results
export function ResultsStep({ onNext }: { onNext: () => void }) {
  const { t } = useTranslation();
  const quals = useQuery({ queryKey: ['applicant', 'qualifications'], queryFn: () => api<Qualification[]>('/api/applicant/qualifications', { realm: 'applicant' }) });
  // the Form Four results registered with (and any older results); further sittings and awards come after the fee
  const shown = (quals.data ?? []).filter((q) => q.registration || ((q.type === 'CSEE' || q.type === 'ACSEE') && !quals.data?.some((x) => x.registration)));
  return (
    <Stack spacing={3}>
      <Typography color="text.secondary">{t('app.resultsIntro')}</Typography>
      {quals.isLoading ? <Loading /> : shown.map((q) => (
        <Card key={q.id}>
          <CardContent>
            <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
              <Box>
                <Typography sx={{ fontWeight: 700 }}>{q.type} · {q.indexNumber}{q.examYear ? ` · ${q.examYear}` : ''}</Typography>
                {q.centreName && <Typography variant="body2" color="text.secondary">{q.centreName}{q.division ? ` · Division ${q.division}` : ''}{q.points ? ` (${q.points} points)` : ''}</Typography>}
              </Box>
              <Stack direction="row" spacing={1} alignItems="center">
                <Chip size="small" color={q.verified ? 'success' : 'warning'} label={q.verified ? t('app.verified') : t('app.pending')} />
                {q.registration && <Tooltip title={t('app.registrationResults')}><LockIcon fontSize="small" color="action" /></Tooltip>}
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
      <Box><Button variant="contained" onClick={onNext} disabled={!quals.data?.length}>{t('app.next')}</Button></Box>
    </Stack>
  );
}

// ---------------------------------------------------------------- Personal details
export function DetailsStep({ onNext }: { onNext: () => void }) {
  const { t } = useTranslation();
  const invalidate = useInvalidate();
  const profile = useQuery({ queryKey: ['applicant', 'profile'], queryFn: () => api<Profile>('/api/applicant/profile', { realm: 'applicant' }) });
  const options = useQuery({ queryKey: ['profile-options'], staleTime: Infinity,
    queryFn: () => api<{ disabilities: string[]; relationships: string[] }>('/api/public/profile-options') });
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
          {field('firstName', t('auth.firstName'), { required: true, disabled: f.namesFromNecta })}
          {field('middleName', t('auth.middleName'), { disabled: f.namesFromNecta })}
          {field('surname', t('auth.surname'), { required: true, disabled: f.namesFromNecta })}
        </Stack>
        {f.namesFromNecta && <Typography variant="caption" color="text.secondary" sx={{ mt: -1 }}>{t('auth.namesFromNecta')}</Typography>}
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <TextField select label={t('profile.sex')} value={f.sex ?? ''} onChange={(e) => setF({ ...f, sex: e.target.value as 'M' | 'F' })} required fullWidth>
            <MenuItem value="F">{t('auth.sexF')}</MenuItem>
            <MenuItem value="M">{t('auth.sexM')}</MenuItem>
          </TextField>
          {field('dateOfBirth', t('profile.dateOfBirth'), { type: 'date', required: true, fullWidth: true, slotProps: { inputLabel: { shrink: true } } })}
          {field('nationality', t('profile.nationality'), { required: true, placeholder: 'Tanzanian', fullWidth: true })}
        </Stack>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          <TextField select fullWidth label={t('profile.disability')} value={options.data?.disabilities.find((d) => d.toLowerCase() === (f.disability ?? '').toLowerCase()) ?? f.disability ?? ''}
            onChange={(e) => setF({ ...f, disability: e.target.value })}>
            <MenuItem value="">{t('profile.none')}</MenuItem>
            {f.disability && !options.data?.disabilities.some((d) => d.toLowerCase() === f.disability!.toLowerCase()) && <MenuItem value={f.disability}>{f.disability}</MenuItem>}
            {options.data?.disabilities.map((d) => <MenuItem key={d} value={d}>{d}</MenuItem>)}
          </TextField>
          {field('email', t('auth.email'), { type: 'email', fullWidth: true })}
        </Stack>

        <Divider textAlign="left"><Typography variant="caption" color="text.secondary">{t('profile.residence')}</Typography></Divider>
        <AreaPicker requireRegion value={{ region: f.region, district: f.district, ward: f.ward, street: f.street }}
          onChange={(a) => setF({ ...f, region: a.region ?? undefined, district: a.district ?? undefined, ward: a.ward ?? undefined, street: a.street ?? undefined })} />
        {field('postalAddress', t('profile.postalAddress'), { placeholder: 'P.O. Box 123, Arusha' })}

        <Divider textAlign="left"><Typography variant="caption" color="text.secondary">{t('profile.education')}</Typography></Divider>
        <PrimarySchoolPicker name={f.primarySchool} code={f.primarySchoolCode} onChange={(name, code) => setF({ ...f, primarySchool: name ?? undefined, primarySchoolCode: code ?? undefined })} />
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          {field('olevelSchool', t('profile.olevelSchool'), { fullWidth: true, disabled: f.olevelFromNecta,
            helperText: f.olevelFromNecta ? t('profile.fromNecta') : t('profile.olevelHint') })}
          {field('alevelSchool', t('profile.alevelSchool'), { fullWidth: true, disabled: f.alevelFromNecta,
            helperText: f.alevelFromNecta ? t('profile.fromNecta') : t('profile.alevelHint') })}
        </Stack>

        <Divider textAlign="left"><Typography variant="caption" color="text.secondary">{t('profile.nextOfKin')}</Typography></Divider>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          {field('nextOfKinName', t('profile.kinName'), { required: true, fullWidth: true })}
          <TextField select fullWidth label={t('profile.relationship')}
            value={options.data?.relationships.find((r) => r.toLowerCase() === (f.nextOfKinRelation ?? '').toLowerCase()) ?? f.nextOfKinRelation ?? ''}
            onChange={(e) => setF({ ...f, nextOfKinRelation: e.target.value })}>
            {f.nextOfKinRelation && !options.data?.relationships.some((r) => r.toLowerCase() === f.nextOfKinRelation!.toLowerCase()) && <MenuItem value={f.nextOfKinRelation}>{f.nextOfKinRelation}</MenuItem>}
            {options.data?.relationships.map((r) => <MenuItem key={r} value={r}>{r}</MenuItem>)}
          </TextField>
        </Stack>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
          {field('nextOfKinPhone', t('profile.kinPhone'), { required: true, inputMode: 'tel', placeholder: '0712 345 678', fullWidth: true })}
          {field('nextOfKinEmail', t('profile.kinEmail'), { type: 'email', fullWidth: true })}
        </Stack>
        {field('nextOfKinAddress', t('profile.kinAddress'), { placeholder: t('profile.kinAddressHint') })}
        <AreaPicker value={{ region: f.nextOfKinRegion, district: f.nextOfKinDistrict, ward: f.nextOfKinWard, street: f.nextOfKinStreet }}
          onChange={(a) => setF({ ...f, nextOfKinRegion: a.region ?? undefined, nextOfKinDistrict: a.district ?? undefined, nextOfKinWard: a.ward ?? undefined, nextOfKinStreet: a.street ?? undefined })} />
        <ErrorAlert error={save.error} />
        <Box><Button type="submit" variant="contained" disabled={save.isPending}>{t('app.save')} & {t('app.next').toLowerCase()}</Button></Box>
      </Stack>
    </form>
  );
}

// ---------------------------------------------------------------- Fee
export function FeeStep({ app, onNext }: { app: Application; onNext: () => void }) {
  const { t } = useTranslation();
  const words = usePayWords();
  const invalidate = useInvalidate();
  const request = useMutation({ mutationFn: () => api<Fee>(`/api/applicant/applications/${app.id}/fee`, { method: 'POST', realm: 'applicant' }), onSuccess: invalidate });
  // live GePG sends the control number a moment after the bill: look again every few seconds until it is there
  const fee = useQuery({
    queryKey: ['applicant', 'fee', app.id], queryFn: () => api<Fee>(`/api/applicant/applications/${app.id}/fee`, { realm: 'applicant' }),
    refetchInterval: (q) => (request.isSuccess && !q.state.data?.controlNo && !q.state.data?.feePaid ? 3000 : false),
  });
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
            <Typography variant="overline" color="text.secondary">{words.number}</Typography>
            <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: { xs: 30, sm: 38 }, letterSpacing: 3 }}>
              {f.controlNo.replace(/(\d{4})(?=\d)/g, '$1 ')}
            </Typography>
            <Typography variant="body2" color="text.secondary">{formatTzs(f.amount - f.paid)} · {t('app.expires', { date: formatDateTime(f.controlNoExpiresAt) })}</Typography>
            <HowToPay reference={f.controlNo} amount={f.amount - f.paid} />
          </CardContent>
        </Card>
      ) : request.isSuccess ? <Alert severity="info">{t('pay.pending')}</Alert> : (
        <Box><Button variant="contained" size="large" onClick={() => request.mutate()} disabled={request.isPending}>{words.getNumber}</Button></Box>
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
  const level = useQuery({ queryKey: ['applicant', 'entry-level'], queryFn: () => api<{ label: string; basis: string }>('/api/applicant/entry-level', { realm: 'applicant' }) });
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
      {level.data && <Alert severity="info" icon={false}>{t('app.entryLevel', { basis: level.data.basis, label: level.data.label })}</Alert>}
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
      {elig.data?.filter((e) => e.atEntryLevel || chosen.includes(e.windowProgrammeId)).map((e) => (
        <ProgrammeOption key={e.windowProgrammeId} e={e} checked={chosen.includes(e.windowProgrammeId)}
          disabled={!chosen.includes(e.windowProgrammeId) && (!e.eligible || chosen.length >= app.maxChoices)}
          onToggle={() => toggle(e.windowProgrammeId)} />
      ))}
      {!!elig.data?.filter((e) => !e.atEntryLevel && !chosen.includes(e.windowProgrammeId)).length && (
        <Typography variant="caption" color="text.secondary">{t('app.otherLevels', { n: elig.data.filter((e) => !e.atEntryLevel && !chosen.includes(e.windowProgrammeId)).length })}</Typography>
      )}
      <ErrorAlert error={save.error} />
      <Box><Button variant="contained" onClick={() => save.mutate()} disabled={!chosen.length || save.isPending
        || chosen.some((id) => byId.get(id) && !byId.get(id)!.eligible)}>{t('app.saveChoices')}</Button></Box>
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
export function ReviewStep({ app, onDone }: { app: Application; onDone?: () => void }) {
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
          <Box component="ul" sx={{ m: 0, pl: 2 }}>
            {app.todo ? app.todo.map((x, i) => <li key={i}>{todoText(x, t)}</li>) : app.blockers.map((b) => <li key={b}>{b}</li>)}
          </Box>
        </Alert>
      )}
      <ErrorAlert error={submit.error} />
      {app.status === 'SUBMITTED' ? (
        <>
          <Alert severity="success">{t('amend.stillSubmitted', { ref: app.referenceNo })}</Alert>
          {onDone && <Box><Button variant="contained" size="large" onClick={onDone}>{t('amend.done')}</Button></Box>}
        </>
      ) : (
        <Box><Button variant="contained" size="large" color="secondary" disabled={app.blockers.length > 0 || submit.isPending} onClick={() => submit.mutate()}>{t('app.submit')}</Button></Box>
      )}
    </Stack>
  );
}

/** A step still to do, in the applicant's language: the missing fields by their names, documents and programmes as given. */
function todoText(x: { code: string; items: string[] }, t: (k: string, o?: Record<string, unknown>) => string): string {
  const key = `app.todoItem.${x.code}`;
  if (x.code === 'PROFILE') return t(key, { items: x.items.map((f) => t(`app.todoField.${f}`, { defaultValue: f })).join(', ') });
  if (x.code === 'CHANGE_CHOICE') return t(key, { programme: x.items[0], reason: x.items[1] });
  return t(key, { items: x.items.join(', '), defaultValue: x.code });
}
