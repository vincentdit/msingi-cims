import DownloadIcon from '@mui/icons-material/Download';
import CheckIcon from '@mui/icons-material/CheckCircleOutline';
import BlockIcon from '@mui/icons-material/ErrorOutline';
import WarningIcon from '@mui/icons-material/WarningAmber';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField,
  Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, download, formatDate } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };

interface Year { id: number; code: string; current: boolean }
interface Check { code: string; severity: 'BLOCKER' | 'WARNING'; title: string; fix: string; link?: string; count: number; examples: string[] }
interface SheetRow { id: number; programmeCode: string; semester: string; yearOfStudy: number; status: string; nactvetStatus?: string; submittedAt?: string }
interface GraduationRow { id: number; name: string; ceremonyDate?: string; status: string; nactvetStatus?: string; nactvetReference?: string; graduands: number }
interface Returns {
  year: Year; years: Year[]; institutionNactvetNo?: string;
  registration: { students: number; notSent: number; submitted: number; registered: number; queried: number };
  results: { sheets: number; published: number; sent: number; accepted: number; queried: number; rows: SheetRow[] };
  graduations: GraduationRow[]; checks: Check[]; blockers: number; warnings: number;
}

const NACTVET: Record<string, string> = { SUBMITTED: 'Sent, waiting', ACCEPTED: 'Accepted', QUERIED: 'Queried', REGISTERED: 'Registered' };

/**
 * NACTVET returns for an academic year: student registration, results and graduand lists, where each stands with NACTVET, and the
 * data problems to put right before sending (FR-REG-07, FR-EXM-11, FR-GRD-02). The returns are still sent from their own pages.
 */
