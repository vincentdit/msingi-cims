import AddIcon from '@mui/icons-material/Add';
import CloseIcon from '@mui/icons-material/Close';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import EditIcon from '@mui/icons-material/EditOutlined';
import PrintIcon from '@mui/icons-material/PrintOutlined';
import {
  Alert, Autocomplete, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, Divider, Drawer,
  FormControlLabel, IconButton, MenuItem, Stack, Switch, TextField, ToggleButton, ToggleButtonGroup, Tooltip, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { api, formatDateTime } from '../../api/client';
import { ORG_UNIT_KINDS, type Department, type OrgPosition, type OrgStructure, type OrgUnit } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';
import { OrgChart, OrgList, UNIT_KIND, holderText } from '../../components/OrgChart';

const staff = { realm: 'staff' as const };
const KEY = ['staff', 'organisation'];

type StaffUser = { id: number; fullName: string; jobTitle?: string | null; status: string };

function flatten(units: OrgUnit[], depth = 0): { u: OrgUnit; depth: number }[] {
  return units.flatMap((u) => [{ u, depth }, ...flatten(u.children, depth + 1)]);
}

/**
 * The organisation structure the institution builds (stakeholders' comments): units and who reports to whom, with positions and
 * their holders, drawn as a chart or a list. Every member of staff sees it; the institution administrator builds it, can start
 * from the usual shape of a college, and publishes it on the public site.
 */
export default function OrganisationPage() {
  const qc = useQueryClient();
  const { user } = useSession();
  const canEdit = (user('staff')?.roles ?? []).includes('INSTITUTION_ADMIN');
  const q = useQuery({ queryKey: KEY, queryFn: () => api<OrgStructure>('/api/organisation', staff) });
  const [view, setView] = useState<'chart' | 'list'>('chart');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [unitForm, setUnitForm] = useState<UnitForm | null>(null);
  const [positionForm, setPositionForm] = useState<PositionForm | null>(null);
  const done = (s: OrgStructure) => qc.setQueryData(KEY, s);
  const template = useMutation({ mutationFn: () => api<OrgStructure>('/api/organisation/template', { ...staff, method: 'POST' }), onSuccess: done });
  const publish = useMutation({ mutationFn: (on: boolean) => api<OrgStructure>(`/api/organisation/publish?publish=${on}`, { ...staff, method: 'POST' }), onSuccess: done });
  const s = q.data;
  const all = useMemo(() => flatten(s?.roots ?? []), [s]);
  const selected = all.find((x) => x.u.id === selectedId)?.u ?? null;

  return (
    <Container maxWidth="xl" disableGutters>
      <PageTitle title="Organisation structure" subtitle="Who reports to whom: the board, offices, directorates, faculties, departments, units and committees, with their positions."
        action={s && s.unitCount > 0 && (
          <Stack direction="row" spacing={1} alignItems="center" sx={{ '@media print': { display: 'none' } }}>
            <ToggleButtonGroup size="small" exclusive value={view} onChange={(_, v) => v && setView(v)}>
              <ToggleButton value="chart">Chart</ToggleButton>
              <ToggleButton value="list">List</ToggleButton>
            </ToggleButtonGroup>
            <Button size="small" startIcon={<PrintIcon />} onClick={() => window.print()}>Print</Button>
            {canEdit && <Button variant="contained" startIcon={<AddIcon />} sx={{ whiteSpace: 'nowrap' }} onClick={() => setUnitForm(newUnit(null))}>Add unit</Button>}
          </Stack>
        )} />
      <ErrorAlert error={q.error ?? template.error ?? publish.error} />
      {q.isLoading && <Loading />}

      {s && s.unitCount === 0 && (
        <Card variant="outlined">
          <CardContent>
            <Typography variant="h6">No organisation structure yet</Typography>
            <Typography color="text.secondary" sx={{ mt: 0.5, mb: 2, maxWidth: 720 }}>
              {canEdit
                ? 'Start from the usual shape of a college — Governing Board, Principal, Academic Board, two Deputy Principals, the offices and units, and the departments already in CIMS — then rename, move or remove anything. Or start from a blank page.'
                : 'The institution administrator has not built the organisation structure yet.'}
            </Typography>
            {canEdit && (
              <Stack direction="row" spacing={1}>
                <Button variant="contained" disabled={template.isPending} onClick={() => template.mutate()}>Start from the college template</Button>
                <Button onClick={() => setUnitForm(newUnit(null, 'BOARD'))}>Start blank</Button>
              </Stack>
            )}
          </CardContent>
        </Card>
      )}

      {s && s.unitCount > 0 && (
        <>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }} sx={{ mb: 2, '@media print': { display: 'none' } }}>
            <Stack direction="row" spacing={1} sx={{ flexGrow: 1 }} flexWrap="wrap" useFlexGap>
              <Chip label={`${s.unitCount} units`} />
              <Chip label={`${s.positionCount} positions`} />
              <Chip color={s.vacantCount ? 'warning' : 'success'} variant="outlined" label={s.vacantCount ? `${s.vacantCount} vacant` : 'All positions filled'} />
            </Stack>
            {canEdit
              ? <FormControlLabel control={<Switch checked={s.published} disabled={publish.isPending} onChange={(e) => publish.mutate(e.target.checked)} />}
                label={s.published ? `On the public site${s.publishedAt ? ` since ${formatDateTime(s.publishedAt)}` : ''}` : 'Show on the public site'} />
              : s.published && <Chip size="small" color="success" variant="outlined" label="On the public site" />}
          </Stack>
          {canEdit && <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5, '@media print': { display: 'none' } }}>Click a unit to change it, its positions and who holds them.</Typography>}
          {view === 'chart'
            ? <OrgChart roots={s.roots} selected={selectedId} onSelect={(u) => setSelectedId(u.id)} />
            : <OrgList roots={s.roots} selected={selectedId} onSelect={(u) => setSelectedId(u.id)} />}
        </>
      )}

      <Drawer anchor="right" open={!!selected} onClose={() => setSelectedId(null)} PaperProps={{ sx: { width: { xs: '100%', sm: 420 } } }}>
        {selected && (
          <UnitPanel u={selected} parent={all.find((x) => x.u.id === selected.parentId)?.u ?? null} canEdit={canEdit}
            onClose={() => setSelectedId(null)}
            onEdit={() => setUnitForm(unitFormOf(selected))}
            onAddUnder={() => setUnitForm(newUnit(selected.id))}
            onAddPosition={() => setPositionForm({ unitId: selected.id, title: '', head: selected.positions.length === 0, holderUserId: null, holderName: '', acting: false })}
            onEditPosition={(p) => setPositionForm({ id: p.id, unitId: selected.id, title: p.title, head: p.head, holderUserId: p.holderUserId ?? null,
              holderName: p.holderUserId ? '' : p.holder ?? '', acting: p.acting })}
            onChanged={(st) => { done(st); if (!flatten(st.roots).some((x) => x.u.id === selected.id)) setSelectedId(null); }} />
        )}
      </Drawer>
      {unitForm && <UnitDialog form={unitForm} units={all} onClose={() => setUnitForm(null)} onDone={(st) => { done(st); setUnitForm(null); }} />}
      {positionForm && <PositionDialog form={positionForm} onClose={() => setPositionForm(null)} onDone={(st) => { done(st); setPositionForm(null); }} />}
    </Container>
  );
}

