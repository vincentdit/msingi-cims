import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import SupportIcon from '@mui/icons-material/SupportAgentOutlined';
import RateIcon from '@mui/icons-material/RateReviewOutlined';
import CheckIcon from '@mui/icons-material/CheckCircle';
import {
  Alert, Box, Button, Card, CardActionArea, CardContent, Chip, Divider, FormControlLabel, MenuItem, Radio, RadioGroup, Stack, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { api, formatDate, formatDateTime, formatTzs } from '../../api/client';
import { ErrorAlert, Loading } from '../../components/common';

const student = { realm: 'student' as const };

interface Question { id: number; section: 'MODULE' | 'TEACHING' | 'GENERAL'; kind: 'RATING' | 'TEXT'; text: string }
interface EvalModule { offeringId: number; code: string; title: string; lecturers?: string; done: boolean }
interface Evaluation { round: { id: number; name: string; closesOn: string }; questions: Question[]; modules: EvalModule[] }
interface MyRequest { id: number; concern: string; status: string; nextSessionAt?: string; openedAt: string }
interface MyCase {
  id: number; caseNo: string; category: string; occurredOn: string; status: string; hearingAt?: string; hearingPlace?: string; studentStatement?: string; outcome?: string;
  outcomeDetails?: string; amount?: number; controlNo?: string; suspendedFrom?: string; suspendedTo?: string; appealBy?: string; canAppeal: boolean; appealText?: string;
  appealResult?: string; appealNote?: string;
}

/** Rows for the student home: the module evaluation (when one is open) and the Dean of Students. */
export function WelfareRows() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const ev = useQuery({ queryKey: ['student', 'evaluations'], queryFn: () => api<Evaluation[]>('/api/student/evaluations', student) });
  const toDo = (ev.data ?? []).reduce((n, e) => n + e.modules.filter((m) => !m.done).length, 0);
  return (
    <>
      {!!ev.data?.length && (
        <>
          <Divider />
          <CardActionArea onClick={() => navigate('/student/evaluations')}>
            <CardContent sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
              <RateIcon color="primary" />
              <Box sx={{ flexGrow: 1 }}>
                <Typography variant="subtitle1">{t('evaluation.title')}</Typography>
                <Typography variant="body2" color="text.secondary">{toDo > 0 ? t('evaluation.toDo', { count: toDo }) : t('evaluation.allDone')}</Typography>
              </Box>
              {toDo > 0 && <Chip size="small" color="warning" label={toDo} />}
              <ChevronRightIcon color="action" />
            </CardContent>
          </CardActionArea>
        </>
      )}
      <Divider />
      <CardActionArea onClick={() => navigate('/student/welfare')}>
        <CardContent sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <SupportIcon color="primary" />
          <Typography variant="subtitle1" sx={{ flexGrow: 1 }}>{t('welfare.title')}</Typography>
          <ChevronRightIcon color="action" />
        </CardContent>
      </CardActionArea>
    </>
  );
}

export function StudentEvaluationsPage() {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['student', 'evaluations'], queryFn: () => api<Evaluation[]>('/api/student/evaluations', student) });
  const [open, setOpen] = useState<{ round: Evaluation; module: EvalModule } | null>(null);
  const [thanks, setThanks] = useState(false);
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  if (open) return <EvaluationForm evaluation={open.round} module={open.module} onDone={() => { setOpen(null); setThanks(true); }} onCancel={() => setOpen(null)} />;
  return (
    <Stack spacing={2}>
      <Box>
        <Typography variant="h5">{t('evaluation.title')}</Typography>
        <Typography variant="body2" color="text.secondary">{t('evaluation.intro')}</Typography>
      </Box>
      {thanks && <Alert severity="success" onClose={() => setThanks(false)}>{t('evaluation.thanks')}</Alert>}
      {q.data.length === 0 && <Alert severity="info">{t('evaluation.none')}</Alert>}
      {q.data.map((e) => (
        <Card key={e.round.id} sx={{ borderRadius: 3 }}>
          <CardContent>
            <Typography variant="subtitle1">{e.round.name}</Typography>
            <Typography variant="caption" color="text.secondary">{t('evaluation.closes', { date: formatDate(e.round.closesOn) })}</Typography>
            <Stack spacing={1} sx={{ mt: 1.5 }}>
              {e.modules.map((m) => (
                <Stack key={m.offeringId} direction="row" alignItems="center" spacing={1.5}>
                  <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{m.code} · {m.title}</Typography>
                    {m.lecturers && <Typography variant="caption" color="text.secondary">{t('evaluation.lecturers', { names: m.lecturers })}</Typography>}
                  </Box>
                  {m.done ? <Chip size="small" color="success" icon={<CheckIcon />} label={t('evaluation.done')} />
                    : <Button variant="contained" size="small" onClick={() => { setThanks(false); setOpen({ round: e, module: m }); }}>{t('evaluation.start')}</Button>}
                </Stack>
              ))}
            </Stack>
          </CardContent>
        </Card>
      ))}
    </Stack>
  );
}

