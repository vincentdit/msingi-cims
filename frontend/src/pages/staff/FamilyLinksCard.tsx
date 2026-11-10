import AddIcon from '@mui/icons-material/Add';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Switch, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, formatDate } from '../../api/client';
import { ErrorAlert } from '../../components/common';

const staff = { realm: 'staff' as const };

interface FamilyLink {
  id: number; phone: string; name?: string; relation?: string; source: 'NEXT_OF_KIN' | 'ADDED'; shareResults: boolean; shareAttendance: boolean;
  active: boolean; lastSeenAt?: string;
}

/**
 * The parents, guardians and others who may follow the student in the family portal (FR-STU-05). The next of kin is linked by itself;
 * the registry adds others and can stop a link. The student decides whether results and attendance are shared.
 */
export default function FamilyLinksCard({ studentId, canEdit }: { studentId: number; canEdit: boolean }) {
  const qc = useQueryClient();
  const key = ['staff', 'student', studentId, 'family'];
  const q = useQuery({ queryKey: key, queryFn: () => api<FamilyLink[]>(`/api/registry/students/${studentId}/family`, staff) });
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ phone: '', name: '', relation: '' });
  const add = useMutation({
    mutationFn: () => api<FamilyLink>(`/api/registry/students/${studentId}/family`, { ...staff, method: 'POST', body: form }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: key }); setOpen(false); setForm({ phone: '', name: '', relation: '' }); },
  });
  const setActive = useMutation({
    mutationFn: (l: FamilyLink) => api<FamilyLink>(`/api/registry/students/${studentId}/family/${l.id}`, { ...staff, method: 'PUT', body: { active: !l.active } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: key }),
  });
  return (
    <Card sx={{ mb: 2.5 }}>
      <CardContent>
        <Stack direction="row" alignItems="center" sx={{ mb: 0.5 }}>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>Family portal</Typography>
          {canEdit && <Button size="small" startIcon={<AddIcon />} onClick={() => setOpen(true)}>Add a person</Button>}
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
          These phones can sign in at the family portal with a code by SMS and see the student's fees. Results and attendance are shown only if the student shares them.
          Sponsors with a sponsorship this year are linked through Sponsors.
        </Typography>
        <ErrorAlert error={q.error ?? setActive.error} />
        {q.data?.length === 0 && <Typography variant="body2" color="text.secondary">No one is linked yet. The next of kin is linked once a phone number is recorded for them.</Typography>}
        <Stack spacing={1}>
          {q.data?.map((l) => (
            <Box key={l.id} sx={{ display: 'flex', gap: 1, alignItems: 'center', p: 1.25, border: 1, borderColor: 'divider', borderRadius: 1, opacity: l.active ? 1 : 0.6 }}>
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Typography sx={{ fontWeight: 600 }}>{l.name ?? l.phone}</Typography>
                  {l.relation && <Typography variant="body2" color="text.secondary">{l.relation}</Typography>}
                  <Chip size="small" variant="outlined" label={l.source === 'NEXT_OF_KIN' ? 'Next of kin' : 'Added by Registry'} />
                  {!l.active && <Chip size="small" label="Stopped" />}
                </Stack>
                <Typography variant="caption" color="text.secondary">
                  {`${l.phone} · sees fees${l.shareResults ? ', results' : ''}${l.shareAttendance ? ', attendance' : ''} · ${l.lastSeenAt ? `last signed in ${formatDate(l.lastSeenAt)}` : 'has not signed in'}`}
                </Typography>
              </Box>
              {canEdit && (
                <Stack alignItems="center">
                  <Switch size="small" checked={l.active} disabled={setActive.isPending} onChange={() => setActive.mutate(l)} slotProps={{ input: { 'aria-label': 'Allowed' } }} />
                  <Typography variant="caption" color="text.secondary">Allowed</Typography>
                </Stack>
              )}
            </Box>
          ))}
        </Stack>
      </CardContent>
      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle>Add a parent or guardian</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField label="Mobile number" value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} required inputMode="tel" placeholder="07XX XXX XXX" />
            <TextField label="Name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
            <TextField label="Relation" value={form.relation} onChange={(e) => setForm({ ...form, relation: e.target.value })} placeholder="Mother, uncle, guardian…" />
            <Alert severity="info">Check the number with the student. Whoever holds this phone will see the student's fees.</Alert>
            <ErrorAlert error={add.error} />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={add.isPending || form.phone.trim().length < 9} onClick={() => add.mutate()}>Add</Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}
