import { Alert, Box, Container, MenuItem, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { Almanac, AlmanacYear, OrgStructure } from '../../api/types';
import { AlmanacView } from '../../components/Almanac';
import { ErrorAlert, Loading } from '../../components/common';
import { useInstitution } from '../../components/InstitutionContext';
import { OrgChart, OrgList } from '../../components/OrgChart';

/**
 * A published almanac: on the public site (the entries for everyone) or on the student portal (also the entries for students).
 */
export function PublishedAlmanac({ realm }: { realm?: 'student' }) {
  const inst = useInstitution();
  const base = realm ? '/api/academic/almanac' : '/api/public/almanac';
  const opts = realm ? { realm } : {};
  const years = useQuery({ queryKey: [realm ?? 'public', 'almanac-years'], queryFn: () => api<AlmanacYear[]>(`${base}/years`, opts) });
  const [yearId, setYearId] = useState<number>();
  useEffect(() => { if (!yearId && years.data?.length) setYearId((years.data.find((y) => y.current) ?? years.data[0]).id); }, [years.data, yearId]);
  const a = useQuery({ queryKey: [realm ?? 'public', 'almanac', yearId], enabled: !!yearId,
    queryFn: () => api<Almanac>(`${base}?academicYearId=${yearId}`, opts) });

  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'flex-end' }} spacing={2} sx={{ mb: 2.5 }}>
        <Box>
          <Typography variant="h4" component="h1" sx={{ fontSize: { xs: 26, md: 32 } }}>Almanac</Typography>
          <Typography color="text.secondary">{`The year at ${inst.name}: teaching, examinations, events, deadlines and holidays.`}</Typography>
        </Box>
        {(years.data?.length ?? 0) > 1 && (
          <TextField select size="small" label="Academic year" value={yearId ?? ''} onChange={(e) => setYearId(Number(e.target.value))} sx={{ minWidth: 160, '@media print': { display: 'none' } }}>
            {years.data!.map((y) => <MenuItem key={y.id} value={y.id}>{y.code}</MenuItem>)}
          </TextField>
        )}
      </Stack>
      <ErrorAlert error={years.error ?? a.error} />
      {(years.isLoading || a.isLoading) && <Loading />}
      {years.data && !years.data.length && <Alert severity="info">The almanac has not been published yet.</Alert>}
      {a.data && <AlmanacView almanac={a.data} title={`${inst.name} — Almanac ${a.data.academicYear}`} />}
    </Box>
  );
}

export function AlmanacPublicPage() {
  return <Container maxWidth="md" sx={{ py: { xs: 3, md: 5 } }}><PublishedAlmanac /></Container>;
}

/** The published organisation structure on the public site. */
export function OrganisationPublicPage() {
  const inst = useInstitution();
  const q = useQuery({ queryKey: ['public', 'organisation'], retry: false, queryFn: () => api<OrgStructure>('/api/public/organisation') });
  const [view, setView] = useState<'chart' | 'list'>(() => (window.innerWidth < 700 ? 'list' : 'chart'));
  const missing = (q.error as { problem?: { status?: number } } | null)?.problem?.status === 404;
  return (
    <Container maxWidth="xl" sx={{ py: { xs: 3, md: 5 } }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'flex-end' }} spacing={2} sx={{ mb: 2.5 }}>
        <Box>
          <Typography variant="h4" component="h1" sx={{ fontSize: { xs: 26, md: 32 } }}>Organisation structure</Typography>
          <Typography color="text.secondary">{`How ${inst.name} is organised, and who leads each part.`}</Typography>
        </Box>
        {q.data && (
          <ToggleButtonGroup size="small" exclusive value={view} onChange={(_, v) => v && setView(v)} sx={{ '@media print': { display: 'none' } }}>
            <ToggleButton value="chart">Chart</ToggleButton>
            <ToggleButton value="list">List</ToggleButton>
          </ToggleButtonGroup>
        )}
      </Stack>
      {q.isLoading && <Loading />}
      {missing && <Alert severity="info">The organisation structure has not been published yet.</Alert>}
      {q.error && !missing && <ErrorAlert error={q.error} onRetry={() => q.refetch()} />}
      {q.data && (view === 'chart' ? <OrgChart roots={q.data.roots} /> : <OrgList roots={q.data.roots} />)}
    </Container>
  );
}