export default function NactvetReturnsPage() {
  const [yearId, setYearId] = useState<number | ''>('');
  const q = useQuery({ queryKey: ['nactvet-returns', yearId], queryFn: () => api<Returns>(`/api/nactvet-returns${yearId ? `?academicYearId=${yearId}` : ''}`, staff) });
  const xlsx = useMutation({ mutationFn: (r: Returns) => download(`/api/nactvet-returns/problems?academicYearId=${r.year.id}`, 'staff', `nactvet-checks-${r.year.code}.xlsx`) });
  const r = q.data;
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="NACTVET returns" subtitle="Student registration, results and graduand lists for the year, and what to put right before sending them."
        action={r && (
          <TextField select size="small" label="Academic year" value={yearId || r.year.id} onChange={(e) => setYearId(Number(e.target.value))} sx={{ width: 220 }}>
            {r.years.map((y) => <MenuItem key={y.id} value={y.id}>{y.code}{y.current ? ' (current)' : ''}</MenuItem>)}
          </TextField>
        )} />
      <ErrorAlert error={q.error} />
      {q.isLoading && <Loading />}
      {r && (
        <Stack spacing={2.5}>
          <Card><CardContent>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }} sx={{ mb: r.checks.length ? 2 : 0 }}>
              <Box sx={{ flexGrow: 1 }}>
                <Typography variant="h6">Checks before sending</Typography>
                <Typography variant="body2" color="text.secondary">
                  {r.institutionNactvetNo ? `Institution NACTVET registration number ${r.institutionNactvetNo}. ` : ''}
                  {r.checks.length === 0 ? 'Nothing to put right.' : `${r.blockers} to fix before sending, ${r.warnings} to look at.`}
                </Typography>
              </Box>
              {r.checks.length > 0 && (
                <Button variant="outlined" startIcon={<DownloadIcon />} disabled={xlsx.isPending} onClick={() => xlsx.mutate(r)}>Download the list to fix</Button>
              )}
            </Stack>
            <ErrorAlert error={xlsx.error} />
            {r.checks.length === 0 ? (
              <Stack direction="row" spacing={1} alignItems="center"><CheckIcon color="success" /><Typography variant="body2">The year's records are ready for NACTVET.</Typography></Stack>
            ) : (
              <Stack spacing={1.5} component="ul" sx={{ p: 0, m: 0, listStyle: 'none' }}>
                {r.checks.map((c) => (
                  <Box component="li" key={c.code} sx={{ display: 'flex', gap: 1.25, p: 1.5, borderRadius: 1, border: 1, borderColor: 'divider' }}>
                    {c.severity === 'BLOCKER' ? <BlockIcon color="error" aria-hidden /> : <WarningIcon color="warning" aria-hidden />}
                    <Box sx={{ flexGrow: 1 }}>
                      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>{c.title}</Typography>
                        <Chip size="small" label={c.severity === 'BLOCKER' ? 'Must fix' : 'Should fix'} color={c.severity === 'BLOCKER' ? 'error' : 'warning'} variant="outlined" />
                        <Chip size="small" label={c.count.toLocaleString()} />
                      </Stack>
                      <Typography variant="body2" color="text.secondary">{c.fix}</Typography>
                      {c.examples.length > 0 && (
                        <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 0.5 }}>
                          For example: {c.examples.join('; ')}{c.count > c.examples.length ? ` and ${c.count - c.examples.length} more` : ''}
                        </Typography>
                      )}
                    </Box>
                    {c.link && <Button size="small" component={StaffLink} to={c.link} sx={{ alignSelf: 'center', flexShrink: 0 }}>Open</Button>}
                  </Box>
                ))}
              </Stack>
            )}
          </CardContent></Card>

          <Grid container spacing={2}>
            <Grid size={{ xs: 12, md: 6 }}>
              <Card sx={{ height: '100%' }}><CardContent>
                <Stack direction="row" alignItems="baseline" sx={{ mb: 1 }}>
                  <Typography variant="h6" sx={{ flexGrow: 1 }}>Student registration</Typography>
                  <Button size="small" component={StaffLink} to="/staff/integrations">Open</Button>
                </Stack>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>Students who entered in {r.year.code} and are studying.</Typography>
                <Figures items={[['Students', r.registration.students], ['Not sent', r.registration.notSent], ['Waiting for NACTVET', r.registration.submitted],
                  ['Registered', r.registration.registered], ['Queried', r.registration.queried]]} />
              </CardContent></Card>
            </Grid>
            <Grid size={{ xs: 12, md: 6 }}>
              <Card sx={{ height: '100%' }}><CardContent>
                <Stack direction="row" alignItems="baseline" sx={{ mb: 1 }}>
                  <Typography variant="h6" sx={{ flexGrow: 1 }}>Results</Typography>
                  <Button size="small" component={StaffLink} to="/staff/results">Open</Button>
                </Stack>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>The year's main result sheets.</Typography>
                <Figures items={[['Sheets', r.results.sheets], ['Published', r.results.published], ['Sent', r.results.sent], ['Accepted', r.results.accepted],
                  ['Queried', r.results.queried]]} />
              </CardContent></Card>
            </Grid>
          </Grid>

          {r.results.rows.length > 0 && (
            <Card>
              <CardContent sx={{ pb: 0 }}><Typography variant="h6">Result sheets</Typography></CardContent>
              <TableContainer>
                <Table size="small" sx={{ minWidth: 640 }}>
                  <TableHead><TableRow>
                    <TableCell>Programme</TableCell><TableCell>Semester</TableCell><TableCell>Year of study</TableCell><TableCell>Stage</TableCell><TableCell>NACTVET</TableCell>
                  </TableRow></TableHead>
                  <TableBody>
                    {r.results.rows.map((s) => (
                      <TableRow key={s.id} hover component={StaffLink} to={`/staff/results/${s.id}`} sx={{ textDecoration: 'none' }}>
                        <TableCell>{s.programmeCode}</TableCell><TableCell>{s.semester}</TableCell><TableCell>{s.yearOfStudy}</TableCell>
                        <TableCell>{s.status.charAt(0) + s.status.slice(1).toLowerCase()}</TableCell>
                        <TableCell>{s.nactvetStatus ? `${NACTVET[s.nactvetStatus]}${s.submittedAt ? ` · ${formatDate(s.submittedAt)}` : ''}` : s.status === 'PUBLISHED' ? 'Not sent' : '—'}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            </Card>
          )}

          <Card>
            <CardContent sx={{ pb: r.graduations.length ? 0 : undefined }}>
              <Stack direction="row" alignItems="baseline">
                <Typography variant="h6" sx={{ flexGrow: 1 }}>Graduand lists</Typography>
                <Button size="small" component={StaffLink} to="/staff/graduations">Open</Button>
              </Stack>
              {r.graduations.length === 0 && <Typography variant="body2" color="text.secondary">No graduation in {r.year.code}.</Typography>}
            </CardContent>
            {r.graduations.length > 0 && (
              <TableContainer>
                <Table size="small" sx={{ minWidth: 560 }}>
                  <TableHead><TableRow>
                    <TableCell>Graduation</TableCell><TableCell>Ceremony</TableCell><TableCell align="right">Graduands</TableCell><TableCell>List</TableCell><TableCell>NACTVET</TableCell>
                  </TableRow></TableHead>
                  <TableBody>
                    {r.graduations.map((g) => (
                      <TableRow key={g.id} hover component={StaffLink} to={`/staff/graduations/${g.id}`} sx={{ textDecoration: 'none' }}>
                        <TableCell>{g.name}</TableCell><TableCell>{g.ceremonyDate ? formatDate(g.ceremonyDate) : '—'}</TableCell>
                        <TableCell align="right">{g.graduands}</TableCell>
                        <TableCell>{g.status === 'APPROVED' ? 'Approved by the Board' : g.status === 'SUBMITTED' ? 'With the Board' : 'Draft'}</TableCell>
                        <TableCell>{g.nactvetStatus ? `${NACTVET[g.nactvetStatus]}${g.nactvetReference ? ` · ${g.nactvetReference}` : ''}` : g.status === 'APPROVED' ? 'Not sent' : '—'}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Card>
          <Alert severity="info">Registration is sent from Integrations › NACTVET registration, results from each result sheet, and graduand lists from each graduation.</Alert>
        </Stack>
      )}
    </Container>
  );
}

function Figures({ items }: { items: [string, number][] }) {
  return (
    <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
      {items.map(([label, n]) => (
        <Box key={label}>
          <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 24, lineHeight: 1.2 }}>{n.toLocaleString()}</Typography>
          <Typography variant="caption" color="text.secondary">{label}</Typography>
        </Box>
      ))}
    </Stack>
  );
}
