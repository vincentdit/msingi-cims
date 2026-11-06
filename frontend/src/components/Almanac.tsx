import EditIcon from '@mui/icons-material/EditOutlined';
import PlaceIcon from '@mui/icons-material/PlaceOutlined';
import PrintIcon from '@mui/icons-material/PrintOutlined';
import SearchIcon from '@mui/icons-material/Search';
import { Alert, Box, Button, Card, Chip, IconButton, InputAdornment, Stack, TextField, Tooltip, Typography } from '@mui/material';
import { useMemo, useState } from 'react';
import type { Almanac, AlmanacEntry } from '../api/types';
import { uiLanguage } from '../locale/ui';

export const ALMANAC_KIND: Record<string, { label: string; colour: string }> = {
  TEACHING: { label: 'Teaching', colour: '#1565C0' },
  REGISTRATION: { label: 'Registration', colour: '#00838F' },
  EXAMINATIONS: { label: 'Examinations', colour: '#C62828' },
  MARKS_ENTRY: { label: 'Marks entry', colour: '#6D4C41' },
  RESULTS: { label: 'Results', colour: '#6A1B9A' },
  GRADUATION: { label: 'Graduation', colour: '#AD8B00' },
  MEETING: { label: 'Meeting', colour: '#283593' },
  ACTIVITY: { label: 'Activity', colour: '#2E7D32' },
  DEADLINE: { label: 'Deadline', colour: '#D84315' },
  HOLIDAY: { label: 'Holiday', colour: '#558B2F' },
  OTHER: { label: 'Other', colour: '#546E7A' },
};

const AUDIENCE: Record<string, string> = { ALL: 'Everyone', STAFF: 'Staff only', STUDENTS: 'Students' };

const locale = () => (uiLanguage() === 'sw' ? 'sw-TZ' : 'en-GB');
const day = (d: string) => new Date(`${d}T00:00:00`).toLocaleDateString(locale(), { weekday: 'short', day: 'numeric', month: 'short' });
const monthOf = (d: string) => d.slice(0, 7);
const monthTitle = (ym: string) => new Date(`${ym}-01T00:00:00`).toLocaleDateString(locale(), { month: 'long', year: 'numeric' });

/** "Mon 3 Nov" or "Mon 3 Nov – Fri 7 Nov". */
export function dateRange(e: { startDate: string; endDate: string }) {
  return e.endDate && e.endDate !== e.startDate ? `${day(e.startDate)} – ${day(e.endDate)}` : day(e.startDate);
}

/**
 * The almanac of a year, month by month: what happens, when, for whom, where and which office is in charge. Filters by kind and
 * words; prints as a clean document (the menus and filters are left out of the print). Staff who keep the calendar can edit the
 * entries that are not semester dates (onEdit).
 */
