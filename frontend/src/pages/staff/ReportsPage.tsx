import DownloadIcon from '@mui/icons-material/Download';
import LockIcon from '@mui/icons-material/LockOutlined';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import TableChartIcon from '@mui/icons-material/TableChartOutlined';
import {
  Alert, Box, Button, Card, CardContent, Chip, Container, List, ListItemButton, ListItemText, ListSubheader, MenuItem, Stack, Table,
  TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, download, formatDate, formatDateTime } from '../../api/client';
import type { AcademicYear, AdmissionWindow, CourseModule, Programme, ReportCatalogueEntry, ReportColumnType, ReportResult, Semester } from '../../api/types';
import { ErrorAlert, Loading, PageTitle, humanise } from '../../components/common';

const staff = { realm: 'staff' as const };
type Filters = Record<string, string>;
const APPLICATION_STATUSES = ['SUBMITTED', 'UNDER_REVIEW', 'SELECTED', 'NOT_SELECTED', 'PENDING_VERIFICATION', 'QUERIED', 'NACTVET_REJECTED',
  'OFFERED', 'ACCEPTED', 'DECLINED', 'OFFER_LAPSED', 'WITHDRAWN'];
const STUDENT_STATUSES = ['ADMITTED', 'ACTIVE', 'POSTPONED', 'SUSPENDED', 'DISCONTINUED', 'WITHDRAWN', 'COMPLETED', 'GRADUATED'];

function cell(type: ReportColumnType, v: unknown) {
  if (v == null || v === '') return '';
  switch (type) {
    case 'NUMBER': return Number(v).toLocaleString();
    case 'DECIMAL': return Number(v).toFixed(2);
    case 'MONEY': return Number(v).toLocaleString(undefined, { minimumFractionDigits: 0, maximumFractionDigits: 2 });
    case 'PERCENT': return `${(Number(v) * 100).toFixed(1)}%`;
    case 'DATE': return formatDate(String(v));
    case 'DATETIME': return formatDateTime(String(v));
    default: return typeof v === 'boolean' ? (v ? 'Yes' : 'No') : String(v);
  }
}

/** The standard report catalogue (FR-RPT-02): pick a report, filter, view, and download as Excel or PDF. */
export default function ReportsPage() {
  const [params, setParams] = useSearchParams();
  const catalogue = useQuery({ queryKey: ['staff', 'reports'], queryFn: () => api<ReportCatalogueEntry[]>('/api/reports', staff) });
  const areas = useMemo(() => {
    const m = new Map<string, ReportCatalogueEntry[]>();
    catalogue.data?.forEach((r) => m.set(r.area, [...(m.get(r.area) ?? []), r]));
    return m;
  }, [catalogue.data]);
  const code = params.get('code') ?? (params.get('area') ? areas.get(params.get('area')!)?.[0]?.code : undefined) ?? catalogue.data?.[0]?.code;
  const selected = catalogue.data?.find((r) => r.code === code);
  return (
    <Container maxWidth="xl" disableGutters>
      <PageTitle title="Reports" subtitle="Standard reports for admissions, registry, finance and governance. Download any of them as Excel or PDF." />
      <ErrorAlert error={catalogue.error} />
      {catalogue.isLoading && <Loading />}
      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, md: 3 }}>
          <Card sx={{ position: { md: 'sticky' }, top: { md: 80 }, maxHeight: { md: 'calc(100vh - 100px)' }, overflowY: { md: 'auto' } }}>
            <List dense disablePadding sx={{ py: 0.5 }}>
              {[...areas.entries()].map(([area, list]) => (
                <Box key={area}>
                  <ListSubheader sx={{ lineHeight: '36px', fontWeight: 700 }}>{area}</ListSubheader>
                  {list.map((r) => (
                    <ListItemButton key={r.code} selected={r.code === code} onClick={() => setParams({ code: r.code })} sx={{ py: 0.5 }}>
                      <ListItemText primary={r.title} secondary={r.code} primaryTypographyProps={{ variant: 'body2' }} secondaryTypographyProps={{ variant: 'caption' }} />
                      {r.personalData && <Tooltip title="Contains personal data: views and downloads are recorded"><LockIcon fontSize="small" color="action" /></Tooltip>}
                    </ListItemButton>
                  ))}
                </Box>
              ))}
            </List>
          </Card>
        </Grid>
        <Grid size={{ xs: 12, md: 9 }}>
          {selected ? <ReportView key={selected.code} entry={selected} /> : !catalogue.isLoading && <Alert severity="info">No reports are available for your role.</Alert>}
        </Grid>
      </Grid>
    </Container>
  );
}

