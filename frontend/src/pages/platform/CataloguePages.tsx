import CloseIcon from '@mui/icons-material/Close';
import SearchIcon from '@mui/icons-material/Search';
import SyncIcon from '@mui/icons-material/Sync';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Drawer, IconButton, InputAdornment, LinearProgress, MenuItem, Stack, Table,
  TableBody, TableCell, TableContainer, TableHead, TablePagination, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { api, formatDateTime, formatTzs } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import {
  levels, n, platform, type CatalogueDetail, type CatalogueInstitution, type CatalogueProgramme, type CatalogueStatus, type PageOf, type SyncRun,
} from './shared';

// ============================================================ the catalogue

/** NACTVET's registered institutions and their programmes, pulled as NOAS does; Msingi onboards institutions from here. */
export function CataloguePage() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [region, setRegion] = useState('');
  const [ownership, setOwnership] = useState('');
  const [onboarded, setOnboarded] = useState('');
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState<number | null>(null);
  useEffect(() => { const t = setTimeout(() => { setSearch(q); setPage(0); }, 300); return () => clearTimeout(t); }, [q]);

  const status = useQuery({
    queryKey: ['platform', 'nactvet', 'status'],
    queryFn: () => api<CatalogueStatus>('/api/platform/nactvet/status', platform),
    refetchInterval: (query) => (query.state.data?.latest?.status === 'RUNNING' ? 2500 : false),
  });
  const running = status.data?.latest?.status === 'RUNNING';
  useEffect(() => { if (!running) qc.invalidateQueries({ queryKey: ['platform', 'nactvet', 'institutions'] }); }, [running, qc]);
  const sync = useMutation({
    mutationFn: () => api<SyncRun>('/api/platform/nactvet/sync', { ...platform, method: 'POST' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['platform', 'nactvet', 'status'] }),
  });

  const qs = new URLSearchParams({ page: String(page), size: '50' });
  if (search) qs.set('q', search);
  if (region) qs.set('region', region);
  if (ownership) qs.set('ownership', ownership);
  if (onboarded) qs.set('onboarded', onboarded);
  const list = useQuery({ queryKey: ['platform', 'nactvet', 'institutions', qs.toString()], placeholderData: keepPreviousData,
    queryFn: () => api<PageOf<CatalogueInstitution>>(`/api/platform/nactvet/institutions?${qs}`, platform) });
  const s = status.data;
  const latest = s?.latest;

  return (
    <>
      <PageTitle title="NACTVET catalogue"
        subtitle="Every institution NACTVET has registered, with its accredited programmes, levels, durations, capacities, fees and entry requirements, pulled from NACTVET's institution/programme service as NOAS does. Onboard institutions from here." />
      {s?.mode === 'SIMULATED' && (
        <Alert severity="info" sx={{ mb: 2 }}>
          This server uses a <strong>simulated sample</strong> of the catalogue (made-up institutions, REG/SIM/…). With the NACTE credentials set
          (<code>NACTE_AUTHORIZATION</code>, <code>NACTE_API_CODE</code>) and NACTVET awards live, the sync reads the real catalogue from NACTVET.
        </Alert>
      )}
      <Card sx={{ mb: 2.5 }}>
        <CardContent>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ md: 'center' }}>
            <Stack direction="row" spacing={4} flexWrap="wrap" useFlexGap>
              <Stat label="Institutions" value={n(s?.institutions)} />
              <Stat label="Programmes" value={n(s?.programmes)} />
              <Stat label="On CIMS" value={n(s?.onboarded)} />
              <Box>
                <Typography variant="body2" color="text.secondary">Last synced</Typography>
                <Typography fontWeight={600}>{s?.lastSyncedAt ? formatDateTime(s.lastSyncedAt) : 'Never'}</Typography>
                <Chip size="small" variant="outlined" sx={{ mt: 0.5 }} label={s?.mode === 'LIVE' ? 'Live from NACTVET' : 'Simulated sample'} />
              </Box>
            </Stack>
            <Button variant="contained" startIcon={<SyncIcon />} disabled={running || sync.isPending} onClick={() => sync.mutate()}>
              {running ? 'Syncing…' : 'Sync from NACTVET'}
            </Button>
          </Stack>
          {running && <Box sx={{ mt: 2 }}><LinearProgress /><Typography variant="caption" color="text.secondary">Reading every page of the catalogue from NACTVET; this can take a few minutes.</Typography></Box>}
          <ErrorAlert error={sync.error ?? status.error} />
          {latest && !running && latest.status === 'FAILED' && (
            <Alert severity="error" sx={{ mt: 2 }}>The last sync ({formatDateTime(latest.startedAt)}) failed: {latest.error}</Alert>
          )}
          {latest && !running && latest.status === 'DONE' && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
              Last sync by {latest.startedBy}: {n(latest.institutions)} institutions, {n(latest.programmes)} programmes;
              {' '}{n(latest.newInstitutions)} new institutions, {n(latest.newProgrammes)} new programmes, {n(latest.withdrawn)} no longer listed.
            </Typography>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardContent sx={{ pb: 1 }}>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5}>
            <TextField size="small" placeholder="Name, code or registration number" value={q} onChange={(e) => setQ(e.target.value)} sx={{ flex: 1, minWidth: { md: 280 } }}
              slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment> } }} />
            <Select label="Region" value={region} onChange={(v) => { setRegion(v); setPage(0); }} options={s?.regions ?? []} all="All regions" />
            <Select label="Ownership" value={ownership} onChange={(v) => { setOwnership(v); setPage(0); }} options={s?.ownerships ?? []} all="All" />
            <TextField select size="small" label="On CIMS" value={onboarded} onChange={(e) => { setOnboarded(e.target.value); setPage(0); }} sx={{ minWidth: 150 }}
              slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
              <MenuItem value="">All</MenuItem><MenuItem value="no">Not yet</MenuItem><MenuItem value="yes">Onboarded</MenuItem>
            </TextField>
          </Stack>
        </CardContent>
        <ErrorAlert error={list.error} />
        {list.isLoading ? <Loading /> : (
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Institution</TableCell><TableCell>Location</TableCell><TableCell>Ownership</TableCell>
                  <TableCell align="right">Programmes</TableCell><TableCell>Levels</TableCell><TableCell align="right">CIMS</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {(list.data?.content ?? []).map((r) => (
                  <TableRow key={r.id} hover sx={{ cursor: 'pointer' }} onClick={() => setOpen(r.id)}>
                    <TableCell>
                      <Typography fontWeight={600}>{r.name}</Typography>
                      <Typography variant="body2" color="text.secondary">{[r.registrationNumber, r.code].filter(Boolean).join(' · ')}</Typography>
                    </TableCell>
                    <TableCell>{[r.district, r.region].filter(Boolean).join(', ') || '—'}</TableCell>
                    <TableCell>{r.ownership ?? '—'}</TableCell>
                    <TableCell align="right">{n(r.programmes)}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{levels(r.minLevel, r.maxLevel)}</TableCell>
                    <TableCell align="right" onClick={(e) => e.stopPropagation()}>
                      {r.tenantId
                        ? <Chip size="small" color="success" component={RouterLink} to={`/platform/institutions/${r.tenantId}`} clickable label={`On CIMS · ${r.tenantCode}`} />
                        : <Button size="small" variant="outlined" onClick={() => navigate(`/platform/institutions/new?nactvet=${r.id}`)}>Onboard</Button>}
                    </TableCell>
                  </TableRow>
                ))}
                {list.data && list.data.content.length === 0 && (
                  <TableRow><TableCell colSpan={6}><Typography color="text.secondary" sx={{ py: 2 }}>
                    {s && s.institutions === 0 ? 'The catalogue is empty. Sync from NACTVET to load it.' : 'No institution matches.'}
                  </Typography></TableCell></TableRow>
                )}
              </TableBody>
            </Table>
          </TableContainer>
        )}
        <TablePagination component="div" count={list.data?.totalElements ?? 0} page={page} onPageChange={(_, p) => setPage(p)} rowsPerPage={50} rowsPerPageOptions={[50]} />
      </Card>
      <InstitutionDrawer id={open} onClose={() => setOpen(null)} />
    </>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography sx={{ fontFamily: 'Sora', fontSize: 26, fontWeight: 700 }}>{value}</Typography>
    </Box>
  );
}

