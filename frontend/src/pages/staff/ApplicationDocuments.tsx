import AddIcon from '@mui/icons-material/Add';
import {
  Box, Button, Card, CardContent, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, MenuItem,
  Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, formatDateTime, privateImageUrl } from '../../api/client';
import { ErrorAlert } from '../../components/common';
import { sizeLabel, type AppDocument } from '../applicant/DocumentsStep';

const staff = { realm: 'staff' as const };
interface DocType { id: number; code: string; name: string; description?: string; required: boolean; maxKb: number; sortOrder: number; uploads: number }

/** The documents an admission window asks applicants to upload (FR-ADM-07). */
export function WindowDocumentsCard({ windowId, canEdit }: { windowId: number; canEdit: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'doc-types', windowId], queryFn: () => api<DocType[]>(`/api/admission/windows/${windowId}/document-types`, staff) });
  const empty = { id: 0, code: '', name: '', description: '', required: true, maxKb: 2048, sortOrder: 0 };
  const [f, setF] = useState<typeof empty | null>(null);
  const done = () => { qc.invalidateQueries({ queryKey: ['staff', 'doc-types', windowId] }); setF(null); };
  const save = useMutation({
    mutationFn: () => f!.id
      ? api(`/api/admission/document-types/${f!.id}`, { ...staff, method: 'PUT', body: f })
      : api(`/api/admission/windows/${windowId}/document-types`, { ...staff, method: 'POST', body: { ...f, sortOrder: (q.data?.length ?? 0) + 1 } }),
    onSuccess: done,
  });
  const del = useMutation({ mutationFn: (id: number) => api(`/api/admission/document-types/${id}`, { ...staff, method: 'DELETE' }), onSuccess: done });
  return (
    <Card sx={{ mt: 2.5 }}><CardContent>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
        <Box>
          <Typography variant="h6">Documents applicants upload</Typography>
          <Typography variant="body2" color="text.secondary">PDF, JPG or PNG. A required document must be uploaded before the application can be submitted.</Typography>
        </Box>
        {canEdit && <Button startIcon={<AddIcon />} onClick={() => setF(empty)}>Add</Button>}
      </Stack>
      <ErrorAlert error={q.error ?? del.error} />
      {(q.data ?? []).length === 0 ? <Typography variant="body2" color="text.secondary">No documents asked for.</Typography> : (
        <Table size="small">
          <TableHead><TableRow><TableCell>Document</TableCell><TableCell>Required</TableCell><TableCell>Size limit</TableCell><TableCell align="right">Uploaded</TableCell><TableCell /></TableRow></TableHead>
          <TableBody>
            {q.data!.map((t) => (
              <TableRow key={t.id} hover>
                <TableCell><b>{t.name}</b> <Typography component="span" variant="caption" color="text.secondary">{t.code}</Typography>
                  {t.description && <Typography variant="caption" component="div" color="text.secondary">{t.description}</Typography>}</TableCell>
                <TableCell>{t.required ? 'Required' : 'Optional'}</TableCell>
                <TableCell>{sizeLabel(t.maxKb)}</TableCell>
                <TableCell align="right">{t.uploads}</TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                  {canEdit && <Button size="small" onClick={() => setF({ ...t, description: t.description ?? '' })}>Edit</Button>}
                  {canEdit && t.uploads === 0 && <IconButton size="small" aria-label="Remove" onClick={() => del.mutate(t.id)}><DeleteIcon fontSize="small" /></IconButton>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      <Dialog open={!!f} onClose={() => setF(null)} fullWidth maxWidth="sm">
        <DialogTitle>{f?.id ? 'Edit document' : 'Ask for a document'}</DialogTitle>
        {f && <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            <TextField label="Name" value={f.name} onChange={(e) => setF({ ...f, name: e.target.value })} placeholder="e.g. Birth certificate" />
            <TextField label="Code" value={f.code} onChange={(e) => setF({ ...f, code: e.target.value.toUpperCase().replace(/[^A-Z0-9_]/g, '_') })} placeholder="BIRTH_CERT" />
            <TextField label="Guidance for applicants (optional)" value={f.description} onChange={(e) => setF({ ...f, description: e.target.value })} multiline minRows={2} />
            <TextField select label="Size limit" value={f.maxKb} onChange={(e) => setF({ ...f, maxKb: Number(e.target.value) })}>
              {[200, 500, 1024, 2048, 5120].map((k) => <MenuItem key={k} value={k}>{sizeLabel(k)}</MenuItem>)}
            </TextField>
            <FormControlLabel control={<Checkbox checked={f.required} onChange={(e) => setF({ ...f, required: e.target.checked })} />} label="Required before submitting" />
            <ErrorAlert error={save.error} />
          </Stack>
        </DialogContent>}
        <DialogActions>
          <Button onClick={() => setF(null)}>Cancel</Button>
          <Button variant="contained" disabled={!f?.name.trim() || (f?.code.length ?? 0) < 2 || save.isPending} onClick={() => save.mutate()}>Save</Button>
        </DialogActions>
      </Dialog>
    </CardContent></Card>
  );
}

/** The applicant's uploads in the application file, with the office's check. */
export function ApplicationDocumentsSection({ applicationId, onChange }: { applicationId: number; onChange?: () => void }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'app-docs', applicationId], queryFn: () => api<AppDocument[]>(`/api/admission/applications/${applicationId}/documents`, staff) });
  const [rejecting, setRejecting] = useState<AppDocument | null>(null);
  const [note, setNote] = useState('');
  const review = useMutation({
    mutationFn: ({ id, accept, note: n }: { id: number; accept: boolean; note?: string }) =>
      api(`/api/admission/documents/${id}/review`, { ...staff, method: 'POST', body: { accept, note: n } }),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['staff', 'app-docs', applicationId] }); setRejecting(null); setNote(''); onChange?.(); },
  });
  const view = async (id: number) => {
    const w = window.open('', '_blank');
    const url = await privateImageUrl(`/api/admission/documents/${id}/file`, 'staff');
    if (w) w.location.href = url; else window.location.href = url;
  };
  if (!q.data?.length) return null;
  return (
    <Box>
      <Typography variant="subtitle2" color="text.secondary">Documents</Typography>
      {q.data.map((d) => (
        <Stack key={d.typeId} direction="row" spacing={1} alignItems="center" sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>
          <Typography variant="body2" sx={{ fontWeight: 700, minWidth: 150 }}>{d.name}{d.required ? '' : ' (optional)'}</Typography>
          {!d.documentId ? <Chip size="small" label="Not uploaded" /> : (
            <>
              <Chip size="small" color={d.status === 'ACCEPTED' ? 'success' : d.status === 'REJECTED' ? 'error' : 'warning'}
                label={d.status === 'ACCEPTED' ? 'Accepted' : d.status === 'REJECTED' ? 'Not accepted' : 'To check'} />
              <Button size="small" onClick={() => view(d.documentId!)}>Open</Button>
              {d.status !== 'ACCEPTED' && <Button size="small" color="success" disabled={review.isPending} onClick={() => review.mutate({ id: d.documentId!, accept: true })}>Accept</Button>}
              {d.status === 'UPLOADED' && <Button size="small" color="error" onClick={() => setRejecting(d)}>Not accepted…</Button>}
            </>
          )}
          {d.reviewNote && <Typography variant="caption" color="error.main" sx={{ width: '100%' }}>{d.reviewNote} — {d.reviewedByName}, {formatDateTime(d.reviewedAt)}</Typography>}
        </Stack>
      ))}
      <ErrorAlert error={review.error} />
      <Dialog open={!!rejecting} onClose={() => setRejecting(null)} fullWidth maxWidth="xs">
        <DialogTitle>Not accepted: {rejecting?.name}</DialogTitle>
        <DialogContent>
          <Typography variant="body2" sx={{ mb: 1.5 }}>The applicant gets an SMS with your reason and can upload it again.</Typography>
          <TextField fullWidth label="Reason" value={note} onChange={(e) => setNote(e.target.value)} placeholder="e.g. The scan is not readable" />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRejecting(null)}>Cancel</Button>
          <Button variant="contained" color="error" disabled={!note.trim() || review.isPending}
            onClick={() => review.mutate({ id: rejecting!.documentId!, accept: false, note })}>Send</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
