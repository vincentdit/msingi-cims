import AssignmentIcon from '@mui/icons-material/AssignmentOutlined';
import AttachIcon from '@mui/icons-material/AttachFile';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import DownloadIcon from '@mui/icons-material/Download';
import LinkIcon from '@mui/icons-material/Link';
import NoteIcon from '@mui/icons-material/DescriptionOutlined';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, Link, Stack,
  Switch, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, ToggleButton, ToggleButtonGroup, Tooltip, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, download, formatDateTime, uploadForm } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';

const staff = { realm: 'staff' as const };

export interface Material {
  id: number; offeringId: number; kind: 'NOTE' | 'ASSIGNMENT'; title: string; body?: string; linkUrl?: string; fileName?: string; contentType?: string;
  sizeBytes?: number; dueAt?: string; maxScore?: number; acceptLate: boolean; published: boolean; createdByName: string; createdAt: string; updatedAt: string;
  submissions?: number; marked?: number; classSize?: number;
  mine?: { id: number; fileName: string; sizeBytes: number; submittedAt: string; late: boolean; timesSubmitted: number; note?: string; score?: number; feedback?: string; markedAt?: string };
}
interface Submission {
  id: number; studentId: number; registrationNo: string; studentName: string; fileName: string; sizeBytes: number; submittedAt: string; late: boolean;
  timesSubmitted: number; note?: string; score?: number; feedback?: string; markedByName?: string; markedAt?: string;
}
interface Sheet { assignment: Material; students: { studentId: number; registrationNo: string; studentName: string; submission?: Submission }[] }
interface Offering { id: number; code: string; title: string; semesterName?: string }

export const fileSize = (n?: number) => (n == null ? '' : n < 1024 * 1024 ? `${Math.max(1, Math.round(n / 1024))} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`);

