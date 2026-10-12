import DeleteIcon from '@mui/icons-material/DeleteOutline';
import {
  Alert, Box, Button, Card, CardContent, Chip, IconButton, MenuItem, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../../api/client';
import type { Application, Qualification } from '../../api/types';
import { ErrorAlert, Loading } from '../../components/common';

type Path = 'necta' | 'nta' | 'nva' | 'other' | 'none';
const OTHER_LEVELS = ['CERTIFICATE', 'DIPLOMA', 'ADVANCED_DIPLOMA', 'DEGREE', 'MASTERS', 'OTHER'] as const;
const applicant = { realm: 'applicant' as const };
const thisYear = new Date().getFullYear();

/** After the fee, as in NOAS: another CSEE/ACSEE sitting, an NTA award (from NACTVET), an NVA award, another award — or none. */
export function OtherQualificationsStep({ app, onNext }: { app: Application; onNext: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const quals = useQuery({ queryKey: ['applicant', 'qualifications'], queryFn: () => api<Qualification[]>('/api/applicant/qualifications', applicant) });
  const [path, setPath] = useState<Path | null>(null);
  const done = () => { qc.invalidateQueries({ queryKey: ['applicant'] }); };
  const remove = useMutation({ mutationFn: (id: number) => api(`/api/applicant/qualifications/${id}`, { ...applicant, method: 'DELETE' }), onSuccess: done });
  const extra = (quals.data ?? []).filter((q) => !q.registration);

  if (!app.feePaid) return <Alert severity="info">{t('other.payFirst')}</Alert>;
  return (
    <Stack spacing={2.5}>
      <Box>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{t('other.question')}</Typography>
        <Typography variant="body2" color="text.secondary">{t('other.intro')}</Typography>
      </Box>
      <ToggleButtonGroup exclusive value={path} onChange={(_, v) => v && setPath(v)} color="primary"
        sx={{ flexWrap: 'wrap', gap: 1, '& .MuiToggleButton-root': { border: 1, borderColor: 'divider', borderRadius: '8px !important', textTransform: 'none', px: 2 } }}>
        <ToggleButton value="necta">{t('other.necta')}</ToggleButton>
        <ToggleButton value="nta">{t('other.nta')}</ToggleButton>
        <ToggleButton value="nva">{t('other.nva')}</ToggleButton>
        <ToggleButton value="other">{t('other.other')}</ToggleButton>
        <ToggleButton value="none">{t('other.none')}</ToggleButton>
      </ToggleButtonGroup>

      {path === 'necta' && <NectaSitting onAdded={done} />}
      {path === 'nta' && <NtaAward onAdded={done} />}
      {path === 'nva' && <NvaAward onAdded={done} />}
      {path === 'other' && <OtherAward onAdded={done} />}
      {path === 'none' && <Alert severity="success" icon={false}>{t('other.noneText')}</Alert>}

      <ErrorAlert error={remove.error} />
      {quals.isLoading ? <Loading /> : extra.length > 0 && (
        <Stack spacing={1.5}>
          <Typography variant="subtitle2" color="text.secondary">{t('other.added')}</Typography>
          {extra.map((q) => (
            <Card key={q.id} variant="outlined">
              <CardContent sx={{ py: 1.5, '&:last-child': { pb: 1.5 } }}>
                <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={1}>
                  <Box>
                    <Typography sx={{ fontWeight: 700 }}>
                      {q.type === 'OTHER' ? q.awardName : q.type === 'NTA' ? `NTA Level ${q.awardLevel} · ${q.awardName ?? ''}` : q.type === 'NVA' ? `NVA Level ${q.awardLevel}` : `${q.type} · ${q.indexNumber}`}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {[q.type === 'CSEE' || q.type === 'ACSEE' || q.indexNumber.startsWith('OTH-') ? null : q.indexNumber, q.institution ?? q.centreName, q.examYear,
                        q.division ? `Division ${q.division}` : null, q.gpa != null ? `GPA ${q.gpa}` : null, q.classAward,
                        q.otherLevel ? t(`other.level_${q.otherLevel}`) : null].filter(Boolean).join(' · ')}
                    </Typography>
                  </Box>
                  <Stack direction="row" spacing={1} alignItems="center">
                    <Chip size="small" color={q.verified ? 'success' : 'warning'} label={q.verified ? t('app.verified') : t('app.pending')} />
                    <IconButton size="small" onClick={() => remove.mutate(q.id)} aria-label={t('app.remove')}><DeleteIcon fontSize="small" /></IconButton>
                  </Stack>
                </Stack>
                {q.subjects.length > 0 && (
                  <Stack direction="row" flexWrap="wrap" useFlexGap spacing={0.75} sx={{ mt: 1 }}>
                    {q.subjects.map((s, i) => <Chip key={i} size="small" variant="outlined" label={`${s.name} — ${s.grade}`} />)}
                  </Stack>
                )}
              </CardContent>
            </Card>
          ))}
        </Stack>
      )}

      <Box><Button variant="contained" onClick={onNext}>{t('app.next')}</Button></Box>
    </Stack>
  );
}

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Card variant="outlined" sx={{ bgcolor: 'action.hover' }}>
      <CardContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>{title}</Typography>
        {children}
      </CardContent>
    </Card>
  );
}

