import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import {
  Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, MenuItem, Stack, Tab, Tabs, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api } from '../../api/client';
import type { ApprovalStage } from '../../api/types';
import { ErrorAlert } from '../../components/common';

const staff = { realm: 'staff' as const };
interface Routes { moduleResults: ApprovalStage[]; moduleStagesConfigured: boolean; resultSheet: ApprovalStage[] }

const ROLE_LABEL: Record<string, string> = {
  HEAD_OF_DEPARTMENT: 'Head of Department (of the module or programme)', EXAMINATIONS_OFFICER: 'Examinations Officer',
  DEPUTY_PRINCIPAL_ACADEMIC: 'Deputy Principal (Academic)', MANAGEMENT_VIEWER: 'Principal / Management', INSTITUTION_ADMIN: 'Institution administrator',
  PROGRAMME_COORDINATOR: 'Programme Coordinator', DEPARTMENT_BOARD: 'Head of Department or Examinations Officer',
};

/** Marks entry for one assessment item across the semester's modules (FR-EXM-02). */
export function ItemWindowDialog({ semesterId, onClose }: { semesterId: number; onClose: () => void }) {
  const [name, setName] = useState('');
  const [opens, setOpens] = useState('');
  const [closes, setCloses] = useState('');
  const m = useMutation({
    mutationFn: () => api<{ items: number }>(`/api/exams/semesters/${semesterId}/component-windows`, { ...staff, method: 'PUT', body: { name, opens: opens || null, closes: closes || null } }),
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>Marks entry for one assessment item</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          For example, open "Test 1" marks for a fortnight. Applies to every module of the semester with an item of that name. Leave both dates empty to go back to the semester's dates.
        </Typography>
        <Stack spacing={2}>
          <TextField label="Assessment item" value={name} onChange={(e) => setName(e.target.value)} placeholder="Test 1" />
          <TextField type="date" label="Opens" value={opens} onChange={(e) => setOpens(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField type="date" label="Closes" value={closes} onChange={(e) => setCloses(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          {m.isSuccess && <Alert severity="success">Set for {m.data.items} module{m.data.items === 1 ? '' : 's'}.</Alert>}
          <ErrorAlert error={m.error} />
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button><Button variant="contained" disabled={!name.trim() || m.isPending} onClick={() => m.mutate()}>Save</Button></DialogActions>
    </Dialog>
  );
}

/** The approval stages results pass through (FR-EXM-05 / FR-ADM-S03). */
export function ApprovalStagesDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['approval-stages'], queryFn: () => api<Routes>('/api/exams/approval-stages', staff) });
  const [flow, setFlow] = useState<'MODULE_RESULTS' | 'RESULT_SHEET'>('MODULE_RESULTS');
  const [list, setList] = useState<{ name: string; role: string }[]>([]);
  useEffect(() => {
    if (q.data) setList((flow === 'MODULE_RESULTS' ? q.data.moduleResults : q.data.resultSheet).map((x) => ({ name: x.name, role: x.role === 'DEPARTMENT_BOARD' ? 'HEAD_OF_DEPARTMENT' : x.role })));
  }, [q.data, flow]);
  const save = useMutation({
    mutationFn: () => api<Routes>(`/api/exams/approval-stages/${flow}`, { ...staff, method: 'PUT', body: list }),
    onSuccess: (r) => qc.setQueryData(['approval-stages'], r),
  });
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>Approval stages for results</DialogTitle>
      <DialogContent>
        <Tabs value={flow} onChange={(_, v) => setFlow(v)} sx={{ mb: 2 }}>
          <Tab value="MODULE_RESULTS" label="Module results" />
          <Tab value="RESULT_SHEET" label="Class result sheet" />
        </Tabs>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {flow === 'MODULE_RESULTS'
            ? 'Lecturer submits → Head of Department approves → the stages below → the module counts as approved by the department. Each stage can return the marks to the lecturer with a comment.'
            : 'Examinations Officer computes → the stages below → Academic Committee recommends → Governing Board confirms (results lock) → published. A stage can send the sheet back with a comment.'}
        </Typography>
        <Stack spacing={1.5}>
          {list.map((x, i) => (
            <Stack key={i} direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }}>
              <Typography sx={{ width: 24 }}>{i + 1}.</Typography>
              <TextField size="small" label="Stage" value={x.name} onChange={(e) => setList(list.map((y, j) => (j === i ? { ...y, name: e.target.value } : y)))} sx={{ flex: 1 }} />
              <TextField select size="small" label="Who acts" value={x.role} onChange={(e) => setList(list.map((y, j) => (j === i ? { ...y, role: e.target.value } : y)))} sx={{ flex: 1 }}>
                {Object.entries(ROLE_LABEL).filter(([k]) => k !== 'DEPARTMENT_BOARD').map(([k, v]) => <MenuItem key={k} value={k}>{v}</MenuItem>)}
              </TextField>
              <IconButton aria-label="Remove" onClick={() => setList(list.filter((_, j) => j !== i))}><DeleteIcon /></IconButton>
            </Stack>
          ))}
          {!list.length && <Typography color="text.secondary">No extra stages.</Typography>}
          <Box><Button startIcon={<AddIcon />} disabled={list.length >= 6} onClick={() => setList([...list, { name: '', role: 'EXAMINATIONS_OFFICER' }])}>Add a stage</Button></Box>
          {save.isSuccess && <Alert severity="success">Saved.</Alert>}
          <ErrorAlert error={save.error ?? q.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Close</Button>
        <Button variant="contained" disabled={list.some((x) => !x.name.trim()) || save.isPending} onClick={() => save.mutate()}>Save</Button>
      </DialogActions>
    </Dialog>
  );
}
