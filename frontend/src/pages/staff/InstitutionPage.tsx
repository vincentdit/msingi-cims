import DeleteIcon from '@mui/icons-material/DeleteOutline';
import UploadIcon from '@mui/icons-material/PhotoCamera';
import PdfIcon from '@mui/icons-material/PictureAsPdf';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Container, Divider, FormControlLabel, List, ListItem, ListItemText, Stack, TextField, Typography, useTheme,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { api, download, formatDateTime, privateImageUrl, uploadForm } from '../../api/client';
import type { InstitutionPhoto } from '../../api/types';
import { CampusIllustration } from '../../components/CampusIllustration';
import { ErrorAlert, PageTitle } from '../../components/common';
import { useInstitution } from '../../components/InstitutionContext';
import { usePublicImage } from '../../components/usePublicImage';

const PATH = '/api/admin/institution/photos/campus';
const staff = { realm: 'staff' as const };
const mb = (n: number) => (n < 1024 * 1024 ? `${Math.max(1, Math.round(n / 1024))} KB` : `${(n / 1024 / 1024).toFixed(1)} MB`);

/**
 * The administrator's page for how the institution appears on its portal, its programme pages and its advert posters: the campus
 * photo, the logo, and how the public reaches it (phones, emails, website, WhatsApp, social media).
 */
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
      <PageTitle title="Institution" subtitle="How the institution appears on the admission portal, the programme pages and the advert posters." />
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
      <Grid container spacing={3} sx={{ mt: 0.5 }}>
        <Grid size={{ xs: 12, md: 5 }}><LogoCard /></Grid>
        <Grid size={{ xs: 12, md: 7 }}><ContactsCard /></Grid>
        <Grid size={12}><PrincipalCard /></Grid>
        <Grid size={12}><FamilyPortalCard /></Grid>
      </Grid>
    </Container>
  );
}

const LOGO = '/api/admin/institution/photos/logo';

/** The institution's logo: on the programme pages, the advert posters and the printed documents (PNG with a clear background works best). */
function LogoCard() {
  const qc = useQueryClient();
  const logo = useQuery({ queryKey: ['staff', 'logo'], queryFn: async () => (await api<InstitutionPhoto | undefined>(LOGO, staff)) ?? null });
  const src = usePublicImage(logo.data?.url);
  const file = useRef<HTMLInputElement>(null);
  const changed = (p: InstitutionPhoto | null) => {
    qc.setQueryData(['staff', 'logo'], p);
    qc.invalidateQueries({ queryKey: ['institution'] });
  };
  const send = useMutation({ mutationFn: (f: File) => { const form = new FormData(); form.append('file', f); return uploadForm<InstitutionPhoto>(LOGO, 'staff', form); },
    onSuccess: changed });
  const remove = useMutation({ mutationFn: () => api<void>(LOGO, { ...staff, method: 'DELETE' }), onSuccess: () => changed(null) });
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="h6">Logo</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, mb: 2 }}>
          Shown on the programme pages, the advert posters and at the top of every letter and document the institution prints
          (admission letters, statements of results, certificates, ID cards, receipts). A square PNG with a clear or white background works best:
          PNG or JPEG, at least 200 pixels wide, up to 5 MB.
        </Typography>
        <Stack direction="row" spacing={2} alignItems="center">
          <Box sx={{ width: 96, height: 96, borderRadius: 2, border: 1, borderColor: 'divider', bgcolor: 'background.default', display: 'grid', placeItems: 'center', overflow: 'hidden', flexShrink: 0 }}>
            {logo.data && src ? <Box component="img" src={src} alt="Logo" sx={{ maxWidth: '100%', maxHeight: '100%', objectFit: 'contain' }} />
              : <Typography variant="caption" color="text.secondary">No logo</Typography>}
          </Box>
          <Stack spacing={1}>
            <input ref={file} type="file" accept="image/png,image/jpeg" hidden
              onChange={(e) => { const f = e.target.files?.[0]; if (f) send.mutate(f); e.target.value = ''; }} />
            <Button variant="contained" startIcon={<UploadIcon />} disabled={send.isPending} onClick={() => file.current?.click()}>
              {send.isPending ? 'Uploading…' : logo.data ? 'Replace logo' : 'Upload logo'}
            </Button>
            {logo.data && <Button color="error" startIcon={<DeleteIcon />} disabled={remove.isPending} onClick={() => remove.mutate()}>Remove logo</Button>}
          </Stack>
        </Stack>
        {logo.isSuccess && !logo.data && <Alert severity="info" sx={{ mt: 2 }}>Until a logo is uploaded, posters and documents show the institution's initials.</Alert>}
        <ErrorAlert error={send.error ?? remove.error} />
      </CardContent>
    </Card>
  );
}

