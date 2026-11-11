import CheckIcon from '@mui/icons-material/CheckCircle';
import ErrorIcon from '@mui/icons-material/ErrorOutline';
import {
  Alert, Box, Button, Checkbox, Chip, CircularProgress, Dialog, DialogActions, DialogContent, DialogTitle, Link, MenuItem, Stack, Table, TableBody, TableCell,
  TableHead, TableRow, TextField, Typography, useMediaQuery, useTheme,
} from '@mui/material';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, formatDateTime } from '../../api/client';
import { ErrorAlert, Loading } from '../../components/common';
import type { AcademicYear } from '../../api/types';

const staff = { realm: 'staff' as const };

interface Row {
  id: number; code: string; name: string; awardFamily: string; awardLevel: number; durationSemesters: number; levels: string[]; nactvetProgrammeId?: string;
  idFromCatalogue: boolean; lastImport?: { at: string; outcome: string; message?: string; curriculumId?: number; byName: string }; note?: string;
}
interface Slot { year: number; semester: number; level: string; curriculumCode?: string; modules: number; message?: string }
interface Result {
  programmeId: number; programmeCode: string; outcome: 'IMPORTED' | 'FAILED'; curriculumId?: number; curriculumVersion?: number; replacedDraft: boolean;
  modulesCreated: number; modulesUpdated: number; modulesPlaced: number; slots: Slot[]; notes: string[]; message?: string;
}
type Outcome = { state: 'waiting' | 'working' | 'done' | 'failed'; result?: Result; error?: string };