function Select({ label, value, onChange, options, all }: { label: string; value: string; onChange: (v: string) => void; options: string[]; all: string }) {
  return (
    <TextField select size="small" label={label} value={value} onChange={(e) => onChange(e.target.value)} sx={{ minWidth: 170 }}
      slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
      <MenuItem value="">{all}</MenuItem>
      {options.map((o) => <MenuItem key={o} value={o}>{o}</MenuItem>)}
    </TextField>
  );
}

/** One catalogue institution and its programmes, as NACTVET lists them. */
function InstitutionDrawer({ id, onClose }: { id: number | null; onClose: () => void }) {
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ['platform', 'nactvet', 'institution', id], enabled: id != null,
    queryFn: () => api<CatalogueDetail>(`/api/platform/nactvet/institutions/${id}`, platform) });
  const d = q.data;
  return (
    <Drawer anchor="right" open={id != null} onClose={onClose} PaperProps={{ sx: { width: { xs: '100%', md: 760 } } }}>
      <Box sx={{ p: 3 }}>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
          <Box>
            <Typography variant="h6">{d?.institution.name ?? 'Institution'}</Typography>
            {d && <Typography color="text.secondary">{[d.institution.registrationNumber, d.institution.ownership, [d.institution.district, d.institution.region].filter(Boolean).join(', '), d.institution.mobileNumber].filter(Boolean).join(' · ')}</Typography>}
          </Box>
          <IconButton onClick={onClose} aria-label="Close"><CloseIcon /></IconButton>
        </Stack>
        {q.isLoading && <Loading />}
        <ErrorAlert error={q.error} />
        {d && (
          <>
            <Box sx={{ my: 2 }}>
              {d.institution.tenantId
                ? <Button variant="outlined" component={RouterLink} to={`/platform/institutions/${d.institution.tenantId}`}>Open {d.institution.tenantCode} on CIMS</Button>
                : <Button variant="contained" onClick={() => navigate(`/platform/institutions/new?nactvet=${d.institution.id}`)}>Onboard this institution</Button>}
              {!d.institution.active && <Chip color="warning" sx={{ ml: 1 }} label="No longer listed by NACTVET" />}
            </Box>
            <ProgrammeTable programmes={d.programmes} />
          </>
        )}
      </Box>
    </Drawer>
  );
}

