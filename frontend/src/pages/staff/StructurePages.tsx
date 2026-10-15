import AddIcon from '@mui/icons-material/Add';
import {
  Box, Button, Card, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, MenuItem, Stack, Switch, Table, TableBody,
  TableCell, TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import type { Campus, Department, NamedItem } from '../../api/types';
import { ErrorAlert, Loading } from '../../components/common';

const staff = { realm: 'staff' as const };

export const useCampuses = () => useQuery({ queryKey: ['staff', 'campuses'], queryFn: () => api<Campus[]>('/api/academic/campuses', staff) });
export const useFaculties = () => useQuery({ queryKey: ['staff', 'faculties'], queryFn: () => api<NamedItem[]>('/api/academic/faculties', staff) });
export const useCategories = () => useQuery({ queryKey: ['staff', 'student-categories'], queryFn: () => api<NamedItem[]>('/api/academic/student-categories', staff) });
export const useProgrammeCampuses = () => useQuery({ queryKey: ['staff', 'programme-campuses'], queryFn: () => api<Record<string, number[]>>('/api/academic/programme-campuses', staff) });

const CODE = /^[A-Z0-9_-]{2,20}$/;

/** Campuses of the institution (FR-ACA-01): one main campus; programmes are taught at one or more campuses. */
export function CampusesTab({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const q = useCampuses();
  const empty = { id: 0, code: '', name: '', region: '', district: '', address: '', main: false, active: true };
  const [form, setForm] = useState<typeof empty | null>(null);
  const save = useMutation({
    mutationFn: (f: typeof empty) => api(f.id ? `/api/academic/campuses/${f.id}` : '/api/academic/campuses', { method: f.id ? 'PUT' : 'POST', ...staff,
      body: { code: f.code, name: f.name, region: f.region || null, district: f.district || null, address: f.address || null, main: f.main, active: f.active } }),
    onSuccess: () => { setForm(null); qc.invalidateQueries({ queryKey: ['staff', 'campuses'] }); },
  });
  if (q.isLoading) return <Loading />;
  return (
    <>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }}>
        <Typography variant="body2" color="text.secondary">A programme with no campus listed is taught at the main campus. Students are placed at their programme's campus at hand-over and move only by an approved transfer.</Typography>
        {canEdit && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setForm(empty)} sx={{ flexShrink: 0, ml: 2 }}>New campus</Button>}
      </Stack>
      <ErrorAlert error={q.error} />
      <Card><TableContainer><Table size="small">
        <TableHead><TableRow><TableCell>Code</TableCell><TableCell>Campus</TableCell><TableCell>Location</TableCell><TableCell align="right">Programmes</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {q.data?.length === 0 && <TableRow><TableCell colSpan={5}><Typography color="text.secondary">No campuses listed: the institution has one site.</Typography></TableCell></TableRow>}
          {q.data?.map((c) => (
            <TableRow key={c.id} hover={canEdit} sx={{ cursor: canEdit ? 'pointer' : 'default' }}
              onClick={() => canEdit && setForm({ id: c.id, code: c.code, name: c.name, region: c.region ?? '', district: c.district ?? '', address: c.address ?? '', main: c.main, active: c.active })}>
              <TableCell sx={{ fontFamily: 'monospace' }}>{c.code}</TableCell>
              <TableCell>{c.name}</TableCell>
              <TableCell>{[c.address, c.district, c.region].filter(Boolean).join(', ') || '—'}</TableCell>
              <TableCell align="right">{c.programmes}</TableCell>
              <TableCell align="right">
                <Stack direction="row" spacing={0.5} justifyContent="flex-end">
                  {c.main && <Chip size="small" color="primary" label="Main campus" />}
                  {!c.active && <Chip size="small" label="Inactive" />}
                </Stack>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table></TableContainer></Card>
      <Dialog open={!!form} onClose={() => setForm(null)} fullWidth maxWidth="sm">
        <DialogTitle>{form?.id ? form.name : 'New campus'}</DialogTitle>
        {form && (
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              <Stack direction="row" spacing={2}>
                <TextField label="Code" required value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} sx={{ width: 160 }}
                  error={!!form.code && !CODE.test(form.code)} helperText="e.g. MAIN, ARUSHA" />
                <TextField label="Name" required fullWidth value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              </Stack>
              <Stack direction="row" spacing={2}>
                <TextField label="Region" fullWidth value={form.region} onChange={(e) => setForm({ ...form, region: e.target.value })} />
                <TextField label="District" fullWidth value={form.district} onChange={(e) => setForm({ ...form, district: e.target.value })} />
              </Stack>
              <TextField label="Address" value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
              <Stack direction="row" spacing={2}>
                <FormControlLabel control={<Switch checked={form.main} onChange={(e) => setForm({ ...form, main: e.target.checked })} />} label="Main campus" />
                <FormControlLabel control={<Switch checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />} label="Active" />
              </Stack>
              <ErrorAlert error={save.error} />
            </Stack>
          </DialogContent>
        )}
        <DialogActions><Button onClick={() => setForm(null)}>Cancel</Button>
          <Button variant="contained" disabled={!form || !CODE.test(form.code) || !form.name.trim() || save.isPending} onClick={() => form && save.mutate(form)}>Save</Button></DialogActions>
      </Dialog>
    </>
  );
}

/** Departments, each optionally under a faculty or school. */
export function DepartmentsTab({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'departments'], queryFn: () => api<Department[]>('/api/academic/departments', staff) });
  const faculties = useFaculties();
  const empty = { id: 0, code: '', name: '', facultyId: '', active: true };
  const [form, setForm] = useState<typeof empty | null>(null);
  const save = useMutation({
    mutationFn: (f: typeof empty) => api(f.id ? `/api/academic/departments/${f.id}` : '/api/academic/departments', { method: f.id ? 'PUT' : 'POST', ...staff,
      body: { code: f.code, name: f.name, active: f.active, facultyId: f.facultyId ? Number(f.facultyId) : null } }),
    onSuccess: () => { setForm(null); qc.invalidateQueries({ queryKey: ['staff', 'departments'] }); },
  });
  const facultyName = (id?: number) => faculties.data?.find((f) => f.id === id)?.name;
  if (q.isLoading) return <Loading />;
  return (
    <>
      <Stack direction="row" justifyContent="flex-end" sx={{ mb: 1.5 }}>
        {canEdit && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setForm(empty)}>New department</Button>}
      </Stack>
      <Card><TableContainer><Table size="small">
        <TableHead><TableRow><TableCell>Code</TableCell><TableCell>Department</TableCell><TableCell>Faculty / school</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {q.data?.map((d) => (
            <TableRow key={d.id} hover={canEdit} sx={{ cursor: canEdit ? 'pointer' : 'default' }}
              onClick={() => canEdit && setForm({ id: d.id, code: d.code, name: d.name, facultyId: d.facultyId ? String(d.facultyId) : '', active: d.active })}>
              <TableCell sx={{ fontFamily: 'monospace' }}>{d.code}</TableCell><TableCell>{d.name}</TableCell>
              <TableCell>{facultyName(d.facultyId) ?? '—'}</TableCell>
              <TableCell align="right">{!d.active && <Chip size="small" label="Inactive" />}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table></TableContainer></Card>
      <Dialog open={!!form} onClose={() => setForm(null)} fullWidth maxWidth="sm">
        <DialogTitle>{form?.id ? form.name : 'New department'}</DialogTitle>
        {form && (
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              <Stack direction="row" spacing={2}>
                <TextField label="Code" required value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} sx={{ width: 160 }} />
                <TextField label="Name" required fullWidth value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              </Stack>
              <TextField select label="Faculty or school (optional)" value={form.facultyId} onChange={(e) => setForm({ ...form, facultyId: e.target.value })}
                slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}>
                <MenuItem value="">None</MenuItem>
                {faculties.data?.filter((f) => f.active).map((f) => <MenuItem key={f.id} value={String(f.id)}>{f.name}</MenuItem>)}
              </TextField>
              <FormControlLabel control={<Switch checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />} label="Active" />
              <ErrorAlert error={save.error} />
            </Stack>
          </DialogContent>
        )}
        <DialogActions><Button onClick={() => setForm(null)}>Cancel</Button>
          <Button variant="contained" disabled={!form?.code || !form?.name.trim() || save.isPending} onClick={() => form && save.mutate(form)}>Save</Button></DialogActions>
      </Dialog>
    </>
  );
}