/** Curricula from NACTVET for one or all programmes: each becomes the programme's draft, approved as usual. */
export default function NactvetCurriculumDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const years = useQuery({ queryKey: ['staff', 'years'], queryFn: () => api<AcademicYear[]>('/api/academic/years', staff) });
  const rows = useQuery({ queryKey: ['staff', 'nactvet-curricula'], queryFn: () => api<Row[]>('/api/academic/nactvet-curricula', staff) });
  const [yearId, setYearId] = useState('');
  const [intake, setIntake] = useState('SEPT');
  const [picked, setPicked] = useState<Set<number> | null>(null);
  const [ids, setIds] = useState<Record<number, string>>({});
  const [out, setOut] = useState<Record<number, Outcome>>({});
  const [running, setRunning] = useState(false);
  const [idError, setIdError] = useState<unknown>(null);
  const theme = useTheme();
  const phone = useMediaQuery(theme.breakpoints.down('sm'));

  const eligible = (rows.data ?? []).filter((r) => r.awardFamily === 'NTA');
  const chosen = picked ?? new Set(eligible.filter((r) => r.nactvetProgrammeId).map((r) => r.id));
  const year = yearId || (years.data?.find((y) => (y as { current?: boolean }).current)?.id ?? years.data?.[0]?.id ?? '').toString();

  const saveId = async (r: Row) => {
    setIdError(null);
    try {
      await api(`/api/academic/programmes/${r.id}/nactvet-programme-id`, { ...staff, method: 'PUT', body: { nactvetProgrammeId: ids[r.id] ?? '' } });
      await rows.refetch();
      setPicked(new Set([...chosen, r.id]));
    } catch (e) { setIdError(e); }
  };

  const run = async () => {
    setRunning(true);
    const list = eligible.filter((r) => chosen.has(r.id) && r.nactvetProgrammeId);
    setOut(Object.fromEntries(list.map((r) => [r.id, { state: 'waiting' } as Outcome])));
    // one programme at a time: each asks NACTVET once per level and semester
    for (const r of list) {
      setOut((o) => ({ ...o, [r.id]: { state: 'working' } }));
      try {
        const res = await api<Result>(`/api/academic/programmes/${r.id}/nactvet-curriculum`, { ...staff, method: 'POST', body: { effectiveYearId: Number(year), intake } });
        setOut((o) => ({ ...o, [r.id]: { state: res.outcome === 'IMPORTED' ? 'done' : 'failed', result: res } }));
      } catch (e) {
        setOut((o) => ({ ...o, [r.id]: { state: 'failed', error: e instanceof Error ? e.message : String(e) } }));
      }
    }
    setRunning(false);
    qc.invalidateQueries({ queryKey: ['staff', 'curricula'] });
    qc.invalidateQueries({ queryKey: ['staff', 'nactvet-curricula'] });
  };

  const started = Object.keys(out).length > 0;
  return (
    <Dialog open onClose={running ? undefined : onClose} fullWidth maxWidth="md" fullScreen={phone}>
      <DialogTitle>Curricula from NACTVET</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          CIMS asks NACTVET for each programme's modules, level by level and semester by semester, adds them to the module catalogue and puts them in the
          programme's draft curriculum. The Deputy Principal (Academic) then checks and approves it as usual.
        </Typography>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }}>
          <TextField select size="small" label="For students joining in" value={year} onChange={(e) => setYearId(e.target.value)} sx={{ minWidth: 200 }} disabled={running}>
            {(years.data ?? []).map((y) => <MenuItem key={y.id} value={String(y.id)}>{y.code}</MenuItem>)}
          </TextField>
          <TextField select size="small" label="Intake" value={intake} onChange={(e) => setIntake(e.target.value)} sx={{ minWidth: 160 }} disabled={running}>
            <MenuItem value="SEPT">September</MenuItem><MenuItem value="MARCH">March</MenuItem>
          </TextField>
        </Stack>
        {rows.isLoading && <Loading />}
        <ErrorAlert error={rows.error ?? idError} />
        {!!rows.data && (
          <Box sx={{ overflowX: 'auto' }}>
          <Table size="small" sx={{ minWidth: 680 }}>
            <TableHead><TableRow><TableCell padding="checkbox" /><TableCell>Programme</TableCell><TableCell>NACTVET</TableCell><TableCell>Result</TableCell></TableRow></TableHead>
            <TableBody>
              {rows.data.map((r) => {
                const o = out[r.id];
                const nta = r.awardFamily === 'NTA';
                return (
                  <TableRow key={r.id} sx={{ opacity: nta ? 1 : 0.55, verticalAlign: 'top' }}>
                    <TableCell padding="checkbox">
                      <Checkbox size="small" disabled={!nta || !r.nactvetProgrammeId || running} checked={chosen.has(r.id) && !!r.nactvetProgrammeId}
                        onChange={(e) => { const n = new Set(chosen); if (e.target.checked) n.add(r.id); else n.delete(r.id); setPicked(n); }} />
                    </TableCell>
                    <TableCell>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{r.code}</Typography>
                      <Typography variant="caption" color="text.secondary">{r.name}{nta ? ` · ${r.levels.join(', ')}` : ''}</Typography>
                      {r.lastImport && !o && (
                        <Typography variant="caption" component="div" color={r.lastImport.outcome === 'IMPORTED' ? 'text.secondary' : 'error.main'}>
                          Last: {formatDateTime(r.lastImport.at)} · {r.lastImport.message}
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell sx={{ width: 300, maxWidth: 300 }}>
                      {!nta ? <Typography variant="caption" color="text.secondary">{r.note}</Typography>
                        : r.nactvetProgrammeId ? <Typography variant="caption" sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{r.nactvetProgrammeId}</Typography>
                          : (
                            <Stack direction="row" spacing={0.5}>
                              <TextField size="small" fullWidth placeholder="NACTVET programme ID" value={ids[r.id] ?? ''} onChange={(e) => setIds({ ...ids, [r.id]: e.target.value })}
                                inputProps={{ style: { fontFamily: 'monospace', fontSize: 12 } }} />
                              <Button size="small" disabled={!ids[r.id]?.trim()} onClick={() => saveId(r)}>Save</Button>
                            </Stack>
                          )}
                    </TableCell>
                    <TableCell sx={{ minWidth: 200 }}>
                      {o?.state === 'waiting' && <Typography variant="caption" color="text.secondary">Waiting</Typography>}
                      {o?.state === 'working' && <Stack direction="row" spacing={1} alignItems="center"><CircularProgress size={14} /><Typography variant="caption">Asking NACTVET…</Typography></Stack>}
                      {o?.state === 'done' && o.result && (
                        <Box>
                          <Chip size="small" color="success" icon={<CheckIcon />} label={`Draft v${o.result.curriculumVersion}`} />
                          <Typography variant="caption" component="div">{o.result.modulesPlaced} modules · {o.result.modulesCreated} new{o.result.modulesUpdated ? ` · ${o.result.modulesUpdated} updated` : ''}</Typography>
                          {o.result.slots.filter((s) => s.message).map((s) => (
                            <Typography key={`${s.year}${s.semester}`} variant="caption" component="div" color="warning.main">Year {s.year} semester {s.semester} ({s.level}): {s.message}</Typography>
                          ))}
                          {o.result.notes.map((n) => <Typography key={n} variant="caption" component="div" color="warning.main">{n}</Typography>)}
                          <Link component="button" variant="caption" onClick={() => navigate(`/staff/curricula/${o.result!.curriculumId}`)}>Open the draft</Link>
                        </Box>
                      )}
                      {o?.state === 'failed' && (
                        <Box>
                          <Chip size="small" color="error" icon={<ErrorIcon />} label="Not imported" />
                          <Typography variant="caption" component="div" color="error.main">{o.result?.message ?? o.error}</Typography>
                          {o.result?.slots.filter((s) => s.message).map((s) => (
                            <Typography key={`${s.year}${s.semester}`} variant="caption" component="div" color="text.secondary">{s.level} semester {s.semester}: {s.message}</Typography>
                          ))}
                        </Box>
                      )}
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
          </Box>
        )}
        {started && !running && <Alert severity="info" sx={{ mt: 2 }}>Each imported curriculum is a draft: open it, settle any electives, and send it for approval.</Alert>}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={running}>{started && !running ? 'Close' : 'Cancel'}</Button>
        <Button variant="contained" disabled={running || !year || ![...chosen].some((id) => eligible.find((r) => r.id === id)?.nactvetProgrammeId)} onClick={run}>
          {running ? 'Getting curricula…' : `Get ${chosen.size === 1 ? 'the curriculum' : `${[...chosen].filter((id) => eligible.find((r) => r.id === id)?.nactvetProgrammeId).length} curricula`}`}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