function ReportView({ entry }: { entry: ReportCatalogueEntry }) {
  const [filters, setFilters] = useState<Filters>({});
  const [applied, setApplied] = useState<Filters>({});
  const [dlError, setDlError] = useState<unknown>(null);
  const [busy, setBusy] = useState<string | null>(null);
  // a Head of Department or Programme Coordinator runs reports for one of their programmes at a time: start with the first
  const scope = useQuery({ queryKey: ['staff', 'scope'], staleTime: 300_000,
    queryFn: () => api<{ all: boolean }>('/api/auth/scope', staff) });
  const limited = !!scope.data && !scope.data.all;
  const progs = useQuery({ queryKey: ['staff', 'programmes'], enabled: limited, queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  useEffect(() => {
    if (limited && progs.data?.length && !applied.programmeId) {
      const id = String(progs.data[0].id);
      setFilters((f) => ({ ...f, programmeId: id }));
      setApplied((a) => ({ ...a, programmeId: id }));
    }
  }, [limited, progs.data]); // eslint-disable-line react-hooks/exhaustive-deps
  const qs = new URLSearchParams(Object.entries(applied).filter(([, v]) => v !== '')).toString();
  const report = useQuery({
    enabled: scope.isSuccess && (!limited || !!applied.programmeId),
    queryKey: ['staff', 'report', entry.code, qs],
    queryFn: () => api<ReportResult>(`/api/reports/${entry.code}${qs ? `?${qs}` : ''}`, staff),
  });
  useEffect(() => setDlError(null), [qs]);
  const r = report.data;
  const get = (format: 'xlsx' | 'pdf') => {
    setBusy(format); setDlError(null);
    const name = `${entry.code}-${entry.title.replace(/\W+/g, '-')}.${format}`;
    download(`/api/reports/${entry.code}/export?format=${format}${qs ? `&${qs}` : ''}`, 'staff', name).catch(setDlError).finally(() => setBusy(null));
  };
  return (
    <Stack spacing={2}>
      <Card><CardContent>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'flex-start' }}>
          <Box sx={{ flexGrow: 1 }}>
            <Stack direction="row" spacing={1} alignItems="center">
              <Typography variant="h6">{entry.title}</Typography>
              <Chip size="small" variant="outlined" label={entry.code} />
            </Stack>
            <Typography variant="body2" color="text.secondary">{entry.description}</Typography>
          </Box>
          <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
            <Button variant="outlined" startIcon={<TableChartIcon />} disabled={!!busy || !r} onClick={() => get('xlsx')}>Excel</Button>
            <Button variant="outlined" startIcon={<DownloadIcon />} disabled={!!busy || !r} onClick={() => get('pdf')}>PDF</Button>
          </Stack>
        </Stack>
        {!!entry.filters.length && (
          <>
            <FilterBar entry={entry} filters={filters} onChange={setFilters} limited={limited} />
            <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
              <Button variant="contained" startIcon={<PlayArrowIcon />} onClick={() => setApplied({ ...filters })}>Run</Button>
              {Object.values(filters).some(Boolean) && <Button onClick={() => { setFilters({}); setApplied({}); }}>Clear filters</Button>}
            </Stack>
          </>
        )}
        <ErrorAlert error={dlError} />
      </CardContent></Card>

      <ErrorAlert error={report.error} />
      {report.isFetching && !r && <Loading />}
      {r && (
        <Card sx={{ opacity: report.isFetching ? 0.6 : 1 }}><CardContent>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mb: 1.5 }}>
            <Typography variant="body2" color="text.secondary" sx={{ flexGrow: 1 }}>{r.filterLines.join(' · ')}</Typography>
            <Typography variant="body2" color="text.secondary">{r.rowCount.toLocaleString()} row{r.rowCount === 1 ? '' : 's'}</Typography>
          </Stack>
          {r.truncated && <Alert severity="info" sx={{ mb: 1.5 }}>Showing the first {r.rows.length.toLocaleString()} of {r.rowCount.toLocaleString()} rows. The Excel file has them all.</Alert>}
          <TableContainer sx={{ maxHeight: 640 }}>
            <Table size="small" stickyHeader>
              <TableHead><TableRow>
                {r.columns.map((c) => <TableCell key={c.key} align={['NUMBER', 'DECIMAL', 'MONEY', 'PERCENT'].includes(c.type) ? 'right' : 'left'} sx={{ whiteSpace: 'nowrap', fontWeight: 700 }}>{c.label}</TableCell>)}
              </TableRow></TableHead>
              <TableBody>
                {r.rows.map((row, i) => (
                  <TableRow key={i} hover>
                    {r.columns.map((c, j) => (
                      <TableCell key={c.key} align={['NUMBER', 'DECIMAL', 'MONEY', 'PERCENT'].includes(c.type) ? 'right' : 'left'}
                        sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: c.key === 'details' || c.key === 'note' ? 'normal' : 'nowrap', maxWidth: c.key === 'details' ? 420 : undefined, minWidth: c.key === 'details' ? 280 : undefined, wordBreak: c.key === 'details' ? 'break-word' : undefined, fontSize: c.key === 'details' ? 12 : undefined }}>
                        {cell(c.type, row[j])}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
                {!r.rows.length && <TableRow><TableCell colSpan={r.columns.length}><Typography color="text.secondary" sx={{ py: 2 }}>Nothing to report for these filters.</Typography></TableCell></TableRow>}
                {r.totals && !!r.rows.length && (
                  <TableRow>
                    {r.columns.map((c, j) => (
                      <TableCell key={c.key} align={['NUMBER', 'DECIMAL', 'MONEY', 'PERCENT'].includes(c.type) ? 'right' : 'left'} sx={{ fontWeight: 700, borderTop: '2px solid', borderColor: 'divider' }}>
                        {cell(c.type, r.totals![j])}
                      </TableCell>
                    ))}
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
          {r.notes.map((n) => <Typography key={n} variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>{n}</Typography>)}
          <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 1 }}>Generated {formatDateTime(r.generatedAt)} by {r.generatedBy}</Typography>
        </CardContent></Card>
      )}
    </Stack>
  );
}

function FilterBar({ entry, filters, onChange, limited }: { entry: ReportCatalogueEntry; filters: Filters; onChange: (f: Filters) => void; limited?: boolean }) {
  const has = (f: string) => entry.filters.includes(f);
  const years = useQuery({ queryKey: ['staff', 'years'], enabled: has('academicYear'), queryFn: () => api<AcademicYear[]>('/api/academic/years', staff) });
  const intakes = useQuery({ queryKey: ['staff', 'intakes'], enabled: has('intake'), queryFn: () => api<{ id: number; name: string }[]>('/api/academic/intakes', staff) });
  const windows = useQuery({ queryKey: ['staff', 'windows'], enabled: has('window'), retry: false, queryFn: () => api<AdmissionWindow[]>('/api/admission/windows', staff) });
  const programmes = useQuery({ queryKey: ['staff', 'programmes'], enabled: has('programme'), queryFn: () => api<Programme[]>('/api/academic/programmes', staff) });
  const semesters = useQuery({ queryKey: ['staff', 'semesters'], enabled: has('semester'), queryFn: () => api<Semester[]>('/api/academic/semesters', staff) });
  const modules = useQuery({ queryKey: ['staff', 'modules'], enabled: has('module'), queryFn: () => api<CourseModule[]>('/api/academic/modules', staff) });
  const set = (k: string, v: string) => onChange({ ...filters, [k]: v });
  const sel = (key: string, label: string, options: { value: string | number; label: string }[], all: string | null = 'All') => (
    <Grid size={{ xs: 12, sm: 6, md: 3 }} key={key}>
      <TextField select size="small" label={label} value={filters[key] ?? ''} onChange={(e) => set(key, e.target.value)}>
        {all !== null && <MenuItem value="">{all}</MenuItem>}
        {options.map((o) => <MenuItem key={o.value} value={String(o.value)}>{o.label}</MenuItem>)}
      </TextField>
    </Grid>
  );
  return (
    <Grid container spacing={1.5} sx={{ mt: 1.5 }}>
      {has('semester') && sel('semesterId', 'Semester', semesters.data?.map((s) => ({ value: s.id, label: `${s.name} · ${s.academicYear}` })) ?? [], 'Choose a semester')}
      {has('module') && sel('moduleId', 'Module', modules.data?.map((m) => ({ value: m.id, label: `${m.code} · ${m.title}` })) ?? [], 'Choose a module')}
      {has('academicYear') && sel('academicYearId', 'Academic year', years.data?.map((y) => ({ value: y.id, label: y.code })) ?? [], 'All years')}
      {has('intake') && sel('intakeId', 'Intake', intakes.data?.map((i) => ({ value: i.id, label: i.name })) ?? [], 'All intakes')}
      {has('window') && windows.data && sel('windowId', 'Admission window', windows.data.map((w) => ({ value: w.id, label: w.name })), 'All windows')}
      {has('programme') && sel('programmeId', 'Programme', programmes.data?.map((p) => ({ value: p.id, label: `${p.code} · ${p.name}` })) ?? [], limited ? null : 'All programmes')}
      {has('awardFamily') && sel('awardFamily', 'Award family', [{ value: 'NTA', label: 'NTA' }, { value: 'NVA', label: 'NVA' }])}
      {has('level') && sel('level', 'Level', [1, 2, 3, 4, 5, 6, 7, 8].map((l) => ({ value: l, label: `Level ${l}` })), 'All levels')}
      {has('sex') && sel('sex', 'Sex', [{ value: 'F', label: 'Female' }, { value: 'M', label: 'Male' }])}
      {has('applicationStatus') && sel('status', 'Application status', APPLICATION_STATUSES.map((s) => ({ value: s, label: humanise(s) })))}
      {has('studentStatus') && sel('status', 'Student status', STUDENT_STATUSES.map((s) => ({ value: s, label: humanise(s) })), 'Current (admitted or registered)')}
      {has('yearOfStudy') && sel('yearOfStudy', 'Year of study', [1, 2, 3, 4].map((y) => ({ value: y, label: `Year ${y}` })), 'All years')}
      {has('period') && (
        <>
          <Grid size={{ xs: 6, md: 3 }}><TextField size="small" type="date" label="From" value={filters.from ?? ''} onChange={(e) => set('from', e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }} helperText={!filters.from ? 'Default: start of this month' : ' '} /></Grid>
          <Grid size={{ xs: 6, md: 3 }}><TextField size="small" type="date" label="To" value={filters.to ?? ''} onChange={(e) => set('to', e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }} helperText={!filters.to ? 'Default: today' : ' '} /></Grid>
        </>
      )}
      {has('text') && (
        <Grid size={{ xs: 12, md: 6 }}><TextField size="small" label="Action, person or record contains" value={filters.text ?? ''} onChange={(e) => set('text', e.target.value)}
          placeholder="e.g. LOGIN, REPORT_EXPORTED, Bursar" /></Grid>
      )}
    </Grid>
  );
}