function NectaSitting({ onAdded }: { onAdded: () => void }) {
  const { t } = useTranslation();
  const [f, setF] = useState({ type: 'ACSEE', index: '', year: String(thisYear - 1) });
  const m = useMutation({
    mutationFn: () => api('/api/applicant/qualifications/necta', { ...applicant, method: 'POST', body: { type: f.type, indexNumber: f.index.trim().toUpperCase(), year: Number(f.year) } }),
    onSuccess: () => { setF({ ...f, index: '' }); onAdded(); },
  });
  return (
    <Panel title={t('other.nectaText')}>
      <form onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
          <TextField select size="small" label={t('app.examType')} value={f.type} onChange={(e) => setF({ ...f, type: e.target.value })} sx={{ minWidth: 170 }}>
            <MenuItem value="CSEE">CSEE (Form Four)</MenuItem>
            <MenuItem value="ACSEE">ACSEE (Form Six)</MenuItem>
          </TextField>
          <TextField size="small" label={t('app.indexNumber')} placeholder="S0101/0001" value={f.index} onChange={(e) => setF({ ...f, index: e.target.value })} required />
          <TextField size="small" label={t('app.year')} value={f.year} onChange={(e) => setF({ ...f, year: e.target.value.replace(/\D/g, '') })} required sx={{ maxWidth: { sm: 110 } }} />
          <Button type="submit" variant="contained" disabled={m.isPending || !f.index.trim()}>{m.isPending ? '…' : t('other.fetchNecta')}</Button>
        </Stack>
        <ErrorAlert error={m.error} />
      </form>
    </Panel>
  );
}

function NtaAward({ onAdded }: { onAdded: () => void }) {
  const { t } = useTranslation();
  const [f, setF] = useState({ reg: '', year: String(thisYear - 1) });
  const m = useMutation({
    mutationFn: () => api('/api/applicant/qualifications/nta', { ...applicant, method: 'POST', body: { registrationNumber: f.reg.trim(), graduationYear: Number(f.year) } }),
    onSuccess: () => { setF({ ...f, reg: '' }); onAdded(); },
  });
  return (
    <Panel title={t('other.ntaText')}>
      <form onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
          <TextField size="small" label={t('other.regNo')} placeholder="NS0816/0129/2015" value={f.reg} onChange={(e) => setF({ ...f, reg: e.target.value.toUpperCase() })} required sx={{ flex: 1 }} />
          <TextField size="small" label={t('other.gradYear')} value={f.year} onChange={(e) => setF({ ...f, year: e.target.value.replace(/\D/g, '') })} required sx={{ maxWidth: { sm: 140 } }} />
          <Button type="submit" variant="contained" disabled={m.isPending || !f.reg.trim()}>{m.isPending ? '…' : t('other.fetchNactvet')}</Button>
        </Stack>
        <ErrorAlert error={m.error} />
      </form>
    </Panel>
  );
}

