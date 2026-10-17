import FactCheckIcon from '@mui/icons-material/FactCheckOutlined';
import {
  Alert, Badge, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Tab, Table, TableBody,
  TableCell, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, formatDateTime } from '../../api/client';
import type { ChangeRequest } from '../../api/types';
import { ErrorAlert, Loading } from '../../components/common';
import { StaffLink } from '../../components/StaffLink';

const staff = { realm: 'staff' as const };

/** Button with the number of pending requests; opens the review dialog (FR-REG-08). */
export function ChangeRequestsButton({ canDecide }: { canDecide: boolean }) {
  const [open, setOpen] = useState(false);
  const count = useQuery({ queryKey: ['staff', 'change-requests', 'count'], queryFn: () => api<{ pending: number }>('/api/registry/change-requests/count', staff) });
  return (
    <>
      <Badge color="error" badgeContent={count.data?.pending ?? 0}>
        <Button variant="outlined" startIcon={<FactCheckIcon />} onClick={() => setOpen(true)} sx={{ whiteSpace: 'nowrap' }}>Update requests</Button>
      </Badge>
      {open && <ChangeRequestsDialog canDecide={canDecide} onClose={() => setOpen(false)} />}
    </>
  );
}

function ChangeRequestsDialog({ canDecide, onClose }: { canDecide: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const [tab, setTab] = useState<'PENDING' | 'DECIDED'>('PENDING');
  const [rejecting, setRejecting] = useState<ChangeRequest | null>(null);
  const [note, setNote] = useState('');
  const list = useQuery({ queryKey: ['staff', 'change-requests', tab], queryFn: () => api<ChangeRequest[]>(`/api/registry/change-requests?status=${tab}`, staff) });
  const done = () => qc.invalidateQueries({ queryKey: ['staff', 'change-requests'] }).then(() => qc.invalidateQueries({ queryKey: ['staff', 'students'] }));
  const approve = useMutation({
    mutationFn: (r: ChangeRequest) => api<ChangeRequest>(`/api/registry/change-requests/${r.id}/approve`, { ...staff, method: 'POST', body: {} }),
    onSuccess: done,
  });
  const reject = useMutation({
    mutationFn: () => api<ChangeRequest>(`/api/registry/change-requests/${rejecting!.id}/reject`, { ...staff, method: 'POST', body: { note } }),
    onSuccess: () => { setRejecting(null); setNote(''); done(); },
  });
  return (
    <Dialog open onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>Requests to update student details</DialogTitle>
      <DialogContent>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2 }}>
          <Tab value="PENDING" label="Waiting" />
          <Tab value="DECIDED" label="Decided" />
        </Tabs>
        <ErrorAlert error={list.error ?? approve.error} />
        {list.isLoading && <Loading />}
        {list.data && !list.data.length && <Typography color="text.secondary">{tab === 'PENDING' ? 'No requests are waiting.' : 'No decisions yet.'}</Typography>}
        <Stack spacing={1.5}>
          {list.data?.map((r) => (
            <Card key={r.id} variant="outlined">
              <CardContent>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'center' }} sx={{ mb: 1 }}>
                  <Box sx={{ flexGrow: 1 }}>
                    <Typography sx={{ fontWeight: 700 }} component={StaffLink} to={`/staff/students/${r.studentId}`} onClick={onClose} color="inherit">
                      {r.studentName}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">{r.registrationNo} · {r.programmeName} · sent {formatDateTime(r.submittedAt)}</Typography>
                  </Box>
                  {r.status !== 'PENDING' && <Chip size="small" color={r.status === 'APPROVED' ? 'success' : 'error'} label={r.status === 'APPROVED' ? 'Approved' : 'Rejected'} />}
                </Stack>
                <Table size="small">
                  <TableHead><TableRow><TableCell>Detail</TableCell><TableCell>Now</TableCell><TableCell>Requested</TableCell></TableRow></TableHead>
                  <TableBody>
                    {r.changes.map((c) => (
                      <TableRow key={c.field}>
                        <TableCell>{c.label}</TableCell>
                        <TableCell sx={{ color: 'text.secondary' }}>{c.from || '—'}</TableCell>
                        <TableCell sx={{ fontWeight: 700 }}>{c.to || '—'}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
                <Typography variant="body2" sx={{ mt: 1 }}><strong>Reason:</strong> {r.reason}</Typography>
                {r.needsDocuments && r.status === 'PENDING' && (
                  <Alert severity="warning" sx={{ mt: 1 }}>Name or date of birth: check the original documents (e.g. birth certificate, CSEE certificate) before approving.</Alert>
                )}
                {r.decidedAt && (
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                    {r.decidedByName}, {formatDateTime(r.decidedAt)}{r.decisionNote ? ` — “${r.decisionNote}”` : ''}
                  </Typography>
                )}
                {canDecide && r.status === 'PENDING' && (
                  <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
                    <Button variant="contained" color="success" disabled={approve.isPending} onClick={() => approve.mutate(r)}>Approve and apply</Button>
                    <Button variant="outlined" color="error" onClick={() => { setRejecting(r); setNote(''); }}>Reject</Button>
                  </Stack>
                )}
              </CardContent>
            </Card>
          ))}
        </Stack>
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Close</Button></DialogActions>
      <Dialog open={!!rejecting} onClose={() => setRejecting(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Reject request from {rejecting?.studentName}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>The reason is sent to the student by SMS and shown on the portal.</Typography>
          <TextField autoFocus multiline minRows={2} label="Reason" value={note} onChange={(e) => setNote(e.target.value)} inputProps={{ maxLength: 500 }} />
          <ErrorAlert error={reject.error} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRejecting(null)}>Cancel</Button>
          <Button variant="contained" color="error" disabled={!note.trim() || reject.isPending} onClick={() => reject.mutate()}>Reject</Button>
        </DialogActions>
      </Dialog>
    </Dialog>
  );
}
