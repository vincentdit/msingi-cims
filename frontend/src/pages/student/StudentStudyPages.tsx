import AssignmentIcon from '@mui/icons-material/AssignmentOutlined';
import AttachIcon from '@mui/icons-material/AttachFile';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import LibraryIcon from '@mui/icons-material/LocalLibraryOutlined';
import LinkIcon from '@mui/icons-material/Link';
import MenuBookIcon from '@mui/icons-material/MenuBookOutlined';
import NoteIcon from '@mui/icons-material/DescriptionOutlined';
import UploadIcon from '@mui/icons-material/UploadFile';
import {
  Alert, Box, Button, Card, CardActionArea, CardContent, Chip, Divider, Link, Stack, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link as RouterLink, useNavigate, useParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime, formatTzs, uploadForm } from '../../api/client';
import { ErrorAlert, Loading } from '../../components/common';
import { WelfareRows } from './StudentWelfarePages';
import { fileSize, type Material } from '../staff/MaterialsPage';

const student = { realm: 'student' as const };

interface MyModule { offeringId: number; code: string; title: string; semester: string; notes: number; assignments: number; openAssignments: number; handedIn: number }
interface Loan { id: number; barcode: string; title: string; callNumber?: string; issuedAt: string; dueOn: string; renewals: number; returnedAt?: string; lost: boolean; daysLate: number; fine: number; fineStatus: string; controlNo?: string }
interface MyLibrary { onLoan: Loan[]; history: Loan[]; unpaidFines: number; maxLoans: number; loanDays: number }
interface CatalogueEntry { id: number; title: string; authors?: string; callNumber?: string; kind: string; copies: number; available: number; nextDue?: string }

/** Home page card: the semester's modules, with assignments still to hand in. */
export function StudyCard() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ['student', 'modules'], queryFn: () => api<MyModule[]>('/api/student/modules', student) });
  const open = (q.data ?? []).reduce((n, m) => n + m.openAssignments, 0);
  return (
    <Card sx={{ borderRadius: 3 }}>
      <CardActionArea onClick={() => navigate('/student/modules')}>
        <CardContent sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <MenuBookIcon color="primary" />
          <Box sx={{ flexGrow: 1 }}>
            <Typography variant="subtitle1">{t('study.title')}</Typography>
            <Typography variant="body2" color="text.secondary">
              {q.data ? (q.data.length === 0 ? t('study.noModules') : open > 0 ? t('study.openAssignments', { count: open }) : t('study.modules', { count: q.data.length })) : ' '}
            </Typography>
          </Box>
          {open > 0 && <Chip size="small" color="warning" label={open} />}
          <ChevronRightIcon color="action" />
        </CardContent>
      </CardActionArea>
      <Divider />
      <CardActionArea onClick={() => navigate('/student/library')}>
        <CardContent sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <LibraryIcon color="primary" />
          <Typography variant="subtitle1" sx={{ flexGrow: 1 }}>{t('library.title')}</Typography>
          <ChevronRightIcon color="action" />
        </CardContent>
      </CardActionArea>
      <WelfareRows />
    </Card>
  );
}

export function StudentModulesPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ['student', 'modules'], queryFn: () => api<MyModule[]>('/api/student/modules', student) });
  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} onRetry={() => q.refetch()} />;
  return (
    <Stack spacing={2}>
      <Box>
        <Typography variant="h5" component="h1">{t('study.title')}</Typography>
        {q.data[0] && <Typography variant="body2" color="text.secondary">{q.data[0].semester}</Typography>}
      </Box>
      {q.data.length === 0 && <Alert severity="info">{t('study.noModulesLong')}</Alert>}
      {q.data.map((m) => (
        <Card key={m.offeringId} sx={{ borderRadius: 3 }}>
          <CardActionArea onClick={() => navigate(`/student/modules/${m.offeringId}`)}>
            <CardContent sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Typography sx={{ fontWeight: 700 }}>{m.code}</Typography>
                <Typography variant="body2" color="text.secondary">{m.title}</Typography>
                <Stack direction="row" spacing={1} sx={{ mt: 0.75 }} flexWrap="wrap" useFlexGap>
                  <Chip size="small" icon={<NoteIcon />} label={t('study.notesCount', { count: m.notes })} />
                  <Chip size="small" icon={<AssignmentIcon />} label={t('study.assignmentsCount', { count: m.assignments })} />
                  {m.openAssignments > 0 && <Chip size="small" color="warning" label={t('study.toHandIn', { count: m.openAssignments })} />}
                </Stack>
              </Box>
              <ChevronRightIcon color="action" />
            </CardContent>
          </CardActionArea>
        </Card>
      ))}
    </Stack>
  );
}

