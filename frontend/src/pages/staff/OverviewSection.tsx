import CheckIcon from '@mui/icons-material/CheckCircleOutline';
import WarningIcon from '@mui/icons-material/WarningAmber';
import {
  Box, Card, CardContent, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { api, formatTzs } from '../../api/client';
import type { Overview, OverviewProgramme } from '../../api/types';
import { ColumnChart } from '../../components/charts';
import { ErrorAlert, Loading } from '../../components/common';

const staff = { realm: 'staff' as const };
const pct = (v?: number | null) => (v == null ? '—' : `${Math.round(v * 100)}%`);
const tzsShort = (n: number) => (n >= 1_000_000 ? `TZS ${(n / 1_000_000).toFixed(n >= 10_000_000 ? 0 : 1)}M` : n >= 1000 ? `TZS ${Math.round(n / 1000)}k` : `TZS ${n}`);

/**
 * The institution overview for the Principal and management: the year's students, intake against places, fee collection,
 * pass rate and demand, by programme, with what needs attention. Figures only; nothing here changes data.
 */
export function OverviewSection() {
  const [yearId, setYearId] = useState<number | ''>('');
  const q = useQuery({ queryKey: ['staff', 'overview', yearId], refetchInterval: 300_000,
    queryFn: () => api<Overview>(`/api/dashboard/overview${yearId ? `?academicYearId=${yearId}` : ''}`, staff) });
  const o = q.data;
  if (q.isLoading) return <Loading />;
  if (!o) return <ErrorAlert error={q.error} />;
  const t = o.totals, th = o.thresholds;
  const flagged = o.programmes.filter((p) => p.attention.length);

  return (
    <Box component="section">
      <Stack direction={{ xs: 'column', sm: 'row' }} alignItems={{ sm: 'center' }} spacing={2} sx={{ mb: 2 }}>
        <Box sx={{ flexGrow: 1 }}>
          <Typography variant="h5" component="h2">Institution overview</Typography>
          <Typography variant="body2" color="text.secondary">Enrolment, intake, fees, results and demand for the academic year, by programme.</Typography>
        </Box>
        {o.year && (
          <TextField select size="small" label="Academic year" value={yearId || o.year.id} onChange={(e) => setYearId(Number(e.target.value))} sx={{ width: { xs: '100%', sm: 230 }, flexShrink: 0 }}>
            {o.years.map((y) => <MenuItem key={y.id} value={y.id}>{y.code}{y.current ? ' (current)' : ''}</MenuItem>)}
          </TextField>
        )}
      </Stack>
      <ErrorAlert error={q.error} />

      <Grid container spacing={1.5} sx={{ mb: 1.5 }}>
        <Tile label="Current students" value={t.students.toLocaleString()} hint={`${t.female.toLocaleString()} female · ${t.male.toLocaleString()} male`} />
        <Tile label={`New students ${o.year?.code ?? ''}`} value={t.newStudents.toLocaleString()}
          hint={t.places ? `${pct(t.intakeRate)} of ${t.places.toLocaleString()} accredited places` : 'No accredited places recorded'}
          low={t.intakeRate != null && t.intakeRate < th.lowIntake} />
        <Tile label="Fees collected" value={pct(t.collectionRate)}
          hint={t.billed ? `${formatTzs(t.collected)} of ${formatTzs(t.billed)} · ${formatTzs(t.outstanding)} outstanding` : 'No annual fees billed for this year yet'}
          low={t.collectionRate != null && t.collectionRate < th.lowCollection} />
        <Tile label="Pass rate" value={pct(t.passRate)}
          hint={t.assessed ? `${t.passed.toLocaleString()} of ${t.assessed.toLocaleString()} students passed (published results)` : 'No results published for this year yet'}
          low={t.passRate != null && t.passRate < th.lowPass} />
        <Tile label="Applicants per place" value={t.applicantsPerPlace == null ? '—' : t.applicantsPerPlace.toFixed(1)}
          hint={t.windowName ? `${t.applicants.toLocaleString()} first-choice ${t.applicants === 1 ? 'applicant' : 'applicants'} for ${t.windowPlaces.toLocaleString()} places · ${t.windowName}` : 'No admission window this year'}
          low={t.applicantsPerPlace != null && t.applicantsPerPlace < th.lowDemand} />
      </Grid>

      <Grid container spacing={1.5}>
        <Grid size={{ xs: 12, md: 5 }}>
          <Card sx={{ height: '100%' }}><CardContent>
            <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>Needs attention</Typography>
            <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 1.5 }}>
              Programmes with intake below {pct(th.lowIntake)} of places, fees collected below {pct(th.lowCollection)}, a pass rate below {pct(th.lowPass)},
              or fewer first-choice applicants than places.
            </Typography>
            {flagged.length ? (
              <Stack spacing={1.25} component="ul" sx={{ p: 0, m: 0, listStyle: 'none' }}>
                {flagged.map((p) => (
                  <Box component="li" key={p.id} sx={{ display: 'flex', gap: 1 }}>
                    <WarningIcon fontSize="small" sx={{ color: 'warning.main', mt: '2px' }} aria-hidden />
                    <Box>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{p.code} · {p.name}</Typography>
                      <Typography variant="body2" color="text.secondary">{p.attention.join(' · ')}</Typography>
                    </Box>
                  </Box>
                ))}
              </Stack>
            ) : (
              <Stack direction="row" spacing={1} alignItems="center">
                <CheckIcon fontSize="small" sx={{ color: 'success.main' }} aria-hidden />
                <Typography variant="body2">Every programme is within the thresholds.</Typography>
              </Stack>
            )}
          </CardContent></Card>
        </Grid>
        <Grid size={{ xs: 12, md: 7 }}>
          <Card sx={{ height: '100%' }}><CardContent>
            <Grid container spacing={2}>
              <Grid size={12}>
                <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 1.5 }}>New students by year of entry</Typography>
                <ColumnChart ariaLabel="New students by academic year of entry" height={120}
                  points={o.intakeByYear.map((p) => ({ ...p, tooltip: `New students ${p.label}` }))} />
              </Grid>
              <Grid size={12}>
                <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 1.5 }}>Payments received by month</Typography>
                <ColumnChart ariaLabel="Payments received per month over the last twelve months" height={120} format={tzsShort}
                  points={o.collectionsByMonth.map((p) => ({ ...p, tooltip: p.key }))} />
              </Grid>
            </Grid>
          </CardContent></Card>
        </Grid>

        <Grid size={12}>
          <Card>
            <CardContent sx={{ pb: 0 }}>
              <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>By programme</Typography>
            </CardContent>
            <TableContainer>
              <Table size="small" sx={{ minWidth: 820 }}>
                <TableHead>
                  <TableRow>
                    <TableCell>Programme</TableCell>
                    <TableCell align="right">Students</TableCell>
                    <TableCell align="right">New / places</TableCell>
                    <TableCell align="right">Fees collected</TableCell>
                    <TableCell align="right">Outstanding</TableCell>
                    <TableCell align="right">Pass rate</TableCell>
                    <TableCell align="right">Applicants per place</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {o.programmes.map((p) => <ProgrammeLine key={p.id} p={p} th={th} />)}
                  <TableRow sx={{ '& td': { fontWeight: 700, borderBottom: 0 } }}>
                    <TableCell>All programmes</TableCell>
                    <TableCell align="right">{t.students.toLocaleString()}</TableCell>
                    <TableCell align="right">{t.newStudents} / {t.places || '—'}</TableCell>
                    <TableCell align="right">{pct(t.collectionRate)}</TableCell>
                    <TableCell align="right">{formatTzs(t.outstanding)}</TableCell>
                    <TableCell align="right">{pct(t.passRate)}</TableCell>
                    <TableCell align="right">{t.applicantsPerPlace == null ? '—' : t.applicantsPerPlace.toFixed(1)}</TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </TableContainer>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
}

