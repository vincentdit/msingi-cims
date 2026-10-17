import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import type { AdmissionWindow, EntryRules, Programme, WindowProgramme } from '../../api/types';
import { ErrorAlert } from '../../components/common';
import EntryRulesEditor, { rulesProblems } from '../../components/EntryRulesEditor';

const DEFAULT_RULES: EntryRules = {
  summary: 'Four passes in CSEE including Basic Mathematics',
  pathways: [{ label: 'Form Four (CSEE)', conditions: [
    { type: 'CSEE_MIN_PASSES', count: 4, minGrade: 'D' },
    { type: 'CSEE_SUBJECT', count: 1, minGrade: 'D', subjects: ['BASIC MATHEMATICS'] },
  ] }],
};

/** Offers a programme in an admission window, or changes the places and entry requirements of one already offered. */
export default function ProgrammeOfferDialog({ win, offer, programmes, onClose, onSaved }: {
  win: AdmissionWindow; offer: WindowProgramme | null; programmes: Programme[]; onClose: () => void; onSaved: () => void;
}) {
  const [programmeId, setProgrammeId] = useState(offer ? String(offer.programmeId) : '');
  const [capacity, setCapacity] = useState(String(offer?.capacity ?? 50));
  const [rules, setRules] = useState<EntryRules>(() => structuredClone(offer?.entryRules ?? DEFAULT_RULES));
  const [tried, setTried] = useState(false);
  const save = useMutation({
    mutationFn: () => api(offer ? `/api/admission/windows/${win.id}/programmes/${offer.id}` : `/api/admission/windows/${win.id}/programmes`, {
      realm: 'staff', method: offer ? 'PUT' : 'POST',
      body: { programmeId: Number(programmeId), capacity: Number(capacity), entryRules: { ...rules, summary: rules.summary.trim() } },
    }),
    onSuccess: onSaved,
  });
  const problems = [
    ...(!programmeId ? ['Choose the programme.'] : []),
    ...(!(Number(capacity) >= 1) ? ['Places must be 1 or more.'] : []),
    ...rulesProblems(rules),
  ];
  const submit = () => { setTried(true); if (!problems.length) save.mutate(); };
  const applicationsMayExist = win.state !== 'DRAFT' && win.state !== 'SCHEDULED';
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md" scroll="paper">
      <DialogTitle>{offer ? `Edit ${offer.programmeName}` : 'Offer a programme'}</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2}>
          {offer ? <Typography color="text.secondary">{offer.awardName}</Typography> : (
            <TextField select label="Programme" value={programmeId} onChange={(e) => setProgrammeId(e.target.value)} required>
              {programmes.filter((p) => !win.programmes?.some((wp) => wp.programmeId === p.id)).map((p) => <MenuItem key={p.id} value={String(p.id)}>{p.name}</MenuItem>)}
            </TextField>
          )}
          <TextField label="Places (capacity)" type="number" value={capacity} onChange={(e) => setCapacity(e.target.value)} sx={{ maxWidth: 200 }} inputProps={{ min: 1 }} required />
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>NACTVET minimum entry requirements</Typography>
          {offer && applicationsMayExist && (
            <Alert severity="info">
              New requirements apply to choices made from now on. Applicants who already chose this programme keep their choice; use Qualification checks and
              Test entry rules to see who meets the new requirements.
            </Alert>
          )}
          <EntryRulesEditor value={rules} onChange={setRules} />
          {tried && problems.length > 0 && <Alert severity="warning">{problems.map((p) => <div key={p}>{p}</div>)}</Alert>}
          <ErrorAlert error={save.error} />
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" onClick={submit} disabled={save.isPending}>{offer ? 'Save changes' : 'Add'}</Button>
      </DialogActions>
    </Dialog>
  );
}