export function StudentModulePage() {
  const { t } = useTranslation();
  const offeringId = Number(useParams().id);
  const modules = useQuery({ queryKey: ['student', 'modules'], queryFn: () => api<MyModule[]>('/api/student/modules', student) });
  const q = useQuery({ queryKey: ['student', 'materials', offeringId], queryFn: () => api<Material[]>(`/api/student/modules/${offeringId}/materials`, student) });
  const m = modules.data?.find((x) => x.offeringId === offeringId);
  const assignments = (q.data ?? []).filter((x) => x.kind === 'ASSIGNMENT');
  const notes = (q.data ?? []).filter((x) => x.kind === 'NOTE');
  return (
    <Stack spacing={2}>
      <Box>
        <Button component={RouterLink} to="/student/modules" size="small" sx={{ px: 0 }}>{t('study.allModules')}</Button>
        <Typography variant="h5" component="h1">{m ? m.code : ''}</Typography>
        {m && <Typography variant="body2" color="text.secondary">{m.title}</Typography>}
      </Box>
      <ErrorAlert error={q.error} onRetry={() => q.refetch()} />
      {q.isLoading && <Loading />}
      {q.data && q.data.length === 0 && <Alert severity="info">{t('study.nothingYet')}</Alert>}
      {assignments.length > 0 && <Typography variant="h6">{t('study.assignments')}</Typography>}
      {assignments.map((a) => <AssignmentCard key={a.id} a={a} offeringId={offeringId} />)}
      {notes.length > 0 && <Typography variant="h6">{t('study.notes')}</Typography>}
      {notes.map((n) => (
        <Card key={n.id} sx={{ borderRadius: 3 }}><CardContent>
          <Typography sx={{ fontWeight: 600 }}>{n.title}</Typography>
          <Typography variant="caption" color="text.secondary">{`${n.createdByName} · ${formatDate(n.createdAt)}`}</Typography>
          {n.body && <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mt: 1 }}>{n.body}</Typography>}
          <Attachments m={n} />
        </CardContent></Card>
      ))}
    </Stack>
  );
}

function Attachments({ m }: { m: Material }) {
  if (!m.fileName && !m.linkUrl) return null;
  return (
    <Stack spacing={0.75} sx={{ mt: 1.25 }}>
      {m.fileName && <Button variant="outlined" size="small" startIcon={<AttachIcon />} sx={{ alignSelf: 'flex-start', textTransform: 'none' }}
        onClick={() => download(`/api/student/materials/${m.id}/file`, 'student', m.fileName!)}>{`${m.fileName} (${fileSize(m.sizeBytes)})`}</Button>}
      {m.linkUrl && <Link href={m.linkUrl} target="_blank" rel="noopener noreferrer" variant="body2" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, wordBreak: 'break-all' }}>
        <LinkIcon fontSize="small" />{m.linkUrl}</Link>}
    </Stack>
  );
}