export function AlmanacView({ almanac, showAudience, onEdit, title }: {
  almanac: Almanac; showAudience?: boolean; onEdit?: (e: AlmanacEntry) => void; title?: string;
}) {
  const [kinds, setKinds] = useState<string[]>([]);
  const [q, setQ] = useState('');
  const present = useMemo(() => Object.keys(ALMANAC_KIND).filter((k) => almanac.entries.some((e) => e.kind === k)), [almanac]);
  const shown = almanac.entries.filter((e) => (!kinds.length || kinds.includes(e.kind))
    && (!q.trim() || `${e.title} ${e.venue ?? ''} ${e.responsible ?? ''} ${e.description ?? ''}`.toLowerCase().includes(q.trim().toLowerCase())));
  const months = useMemo(() => {
    const m = new Map<string, AlmanacEntry[]>();
    for (const e of shown) m.set(monthOf(e.startDate), [...(m.get(monthOf(e.startDate)) ?? []), e]);
    return [...m.entries()];
  }, [shown]);
  const thisMonth = new Date().toISOString().slice(0, 7);
  const today = new Date().toISOString().slice(0, 10);

  return (
    <Box className="cims-almanac">
      <Box sx={{ display: 'none', '@media print': { display: 'block', mb: 2 } }}>
        <Typography variant="h5">{title ?? `Almanac ${almanac.academicYear}`}</Typography>
      </Box>
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1.5} alignItems={{ md: 'center' }} sx={{ mb: 2, '@media print': { display: 'none' } }}>
        <TextField size="small" placeholder="Search the almanac" value={q} onChange={(e) => setQ(e.target.value)} sx={{ minWidth: 240 }}
          slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment> } }} />
        <Stack direction="row" spacing={0.75} flexWrap="wrap" useFlexGap sx={{ flexGrow: 1 }}>
          {present.map((k) => {
            const on = kinds.includes(k);
            return <Chip key={k} size="small" label={ALMANAC_KIND[k].label} variant={on ? 'filled' : 'outlined'}
              onClick={() => setKinds(on ? kinds.filter((x) => x !== k) : [...kinds, k])}
              sx={{ borderColor: ALMANAC_KIND[k].colour, ...(on ? { bgcolor: ALMANAC_KIND[k].colour, color: '#fff' } : { color: ALMANAC_KIND[k].colour }) }} />;
          })}
        </Stack>
        <Button size="small" startIcon={<PrintIcon />} onClick={() => window.print()}>Print</Button>
      </Stack>

      {!almanac.entries.length && <Alert severity="info">Nothing in the almanac for {almanac.academicYear} yet.</Alert>}
      {!!almanac.entries.length && !shown.length && <Alert severity="info">Nothing matches.</Alert>}

      <Stack spacing={2.5}>
        {months.map(([ym, list]) => (
          <Box key={ym} sx={{ breakInside: 'avoid-page' }}>
            <Stack direction="row" alignItems="baseline" spacing={1} sx={{ mb: 1 }}>
              <Typography variant="h6" sx={{ textTransform: 'capitalize' }}>{monthTitle(ym)}</Typography>
              {ym === thisMonth && <Chip size="small" color="secondary" label="This month" sx={{ '@media print': { display: 'none' } }} />}
            </Stack>
            <Card variant="outlined" sx={{ '@media print': { border: 'none' } }}>
              {list.map((e, i) => {
                const k = ALMANAC_KIND[e.kind] ?? ALMANAC_KIND.OTHER;
                const past = (e.endDate || e.startDate) < today;
                return (
                  <Stack key={`${e.eventId ?? 's'}-${e.title}-${i}`} direction="row" spacing={2} alignItems="flex-start"
                    sx={{ px: 2, py: 1.25, borderTop: i ? 1 : 0, borderColor: 'divider', opacity: past ? 0.6 : 1, breakInside: 'avoid',
                      borderLeft: 4, borderLeftColor: k.colour }}>
                    <Typography sx={{ width: { xs: 96, sm: 170 }, flexShrink: 0, fontWeight: 600, fontSize: 14 }}>{dateRange(e)}</Typography>
                    <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                      <Typography sx={{ fontWeight: 600 }}>{e.title}</Typography>
                      {(e.venue || e.responsible) && (
                        <Typography variant="body2" color="text.secondary" sx={{ display: 'flex', alignItems: 'center', gap: 0.5, flexWrap: 'wrap' }}>
                          {e.venue && <><PlaceIcon sx={{ fontSize: 15 }} />{e.venue}</>}
                          {e.venue && e.responsible && <span>·</span>}
                          {e.responsible && <span>{e.responsible}</span>}
                        </Typography>
                      )}
                      {e.description && <Typography variant="body2" color="text.secondary">{e.description}</Typography>}
                    </Box>
                    <Stack direction="row" spacing={0.75} alignItems="center" sx={{ flexShrink: 0 }}>
                      <Chip size="small" label={k.label} variant="outlined" sx={{ borderColor: k.colour, color: k.colour }} />
                      {showAudience && e.audience !== 'ALL' && <Chip size="small" label={AUDIENCE[e.audience]} />}
                      {onEdit && (e.eventId
                        ? <Tooltip title="Edit"><IconButton size="small" onClick={() => onEdit(e)} sx={{ '@media print': { display: 'none' } }}><EditIcon fontSize="small" /></IconButton></Tooltip>
                        : <Tooltip title="From the semester: change it on the Academic calendar"><Box sx={{ width: 34 }} /></Tooltip>)}
                    </Stack>
                  </Stack>
                );
              })}
            </Card>
          </Box>
        ))}
      </Stack>
    </Box>
  );
}
