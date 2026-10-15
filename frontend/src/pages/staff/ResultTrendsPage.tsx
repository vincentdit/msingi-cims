import ArrowBackIcon from '@mui/icons-material/ArrowBack';
import { Alert, Box, Button, Card, CardContent, Chip, Container, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { api } from '../../api/client';
import type { Programme } from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';

const staff = { realm: 'staff' as const };
interface YearRow { academicYear: string; students: number; passed: number; passRate?: number; meanGpa?: number; decisions: Record<string, number>; grades: Record<string, number>; beforeCims: boolean }
interface Trends { years: string[]; rows: YearRow[]; modules: { code: string; title: string; passRateByYear: Record<string, number | null>; meanMarkByYear: Record<string, number | null> }[] }

/** Results compared across academic years (FR-EXM-14). */
export default function ResultTrendsPage() {
  const [programmeId, setProgrammeId] = useState('');
  const [year, setYear] = useState('');
  const programmes = useQuery({ queryKey: ['staff', 'programmes'], queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const q = useQuery({
    queryKey: ['trends', programmeId, year],
    queryFn: () => api<Trends>(`/api/exams/analytics/trends?${new URLSearchParams({ ...(programmeId ? { programmeId } : {}), ...(year ? { yearOfStudy: year } : {}) })}`, staff),
  });
  const t = q.data;
  const grades = t ? [...new Set(t.rows.flatMap((r) => Object.keys(r.grades)))].sort() : [];
  return (
    <Container maxWidth="lg" disableGutters>
      <Button component={RouterLink} to="/staff/results" startIcon={<ArrowBackIcon />} sx={{ mb: 1 }}>Results</Button>
      <PageTitle title="Results across years" subtitle="Pass rates, mean GPA, grades and module performance year on year, from confirmed results and results brought in from before CIMS." />
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mb: 2 }}>
        <TextField select size="small" label="Programme" value={programmeId} onChange={(e) => setProgrammeId(e.target.value)} sx={{ minWidth: 320 }}>
          <MenuItem value="">All my programmes</MenuItem>
          {(programmes.data ?? []).map((p) => <MenuItem key={p.id} value={p.id}>{p.code} · {p.name}</MenuItem>)}
        </TextField>
        <TextField select size="small" label="Year of study" value={year} onChange={(e) => setYear(e.target.value)} sx={{ minWidth: 160 }}>
          <MenuItem value="">All years</MenuItem>
          {[1, 2, 3, 4].map((y) => <MenuItem key={y} value={y}>Year {y}</MenuItem>)}
        </TextField>
      </Stack>
      <ErrorAlert error={q.error} />
      {q.isLoading ? <Loading /> : !t?.rows.length ? <Alert severity="info">No confirmed results yet for this choice.</Alert> : (
        <Stack spacing={2.5}>
          <Card><CardContent>
            <Typography variant="h6" sx={{ mb: 1 }}>Pass rate by academic year</Typography>
            {t.rows.map((r) => (
              <Stack key={r.academicYear} direction="row" spacing={1.5} alignItems="center" sx={{ my: 0.75 }}>
                <Typography sx={{ width: 90, fontVariantNumeric: 'tabular-nums' }}>{r.academicYear}</Typography>
                <Box sx={{ flex: 1, height: 18, bgcolor: 'action.hover', borderRadius: 1, overflow: 'hidden' }}>
                  <Box sx={{ width: `${r.passRate ?? 0}%`, height: '100%', bgcolor: 'primary.main' }} />
                </Box>
                <Typography sx={{ width: 160, fontVariantNumeric: 'tabular-nums' }}>{r.passRate ?? '—'}% · {r.passed}/{r.students}</Typography>
                {r.beforeCims && <Chip size="small" label="Before CIMS" />}
              </Stack>
            ))}
          </CardContent></Card>
          <Card><TableContainer>
            <Table size="small">
              <TableHead><TableRow><TableCell>Year</TableCell><TableCell align="right">Students</TableCell><TableCell align="right">Passed</TableCell><TableCell align="right">Mean GPA</TableCell>
                {grades.map((g) => <TableCell key={g} align="right">{g}</TableCell>)}<TableCell>Decisions</TableCell></TableRow></TableHead>
              <TableBody>
                {t.rows.map((r) => (
                  <TableRow key={r.academicYear}>
                    <TableCell>{r.academicYear}</TableCell><TableCell align="right">{r.students}</TableCell><TableCell align="right">{r.passRate ?? '—'}%</TableCell>
                    <TableCell align="right">{r.meanGpa ?? '—'}</TableCell>
                    {grades.map((g) => <TableCell key={g} align="right">{r.grades[g] ?? 0}</TableCell>)}
                    <TableCell>{Object.entries(r.decisions).map(([d, n]) => `${d.toLowerCase().replace(/_/g, ' ')} ${n}`).join(' · ')}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer></Card>
          <Card><CardContent>
            <Typography variant="h6" sx={{ mb: 1 }}>Modules year on year (first attempts)</Typography>
            <TableContainer>
              <Table size="small">
                <TableHead><TableRow><TableCell>Module</TableCell>{t.years.map((y) => <TableCell key={y} align="right">{y}</TableCell>)}</TableRow></TableHead>
                <TableBody>
                  {t.modules.map((m) => (
                    <TableRow key={m.code}>
                      <TableCell><b>{m.code}</b> {m.title}</TableCell>
                      {t.years.map((y) => (
                        <TableCell key={y} align="right" sx={{ color: (m.passRateByYear[y] ?? 100) < 50 ? 'error.main' : undefined }}>
                          {m.passRateByYear[y] != null ? `${m.passRateByYear[y]}%` : '—'}
                          {m.meanMarkByYear[y] != null && <Typography variant="caption" component="div" color="text.secondary">mean {m.meanMarkByYear[y]}</Typography>}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          </CardContent></Card>
        </Stack>
      )}
    </Container>
  );
}