function UnitPanel({ u, parent, canEdit, onClose, onEdit, onAddUnder, onAddPosition, onEditPosition, onChanged }: {
  u: OrgUnit; parent: OrgUnit | null; canEdit: boolean; onClose: () => void; onEdit: () => void; onAddUnder: () => void; onAddPosition: () => void;
  onEditPosition: (p: OrgPosition) => void; onChanged: (s: OrgStructure) => void;
}) {
  const k = UNIT_KIND[u.kind] ?? UNIT_KIND.UNIT;
  const delUnit = useMutation({ mutationFn: () => api<OrgStructure>(`/api/organisation/units/${u.id}`, { ...staff, method: 'DELETE' }), onSuccess: onChanged });
  const delPosition = useMutation({ mutationFn: (id: number) => api<OrgStructure>(`/api/organisation/positions/${id}`, { ...staff, method: 'DELETE' }), onSuccess: onChanged });
  return (
    <Box sx={{ p: 2.5 }}>
      <Stack direction="row" alignItems="flex-start" spacing={1}>
        <Box sx={{ flexGrow: 1 }}>
          <Typography sx={{ fontSize: 11, letterSpacing: 0.6, textTransform: 'uppercase', color: k.colour, fontWeight: 700 }}>{k.label}{u.advisory ? ' · advisory' : ''}</Typography>
          <Typography variant="h6">{u.name}{u.shortName ? ` (${u.shortName})` : ''}</Typography>
          <Typography variant="body2" color="text.secondary">{parent ? `${u.advisory ? 'Advises' : 'Reports to'} ${parent.name}` : 'Top of the structure'}</Typography>
        </Box>
        <IconButton aria-label="Close" onClick={onClose}><CloseIcon /></IconButton>
      </Stack>
      {u.description && <Typography variant="body2" sx={{ mt: 1.5 }}>{u.description}</Typography>}
      {canEdit && (
        <Stack direction="row" spacing={1} sx={{ mt: 2 }} flexWrap="wrap" useFlexGap>
          <Button size="small" variant="outlined" startIcon={<EditIcon />} onClick={onEdit}>Edit unit</Button>
          <Button size="small" variant="outlined" startIcon={<AddIcon />} onClick={onAddUnder}>Add unit under it</Button>
        </Stack>
      )}
      <Divider sx={{ my: 2 }} />
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
        <Typography variant="subtitle1" fontWeight={600}>Positions</Typography>
        {canEdit && <Button size="small" startIcon={<AddIcon />} onClick={onAddPosition}>Add position</Button>}
      </Stack>
      {!u.positions.length && <Typography color="text.secondary" variant="body2">No positions yet.</Typography>}
      <Stack spacing={1}>
        {u.positions.map((p) => (
          <Card key={p.id} variant="outlined">
            <Stack direction="row" alignItems="center" spacing={1} sx={{ px: 1.5, py: 1 }}>
              <Box sx={{ flexGrow: 1 }}>
                <Stack direction="row" spacing={0.75} alignItems="center">
                  <Typography sx={{ fontWeight: 600 }}>{p.title}</Typography>
                  {p.head && <Chip size="small" label="Head" color="primary" variant="outlined" sx={{ height: 20 }} />}
                </Stack>
                <Typography variant="body2" sx={{ color: p.holder ? 'text.secondary' : 'warning.main' }}>{holderText(p)}</Typography>
              </Box>
              {canEdit && <>
                <Tooltip title="Edit"><IconButton size="small" onClick={() => onEditPosition(p)}><EditIcon fontSize="small" /></IconButton></Tooltip>
                <Tooltip title="Remove"><IconButton size="small" disabled={delPosition.isPending}
                  onClick={() => { if (window.confirm(`Remove the position "${p.title}"?`)) delPosition.mutate(p.id); }}><DeleteIcon fontSize="small" /></IconButton></Tooltip>
              </>}
            </Stack>
          </Card>
        ))}
      </Stack>
      {u.children.length > 0 && (
        <>
          <Divider sx={{ my: 2 }} />
          <Typography variant="subtitle1" fontWeight={600} sx={{ mb: 0.5 }}>Units under it</Typography>
          {u.children.map((c) => <Typography key={c.id} variant="body2">• {c.name}{c.advisory ? ' (advisory)' : ''}</Typography>)}
        </>
      )}
      <ErrorAlert error={delUnit.error ?? delPosition.error} />
      {canEdit && (
        <Button color="error" sx={{ mt: 3 }} startIcon={<DeleteIcon />} disabled={delUnit.isPending || u.children.length > 0}
          onClick={() => { if (window.confirm(`Remove ${u.name} and its positions?`)) delUnit.mutate(); }}>
          {u.children.length > 0 ? 'Move or remove the units under it to remove this one' : 'Remove this unit'}
        </Button>
      )}
    </Box>
  );
}

