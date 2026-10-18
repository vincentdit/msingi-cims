import CloseIcon from '@mui/icons-material/Close';
import CopyIcon from '@mui/icons-material/ContentCopy';
import DownloadIcon from '@mui/icons-material/Download';
import OpenIcon from '@mui/icons-material/OpenInNew';
import {
  Alert, Box, Button, CircularProgress, Dialog, DialogContent, DialogTitle, IconButton, Snackbar, Stack, TextField, ToggleButton,
  ToggleButtonGroup, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, download, publicImageUrl } from '../../api/client';
import type { AdmissionWindow, WindowProgramme } from '../../api/types';
import { ErrorAlert } from '../../components/common';
import { uiLanguage } from '../../locale/ui';

const staff = { realm: 'staff' as const };
const MAX_LINES = 6;
const MAX_LINE = 80;

/**
 * Makes the programme's advert: its selling points, then the poster (square for Facebook, Instagram and WhatsApp status, or
 * portrait), in English or Kiswahili, to download as PNG or PDF, and the short link to share, which shows the poster as its preview.
 * Everything on the poster (fee, instalments, requirements, places, dates) comes from what CIMS already holds.
 */
export default function AdvertDialog({ win, wp, canEdit, onClose }: { win: AdmissionWindow; wp: WindowProgramme; canEdit: boolean; onClose: () => void }) {
  const qc = useQueryClient();
  const [lang, setLang] = useState<'en' | 'sw'>(uiLanguage() === 'sw' ? 'sw' : 'en');
  const [format, setFormat] = useState<'square' | 'portrait'>('square');
  const [text, setText] = useState(wp.highlights ?? '');
  const [version, setVersion] = useState(0);
  const [copied, setCopied] = useState(false);
  const code = encodeURIComponent(wp.programmeCode);
  const poster = `/api/public/programmes/${code}/poster.png?format=${format}&lang=${lang}`;
  const img = useQuery({ queryKey: ['advert-poster', poster, version], retry: false, staleTime: Infinity,
    queryFn: () => publicImageUrl(`${poster}&v=${Date.now()}`) });
  useEffect(() => () => { if (img.data?.startsWith('blob:')) URL.revokeObjectURL(img.data); }, [img.data]);

  const lines = text.split('\n');
  const tooLong = lines.length > MAX_LINES || lines.some((l) => l.trim().length > MAX_LINE);
  const save = useMutation({
    mutationFn: () => api<WindowProgramme>(`/api/admission/windows/${win.id}/programmes/${wp.id}/highlights`, { ...staff, method: 'PUT', body: { highlights: text } }),
    onSuccess: (x) => { setText(x.highlights ?? ''); setVersion((v) => v + 1); qc.invalidateQueries({ queryKey: ['staff', 'window'] }); },
  });

  const link = (src: string) => `${window.location.origin}/p/${code}?${lang === 'sw' ? 'lang=sw&' : ''}src=${src}`;
  const message = lang === 'sw'
    ? `${wp.programmeName} — maombi yanapokelewa. Ada, sifa na kutuma maombi:`
    : `${wp.programmeName} — applications are open. Fees, entry requirements and how to apply:`;
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(link('link'));
      setCopied(true);
    } catch {
      /* clipboard unavailable */
    }
  };
  const file = `advert-${wp.programmeCode}-${format}-${lang}`;

  return (
    <Dialog open onClose={onClose} maxWidth="lg" fullWidth>
      <DialogTitle sx={{ pr: 6 }}>
        {`Advert — ${wp.programmeName}`}
        <IconButton aria-label="Close" onClick={onClose} sx={{ position: 'absolute', right: 8, top: 8 }}><CloseIcon /></IconButton>
      </DialogTitle>
      <DialogContent dividers>
        <Grid container spacing={3}>
          <Grid size={{ xs: 12, md: 6 }}>
            <Box sx={{ borderRadius: 2, overflow: 'hidden', border: 1, borderColor: 'divider', bgcolor: 'action.hover', position: 'relative',
              aspectRatio: format === 'square' ? '1 / 1' : '4 / 5', display: 'grid', placeItems: 'center' }}>
              {img.data && <Box component="img" src={img.data} alt={`Advert poster for ${wp.programmeName}`} sx={{ width: '100%', height: '100%', display: 'block', objectFit: 'contain' }} />}
              {img.isFetching && <CircularProgress sx={{ position: 'absolute' }} />}
              {img.isError && <Alert severity="warning" sx={{ m: 2 }}>The poster could not be made. The programme must be in a window that is open or published to open.</Alert>}
            </Box>
          </Grid>
          <Grid size={{ xs: 12, md: 6 }}>
            <Stack spacing={2.5}>
              <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
                <ToggleButtonGroup size="small" exclusive value={lang} onChange={(_, v) => v && setLang(v)} aria-label="Language">
                  <ToggleButton value="en">English</ToggleButton>
                  <ToggleButton value="sw">Kiswahili</ToggleButton>
                </ToggleButtonGroup>
                <ToggleButtonGroup size="small" exclusive value={format} onChange={(_, v) => v && setFormat(v)} aria-label="Format">
                  <ToggleButton value="square">Square (feed, WhatsApp)</ToggleButton>
                  <ToggleButton value="portrait">Portrait (4:5)</ToggleButton>
                </ToggleButtonGroup>
              </Stack>
              <Box>
                <Typography variant="subtitle2">Selling points</Typography>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                  {`One per line, up to ${MAX_LINES} short lines (e.g. “Practical training at the hospital”, “Hostel on campus”). Shown on the poster and the programme page. The fee, requirements, places and dates are filled in by CIMS.`}
                </Typography>
                <TextField fullWidth multiline minRows={3} maxRows={7} value={text} disabled={!canEdit} onChange={(e) => setText(e.target.value)}
                  error={tooLong} helperText={tooLong ? `At most ${MAX_LINES} lines of ${MAX_LINE} characters.` : `${lines.filter((l) => l.trim()).length} of ${MAX_LINES}`} />
                {canEdit && <Button sx={{ mt: 1 }} variant="outlined" disabled={save.isPending || tooLong || text === (wp.highlights ?? '')} onClick={() => save.mutate()}>
                  Save selling points</Button>}
                <ErrorAlert error={save.error} />
              </Box>
              <Box>
                <Typography variant="subtitle2" sx={{ mb: 1 }}>Download</Typography>
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                  <Button variant="contained" startIcon={<DownloadIcon />}
                    onClick={() => download(`${poster}&download=true`, 'staff', `${file}.png`)}>Image (PNG)</Button>
                  <Button variant="outlined" startIcon={<DownloadIcon />}
                    onClick={() => download(`/api/public/programmes/${code}/poster.pdf?format=${format}&lang=${lang}`, 'staff', `${file}.pdf`)}>For printing (PDF)</Button>
                </Stack>
              </Box>
              <Box>
                <Typography variant="subtitle2">Share the link</Typography>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
                  On Facebook and WhatsApp the link shows the poster; it opens the programme page with Apply now.
                </Typography>
                <TextField fullWidth size="small" value={link('link')} slotProps={{ input: { readOnly: true } }} />
                <Stack direction="row" spacing={1} sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>
                  <Button startIcon={<CopyIcon />} onClick={copy}>Copy link</Button>
                  <Button component="a" target="_blank" rel="noopener" href={`https://wa.me/?text=${encodeURIComponent(`${message} ${link('whatsapp')}`)}`}>WhatsApp</Button>
                  <Button component="a" target="_blank" rel="noopener" href={`https://www.facebook.com/sharer/sharer.php?u=${encodeURIComponent(link('facebook'))}`}>Facebook</Button>
                  <Button component="a" target="_blank" rel="noopener" startIcon={<OpenIcon />} href={`/programmes/${code}${lang === 'sw' ? '?lang=sw' : ''}`}>Programme page</Button>
                </Stack>
              </Box>
            </Stack>
          </Grid>
        </Grid>
      </DialogContent>
      <Snackbar open={copied} autoHideDuration={2500} onClose={() => setCopied(false)} message="Link copied" />
    </Dialog>
  );
}