function EvaluationForm({ evaluation, module, onDone, onCancel }: { evaluation: Evaluation; module: EvalModule; onDone: () => void; onCancel: () => void }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [ratings, setRatings] = useState<Record<string, number>>({});
  const [comments, setComments] = useState<Record<string, string>>({});
  const rating = evaluation.questions.filter((q) => q.kind === 'RATING');
  const complete = rating.every((q) => ratings[q.id]);
  const send = useMutation({
    mutationFn: () => api(`/api/student/evaluations/${evaluation.round.id}/offerings/${module.offeringId}`, { ...student, method: 'POST', body: { ratings, comments } }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['student', 'evaluations'] }); onDone(); },
  });
  const sections = ['MODULE', 'TEACHING', 'GENERAL'] as const;
  return (
    <Stack spacing={2}>
      <Box>
        <Button size="small" onClick={onCancel}>‹ {t('evaluation.title')}</Button>
        <Typography variant="h6">{module.code} · {module.title}</Typography>
        {module.lecturers && <Typography variant="body2" color="text.secondary">{t('evaluation.lecturers', { names: module.lecturers })}</Typography>}
        <Typography variant="caption" color="text.secondary">{t('evaluation.scale')}</Typography>
      </Box>
      {sections.map((sec) => {
        const qs = evaluation.questions.filter((q) => q.section === sec);
        if (!qs.length) return null;
        return (
          <Card key={sec} sx={{ borderRadius: 3 }}>
            <CardContent>
              <Typography variant="subtitle1" sx={{ mb: 1 }}>{t(`evaluation.section${sec}`)}</Typography>
              <Stack spacing={2}>
                {qs.map((q) => q.kind === 'RATING' ? (
                  <Box key={q.id}>
                    <Typography variant="body2">{q.text}</Typography>
                    <RadioGroup row value={ratings[q.id] ?? ''} onChange={(e) => setRatings({ ...ratings, [q.id]: Number(e.target.value) })}>
                      {[1, 2, 3, 4, 5].map((n) => <FormControlLabel key={n} value={n} control={<Radio size="small" />} label={String(n)} sx={{ mr: 1.5 }} />)}
                    </RadioGroup>
                  </Box>
                ) : (
                  <TextField key={q.id} label={q.text} helperText={t('evaluation.optional')} multiline minRows={2} inputProps={{ maxLength: 1000 }}
                    value={comments[q.id] ?? ''} onChange={(e) => setComments({ ...comments, [q.id]: e.target.value })} />
                ))}
              </Stack>
            </CardContent>
          </Card>
        );
      })}
      {!complete && <Typography variant="body2" color="text.secondary">{t('evaluation.answerAll')}</Typography>}
      <ErrorAlert error={send.error} />
      <Button variant="contained" size="large" disabled={!complete || send.isPending} onClick={() => send.mutate()}>{t('evaluation.send')}</Button>
    </Stack>
  );
}

export function StudentWelfarePage() {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const requests = useQuery({ queryKey: ['student', 'welfare'], queryFn: () => api<MyRequest[]>('/api/student/welfare', student) });
  const cases = useQuery({ queryKey: ['student', 'discipline'], queryFn: () => api<MyCase[]>('/api/student/discipline', student) });
  const [concern, setConcern] = useState('ACADEMIC');
  const [text, setText] = useState('');
  const [phone, setPhone] = useState('');
  const [sent, setSent] = useState(false);
  const ask = useMutation({
    mutationFn: () => api<MyRequest[]>('/api/student/welfare', { ...student, method: 'POST', body: { concern, text, contactPhone: phone } }),
    onSuccess: (d) => { qc.setQueryData(['student', 'welfare'], d); setText(''); setSent(true); },
  });
  const openRequest = requests.data?.some((r) => r.status === 'OPEN');
  return (
    <Stack spacing={2}>
      <Typography variant="h5">{t('welfare.title')}</Typography>
      <Card sx={{ borderRadius: 3 }}>
        <CardContent>
          <Typography variant="subtitle1">{t('welfare.talk')}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{t('welfare.talkIntro')}</Typography>
          {sent && <Alert severity="success" sx={{ mb: 2 }}>{t('welfare.sent')}</Alert>}
          {!openRequest && (
            <Stack spacing={1.5}>
              <TextField select label={t('welfare.concern')} value={concern} onChange={(e) => setConcern(e.target.value)}>
                {['ACADEMIC', 'FINANCIAL', 'HEALTH', 'FAMILY', 'PERSONAL', 'OTHER'].map((c) => <MenuItem key={c} value={c}>{t(`welfare.${c}`)}</MenuItem>)}
              </TextField>
              <TextField label={t('welfare.text')} multiline minRows={3} value={text} onChange={(e) => setText(e.target.value)} inputProps={{ maxLength: 2000 }} />
              <TextField label={t('welfare.phone')} value={phone} onChange={(e) => setPhone(e.target.value)} inputMode="tel" />
              <ErrorAlert error={ask.error} />
              <Button variant="contained" disabled={ask.isPending} onClick={() => ask.mutate()}>{t('welfare.send')}</Button>
            </Stack>
          )}
          {!!requests.data?.length && (
            <>
              <Typography variant="subtitle2" sx={{ mt: 2 }}>{t('welfare.requests')}</Typography>
              {requests.data.map((r) => (
                <Typography key={r.id} variant="body2" sx={{ mt: 0.5 }}>
                  {t(`welfare.${r.concern}`)} · {r.status === 'CLOSED' ? t('welfare.closedReq') : r.nextSessionAt
                    ? t('welfare.appointment', { when: formatDateTime(r.nextSessionAt) }) : t('welfare.waiting')}
                </Typography>
              ))}
            </>
          )}
        </CardContent>
      </Card>
      <Typography variant="h6">{t('welfare.cases')}</Typography>
      {cases.isLoading && <Loading />}
      <ErrorAlert error={cases.error} />
      {cases.data?.length === 0 && <Typography variant="body2" color="text.secondary">{t('welfare.noCases')}</Typography>}
      {cases.data?.map((c) => <CaseCard key={c.id} c={c} />)}
    </Stack>
  );
}

