import EditIcon from '@mui/icons-material/Edit';
import {
  Alert, Box, Button, Card, CardContent, Dialog, DialogActions, DialogContent, DialogTitle, Stack, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, formatDate } from '../../api/client';
import type { AdmissionWindow, JoiningInstructions } from '../../api/types';
import { ErrorAlert } from '../../components/common';

const toLines = (a?: string[]) => (a ?? []).join('\n');
const fromLines = (s: string) => s.split('\n').map((l) => l.trim()).filter(Boolean);

type Form = {
  reportingDate: string; reportingTime: string; reportingVenue: string; registrationDeadline: string;
  documentsToBring: string; itemsToBring: string; feesNote: string; notes: string; signatoryName: string; signatoryTitle: string;
};

const toForm = (j?: JoiningInstructions): Form => ({
  reportingDate: j?.reportingDate ?? '', reportingTime: j?.reportingTime ?? '08:00', reportingVenue: j?.reportingVenue ?? '',
  registrationDeadline: j?.registrationDeadline ?? '', documentsToBring: toLines(j?.documentsToBring), itemsToBring: toLines(j?.itemsToBring),
  feesNote: j?.feesNote ?? '', notes: toLines(j?.notes), signatoryName: j?.signatoryName ?? '', signatoryTitle: j?.signatoryTitle ?? 'Principal',
});

/** Joining instructions printed with every admission letter of the window (FR-ADM-13). */
export default function JoiningInstructionsCard({ win, canEdit }: { win: AdmissionWindow; canEdit: boolean }) {
  const qc = useQueryClient();
  const j = win.joiningInstructions;
  const [form, setForm] = useState<Form | null>(null);
  const save = useMutation({
    mutationFn: (f: Form) => api<JoiningInstructions>(`/api/admission/windows/${win.id}/joining-instructions`, {
      method: 'PUT', realm: 'staff', body: {
        reportingDate: f.reportingDate, reportingTime: f.reportingTime || null, reportingVenue: f.reportingVenue,
        registrationDeadline: f.registrationDeadline || null, documentsToBring: fromLines(f.documentsToBring),
        itemsToBring: fromLines(f.itemsToBring), feesNote: f.feesNote || null, notes: fromLines(f.notes),
        signatoryName: f.signatoryName, signatoryTitle: f.signatoryTitle,
      },
    }),
    onSuccess: () => { setForm(null); qc.invalidateQueries({ queryKey: ['staff'] }); },
  });
  const set = (k: keyof Form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm((f) => f && { ...f, [k]: e.target.value });
  const valid = form && form.reportingDate && form.reportingVenue.trim() && form.signatoryName.trim() && form.signatoryTitle.trim();

  return (
    <Card sx={{ mt: 3 }}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={2} sx={{ mb: 1 }}>
          <Box>
            <Typography variant="h6">Joining instructions</Typography>
            <Typography variant="body2" color="text.secondary">Printed with every admission letter of this window. Letters already issued keep the instructions they were issued with.</Typography>
          </Box>
          {canEdit && <Button startIcon={<EditIcon />} onClick={() => setForm(toForm(j))}>{j ? 'Edit' : 'Set up'}</Button>}
        </Stack>
        {!j && <Alert severity="warning">Not set yet — admission letters cannot be issued until the reporting date, venue and signatory are set.</Alert>}
        {j && (
          <Grid container spacing={2}>
            <Grid size={{ xs: 12, md: 4 }}>
              <Typography variant="body2" color="text.secondary">Reporting</Typography>
              <Typography>{formatDate(j.reportingDate)}{j.reportingTime ? ` at ${j.reportingTime}` : ''}</Typography>
              <Typography>{j.reportingVenue}</Typography>
              {j.registrationDeadline && <Typography variant="body2" color="text.secondary">Register by {formatDate(j.registrationDeadline)}</Typography>}
            </Grid>
            <Grid size={{ xs: 12, md: 4 }}>
              <Typography variant="body2" color="text.secondary">To bring</Typography>
              <Typography>{j.documentsToBring.length} document(s), {j.itemsToBring.length} item(s)</Typography>
              {j.feesNote && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Fees note included</Typography>}
            </Grid>
            <Grid size={{ xs: 12, md: 4 }}>
              <Typography variant="body2" color="text.secondary">Signed by</Typography>
              <Typography>{j.signatoryName}</Typography>
              <Typography variant="body2">{j.signatoryTitle}</Typography>
            </Grid>
          </Grid>
        )}
      </CardContent>

      <Dialog open={!!form} onClose={() => setForm(null)} maxWidth="md" fullWidth>
        <DialogTitle>Joining instructions</DialogTitle>
        {form && (
          <DialogContent>
            <Grid container spacing={2} sx={{ mt: 0.5 }}>
              <Grid size={{ xs: 12, sm: 4 }}><TextField label="Reporting date" type="date" fullWidth required value={form.reportingDate} onChange={set('reportingDate')} slotProps={{ inputLabel: { shrink: true } }} /></Grid>
              <Grid size={{ xs: 12, sm: 3 }}><TextField label="Time (HH:mm)" fullWidth value={form.reportingTime} onChange={set('reportingTime')} /></Grid>
              <Grid size={{ xs: 12, sm: 5 }}><TextField label="Register by" type="date" fullWidth value={form.registrationDeadline} onChange={set('registrationDeadline')} slotProps={{ inputLabel: { shrink: true } }} /></Grid>
              <Grid size={12}><TextField label="Reporting venue" fullWidth required value={form.reportingVenue} onChange={set('reportingVenue')} /></Grid>
              <Grid size={{ xs: 12, md: 6 }}><TextField label="Documents to bring (one per line)" fullWidth multiline minRows={4} value={form.documentsToBring} onChange={set('documentsToBring')} /></Grid>
              <Grid size={{ xs: 12, md: 6 }}><TextField label="Items to bring (one per line)" fullWidth multiline minRows={4} value={form.itemsToBring} onChange={set('itemsToBring')} /></Grid>
              <Grid size={12}><TextField label="Fees" fullWidth multiline minRows={2} value={form.feesNote} onChange={set('feesNote')} helperText="Amounts and how to pay. Fees are always paid through a GePG control number." /></Grid>
              <Grid size={12}><TextField label="Other information (one per line)" fullWidth multiline minRows={2} value={form.notes} onChange={set('notes')} /></Grid>
              <Grid size={{ xs: 12, sm: 6 }}><TextField label="Signatory name" fullWidth required value={form.signatoryName} onChange={set('signatoryName')} /></Grid>
              <Grid size={{ xs: 12, sm: 6 }}><TextField label="Signatory title" fullWidth required value={form.signatoryTitle} onChange={set('signatoryTitle')} /></Grid>
            </Grid>
            <Box sx={{ mt: 2 }}><ErrorAlert error={save.error} /></Box>
          </DialogContent>
        )}
        <DialogActions>
          <Button onClick={() => setForm(null)}>Cancel</Button>
          <Button variant="contained" disabled={!valid || save.isPending} onClick={() => form && save.mutate(form)}>Save</Button>
        </DialogActions>
      </Dialog>
    </Card>
  );
}
