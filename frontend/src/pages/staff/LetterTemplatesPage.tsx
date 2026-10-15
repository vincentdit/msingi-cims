import { Alert, Box, Button, Card, CardContent, Chip, Container, List, ListItemButton, ListItemText, ListSubheader, Stack, TextField, Typography } from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { api, download, formatDateTime } from '../../api/client';
import { useSession } from '../../auth/session';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';

const staff = { realm: 'staff' as const };
interface Template {
  code: string; letter: string; part: string; description: string; placeholders: { name: string; meaning: string }[];
  body: string; defaultBody: string; customised: boolean; revision: number; updatedByName?: string; updatedAt?: string;
}

/** The wording of the letters CIMS prints (FR-ADM-S05). */
export default function LetterTemplatesPage() {
  const qc = useQueryClient();
  const { user } = useSession();
  const roles = user('staff')?.roles ?? [];
  const canEdit = roles.some((r) => ['INSTITUTION_ADMIN', 'ADMISSIONS_OFFICER', 'IPT_COORDINATOR'].includes(r));
  const q = useQuery({ queryKey: ['letter-templates'], queryFn: () => api<Template[]>('/api/letter-templates', staff) });
  const [code, setCode] = useState<string | null>(null);
  const [text, setText] = useState('');
  const box = useRef<HTMLTextAreaElement>(null);
  const t = q.data?.find((x) => x.code === code) ?? q.data?.[0];
  useEffect(() => { if (t) { setCode(t.code); setText(t.body); } }, [t?.code, t?.body]);
  const done = (x: Template) => { qc.setQueryData<Template[]>(['letter-templates'], (old) => old?.map((o) => (o.code === x.code ? x : o))); setText(x.body); };
  const save = useMutation({ mutationFn: () => api<Template>(`/api/letter-templates/${t!.code}`, { ...staff, method: 'PUT', body: { body: text } }), onSuccess: done });
  const reset = useMutation({ mutationFn: () => api<Template>(`/api/letter-templates/${t!.code}`, { ...staff, method: 'DELETE' }), onSuccess: done });
  const preview = useMutation({ mutationFn: () => download(`/api/letter-templates/${t!.code}/preview`, 'staff', 'sample-letter.pdf', 'POST', { body: text }) });
  if (q.isLoading) return <Loading />;
  if (!t) return <ErrorAlert error={q.error} />;
  const insert = (name: string) => {
    const el = box.current; const tag = `{${name}}`;
    if (!el) { setText(text + tag); return; }
    const a = el.selectionStart ?? text.length, b = el.selectionEnd ?? text.length;
    setText(text.slice(0, a) + tag + text.slice(b));
    requestAnimationFrame(() => { el.focus(); el.selectionStart = el.selectionEnd = a + tag.length; });
  };
  const letters = [...new Set(q.data!.map((x) => x.letter))];
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Letter wording" subtitle="Write the wording of the letters CIMS prints. The layout, details tables, signature and verification code stay as they are." />
      <Grid container spacing={2.5}>
        <Grid size={{ xs: 12, md: 4 }}>
          <Card><List dense>
            {letters.map((l) => (
              <Box key={l}>
                <ListSubheader>{l}</ListSubheader>
                {q.data!.filter((x) => x.letter === l).map((x) => (
                  <ListItemButton key={x.code} selected={x.code === t.code} onClick={() => setCode(x.code)}>
                    <ListItemText primary={x.part} secondary={x.customised ? `Your wording · revision ${x.revision}` : 'Built-in wording'} />
                  </ListItemButton>
                ))}
              </Box>
            ))}
          </List></Card>
        </Grid>
        <Grid size={{ xs: 12, md: 8 }}>
          <Card><CardContent>
            <Typography variant="h6">{t.letter} — {t.part}</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>{t.description}</Typography>
            {t.code.startsWith('ADMISSION') && <Alert severity="info" sx={{ mb: 1.5 }}>Letters already issued keep the wording they were issued with; new wording applies to letters issued from now on.</Alert>}
            <TextField inputRef={box} fullWidth multiline minRows={10} value={text} onChange={(e) => setText(e.target.value)} disabled={!canEdit}
              inputProps={{ style: { fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 14 } }} />
            <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
              A blank line starts a new paragraph · lines starting with "- " make a bulleted list and "1. " a numbered list · **text** is bold · click a value below to insert it.
            </Typography>
            <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap sx={{ mt: 1.5 }}>
              {t.placeholders.map((p) => <Chip key={p.name} size="small" label={`{${p.name}}`} title={p.meaning} onClick={canEdit ? () => insert(p.name) : undefined} />)}
            </Stack>
            <ErrorAlert error={save.error ?? reset.error ?? preview.error} />
            {save.isSuccess && <Alert severity="success" sx={{ mt: 1.5 }}>Saved.</Alert>}
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 2 }} alignItems={{ sm: 'center' }}>
              <Button variant="outlined" onClick={() => preview.mutate()} disabled={!text.trim() || preview.isPending}>Preview sample letter</Button>
              {canEdit && <Button variant="contained" onClick={() => save.mutate()} disabled={!text.trim() || text === t.body || save.isPending}>Save</Button>}
              {canEdit && t.customised && <Button color="inherit" onClick={() => reset.mutate()} disabled={reset.isPending}>Back to built-in wording</Button>}
              <Box sx={{ flex: 1 }} />
              {t.customised && <Typography variant="caption" color="text.secondary">Last changed by {t.updatedByName}, {formatDateTime(t.updatedAt)}</Typography>}
            </Stack>
          </CardContent></Card>
        </Grid>
      </Grid>
    </Container>
  );
}