function CaseCard({ c }: { c: MyCase }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [statement, setStatement] = useState(c.studentStatement ?? '');
  const [appeal, setAppeal] = useState('');
  const [appealing, setAppealing] = useState(false);
  const post = useMutation({
    mutationFn: (x: { path: string; text: string }) => api<MyCase[]>(`/api/student/discipline/${c.id}/${x.path}`, { ...student, method: 'POST', body: { text: x.text } }),
    onSuccess: (d) => { qc.setQueryData(['student', 'discipline'], d); setAppealing(false); },
  });
  return (
    <Card sx={{ borderRadius: 3 }}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={1}>
          <Typography variant="subtitle1">{t('welfare.caseNo', { no: c.caseNo })}</Typography>
          <Chip size="small" label={t(`welfare.status${c.status}`)} color={c.status === 'CLOSED' ? 'default' : 'warning'} />
        </Stack>
        <Typography variant="caption" color="text.secondary">{formatDate(c.occurredOn)}</Typography>
        {c.hearingAt && c.status === 'HEARING' && (
          <Alert severity="warning" sx={{ mt: 1 }}>{t('welfare.hearing', { when: formatDateTime(c.hearingAt), place: c.hearingPlace })}</Alert>
        )}
        {['REPORTED', 'HEARING'].includes(c.status) && (
          <Stack spacing={1} sx={{ mt: 1.5 }}>
            <TextField label={t('welfare.statement')} helperText={t('welfare.statementHint')} multiline minRows={3} value={statement}
              onChange={(e) => setStatement(e.target.value)} inputProps={{ maxLength: 2000 }} />
            <Button variant="outlined" disabled={!statement.trim() || post.isPending} onClick={() => post.mutate({ path: 'statement', text: statement })}>{t('welfare.sendStatement')}</Button>
          </Stack>
        )}
        {c.outcome && (
          <Box sx={{ mt: 1.5 }}>
            <Typography variant="subtitle2">{t('welfare.decision')}: {t(`welfare.outcome${c.outcome}`)}</Typography>
            {c.outcomeDetails && <Typography variant="body2">{c.outcomeDetails}</Typography>}
            {c.amount != null && c.controlNo && <Typography variant="body2">{t('welfare.pay', { amount: formatTzs(c.amount), cn: c.controlNo })}</Typography>}
            {c.suspendedFrom && <Typography variant="body2">{t('welfare.suspended', { from: formatDate(c.suspendedFrom), to: formatDate(c.suspendedTo) })}</Typography>}
            {c.canAppeal && c.appealBy && <Typography variant="body2" color="text.secondary">{t('welfare.appealBy', { date: formatDate(c.appealBy) })}</Typography>}
          </Box>
        )}
        {c.status === 'APPEALED' && <Alert severity="info" sx={{ mt: 1 }}>{t('welfare.appealed')}</Alert>}
        {c.appealResult && <Alert severity="info" sx={{ mt: 1 }}>{t(`welfare.appealResult${c.appealResult}`)} {c.appealNote}</Alert>}
        {c.canAppeal && !appealing && <Button sx={{ mt: 1 }} onClick={() => setAppealing(true)}>{t('welfare.appeal')}</Button>}
        {appealing && (
          <Stack spacing={1} sx={{ mt: 1.5 }}>
            <TextField label={t('welfare.appealText')} multiline minRows={3} value={appeal} onChange={(e) => setAppeal(e.target.value)} inputProps={{ maxLength: 2000 }} />
            <Button variant="contained" disabled={!appeal.trim() || post.isPending} onClick={() => post.mutate({ path: 'appeal', text: appeal })}>{t('welfare.sendAppeal')}</Button>
          </Stack>
        )}
        <ErrorAlert error={post.error} />
      </CardContent>
    </Card>
  );
}