function Tile({ label, value, hint, low }: { label: string; value: ReactNode; hint?: ReactNode; low?: boolean }) {
  return (
    <Grid size={{ xs: 12, sm: 6, md: 'grow' }}>
      <Card sx={{ height: '100%' }}><CardContent sx={{ py: 1.75, '&:last-child': { pb: 1.75 } }}>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
        <Stack direction="row" spacing={0.75} alignItems="center">
          <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: { xs: 22, md: 28 }, lineHeight: 1.25 }}>{value}</Typography>
          {low && <Stack direction="row" spacing={0.25} alignItems="center" sx={{ color: 'warning.dark' }}>
            <WarningIcon sx={{ fontSize: 18 }} aria-hidden /><Typography variant="caption" sx={{ fontWeight: 600 }}>Below target</Typography>
          </Stack>}
        </Stack>
        {hint && <Typography variant="caption" color="text.secondary">{hint}</Typography>}
      </CardContent></Card>
    </Grid>
  );
}

/** A figure with the warning icon when it is below its threshold (status shown by icon and text, not colour alone). */
function Flag({ value, low, label }: { value: ReactNode; low: boolean; label: string }) {
  return (
    <Box component="span" sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, color: low ? 'warning.dark' : undefined, fontWeight: low ? 600 : undefined }}>
      {low && <WarningIcon sx={{ fontSize: 16 }} titleAccess={label} />}{value}
    </Box>
  );
}

function ProgrammeLine({ p, th }: { p: OverviewProgramme; th: Overview['thresholds'] }) {
  return (
    <TableRow hover>
      <TableCell>
        <Typography variant="body2" sx={{ fontWeight: 600 }}>{p.code} · {p.name}</Typography>
        <Typography variant="caption" color="text.secondary">{p.award}</Typography>
      </TableCell>
      <TableCell align="right">
        {p.students.toLocaleString()}
        <Typography variant="caption" color="text.secondary" display="block">{p.female} F · {p.male} M</Typography>
      </TableCell>
      <TableCell align="right">
        <Flag value={`${p.newStudents} / ${p.places ?? '—'}`} low={p.intakeRate != null && p.intakeRate < th.lowIntake} label="Intake below target" />
        <Typography variant="caption" color="text.secondary" display="block">{pct(p.intakeRate)}</Typography>
      </TableCell>
      <TableCell align="right">
        <Flag value={pct(p.collectionRate)} low={p.collectionRate != null && p.collectionRate < th.lowCollection} label="Fee collection below target" />
        {p.billed > 0 && <Typography variant="caption" color="text.secondary" display="block">{formatTzs(p.collected)}</Typography>}
      </TableCell>
      <TableCell align="right">{p.billed > 0 ? formatTzs(p.outstanding) : '—'}</TableCell>
      <TableCell align="right">
        <Flag value={pct(p.passRate)} low={p.passRate != null && p.passRate < th.lowPass} label="Pass rate below target" />
        {p.assessed > 0 && <Typography variant="caption" color="text.secondary" display="block">{p.passed} of {p.assessed}</Typography>}
      </TableCell>
      <TableCell align="right">
        <Flag value={p.applicantsPerPlace == null ? '—' : p.applicantsPerPlace.toFixed(1)} low={p.applicantsPerPlace != null && p.applicantsPerPlace < th.lowDemand}
          label="Fewer applicants than places" />
        {p.windowPlaces > 0 && <Typography variant="caption" color="text.secondary" display="block">{p.applicants} for {p.windowPlaces}</Typography>}
      </TableCell>
    </TableRow>
  );
}