type UnitForm = { id?: number; parentId: number | null; kind: string; name: string; shortName: string; departmentId: number | null; advisory: boolean; description: string };

const newUnit = (parentId: number | null, kind = 'UNIT'): UnitForm => ({ parentId, kind, name: '', shortName: '', departmentId: null, advisory: false, description: '' });
const unitFormOf = (u: OrgUnit): UnitForm => ({ id: u.id, parentId: u.parentId ?? null, kind: u.kind, name: u.name, shortName: u.shortName ?? '',
  departmentId: u.departmentId ?? null, advisory: u.advisory, description: u.description ?? '' });

/** All the units under a unit (it cannot be moved under any of them). */
function descendants(units: { u: OrgUnit }[], id?: number): Set<number> {
  const out = new Set<number>();
  const start = units.find((x) => x.u.id === id)?.u;
  const walk = (u: OrgUnit) => u.children.forEach((c) => { out.add(c.id); walk(c); });
  if (start) walk(start);
  return out;
}

function UnitDialog({ form, units, onClose, onDone }: { form: UnitForm; units: { u: OrgUnit; depth: number }[]; onClose: () => void; onDone: (s: OrgStructure) => void }) {
  const [f, setF] = useState(form);
  const depts = useQuery({ queryKey: ['staff', 'departments'], queryFn: () => api<Department[]>('/api/academic/departments', staff) });
  const blocked = descendants(units, f.id);
  const save = useMutation({
    mutationFn: () => api<OrgStructure>(f.id ? `/api/organisation/units/${f.id}` : '/api/organisation/units', { ...staff, method: f.id ? 'PUT' : 'POST',
      body: { ...f, name: f.name.trim(), shortName: f.shortName.trim() || null, description: f.description.trim() || null } }),
    onSuccess: onDone,
  });
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <form onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        <DialogTitle>{f.id ? `Edit ${form.name}` : 'New unit'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField required label="Name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="e.g. Department of Clinical Medicine" />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField select fullWidth label="Kind" value={f.kind} onChange={(e) => setF({ ...f, kind: e.target.value })}>
                {ORG_UNIT_KINDS.map((k) => <MenuItem key={k} value={k}>{UNIT_KIND[k].label}</MenuItem>)}
              </TextField>
              <TextField fullWidth label="Short name (optional)" value={f.shortName} onChange={(e) => setF({ ...f, shortName: e.target.value })} slotProps={{ htmlInput: { maxLength: 30 } }} />
            </Stack>
            <TextField select label="Reports to" value={f.parentId ?? ''} onChange={(e) => setF({ ...f, parentId: e.target.value === '' ? null : Number(e.target.value) })}
              helperText={f.parentId == null ? 'Nothing above it: the top of the structure' : undefined}>
              <MenuItem value="">Nobody (top of the structure)</MenuItem>
              {units.filter((x) => x.u.id !== f.id && !blocked.has(x.u.id)).map((x) => (
                <MenuItem key={x.u.id} value={x.u.id} sx={{ pl: 2 + x.depth * 2 }}>{x.u.name}</MenuItem>
              ))}
            </TextField>
            <FormControlLabel control={<Switch checked={f.advisory} onChange={(e) => setF({ ...f, advisory: e.target.checked })} />}
              label="Advisory (a board or committee drawn beside the unit it advises, with a dashed line)" />
            {f.kind === 'DEPARTMENT' && (
              <TextField select label="Department in CIMS (optional)" value={f.departmentId ?? ''} onChange={(e) => setF({ ...f, departmentId: e.target.value === '' ? null : Number(e.target.value) })}>
                <MenuItem value="">Not linked</MenuItem>
                {(depts.data ?? []).map((d) => <MenuItem key={d.id} value={d.id}>{d.name}</MenuItem>)}
              </TextField>
            )}
            <TextField multiline minRows={2} label="What it does (optional)" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })}
              slotProps={{ htmlInput: { maxLength: 500 } }} />
          </Stack>
          <ErrorAlert error={save.error} />
        </DialogContent>
        <DialogActions><Button onClick={onClose}>Cancel</Button><Button type="submit" variant="contained" disabled={!f.name.trim() || save.isPending}>Save</Button></DialogActions>
      </form>
    </Dialog>
  );
}