function AssignmentCard({ a, offeringId }: { a: Material; offeringId: number }) {
  const { t } = useTranslation();
  const qc = useQueryClient();
  const [file, setFile] = useState<File | null>(null);
  const [note, setNote] = useState('');
  const due = a.dueAt ? new Date(a.dueAt) : null;
  const past = !!due && due < new Date();
  const closed = past && !a.acceptLate;
  const mine = a.mine;
  const handIn = useMutation({
    mutationFn: () => { const f = new FormData(); f.append('file', file!); if (note) f.append('note', note); return uploadForm<Material>(`/api/student/materials/${a.id}/submission`, 'student', f, 'POST'); },
    onSuccess: () => { setFile(null); setNote(''); qc.invalidateQueries({ queryKey: ['student', 'materials', offeringId] }); qc.invalidateQueries({ queryKey: ['student', 'modules'] }); },
  });
  return (
    <Card sx={{ borderRadius: 3, borderLeft: 4, borderLeftColor: mine ? 'success.main' : past ? 'divider' : 'warning.main' }}>
      <CardContent>
        <Stack direction="row" spacing={1} alignItems="flex-start">
          <Box sx={{ flexGrow: 1 }}>
            <Typography sx={{ fontWeight: 600 }}>{a.title}</Typography>
            <Typography variant="caption" color={past ? 'text.secondary' : 'warning.main'}>{due ? t('study.due', { when: formatDateTime(a.dueAt) }) : ''}{a.maxScore ? ` · ${t('study.outOf', { n: a.maxScore })}` : ''}</Typography>
          </Box>
          {mine && <Chip size="small" color={mine.markedAt ? 'success' : 'default'} label={mine.markedAt ? (mine.score != null ? `${mine.score}${a.maxScore ? ` / ${a.maxScore}` : ''}` : t('study.marked')) : t('study.handedIn')} />}
        </Stack>
        {a.body && <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mt: 1 }}>{a.body}</Typography>}
        <Attachments m={a} />
        {mine && (
          <Box sx={{ mt: 1.5, p: 1.25, bgcolor: 'action.hover', borderRadius: 2 }}>
            <Typography variant="body2">{t('study.yourWork', { file: mine.fileName, when: formatDateTime(mine.submittedAt) })}{mine.late ? ` (${t('study.late')})` : ''}</Typography>
            <Button size="small" sx={{ px: 0 }} onClick={() => download(`/api/student/submissions/${mine.id}/file`, 'student', mine.fileName)}>{t('study.download')}</Button>
            {mine.feedback && <Alert severity="success" sx={{ mt: 1 }}>{mine.feedback}</Alert>}
          </Box>
        )}
        {!mine?.markedAt && !closed && (
          <Stack spacing={1.25} sx={{ mt: 1.5 }}>
            <Button component="label" variant="outlined" startIcon={<UploadIcon />} sx={{ alignSelf: 'flex-start' }}>
              {file ? file.name : mine ? t('study.replace') : t('study.chooseFile')}
              <input hidden type="file" accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.jpg,.jpeg,.png,.txt,.zip" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            </Button>
            {file && <TextField size="small" label={t('study.noteToLecturer')} value={note} onChange={(e) => setNote(e.target.value)} />}
            {file && <Button variant="contained" disabled={handIn.isPending} onClick={() => handIn.mutate()} sx={{ alignSelf: 'flex-start' }}>{t('study.handIn')}</Button>}
            {past && <Typography variant="caption" color="warning.main">{t('study.lateWarning')}</Typography>}
            <ErrorAlert error={handIn.error} />
          </Stack>
        )}
        {closed && !mine && <Alert severity="warning" sx={{ mt: 1.5 }}>{t('study.closed')}</Alert>}
      </CardContent>
    </Card>
  );
}