/** Notes, links and assignments for one module offering (FR-TCH-06): what the lecturers post and the work students hand in. */
export default function MaterialsPage() {
  const offeringId = Number(useParams().offeringId);
  const qc = useQueryClient();
  const offering = useQuery({ queryKey: ['staff', 'offering', offeringId], queryFn: () => api<Offering>(`/api/exams/offerings/${offeringId}`, staff) });
  const q = useQuery({ queryKey: ['staff', 'materials', offeringId], queryFn: () => api<Material[]>(`/api/teaching/offerings/${offeringId}/materials`, staff) });
  const [posting, setPosting] = useState<'NOTE' | 'ASSIGNMENT' | null>(null);
  const [edit, setEdit] = useState<Material | null>(null);
  const [sheet, setSheet] = useState<Material | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff', 'materials', offeringId] });
  const remove = useMutation({ mutationFn: (m: Material) => api(`/api/teaching/materials/${m.id}`, { ...staff, method: 'DELETE' }), onSuccess: refresh });
  const o = offering.data;
  const list = q.data ?? [];
  return (
    <Box>
      <PageTitle title={o ? `${o.code} · Materials` : 'Materials'} subtitle={o ? `${o.title}${o.semesterName ? ` · ${o.semesterName}` : ''}` : 'Notes, links and assignments for the class.'}
        action={(
          <Stack direction="row" spacing={1}>
            <Button variant="outlined" startIcon={<NoteIcon />} onClick={() => setPosting('NOTE')}>Post notes</Button>
            <Button variant="contained" startIcon={<AssignmentIcon />} onClick={() => setPosting('ASSIGNMENT')}>Set an assignment</Button>
          </Stack>
        )} />
      <ErrorAlert error={q.error ?? remove.error} />
      {q.isLoading && <Loading />}
      {q.data && list.length === 0 && <Alert severity="info">Nothing posted yet. Students registered for the module see what you post in the student app.</Alert>}
      <Stack spacing={1.5}>
        {list.map((m) => (
          <Card key={m.id} sx={{ opacity: m.published ? 1 : 0.7 }}>
            <CardContent sx={{ display: 'flex', gap: 1.5, alignItems: 'flex-start' }}>
              {m.kind === 'ASSIGNMENT' ? <AssignmentIcon color="secondary" sx={{ mt: 0.3 }} /> : <NoteIcon color="primary" sx={{ mt: 0.3 }} />}
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Typography sx={{ fontWeight: 600 }}>{m.title}</Typography>
                  {!m.published && <Chip size="small" label="Hidden from students" />}
                  {m.kind === 'ASSIGNMENT' && m.dueAt && <Chip size="small" color={new Date(m.dueAt) < new Date() ? 'default' : 'warning'} variant="outlined"
                    label={`Due ${formatDateTime(m.dueAt)}`} />}
                </Stack>
                {m.body && <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mt: 0.5 }}>{m.body}</Typography>}
                <Stack direction="row" spacing={2} sx={{ mt: 0.75 }} flexWrap="wrap" useFlexGap>
                  {m.fileName && <Link component="button" variant="body2" onClick={() => download(`/api/teaching/materials/${m.id}/file`, 'staff', m.fileName!)}
                    sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}><AttachIcon fontSize="small" />{`${m.fileName} (${fileSize(m.sizeBytes)})`}</Link>}
                  {m.linkUrl && <Link href={m.linkUrl} target="_blank" rel="noopener noreferrer" variant="body2" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5 }}>
                    <LinkIcon fontSize="small" />{m.linkUrl.replace(/^https?:\/\//, '').slice(0, 60)}</Link>}
                </Stack>
                <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
                  {`${m.createdByName} · ${formatDateTime(m.createdAt)}`}
                  {m.kind === 'ASSIGNMENT' && ` · ${m.submissions ?? 0} of ${m.classSize ?? 0} handed in, ${m.marked ?? 0} marked`}
                </Typography>
              </Box>
              <Stack direction="row" spacing={0.5} sx={{ flexShrink: 0 }}>
                {m.kind === 'ASSIGNMENT' && <Button size="small" variant="outlined" onClick={() => setSheet(m)}>Work handed in</Button>}
                <Button size="small" onClick={() => setEdit(m)}>Edit</Button>
                <Tooltip title="Delete"><span><IconButton size="small" aria-label="Delete" disabled={remove.isPending || (m.submissions ?? 0) > 0}
                  onClick={() => { if (window.confirm(`Delete "${m.title}"?`)) remove.mutate(m); }}><DeleteIcon fontSize="small" /></IconButton></span></Tooltip>
              </Stack>
            </CardContent>
          </Card>
        ))}
      </Stack>
      {posting && <PostDialog offeringId={offeringId} kind={posting} onClose={() => setPosting(null)} onDone={() => { setPosting(null); refresh(); }} />}
      {edit && <EditDialog material={edit} onClose={() => setEdit(null)} onDone={() => { setEdit(null); refresh(); }} />}
      {sheet && <SubmissionsDialog material={sheet} onClose={() => { setSheet(null); refresh(); }} />}
    </Box>
  );
}

const localInput = (iso?: string) => {
  if (!iso) return '';
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
};