/** A simple code-and-name list: faculties, or student categories priced separately in fee structures. */
export function NamedListTab({ kind, canEdit }: { kind: 'faculties' | 'student-categories'; canEdit: boolean }) {
  const qc = useQueryClient();
  const fq = useFaculties();
  const cq = useCategories();
  const q = kind === 'faculties' ? fq : cq;
  const noun = kind === 'faculties' ? 'faculty or school' : 'student category';
  const empty = { id: 0, code: '', name: '', active: true };
  const [form, setForm] = useState<typeof empty | null>(null);
  const save = useMutation({
    mutationFn: (f: typeof empty) => api(f.id ? `/api/academic/${kind}/${f.id}` : `/api/academic/${kind}`, { method: f.id ? 'PUT' : 'POST', ...staff,
      body: { code: f.code, name: f.name, active: f.active } }),
    onSuccess: () => { setForm(null); qc.invalidateQueries({ queryKey: ['staff', kind] }); },
  });
  if (q.isLoading) return <Loading />;
  return (
    <>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }}>
        <Typography variant="body2" color="text.secondary">
          {kind === 'faculties' ? 'Optional: group departments into faculties or schools.'
            : 'Optional: categories priced differently, such as regular, evening, weekend or distance. A fee structure can be made for one category; students are billed under theirs, else the general structure.'}
        </Typography>
        {canEdit && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setForm(empty)} sx={{ flexShrink: 0, ml: 2 }}>New {noun.split(' ')[0] === 'faculty' ? 'faculty' : 'category'}</Button>}
      </Stack>
      <Card><TableContainer><Table size="small">
        <TableHead><TableRow><TableCell>Code</TableCell><TableCell>Name</TableCell><TableCell /></TableRow></TableHead>
        <TableBody>
          {q.data?.length === 0 && <TableRow><TableCell colSpan={3}><Typography color="text.secondary">None yet.</Typography></TableCell></TableRow>}
          {q.data?.map((c) => (
            <TableRow key={c.id} hover={canEdit} sx={{ cursor: canEdit ? 'pointer' : 'default' }} onClick={() => canEdit && setForm({ ...c })}>
              <TableCell sx={{ fontFamily: 'monospace' }}>{c.code}</TableCell><TableCell>{c.name}</TableCell>
              <TableCell align="right">{!c.active && <Chip size="small" label="Inactive" />}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table></TableContainer></Card>
      <Dialog open={!!form} onClose={() => setForm(null)} fullWidth maxWidth="xs">
        <DialogTitle>{form?.id ? form.name : `New ${noun}`}</DialogTitle>
        {form && (
          <DialogContent>
            <Stack spacing={2} sx={{ mt: 1 }}>
              <TextField label="Code" required value={form.code} onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })} />
              <TextField label="Name" required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              <FormControlLabel control={<Switch checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />} label="Active" />
              <ErrorAlert error={save.error} />
            </Stack>
          </DialogContent>
        )}
        <DialogActions><Button onClick={() => setForm(null)}>Cancel</Button>
          <Button variant="contained" disabled={!form || !CODE.test(form.code) || !form.name.trim() || save.isPending} onClick={() => form && save.mutate(form)}>Save</Button></DialogActions>
      </Dialog>
    </>
  );
}

/** Campus choice for a programme: checkboxes as chips. */
export function CampusPicker({ value, onChange }: { value: number[]; onChange: (v: number[]) => void }) {
  const campuses = useCampuses();
  const active = campuses.data?.filter((c) => c.active) ?? [];
  if (active.length < 2) return null;
  return (
    <Box>
      <Typography variant="caption" color="text.secondary">Taught at (none chosen = the main campus)</Typography>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 0.5 }}>
        {active.map((c) => {
          const on = value.includes(c.id);
          return <Chip key={c.id} label={c.name} color={on ? 'primary' : 'default'} variant={on ? 'filled' : 'outlined'}
            onClick={() => onChange(on ? value.filter((x) => x !== c.id) : [...value, c.id])} />;
        })}
      </Stack>
    </Box>
  );
}
