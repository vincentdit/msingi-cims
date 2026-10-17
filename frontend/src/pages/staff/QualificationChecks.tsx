import {
  Alert, Box, Button, Card, CardContent, Chip, Container, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Tab, Tabs, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, formatDateTime } from '../../api/client';
import type { AdmissionWindow, Qualification } from '../../api/types';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle, humanise, statusColour } from '../../components/common';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };
const DECIDE = ['INSTITUTION_ADMIN', 'ADMISSIONS_OFFICER'];

export interface QualificationCheck {
  qualification: Qualification; applicantId: number; applicantName: string; phone: string;
  applicationId?: number; referenceNo?: string; applicationStatus?: string;
}

export const CHECK_COLOUR = { PENDING: 'warning', VERIFIED: 'success', REJECTED: 'error' } as const;
export const CHECK_LABEL = { PENDING: 'Waiting for check', VERIFIED: 'Verified', REJECTED: 'Not accepted' } as const;

/** One line describing a qualification: type, level, award, number, institution, year. */
export function describe(q: Qualification) {
  const head = q.type === 'OTHER' ? `${q.awardName} (${humanise(q.otherLevel ?? 'OTHER')})`
    : q.type === 'NTA' || q.type === 'NVA' ? `${q.type} Level ${q.awardLevel}${q.awardName ? ` · ${q.awardName}` : ''}` : `${q.type} · ${q.indexNumber}`;
  const rest = [q.indexNumber.startsWith('OTH-') || q.type === 'CSEE' || q.type === 'ACSEE' ? null : q.indexNumber, q.institution ?? q.centreName,
    q.examYear].filter(Boolean).join(' · ');
  return { head, rest };
}

/** Verify a declared qualification, or turn it down with the reason the applicant will see. */
export function CheckButtons({ q, onDone }: { q: Qualification; onDone: () => void }) {
  const { user } = useSession();
  const can = !!user('staff')?.roles.some((r) => DECIDE.includes(r));
  const [rejecting, setRejecting] = useState(false);
  const [note, setNote] = useState('');
  const m = useMutation({
    mutationFn: (verified: boolean) => api(`/api/admission/qualifications/${q.id}/check`, { ...staff, method: 'POST', body: { verified, note: note.trim() || null } }),
    onSuccess: () => { setRejecting(false); setNote(''); onDone(); },
  });
  if (!can || q.source !== 'MANUAL') return null;
  return (
    <>
      <Stack direction="row" spacing={1}>
        {q.checkStatus !== 'VERIFIED' && <Button size="small" variant="contained" color="success" disabled={m.isPending} onClick={() => m.mutate(true)}>Verify</Button>}
        {q.checkStatus !== 'REJECTED' && <Button size="small" color="error" disabled={m.isPending} onClick={() => setRejecting(true)}>Not accepted</Button>}
      </Stack>
      <ErrorAlert error={m.error} />
      <Dialog open={rejecting} onClose={() => setRejecting(false)} fullWidth maxWidth="sm">
        <DialogTitle>Not accepted · {describe(q).head}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>The applicant sees this reason on their application and gets it by SMS. The qualification will not count for eligibility.</Typography>
          <TextField autoFocus fullWidth multiline minRows={2} label="Reason" value={note} onChange={(e) => setNote(e.target.value)}
            placeholder="e.g. The certificate number does not match the awarding body's records" />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRejecting(false)}>Back</Button>
          <Button variant="contained" color="error" disabled={!note.trim() || m.isPending} onClick={() => m.mutate(false)}>Not accepted</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

/** The Admissions Office's queue of declared qualifications (NVA, other awards, typed-in NTA awards) for a window. */
export function QualificationChecksPage() {
  const { id } = useParams();
  const qc = useQueryClient();
  const [tab, setTab] = useState<'PENDING' | 'VERIFIED' | 'REJECTED'>('PENDING');
  const win = useQuery({ queryKey: ['staff', 'window', id], queryFn: () => api<AdmissionWindow>(`/api/admission/windows/${id}`, staff) });
  const list = useQuery({ queryKey: ['staff', 'qualification-checks', id, tab],
    queryFn: () => api<QualificationCheck[]>(`/api/admission/windows/${id}/qualification-checks?status=${tab}`, staff) });
  const refresh = () => qc.invalidateQueries({ queryKey: ['staff', 'qualification-checks', id] });
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Qualification checks" subtitle={`${win.data?.name ?? ''} · awards applicants declared, checked against their certificates. Results from NECTA and NACTVET are verified at source.`}
        action={<Button component={StaffLink} to={`/staff/windows/${id}`}>Back to window</Button>} />
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2, borderBottom: 1, borderColor: 'divider' }}>
        <Tab value="PENDING" label="Waiting" />
        <Tab value="VERIFIED" label="Verified" />
        <Tab value="REJECTED" label="Not accepted" />
      </Tabs>
      <ErrorAlert error={list.error} />
      {list.isLoading && <Loading />}
      {list.data && !list.data.length && <Alert severity="info">{tab === 'PENDING' ? 'Nothing is waiting to be checked.' : 'None.'}</Alert>}
      <Stack spacing={1.5}>
        {list.data?.map((c) => {
          const q = c.qualification;
          const d = describe(q);
          return (
            <Card key={q.id}>
              <CardContent>
                <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ md: 'center' }}>
                  <Box>
                    <Typography sx={{ fontWeight: 700 }}>{d.head}</Typography>
                    <Typography variant="body2" color="text.secondary">{d.rest}</Typography>
                    <Typography variant="body2" component="div" sx={{ mt: 0.5 }}>
                      {c.applicantName} · {c.phone}{c.referenceNo ? ` · ${c.referenceNo}` : ''}
                      {c.applicationStatus && <Chip size="small" sx={{ ml: 1 }} color={statusColour(c.applicationStatus)} label={humanise(c.applicationStatus)} />}
                    </Typography>
                    {q.checkStatus !== 'PENDING' && (
                      <Typography variant="caption" color="text.secondary">
                        {CHECK_LABEL[q.checkStatus]} by {q.checkedByName} · {formatDateTime(q.checkedAt)}{q.checkNote ? ` — ${q.checkNote}` : ''}
                      </Typography>
                    )}
                  </Box>
                  <CheckButtons q={q} onDone={refresh} />
                </Stack>
              </CardContent>
            </Card>
          );
        })}
      </Stack>
    </Container>
  );
}