function PostDialog({ offeringId, kind, onClose, onDone }: { offeringId: number; kind: 'NOTE' | 'ASSIGNMENT'; onClose: () => void; onDone: () => void }) {
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [link, setLink] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [due, setDue] = useState('');
  const [maxScore, setMaxScore] = useState('');
  const [late, setLate] = useState(true);
  const [published, setPublished] = useState(true);
  const [tell, setTell] = useState(true);
  const post = useMutation({
    mutationFn: () => {
      const f = new FormData();
      f.append('kind', kind);
      f.append('title', title);
      if (body) f.append('body', body);
      if (link) f.append('linkUrl', link);
      if (file) f.append('file', file);
      if (kind === 'ASSIGNMENT') {
        if (due) f.append('dueAt', new Date(due).toISOString());
        if (maxScore) f.append('maxScore', maxScore);
        f.append('acceptLate', String(late));
        f.append('tellClass', String(tell));
      }
      f.append('published', String(published));
      return uploadForm<Material>(`/api/teaching/offerings/${offeringId}/materials`, 'staff', f, 'POST');
    },
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{kind === 'ASSIGNMENT' ? 'Set an assignment' : 'Post notes'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField label="Title" value={title} onChange={(e) => setTitle(e.target.value)} required autoFocus />
          <TextField label={kind === 'ASSIGNMENT' ? 'Instructions' : 'Text (optional)'} value={body} onChange={(e) => setBody(e.target.value)} multiline minRows={3} />
          <TextField label="Web link (optional)" value={link} onChange={(e) => setLink(e.target.value)} placeholder="https://" />
          <Box>
            <Button component="label" variant="outlined" startIcon={<AttachIcon />}>
              {file ? 'Choose another file' : 'Attach a file'}
              <input hidden type="file" accept=".pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.jpg,.jpeg,.png,.txt,.zip" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            </Button>
            <Typography variant="caption" color="text.secondary" sx={{ ml: 1.5 }}>{file ? `${file.name} (${fileSize(file.size)})` : 'PDF, Word, PowerPoint, Excel, pictures, text or ZIP; up to 20 MB'}</Typography>
          </Box>
          {kind === 'ASSIGNMENT' && (
            <>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <TextField type="datetime-local" label="Due" value={due} onChange={(e) => setDue(e.target.value)} required slotProps={{ inputLabel: { shrink: true } }} sx={{ flex: 1 }} />
                <TextField type="number" label="Marked out of (optional)" value={maxScore} onChange={(e) => setMaxScore(e.target.value)} sx={{ width: { sm: 200 } }} />
              </Stack>
              <FormControlLabel control={<Switch checked={late} onChange={(e) => setLate(e.target.checked)} />} label="Accept work after the due time (marked late)" />
              <FormControlLabel control={<Switch checked={tell} onChange={(e) => setTell(e.target.checked)} disabled={!published} />} label="Tell the class by SMS" />
            </>
          )}
          <FormControlLabel control={<Switch checked={published} onChange={(e) => setPublished(e.target.checked)} />} label="Students can see it now" />
          <ErrorAlert error={post.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={post.isPending || !title.trim() || (kind === 'ASSIGNMENT' && !due)} onClick={() => post.mutate()}>Post</Button>
      </DialogActions>
    </Dialog>
  );
}

function EditDialog({ material, onClose, onDone }: { material: Material; onClose: () => void; onDone: () => void }) {
  const [m, setM] = useState({ title: material.title, body: material.body ?? '', linkUrl: material.linkUrl ?? '', due: localInput(material.dueAt),
    maxScore: material.maxScore == null ? '' : String(material.maxScore), acceptLate: material.acceptLate, published: material.published });
  const save = useMutation({
    mutationFn: () => api<Material>(`/api/teaching/materials/${material.id}`, { ...staff, method: 'PUT', body: { title: m.title, body: m.body, linkUrl: m.linkUrl,
      dueAt: m.due ? new Date(m.due).toISOString() : null, maxScore: m.maxScore === '' ? null : Number(m.maxScore), acceptLate: m.acceptLate, published: m.published } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{`Edit ${material.kind === 'ASSIGNMENT' ? 'assignment' : 'notes'}`}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          <TextField label="Title" value={m.title} onChange={(e) => setM({ ...m, title: e.target.value })} required />
          <TextField label="Text" value={m.body} onChange={(e) => setM({ ...m, body: e.target.value })} multiline minRows={3} />
          <TextField label="Web link" value={m.linkUrl} onChange={(e) => setM({ ...m, linkUrl: e.target.value })} />
          {material.kind === 'ASSIGNMENT' && (
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField type="datetime-local" label="Due" value={m.due} onChange={(e) => setM({ ...m, due: e.target.value })} slotProps={{ inputLabel: { shrink: true } }} sx={{ flex: 1 }} />
              <TextField type="number" label="Marked out of" value={m.maxScore} onChange={(e) => setM({ ...m, maxScore: e.target.value })} sx={{ width: { sm: 180 } }} />
            </Stack>
          )}
          {material.kind === 'ASSIGNMENT' && <FormControlLabel control={<Switch checked={m.acceptLate} onChange={(e) => setM({ ...m, acceptLate: e.target.checked })} />} label="Accept late work" />}
          <FormControlLabel control={<Switch checked={m.published} onChange={(e) => setM({ ...m, published: e.target.checked })} />} label="Students can see it" />
          {material.fileName && <Typography variant="caption" color="text.secondary">To change the file, delete this item and post it again.</Typography>}
          <ErrorAlert error={save.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={save.isPending || !m.title.trim()} onClick={() => save.mutate()}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}

function SubmissionsDialog({ material, onClose }: { material: Material; onClose: () => void }) {
  const qc = useQueryClient();
  const key = ['staff', 'submissions', material.id];
  const q = useQuery({ queryKey: key, queryFn: () => api<Sheet>(`/api/teaching/materials/${material.id}/submissions`, staff) });
  const [marking, setMarking] = useState<Submission | null>(null);
  const [filter, setFilter] = useState<'all' | 'in' | 'missing'>('all');
  const rows = (q.data?.students ?? []).filter((s) => filter === 'all' || (filter === 'in' ? !!s.submission : !s.submission));
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{material.title}</DialogTitle>
      <DialogContent>
        <ErrorAlert error={q.error} />
        {q.isLoading && <Loading />}
        {q.data && (
          <>
            <Stack direction="row" alignItems="center" spacing={2} sx={{ mb: 1.5 }} flexWrap="wrap" useFlexGap>
              <Typography variant="body2" color="text.secondary" sx={{ flexGrow: 1 }}>
                {`${q.data.students.filter((s) => s.submission).length} of ${q.data.students.length} handed in · due ${formatDateTime(material.dueAt)}`}
              </Typography>
              <ToggleButtonGroup size="small" exclusive value={filter} onChange={(_, v) => v && setFilter(v)}>
                <ToggleButton value="all">All</ToggleButton><ToggleButton value="in">Handed in</ToggleButton><ToggleButton value="missing">Not handed in</ToggleButton>
              </ToggleButtonGroup>
            </Stack>
            <TableContainer>
              <Table size="small" sx={{ minWidth: 640 }}>
                <TableHead><TableRow><TableCell>Student</TableCell><TableCell>Handed in</TableCell><TableCell>File</TableCell><TableCell align="right">Score</TableCell><TableCell /></TableRow></TableHead>
                <TableBody>
                  {rows.map((s) => (
                    <TableRow key={s.studentId}>
                      <TableCell><Typography variant="body2" sx={{ fontWeight: 600 }}>{s.studentName}</Typography><Typography variant="caption" color="text.secondary">{s.registrationNo}</Typography></TableCell>
                      <TableCell>{s.submission ? <>{formatDateTime(s.submission.submittedAt)}{s.submission.late && <Chip size="small" color="warning" label="Late" sx={{ ml: 1 }} />}</> : <Typography variant="body2" color="text.secondary">Not yet</Typography>}</TableCell>
                      <TableCell>{s.submission && <Button size="small" startIcon={<DownloadIcon />} onClick={() => download(`/api/teaching/submissions/${s.submission!.id}/file`, 'staff', s.submission!.fileName)}>{s.submission.fileName.slice(0, 28)}</Button>}</TableCell>
                      <TableCell align="right">{s.submission?.score != null ? `${s.submission.score}${material.maxScore ? ` / ${material.maxScore}` : ''}` : '—'}</TableCell>
                      <TableCell align="right">{s.submission && <Button size="small" onClick={() => setMarking(s.submission!)}>{s.submission.markedAt ? 'Change' : 'Mark'}</Button>}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </>
        )}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
      {marking && <MarkDialog submission={marking} max={material.maxScore} onClose={() => setMarking(null)}
        onDone={() => { setMarking(null); qc.invalidateQueries({ queryKey: key }); }} />}
    </Dialog>
  );
}

function MarkDialog({ submission, max, onClose, onDone }: { submission: Submission; max?: number; onClose: () => void; onDone: () => void }) {
  const [score, setScore] = useState(submission.score == null ? '' : String(submission.score));
  const [feedback, setFeedback] = useState(submission.feedback ?? '');
  const save = useMutation({
    mutationFn: () => api(`/api/teaching/submissions/${submission.id}/mark`, { ...staff, method: 'PUT', body: { score: score === '' ? null : Number(score), feedback } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>{`Mark ${submission.studentName}`}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {submission.note && <Alert severity="info">{`Student's note: ${submission.note}`}</Alert>}
          <TextField type="number" label={max ? `Score (out of ${max})` : 'Score'} value={score} onChange={(e) => setScore(e.target.value)} />
          <TextField label="Feedback to the student" value={feedback} onChange={(e) => setFeedback(e.target.value)} multiline minRows={3} />
          <Typography variant="caption" color="text.secondary">Once marked, the student cannot replace the work. Enter coursework marks in Marks as usual.</Typography>
          <ErrorAlert error={save.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}

