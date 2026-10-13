import DeleteIcon from '@mui/icons-material/DeleteOutline';
import UploadIcon from '@mui/icons-material/PhotoCamera';
import { Alert, Box, Button, Card, CardContent, Container, Stack, TextField, Typography, useTheme } from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { api, formatDateTime, uploadForm } from '../../api/client';
import type { InstitutionPhoto } from '../../api/types';
import { CampusIllustration } from '../../components/CampusIllustration';
import { ErrorAlert, PageTitle } from '../../components/common';
import { useInstitution } from '../../components/InstitutionContext';
import { usePublicImage } from '../../components/usePublicImage';

const PATH = '/api/admin/institution/photos/campus';
const staff = { realm: 'staff' as const };
const mb = (n: number) => (n < 1024 * 1024 ? `${Math.max(1, Math.round(n / 1024))} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`);

/** The administrator's page for how the institution appears on its portal: the campus photo on the admission home page. */
export default function InstitutionPage() {
  const inst = useInstitution();
  const theme = useTheme();
  const qc = useQueryClient();
  const photo = useQuery({ queryKey: ['staff', 'campus-photo'], queryFn: async () => (await api<InstitutionPhoto | undefined>(PATH, staff)) ?? null });
  const src = usePublicImage(photo.data?.url);
  const [caption, setCaption] = useState('');
  const file = useRef<HTMLInputElement>(null);
  useEffect(() => { setCaption(photo.data?.caption ?? ''); }, [photo.data]);

  const changed = (p: InstitutionPhoto | null) => {
    qc.setQueryData(['staff', 'campus-photo'], p);
    qc.invalidateQueries({ queryKey: ['institution'] });
  };
  const send = useMutation({
    mutationFn: (f: File) => { const form = new FormData(); form.append('file', f); if (caption.trim()) form.append('caption', caption.trim());
      return uploadForm<InstitutionPhoto>(PATH, 'staff', form); },
    onSuccess: changed,
  });
  const saveCaption = useMutation({ mutationFn: () => api<InstitutionPhoto>(PATH, { ...staff, method: 'PATCH', body: { caption } }), onSuccess: changed });
  const remove = useMutation({ mutationFn: () => api<void>(PATH, { ...staff, method: 'DELETE' }), onSuccess: () => changed(null) });
  const error = send.error ?? saveCaption.error ?? remove.error;

  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Institution" subtitle="How the institution appears to applicants on the admission portal." />
      <Card>
        <CardContent>
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, md: 6 }}>
              <Box sx={{ aspectRatio: '4 / 3', borderRadius: 2, overflow: 'hidden', bgcolor: 'action.hover', border: 1, borderColor: 'divider' }}>
                {photo.data && src
                  ? <Box component="img" src={src} alt={photo.data.caption ?? inst.name} sx={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
                  : <CampusIllustration primary={theme.palette.primary.main} accent={theme.palette.warning.main} name={inst.name} />}
              </Box>
            </Grid>
            <Grid size={{ xs: 12, md: 6 }}>
              <Typography variant="h6">Campus photo</Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, mb: 2 }}>
                Shown beside the blue banner on the admission home page and, softened, behind it. A wide, bright photo of the main
                building or campus works best: JPEG, PNG or WebP, at least 1600 pixels wide, up to 5 MB.
              </Typography>
              {photo.isSuccess && !photo.data && (
                <Alert severity="info" sx={{ mb: 2 }}>No photo yet. Applicants see the drawing on the left until you upload one.</Alert>
              )}
              <Stack spacing={2}>
                <TextField label="Caption (optional)" value={caption} onChange={(e) => setCaption(e.target.value)} inputProps={{ maxLength: 150 }}
                  helperText="Shown on the photo, e.g. “Main campus, Dodoma”. Leave blank to show the institution's name." />
                <input ref={file} type="file" accept="image/jpeg,image/png,image/webp" hidden
                  onChange={(e) => { const f = e.target.files?.[0]; if (f) send.mutate(f); e.target.value = ''; }} />
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                  <Button variant="contained" startIcon={<UploadIcon />} disabled={send.isPending} onClick={() => file.current?.click()}>
                    {send.isPending ? 'Uploading…' : photo.data ? 'Replace photo' : 'Upload photo'}
                  </Button>
                  {photo.data && (
                    <>
                      <Button variant="outlined" disabled={saveCaption.isPending || caption === (photo.data.caption ?? '')} onClick={() => saveCaption.mutate()}>Save caption</Button>
                      <Button color="error" startIcon={<DeleteIcon />} disabled={remove.isPending} onClick={() => remove.mutate()}>Remove photo</Button>
                    </>
                  )}
                </Stack>
                <ErrorAlert error={error} />
                {photo.data && (
                  <Typography variant="caption" color="text.secondary">
                    {photo.data.width ? `${photo.data.width} × ${photo.data.height} pixels, ` : ''}{mb(photo.data.sizeBytes)}. Uploaded by {photo.data.uploadedByName}
                    {photo.data.updatedAt ? ` on ${formatDateTime(photo.data.updatedAt)}` : ''}.
                  </Typography>
                )}
              </Stack>
            </Grid>
          </Grid>
        </CardContent>
      </Card>
    </Container>
  );
}