function NvaAward({ onAdded }: { onAdded: () => void }) {
  const { t } = useTranslation();
  const [f, setF] = useState({ number: '', level: '3', year: String(thisYear - 1) });
  const m = useMutation({
    mutationFn: () => api('/api/applicant/qualifications/prior-award', { ...applicant, method: 'POST', body: { type: 'NVA', number: f.number.trim(), level: Number(f.level), year: Number(f.year) } }),
    onSuccess: () => { setF({ ...f, number: '' }); onAdded(); },
  });
  return (
    <Panel title={t('other.nvaText')}>
      <form onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
          <TextField size="small" label={t('other.certNo')} value={f.number} onChange={(e) => setF({ ...f, number: e.target.value })} required sx={{ flex: 1 }} />
          <TextField select size="small" label={t('app.level')} value={f.level} onChange={(e) => setF({ ...f, level: e.target.value })} sx={{ minWidth: 110 }}>
            {[1, 2, 3].map((l) => <MenuItem key={l} value={String(l)}>NVA {l}</MenuItem>)}
          </TextField>
          <TextField size="small" label={t('app.year')} value={f.year} onChange={(e) => setF({ ...f, year: e.target.value.replace(/\D/g, '') })} required sx={{ maxWidth: { sm: 110 } }} />
          <Button type="submit" variant="contained" disabled={m.isPending || !f.number.trim()}>{t('app.add')}</Button>
        </Stack>
        <ErrorAlert error={m.error} />
      </form>
    </Panel>
  );
}

function OtherAward({ onAdded }: { onAdded: () => void }) {
  const { t } = useTranslation();
  const empty = { awardName: '', institution: '', year: String(thisYear - 1), reference: '', level: 'CERTIFICATE' };
  const [f, setF] = useState(empty);
  const m = useMutation({
    mutationFn: () => api('/api/applicant/qualifications/other', { ...applicant, method: 'POST',
      body: { ...f, awardName: f.awardName.trim(), institution: f.institution.trim(), year: Number(f.year), reference: f.reference.trim() || null } }),
    onSuccess: () => { setF(empty); onAdded(); },
  });
  return (
    <Panel title={t('other.otherText')}>
      <form onSubmit={(e) => { e.preventDefault(); m.mutate(); }}>
        <Stack spacing={1.5}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <TextField size="small" fullWidth label={t('other.awardName')} placeholder={t('other.awardNameHint')} value={f.awardName} onChange={(e) => setF({ ...f, awardName: e.target.value })} required />
            <TextField select size="small" label={t('app.level')} value={f.level} onChange={(e) => setF({ ...f, level: e.target.value })} sx={{ minWidth: 190 }}>
              {OTHER_LEVELS.map((l) => <MenuItem key={l} value={l}>{t(`other.level_${l}`)}</MenuItem>)}
            </TextField>
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <TextField size="small" fullWidth label={t('other.institution')} value={f.institution} onChange={(e) => setF({ ...f, institution: e.target.value })} required />
            <TextField size="small" label={t('app.year')} value={f.year} onChange={(e) => setF({ ...f, year: e.target.value.replace(/\D/g, '') })} required sx={{ maxWidth: { sm: 110 } }} />
            <TextField size="small" label={t('other.reference')} value={f.reference} onChange={(e) => setF({ ...f, reference: e.target.value })} sx={{ minWidth: 200 }} />
          </Stack>
          <Box><Button type="submit" variant="contained" disabled={m.isPending || !f.awardName.trim() || !f.institution.trim()}>{t('app.add')}</Button></Box>
        </Stack>
        <ErrorAlert error={m.error} />
      </form>
    </Panel>
  );
}