interface Profile {
  name: string; contactEmail?: string | null; contactPhone?: string | null; admissionsEmail?: string | null; phones: string[];
  website?: string | null; whatsapp?: string | null; facebook?: string | null; instagram?: string | null; youtube?: string | null;
  tiktok?: string | null; x?: string | null;
}
const PROFILE = '/api/admin/institution/profile';
type Field = Exclude<keyof Profile, 'name' | 'phones'>;

/** How the public reaches the institution: printed on the advert posters and shown on the programme pages. */
function ContactsCard() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'institution-profile'], queryFn: () => api<Profile>(PROFILE, staff) });
  const [f, setF] = useState<Profile | null>(null);
  const [saved, setSaved] = useState(false);
  useEffect(() => { if (q.data) setF({ ...q.data, phones: [...q.data.phones, '', '', ''].slice(0, 3) }); }, [q.data]);
  const save = useMutation({
    mutationFn: () => api<Profile>(PROFILE, { ...staff, method: 'PUT', body: { ...f, phones: f!.phones.filter((p) => p.trim()) } }),
    onSuccess: (p) => { qc.setQueryData(['staff', 'institution-profile'], p); qc.invalidateQueries({ queryKey: ['institution'] }); setSaved(true); },
  });
  if (!f) return <Card sx={{ height: '100%' }}><CardContent><Typography variant="h6">Contacts and social media</Typography><ErrorAlert error={q.error} /></CardContent></Card>;
  const field = (k: Field, label: string, helperText?: string) => (
    <TextField size="small" fullWidth label={label} helperText={helperText} value={f[k] ?? ''}
      onChange={(e) => { setSaved(false); setF({ ...f, [k]: e.target.value }); }} />
  );
  return (
    <Card sx={{ height: '100%' }}>
      <CardContent>
        <Typography variant="h6">Contacts and social media</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, mb: 2 }}>
          Printed on the advert posters and shown on the programme pages. For social media, the page address or just the name
          (e.g. chuoafyankinga) is enough.
        </Typography>
        <Grid container spacing={1.5}>
          <Grid size={{ xs: 12, sm: 6 }}>{field('contactPhone', 'Main phone')}</Grid>
          <Grid size={{ xs: 12, sm: 6 }}>{field('whatsapp', 'WhatsApp number')}</Grid>
          {f.phones.map((p, i) => (
            <Grid key={i} size={{ xs: 12, sm: 4 }}>
              <TextField size="small" fullWidth label={`Other phone ${i + 1}`} value={p}
                onChange={(e) => { setSaved(false); const phones = [...f.phones]; phones[i] = e.target.value; setF({ ...f, phones }); }} />
            </Grid>
          ))}
          <Grid size={{ xs: 12, sm: 6 }}>{field('contactEmail', 'Contact email')}</Grid>
          <Grid size={{ xs: 12, sm: 6 }}>{field('admissionsEmail', 'Admissions email', 'Printed on posters; the contact email if blank')}</Grid>
          <Grid size={12}>{field('website', 'Website')}</Grid>
          <Grid size={{ xs: 12, sm: 6 }}>{field('facebook', 'Facebook')}</Grid>
          <Grid size={{ xs: 12, sm: 6 }}>{field('instagram', 'Instagram')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{field('youtube', 'YouTube')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{field('tiktok', 'TikTok')}</Grid>
          <Grid size={{ xs: 12, sm: 4 }}>{field('x', 'X (Twitter)')}</Grid>
        </Grid>
        <ErrorAlert error={save.error} />
        <Stack direction="row" spacing={2} alignItems="center" sx={{ mt: 2 }}>
          <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>Save contacts</Button>
          {saved && <Typography variant="body2" color="success.main">Saved.</Typography>}
        </Stack>
      </CardContent>
    </Card>
  );
}

interface Principal {
  id: number; name: string; title: string; signature: boolean; stamp: boolean; signatureUrl?: string | null; stampUrl?: string | null;
  changeNote?: string | null; updatedByName: string; updatedAt: string; changes: number;
}
interface PrincipalChange { id: number; name: string; title: string; signature: boolean; stamp: boolean; changeNote?: string | null; byName: string; at: string }
const PRINCIPAL = '/api/admin/institution/principal';

/** A scanned signature or stamp, fetched with the administrator's session (it is never public). */
function MarkImage({ url, alt }: { url?: string | null; alt: string }) {
  const img = useQuery({ queryKey: ['staff', 'principal-image', url], enabled: !!url, staleTime: Infinity, retry: false,
    queryFn: () => privateImageUrl(url!, 'staff') });
  return (
    <Box sx={{ height: 110, borderRadius: 2, border: 1, borderColor: 'divider', bgcolor: '#fff', display: 'grid', placeItems: 'center', overflow: 'hidden', p: 1 }}>
      {url && img.data ? <Box component="img" src={img.data} alt={alt} sx={{ maxWidth: '100%', maxHeight: 94, objectFit: 'contain', display: 'block' }} />
        : <Typography variant="caption" color="text.secondary">{url ? 'Loading…' : `No ${alt.toLowerCase()} yet`}</Typography>}
    </Box>
  );
}