/** Programmes as NACTVET lists them; with {@code selected}, a checkbox picks which to import. */
export function ProgrammeTable({ programmes, selected, onToggle, onToggleAll }: {
  programmes: CatalogueProgramme[]; selected?: Set<number>; onToggle?: (id: number) => void; onToggleAll?: (ids: number[], on: boolean) => void;
}) {
  const selectable = useMemo(() => programmes.filter((p) => p.active && !p.imported).map((p) => p.id), [programmes]);
  const allOn = selected && selectable.length > 0 && selectable.every((id) => selected.has(id));
  return (
    <TableContainer>
      <Table size="small">
        <TableHead>
          <TableRow>
            {selected && (
              <TableCell padding="checkbox">
                <Checkbox checked={!!allOn} indeterminate={!allOn && selectable.some((id) => selected.has(id))} disabled={!selectable.length}
                  onChange={(e) => onToggleAll?.(selectable, e.target.checked)} inputProps={{ 'aria-label': 'Select all programmes' }} />
              </TableCell>
            )}
            <TableCell>Programme</TableCell><TableCell>Level</TableCell><TableCell align="right">Years</TableCell>
            <TableCell align="right">Capacity</TableCell><TableCell align="right">Fee</TableCell><TableCell>Entry</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {programmes.map((p) => (
            <TableRow key={p.id} sx={{ verticalAlign: 'top', opacity: p.active ? 1 : 0.55 }}>
              {selected && (
                <TableCell padding="checkbox">
                  {p.imported ? <Tooltip title="Already on CIMS"><span><Checkbox checked disabled /></span></Tooltip>
                    : <Checkbox checked={selected.has(p.id)} disabled={!p.active} onChange={() => onToggle?.(p.id)} inputProps={{ 'aria-label': `Import ${p.name}` }} />}
                </TableCell>
              )}
              <TableCell>
                <Typography variant="body2" fontWeight={600}>{p.name}</Typography>
                <Typography variant="caption" color="text.secondary">{[p.courseCode, p.category, p.board].filter(Boolean).join(' · ')}</Typography>
                {p.imported && <Chip size="small" color="success" variant="outlined" label="On CIMS" sx={{ ml: 1 }} />}
                {!p.active && <Chip size="small" label="Not listed now" sx={{ ml: 1 }} />}
              </TableCell>
              <TableCell sx={{ whiteSpace: 'nowrap' }}>{p.ntaLevel ? `NTA ${p.ntaLevel}` : '—'}</TableCell>
              <TableCell align="right">{p.durationYears ?? '—'}</TableCell>
              <TableCell align="right">{p.totalCapacity != null ? n(p.totalCapacity) : '—'}</TableCell>
              <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{p.fee && /^\d+(\.\d+)?$/.test(p.fee) ? formatTzs(Number(p.fee)) : p.fee ?? '—'}</TableCell>
              <TableCell sx={{ maxWidth: 260 }}>
                <Typography variant="caption" sx={{ display: 'block' }}>{p.entryRequirement ?? '—'}</Typography>
                {p.ntaEntryHolderLevel && <Typography variant="caption" color="text.secondary">Holders of NTA {p.ntaEntryHolderLevel}{p.ntaEntryMinGpa ? `, GPA ${p.ntaEntryMinGpa}+` : ''}</Typography>}
                {p.nectaRequirement && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>Subjects: {p.nectaRequirement.replace(/,/g, ', ')}</Typography>}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  );
}