export function StudentLibraryPage() {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['student', 'library'], queryFn: () => api<MyLibrary>('/api/student/library', student) });
  const [search, setSearch] = useState('');
  const [term, setTerm] = useState('');
  const cat = useQuery({ queryKey: ['student', 'library', 'catalogue', term], enabled: term.length >= 2,
    queryFn: () => api<CatalogueEntry[]>(`/api/student/library/catalogue?q=${encodeURIComponent(term)}`, student) });
  const d = q.data;
  return (
    <Stack spacing={2}>
      <Typography variant="h5" component="h1">{t('library.title')}</Typography>
      <ErrorAlert error={q.error} onRetry={() => q.refetch()} />
      {q.isLoading && <Loading />}
      {d && (
        <>
          {d.unpaidFines > 0 && <Alert severity="warning">{t('library.fines', { amount: formatTzs(d.unpaidFines) })}</Alert>}
          <Card sx={{ borderRadius: 3 }}><CardContent>
            <Typography variant="subtitle1">{t('library.onLoan', { n: d.onLoan.length, max: d.maxLoans })}</Typography>
            {d.onLoan.length === 0 && <Typography variant="body2" color="text.secondary">{t('library.none', { days: d.loanDays })}</Typography>}
            <Stack divider={<Divider flexItem />}>
              {d.onLoan.map((l) => (
                <Box key={l.id} sx={{ py: 1 }}>
                  <Typography sx={{ fontWeight: 600 }}>{l.title}</Typography>
                  <Typography variant="body2" color={l.daysLate > 0 ? 'error.main' : 'text.secondary'}>
                    {l.daysLate > 0 ? t('library.late', { days: l.daysLate, due: formatDate(l.dueOn) }) : t('library.dueOn', { due: formatDate(l.dueOn) })}
                  </Typography>
                </Box>
              ))}
            </Stack>
          </CardContent></Card>
        </>
      )}
      <Card sx={{ borderRadius: 3 }}><CardContent>
        <Typography variant="subtitle1" sx={{ mb: 1 }}>{t('library.search')}</Typography>
        <form onSubmit={(e) => { e.preventDefault(); setTerm(search.trim()); }}>
          <Stack direction="row" spacing={1}>
            <TextField size="small" fullWidth placeholder={t('library.searchHint')} value={search} onChange={(e) => setSearch(e.target.value)} />
            <Button type="submit" variant="contained">{t('library.find')}</Button>
          </Stack>
        </form>
        <ErrorAlert error={cat.error} />
        {cat.data?.length === 0 && <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>{t('library.notFound')}</Typography>}
        <Stack divider={<Divider flexItem />} sx={{ mt: 1 }}>
          {cat.data?.map((c) => (
            <Box key={c.id} sx={{ py: 1 }}>
              <Typography sx={{ fontWeight: 600 }}>{c.title}</Typography>
              <Typography variant="caption" color="text.secondary">{[c.authors, c.callNumber].filter(Boolean).join(' · ')}</Typography>
              <Typography variant="body2" color={c.available > 0 ? 'success.main' : 'text.secondary'}>
                {c.kind === 'REFERENCE' ? t('library.reference') : c.available > 0 ? t('library.available', { n: c.available, of: c.copies })
                  : c.nextDue ? t('library.allOut', { due: formatDate(c.nextDue) }) : t('library.noCopies')}
              </Typography>
            </Box>
          ))}
        </Stack>
      </CardContent></Card>
      {d && d.history.length > 0 && (
        <Card sx={{ borderRadius: 3 }}><CardContent>
          <Typography variant="subtitle1">{t('library.history')}</Typography>
          <Stack divider={<Divider flexItem />}>
            {d.history.map((l) => (
              <Box key={l.id} sx={{ py: 1 }}>
                <Typography variant="body2" sx={{ fontWeight: 600 }}>{l.title}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {l.lost ? t('library.lost') : t('library.returned', { when: formatDate(l.returnedAt) })}
                  {l.fine > 0 && ` · ${t('library.fine', { amount: formatTzs(l.fine) })}${l.controlNo && l.fineStatus === 'BILLED' ? ` · ${l.controlNo}` : ''}${l.fineStatus === 'WAIVED' ? ` (${t('library.waived')})` : ''}`}
                </Typography>
              </Box>
            ))}
          </Stack>
        </CardContent></Card>
      )}
    </Stack>
  );
}