/**
 * The Principal, who signs every letter and document the institution issues: name, title, scanned signature and the stamp.
 * Each change is kept, so documents already issued keep the signature they were issued with.
 */
function PrincipalCard() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'principal'], queryFn: async () => (await api<Principal | undefined>(PRINCIPAL, staff)) ?? null });
  const history = useQuery({ queryKey: ['staff', 'principal-history'], enabled: !!q.data, queryFn: () => api<PrincipalChange[]>(`${PRINCIPAL}/history`, staff) });
  const [name, setName] = useState('');
  const [title, setTitle] = useState('Principal');
  const [keepBackground, setKeepBackground] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const sigFile = useRef<HTMLInputElement>(null);
  const stampFile = useRef<HTMLInputElement>(null);
  useEffect(() => { if (q.data !== undefined) { setName(q.data?.name ?? ''); setTitle(q.data?.title ?? 'Principal'); } }, [q.data]);
  const changed = (p: Principal) => { qc.setQueryData(['staff', 'principal'], p); qc.invalidateQueries({ queryKey: ['staff', 'principal-history'] }); };
  const save = useMutation({ mutationFn: () => api<Principal>(PRINCIPAL, { ...staff, method: 'PUT', body: { name: name.trim(), title: title.trim() || 'Principal' } }),
    onSuccess: changed });
  const send = useMutation({
    mutationFn: ({ mark, file }: { mark: 'signature' | 'stamp'; file: File }) => {
      const form = new FormData(); form.append('file', file);
      return uploadForm<Principal>(`${PRINCIPAL}/${mark}?keepBackground=${keepBackground}`, 'staff', form);
    },
    onSuccess: changed,
  });
  const remove = useMutation({ mutationFn: (mark: 'signature' | 'stamp') => api<Principal>(`${PRINCIPAL}/${mark}`, { ...staff, method: 'DELETE' }), onSuccess: changed });
  const sample = useMutation({ mutationFn: () => download(`${PRINCIPAL}/sample`, 'staff', 'sample-signed-letter.pdf') });
  const p = q.data;
  const dirty = name.trim() !== (p?.name ?? '') || (title.trim() || 'Principal') !== (p?.title ?? 'Principal');
  const newPerson = !!p && name.trim() !== '' && name.trim().replace(/\s+/g, ' ').toLowerCase() !== p.name.replace(/\s+/g, ' ').toLowerCase();
  const picker = (mark: 'signature' | 'stamp', ref: React.RefObject<HTMLInputElement>) => (
    <input ref={ref} type="file" accept="image/png,image/jpeg" hidden
      onChange={(e) => { const f = e.target.files?.[0]; if (f) send.mutate({ mark, file: f }); e.target.value = ''; }} />
  );
  return (
    <Card>
      <CardContent>
        <Typography variant="h6">Principal (signs letters and documents)</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, mb: 2 }}>
          Every letter and document the institution issues is signed by the Principal: admission letters, IPT placement letters, statements
          of results, award certificates and student ID cards. Enter the Principal's name, then upload a scan of the signature and of the
          institution's stamp. They are printed on documents only and never shown on the public portal. Documents already issued keep the
          signature they were issued with, even after a new Principal takes office.
        </Typography>
        {q.isSuccess && !p && <Alert severity="warning" sx={{ mb: 2 }}>No Principal recorded yet. Until then, documents print “Principal” with a blank line to sign by hand.</Alert>}
        <Grid container spacing={3}>
          <Grid size={{ xs: 12, md: 4 }}>
            <Stack spacing={1.5}>
              <TextField size="small" label="Principal's full name" required value={name} onChange={(e) => setName(e.target.value)} inputProps={{ maxLength: 120 }}
                helperText="As it should appear under the signature, e.g. Dr. Neema A. Mushi" />
              <TextField size="small" label="Title" value={title} onChange={(e) => setTitle(e.target.value)} inputProps={{ maxLength: 120 }}
                helperText="Usually Principal; Acting Principal while someone acts" />
              {newPerson && <Alert severity="info">A different person: the old signature will not be used. Upload the new Principal's signature after saving. The stamp stays.</Alert>}
              <Stack direction="row" spacing={1}>
                <Button variant="contained" disabled={!name.trim() || !dirty || save.isPending} onClick={() => save.mutate()}>Save</Button>
                <Button startIcon={<PdfIcon />} disabled={!p || sample.isPending} onClick={() => sample.mutate()}>Sample letter</Button>
              </Stack>
            </Stack>
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <Typography variant="subtitle2" gutterBottom>Signature</Typography>
            <MarkImage url={p?.signatureUrl} alt="Signature" />
            {picker('signature', sigFile)}
            <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
              <Button size="small" variant="outlined" startIcon={<UploadIcon />} disabled={!p || send.isPending} onClick={() => sigFile.current?.click()}>
                {p?.signature ? 'Replace signature' : 'Upload signature'}
              </Button>
              {p?.signature && <Button size="small" color="error" startIcon={<DeleteIcon />} disabled={remove.isPending} onClick={() => remove.mutate('signature')}>Remove</Button>}
            </Stack>
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 4 }}>
            <Typography variant="subtitle2" gutterBottom>Stamp</Typography>
            <MarkImage url={p?.stampUrl} alt="Stamp" />
            {picker('stamp', stampFile)}
            <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
              <Button size="small" variant="outlined" startIcon={<UploadIcon />} disabled={!p || send.isPending} onClick={() => stampFile.current?.click()}>
                {p?.stamp ? 'Replace stamp' : 'Upload stamp'}
              </Button>
              {p?.stamp && <Button size="small" color="error" startIcon={<DeleteIcon />} disabled={remove.isPending} onClick={() => remove.mutate('stamp')}>Remove</Button>}
            </Stack>
          </Grid>
        </Grid>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
          Sign (or stamp) in dark ink on plain white paper and scan or photograph it straight on: PNG or JPEG, up to 3 MB. The white paper is
          made see-through so the signature and stamp sit naturally on the page.
        </Typography>
        <FormControlLabel control={<Checkbox size="small" checked={keepBackground} onChange={(e) => setKeepBackground(e.target.checked)} />}
          label="Keep the background of the next upload as it is (for an image already prepared with a clear background)" />
        <ErrorAlert error={save.error ?? send.error ?? remove.error ?? sample.error} />
        {p && (
          <>
            <Divider sx={{ my: 2 }} />
            <Stack direction="row" spacing={2} alignItems="center">
              <Typography variant="caption" color="text.secondary" sx={{ flex: 1 }}>
                {p.changeNote ? `${p.changeNote}. ` : ''}Last changed by {p.updatedByName} on {formatDateTime(p.updatedAt)}.
              </Typography>
              {p.changes > 1 && <Button size="small" onClick={() => setShowHistory((v) => !v)}>{showHistory ? 'Hide changes' : `Changes (${p.changes})`}</Button>}
            </Stack>
            {showHistory && history.data && (
              <List dense>
                {history.data.map((h) => (
                  <ListItem key={h.id} disableGutters>
                    <ListItemText primary={`${h.name}, ${h.title}${h.changeNote ? ` — ${h.changeNote}` : ''}`}
                      secondary={`${h.byName}, ${formatDateTime(h.at)} · ${h.signature ? 'signature' : 'no signature'}, ${h.stamp ? 'stamp' : 'no stamp'}`} />
                  </ListItem>
                ))}
              </List>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}


/** The family and sponsor portal (FR-STU-05): whether parents, guardians and sponsors may sign in, and whether sponsors see results. */
function FamilyPortalCard() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'family-portal'], queryFn: () => api<{ enabled: boolean; sponsorsSeeResults: boolean }>('/api/family-portal/settings', staff) });
  const save = useMutation({
    mutationFn: (b: { enabled: boolean; sponsorsSeeResults: boolean }) => api<{ enabled: boolean; sponsorsSeeResults: boolean }>('/api/family-portal/settings', { ...staff, method: 'PUT', body: b }),
    onSuccess: (d) => qc.setQueryData(['staff', 'family-portal'], d),
  });
  const s = q.data;
  return (
    <Card>
      <CardContent>
        <Typography variant="h6">Family portal</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          {`Parents, guardians and sponsors sign in at ${window.location.host}/family with a code sent by SMS to the phone the college has for them. They see the student's fees and payments; results and attendance only when the student shares them.`}
        </Typography>
        <ErrorAlert error={q.error ?? save.error} />
        {s && (
          <Stack>
            <FormControlLabel label="Open the family portal" control={<Checkbox checked={s.enabled} disabled={save.isPending} onChange={(e) => save.mutate({ ...s, enabled: e.target.checked })} />} />
            <FormControlLabel label="Sponsors also see the results of the students they sponsor" disabled={!s.enabled}
              control={<Checkbox checked={s.sponsorsSeeResults} disabled={save.isPending} onChange={(e) => save.mutate({ ...s, sponsorsSeeResults: e.target.checked })} />} />
          </Stack>
        )}
      </CardContent>
    </Card>
  );
}