type PositionForm = { id?: number; unitId: number; title: string; head: boolean; holderUserId: number | null; holderName: string; acting: boolean };

function PositionDialog({ form, onClose, onDone }: { form: PositionForm; onClose: () => void; onDone: (s: OrgStructure) => void }) {
  const [f, setF] = useState(form);
  const [byName, setByName] = useState(!!form.holderName);
  const people = useQuery({ queryKey: ['staff', 'admin-users'], queryFn: () => api<StaffUser[]>('/api/admin/users', staff) });
  const options = (people.data ?? []).filter((p) => p.status !== 'DISABLED');
  const chosen = options.find((p) => p.id === f.holderUserId) ?? null;
  const save = useMutation({
    mutationFn: () => api<OrgStructure>(f.id ? `/api/organisation/positions/${f.id}` : `/api/organisation/units/${f.unitId}/positions`, {
      ...staff, method: f.id ? 'PUT' : 'POST',
      body: { title: f.title.trim(), head: f.head, holderUserId: byName ? null : f.holderUserId, holderName: byName ? f.holderName.trim() || null : null, acting: f.acting } }),
    onSuccess: onDone,
  });
  const filled = byName ? !!f.holderName.trim() : !!f.holderUserId;
  return (
    <Dialog open onClose={onClose} maxWidth="sm" fullWidth>
      <form onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
        <DialogTitle>{f.id ? `Edit ${form.title}` : 'New position'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField required label="Title" value={f.title} onChange={(e) => setF({ ...f, title: e.target.value })} placeholder="e.g. Head of Department" />
            <FormControlLabel control={<Switch checked={f.head} onChange={(e) => setF({ ...f, head: e.target.checked })} />} label="Head of the unit (shown in its box on the chart)" />
            <Divider />
            <Typography variant="subtitle2">Who holds it</Typography>
            <ToggleButtonGroup size="small" exclusive value={byName ? 'name' : 'account'} onChange={(_, v) => v && setByName(v === 'name')}>
              <ToggleButton value="account">A member of staff in CIMS</ToggleButton>
              <ToggleButton value="name">Type a name</ToggleButton>
            </ToggleButtonGroup>
            {byName
              ? <TextField label="Name" value={f.holderName} onChange={(e) => setF({ ...f, holderName: e.target.value })} placeholder="e.g. Prof. Amina Said (board members and others without an account)"
                  helperText="Leave empty when the position is vacant" />
              : <Autocomplete options={options} value={chosen} loading={people.isLoading} onChange={(_, v) => setF({ ...f, holderUserId: v?.id ?? null })}
                  getOptionLabel={(o) => `${o.fullName}${o.jobTitle ? ` — ${o.jobTitle}` : ''}`} isOptionEqualToValue={(a, b) => a.id === b.id}
                  renderInput={(p) => <TextField {...p} label="Staff member" helperText="Leave empty when the position is vacant" />} />}
            <FormControlLabel control={<Switch checked={f.acting && filled} disabled={!filled} onChange={(e) => setF({ ...f, acting: e.target.checked })} />} label="Acting" />
          </Stack>
          {!filled && <Alert severity="info" sx={{ mt: 2 }}>Saved without a holder, the position shows as vacant.</Alert>}
          <ErrorAlert error={save.error ?? people.error} />
        </DialogContent>
        <DialogActions><Button onClick={onClose}>Cancel</Button><Button type="submit" variant="contained" disabled={!f.title.trim() || save.isPending}>Save</Button></DialogActions>
      </form>
    </Dialog>
  );
}
