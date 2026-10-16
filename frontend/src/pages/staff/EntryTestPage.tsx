import AddIcon from '@mui/icons-material/Add';
import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import CancelIcon from '@mui/icons-material/Cancel';
import {
  Alert, Autocomplete, Box, Button, Card, CardContent, Checkbox, Chip, Collapse, Container, FormControlLabel, IconButton, MenuItem, Stack, Table,
  TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Fragment, useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { api } from '../../api/client';
import type { AdmissionWindow, EntryRules } from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';

const staff = { realm: 'staff' as const };

/** NECTA subject names as they appear in results and in entry requirements. */
const CSEE_SUBJECTS = ['BASIC MATHEMATICS', 'ENGLISH LANGUAGE', 'KISWAHILI', 'BIOLOGY', 'CHEMISTRY', 'PHYSICS', 'CIVICS', 'HISTORY', 'GEOGRAPHY',
  'BOOK KEEPING', 'COMMERCE', 'COMPUTER STUDIES', 'AGRICULTURE', 'LITERATURE IN ENGLISH', 'BIBLE KNOWLEDGE', 'ISLAMIC KNOWLEDGE'];
const ACSEE_SUBJECTS = ['ADVANCED MATHEMATICS', 'PHYSICS', 'CHEMISTRY', 'BIOLOGY', 'GEOGRAPHY', 'HISTORY', 'ECONOMICS', 'COMMERCE', 'ACCOUNTANCY',
  'ENGLISH LANGUAGE', 'KISWAHILI', 'GENERAL STUDIES', 'BASIC APPLIED MATHEMATICS', 'COMPUTER SCIENCE'];
const GRADES = { CSEE: ['A', 'B', 'C', 'D', 'E', 'F'], ACSEE: ['A', 'B', 'C', 'D', 'E', 'S', 'F'] } as const;

type Exam = 'CSEE' | 'ACSEE';
type SubjectRow = { name: string; grade: string };
type Result = {
  entryLevel: string; basis: string; eligibleCount: number;
  programmes: {
    windowProgrammeId: number; code: string; name: string; awardFamily: string; awardLevel: number; atEntryLevel: boolean; eligible: boolean;
    triedChangedRules: boolean; requirementSummary?: string;
    detail: { eligible: boolean; matchedPathway?: string; summary?: string;
      pathways: { label: string; satisfied: boolean; conditions: { requirement: string; satisfied: boolean; found: string }[] }[] };
  }[];
};

const blankSubjects = (exam: Exam, n: number): SubjectRow[] =>
  Array.from({ length: n }, (_, i) => ({ name: exam === 'CSEE' ? CSEE_SUBJECTS[i] ?? '' : '', grade: exam === 'CSEE' ? 'D' : 'E' }));

/** Entry-rule test tool (FR-ADM-04): a sample applicant's results against every programme of the window; nothing is saved. */
export default function EntryTestPage() {
  const { id } = useParams();
  const w = useQuery({ queryKey: ['staff', 'window', id], queryFn: () => api<AdmissionWindow>(`/api/admission/windows/${id}`, staff) });
  const [csee, setCsee] = useState<SubjectRow[]>(blankSubjects('CSEE', 7));
  const [hasAcsee, setHasAcsee] = useState(false);
  const [acsee, setAcsee] = useState<SubjectRow[]>(blankSubjects('ACSEE', 3));
  const [award, setAward] = useState<{ family: '' | 'NTA' | 'NVA'; level: string }>({ family: '', level: '4' });
  const [tryRules, setTryRules] = useState(false);
  const [draft, setDraft] = useState({ windowProgrammeId: '', passes: '4', minGrade: 'D', subjects: 'BASIC MATHEMATICS', altFamily: '', altLevel: '3' });
  const [open, setOpen] = useState<number | null>(null);

  const run = useMutation({
    mutationFn: () => {
      const qualifications: unknown[] = [];
      const rows = (r: SubjectRow[]) => r.filter((s) => s.name.trim()).map((s) => ({ name: s.name.trim().toUpperCase(), grade: s.grade }));
      if (rows(csee).length) qualifications.push({ type: 'CSEE', subjects: rows(csee) });
      if (hasAcsee && rows(acsee).length) qualifications.push({ type: 'ACSEE', subjects: rows(acsee) });
      if (award.family) qualifications.push({ type: award.family, awardLevel: Number(award.level) });
      const body: Record<string, unknown> = { qualifications };
      if (tryRules && draft.windowProgrammeId) {
        body.windowProgrammeId = Number(draft.windowProgrammeId);
        body.rules = draftRules(draft);
      }
      return api<Result>(`/api/admission/windows/${id}/entry-test`, { ...staff, method: 'POST', body });
    },
  });

  if (w.isLoading) return <Loading />;
  if (!w.data) return <ErrorAlert error={w.error} />;
  const win = w.data;
  const r = run.data;

  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Test entry rules" subtitle={`${win.name}: type a sample applicant's results to see which programmes they could apply for, and why. Nothing is saved.`}
        action={<Button startIcon={<ArrowBackIcon />} component={RouterLink} to={`/staff/windows/${id}`}>Back to the window</Button>} />
      <Grid container spacing={2}>
        <Grid size={{ xs: 12, md: 5 }}>
          <Card>
            <CardContent>
              <Typography variant="h6">Sample applicant</Typography>
              <SubjectsEditor title="Form Four (CSEE)" exam="CSEE" rows={csee} onChange={setCsee} />
              <FormControlLabel sx={{ mt: 1 }} control={<Checkbox checked={hasAcsee} onChange={(e) => setHasAcsee(e.target.checked)} />} label="Also has Form Six (ACSEE) results" />
              {hasAcsee && <SubjectsEditor title="Form Six (ACSEE)" exam="ACSEE" rows={acsee} onChange={setAcsee} />}
              <Stack direction="row" spacing={2} sx={{ mt: 2 }}>
                <TextField select label="NACTVET or VETA award" value={award.family} onChange={(e) => setAward({ ...award, family: e.target.value as '' | 'NTA' | 'NVA' })} sx={{ minWidth: 200 }}>
                  <MenuItem value="">None</MenuItem><MenuItem value="NTA">NTA award</MenuItem><MenuItem value="NVA">NVA (VETA) award</MenuItem>
                </TextField>
                {award.family && <TextField select label="Level" value={award.level} onChange={(e) => setAward({ ...award, level: e.target.value })} sx={{ minWidth: 100 }}>
                  {(award.family === 'NVA' ? [1, 2, 3] : [4, 5, 6, 7, 8]).map((l) => <MenuItem key={l} value={String(l)}>{l}</MenuItem>)}
                </TextField>}
              </Stack>

              <FormControlLabel sx={{ mt: 2 }} control={<Checkbox checked={tryRules} onChange={(e) => setTryRules(e.target.checked)} />}
                label="Try changed requirements for one programme (before saving them)" />
              <Collapse in={tryRules}>
                <Stack spacing={2} sx={{ mt: 1 }}>
                  <TextField select label="Programme" value={draft.windowProgrammeId} onChange={(e) => setDraft({ ...draft, windowProgrammeId: e.target.value })}>
                    {(win.programmes ?? []).map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.programmeName}</MenuItem>)}
                  </TextField>
                  <Stack direction="row" spacing={2}>
                    <TextField label="CSEE passes (0 = none)" value={draft.passes} onChange={(e) => setDraft({ ...draft, passes: e.target.value })} />
                    <TextField select label="Minimum grade" value={draft.minGrade} onChange={(e) => setDraft({ ...draft, minGrade: e.target.value })} sx={{ minWidth: 120 }}>
                      {['A', 'B', 'C', 'D'].map((g) => <MenuItem key={g} value={g}>{g}</MenuItem>)}
                    </TextField>
                  </Stack>
                  <TextField label="Required subjects (comma separated)" value={draft.subjects} onChange={(e) => setDraft({ ...draft, subjects: e.target.value })} />
                  <Stack direction="row" spacing={2}>
                    <TextField select label="Alternative: prior award" value={draft.altFamily} onChange={(e) => setDraft({ ...draft, altFamily: e.target.value })} sx={{ minWidth: 180 }}>
                      <MenuItem value="">None</MenuItem><MenuItem value="NVA">NVA</MenuItem><MenuItem value="NTA">NTA</MenuItem>
                    </TextField>
                    <TextField label="Minimum level" value={draft.altLevel} onChange={(e) => setDraft({ ...draft, altLevel: e.target.value })} disabled={!draft.altFamily} />
                  </Stack>
                </Stack>
              </Collapse>
              <ErrorAlert error={run.error} />
              <Button variant="contained" sx={{ mt: 2 }} fullWidth onClick={() => run.mutate()} disabled={run.isPending || (tryRules && !draft.windowProgrammeId)}>
                Check against every programme
              </Button>
            </CardContent>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 7 }}>
          {!r && <Alert severity="info">The results appear here: the level this applicant may apply at, then each programme with every requirement and what was found.</Alert>}
          {r && (
            <Card>
              <CardContent>
                <Typography variant="h6">Can apply for {r.eligibleCount} of {r.programmes.length} programmes</Typography>
                <Typography variant="body2" color="text.secondary">Entry level: {r.entryLevel} (from the {r.basis})</Typography>
              </CardContent>
              <TableContainer>
                <Table size="small">
                  <TableHead><TableRow><TableCell>Programme</TableCell><TableCell>Entry level</TableCell><TableCell>Requirements</TableCell><TableCell /></TableRow></TableHead>
                  <TableBody>
                    {r.programmes.map((p) => (
                      <Fragment key={p.windowProgrammeId}>
                        <TableRow hover onClick={() => setOpen(open === p.windowProgrammeId ? null : p.windowProgrammeId)} sx={{ cursor: 'pointer' }}>
                          <TableCell>
                            <Typography variant="body2" sx={{ fontWeight: 600 }}>{p.name}</Typography>
                            <Typography variant="caption" color="text.secondary">{p.awardFamily} Level {p.awardLevel}{p.triedChangedRules ? ' · changed requirements tried' : ''}</Typography>
                          </TableCell>
                          <TableCell>{p.atEntryLevel ? <Chip size="small" color="success" variant="outlined" label="At entry level" />
                            : <Chip size="small" variant="outlined" label="Not at entry level" />}</TableCell>
                          <TableCell>{p.eligible ? <Chip size="small" color="success" icon={<CheckCircleIcon />} label={`Meets them${p.detail.matchedPathway ? ` (${p.detail.matchedPathway})` : ''}`} />
                            : <Chip size="small" color="warning" icon={<CancelIcon />} label="Does not meet them" />}</TableCell>
                          <TableCell align="right"><Button size="small">{open === p.windowProgrammeId ? 'Hide' : 'Why?'}</Button></TableCell>
                        </TableRow>
                        <TableRow>
                          <TableCell colSpan={4} sx={{ py: 0, borderBottom: open === p.windowProgrammeId ? undefined : 'none' }}>
                            <Collapse in={open === p.windowProgrammeId}>
                              <Box sx={{ py: 1.5 }}>
                                {p.requirementSummary && <Typography variant="body2" sx={{ mb: 1 }}><strong>Requirements:</strong> {p.requirementSummary}</Typography>}
                                {!p.atEntryLevel && <Alert severity="info" sx={{ mb: 1 }}>{p.detail.summary}</Alert>}
                                {p.detail.pathways.map((pw) => (
                                  <Box key={pw.label} sx={{ mb: 1 }}>
                                    <Typography variant="subtitle2">{pw.label}: {pw.satisfied ? 'met' : 'not met'}</Typography>
                                    {pw.conditions.map((c) => (
                                      <Stack key={c.requirement} direction="row" spacing={1} alignItems="center">
                                        {c.satisfied ? <CheckCircleIcon fontSize="small" color="success" /> : <CancelIcon fontSize="small" color="warning" />}
                                        <Typography variant="body2">{c.requirement}</Typography>
                                        <Typography variant="body2" color="text.secondary">· found: {c.found}</Typography>
                                      </Stack>
                                    ))}
                                  </Box>
                                ))}
                              </Box>
                            </Collapse>
                          </TableCell>
                        </TableRow>
                      </Fragment>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            </Card>
          )}
        </Grid>
      </Grid>
    </Container>
  );
}

function SubjectsEditor({ title, exam, rows, onChange }: { title: string; exam: Exam; rows: SubjectRow[]; onChange: (r: SubjectRow[]) => void }) {
  const options = exam === 'CSEE' ? CSEE_SUBJECTS : ACSEE_SUBJECTS;
  const set = (i: number, patch: Partial<SubjectRow>) => onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <Box sx={{ mt: 2 }}>
      <Typography variant="subtitle2" sx={{ mb: 1 }}>{title}</Typography>
      <Stack spacing={1}>
        {rows.map((r, i) => (
          <Stack key={i} direction="row" spacing={1} alignItems="center">
            <Autocomplete freeSolo options={options} value={r.name} onInputChange={(_, v) => set(i, { name: v })} sx={{ flexGrow: 1 }}
              renderInput={(p) => <TextField {...p} size="small" label={`Subject ${i + 1}`} />} />
            <TextField select size="small" label="Grade" value={r.grade} onChange={(e) => set(i, { grade: e.target.value })} sx={{ width: 90 }}>
              {GRADES[exam].map((g) => <MenuItem key={g} value={g}>{g}</MenuItem>)}
            </TextField>
            <Tooltip title="Remove"><IconButton size="small" aria-label={`Remove subject ${i + 1}`} onClick={() => onChange(rows.filter((_, j) => j !== i))}><DeleteIcon fontSize="small" /></IconButton></Tooltip>
          </Stack>
        ))}
      </Stack>
      <Button size="small" startIcon={<AddIcon />} sx={{ mt: 0.5 }} onClick={() => onChange([...rows, { name: '', grade: exam === 'CSEE' ? 'D' : 'E' }])}>Add subject</Button>
    </Box>
  );
}

function draftRules(f: { passes: string; minGrade: string; subjects: string; altFamily: string; altLevel: string }): EntryRules {
  const subjects = f.subjects.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean);
  const conditions = [];
  if (Number(f.passes) > 0) conditions.push({ type: 'CSEE_MIN_PASSES' as const, count: Number(f.passes), minGrade: f.minGrade });
  if (subjects.length) conditions.push({ type: 'CSEE_SUBJECT' as const, count: subjects.length, minGrade: f.minGrade, subjects });
  const pathways: EntryRules['pathways'] = [];
  if (conditions.length) pathways.push({ label: 'Form Four (CSEE)', conditions });
  if (f.altFamily) pathways.push({ label: `${f.altFamily} Level ${f.altLevel} holders`, conditions: [{ type: 'PRIOR_AWARD', family: f.altFamily, minLevel: Number(f.altLevel) }] });
  return { summary: 'Changed requirements (not saved)', pathways };
}
