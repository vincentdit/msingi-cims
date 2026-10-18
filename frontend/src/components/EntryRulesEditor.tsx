import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import { Autocomplete, Box, Button, Chip, IconButton, MenuItem, Paper, Stack, TextField, Tooltip, Typography } from '@mui/material';
import type { Condition, ConditionType, EntryRules } from '../api/types';

/** NECTA subject names as they appear in results and in entry requirements. */
export const CSEE_SUBJECTS = ['BASIC MATHEMATICS', 'ENGLISH LANGUAGE', 'KISWAHILI', 'BIOLOGY', 'CHEMISTRY', 'PHYSICS', 'CIVICS', 'HISTORY', 'GEOGRAPHY',
  'BOOK KEEPING', 'COMMERCE', 'COMPUTER STUDIES', 'AGRICULTURE', 'LITERATURE IN ENGLISH', 'BIBLE KNOWLEDGE', 'ISLAMIC KNOWLEDGE'];
export const ACSEE_SUBJECTS = ['ADVANCED MATHEMATICS', 'PHYSICS', 'CHEMISTRY', 'BIOLOGY', 'GEOGRAPHY', 'HISTORY', 'ECONOMICS', 'COMMERCE', 'ACCOUNTANCY',
  'ENGLISH LANGUAGE', 'KISWAHILI', 'GENERAL STUDIES', 'BASIC APPLIED MATHEMATICS', 'COMPUTER SCIENCE'];

const TYPES: { value: ConditionType; label: string }[] = [
  { value: 'CSEE_MIN_PASSES', label: 'CSEE passes' },
  { value: 'CSEE_SUBJECT', label: 'CSEE subjects' },
  { value: 'ACSEE_MIN_PRINCIPAL_PASSES', label: 'ACSEE principal passes' },
  { value: 'ACSEE_SUBJECT', label: 'ACSEE subjects' },
  { value: 'PRIOR_AWARD', label: 'NTA / NVA award held' },
];
const GRADES: Record<string, string[]> = { CSEE: ['A', 'B', 'C', 'D'], ACSEE: ['A', 'B', 'C', 'D', 'E', 'S'] };
const exam = (t: ConditionType) => (t.startsWith('ACSEE') ? 'ACSEE' : 'CSEE');

export function blankCondition(type: ConditionType): Condition {
  switch (type) {
    case 'CSEE_MIN_PASSES': return { type, count: 4, minGrade: 'D' };
    case 'CSEE_SUBJECT': return { type, count: 1, minGrade: 'D', subjects: [] };
    case 'ACSEE_MIN_PRINCIPAL_PASSES': return { type, count: 1, minGrade: 'E' };
    case 'ACSEE_SUBJECT': return { type, count: 1, minGrade: 'E', subjects: [] };
    case 'PRIOR_AWARD': return { type, family: 'NVA', minLevel: 3 };
  }
}

/** The requirement in words, as applicants read it. */
export function describeCondition(c: Condition): string {
  const n = c.count ?? 1;
  const subjects = (c.subjects ?? []).map((s) => s.toLowerCase().replace(/\b\w/g, (m) => m.toUpperCase()));
  const list = subjects.length <= 1 ? subjects.join('') : `${subjects.slice(0, -1).join(', ')} or ${subjects[subjects.length - 1]}`;
  switch (c.type) {
    case 'CSEE_MIN_PASSES': return `${n} passes in CSEE${c.minGrade && c.minGrade !== 'D' ? ` at grade ${c.minGrade} or better` : ''}`;
    case 'CSEE_SUBJECT': return subjects.length === n && n > 1 ? `passes in ${subjects.join(', ')}${c.minGrade && c.minGrade !== 'D' ? ` (grade ${c.minGrade} or better)` : ''}`
      : `${n > 1 ? `${n} passes from ` : 'a pass in '}${list}${c.minGrade && c.minGrade !== 'D' ? ` (grade ${c.minGrade} or better)` : ''}`;
    case 'ACSEE_MIN_PRINCIPAL_PASSES': return `${n === 1 ? 'one principal pass' : `${n} principal passes`} in ACSEE`;
    case 'ACSEE_SUBJECT': return `${n > 1 ? `${n} principal passes from ` : 'a principal pass in '}${list} (ACSEE)`;
    case 'PRIOR_AWARD': return `${c.family} Level ${c.minLevel} or above`;
  }
}

/** A summary written from the rules, e.g. "Four passes in CSEE including a pass in Basic mathematics, or NVA Level 3 or above". */
export function summarise(rules: EntryRules): string {
  const words = ['', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven'];
  const routes = rules.pathways.filter((p) => p.conditions.length).map((p) => {
    const [first, ...rest] = p.conditions.map(describeCondition);
    return rest.length ? `${first} including ${rest.join(' and ')}` : first;
  }).map((s) => s.replace(/^(\d) /, (_, d) => `${words[Number(d)] ?? d} `));
  const text = routes.join(', or ');
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : 'Open entry';
}

/** Problems that stop the rules being saved (the server checks the same). */
export function rulesProblems(rules: EntryRules): string[] {
  const out: string[] = [];
  if (!rules.summary.trim()) out.push('Write the summary shown to applicants.');
  rules.pathways.forEach((p, i) => {
    if (!p.conditions.length) out.push(`Route ${i + 1} has no conditions: add one or remove the route.`);
    p.conditions.forEach((c) => {
      if ((c.type === 'CSEE_SUBJECT' || c.type === 'ACSEE_SUBJECT') && !(c.subjects ?? []).length) out.push(`Route ${i + 1}: choose at least one subject.`);
      if (c.type !== 'PRIOR_AWARD' && !(Number(c.count) >= 1)) out.push(`Route ${i + 1}: the number must be 1 or more.`);
      if (c.type === 'PRIOR_AWARD' && !(Number(c.minLevel) >= 1)) out.push(`Route ${i + 1}: give the award level.`);
    });
  });
  return [...new Set(out)];
}

/**
 * NACTVET minimum entry requirements for one programme: one or more routes (an applicant qualifies through ANY route), each
 * with conditions that must ALL be met, and the summary applicants read.
 */
export default function EntryRulesEditor({ value, onChange }: { value: EntryRules; onChange: (r: EntryRules) => void }) {
  const setRoute = (i: number, patch: Partial<EntryRules['pathways'][number]>) =>
    onChange({ ...value, pathways: value.pathways.map((p, j) => (j === i ? { ...p, ...patch } : p)) });
  const setCondition = (i: number, k: number, c: Condition) =>
    setRoute(i, { conditions: value.pathways[i].conditions.map((x, j) => (j === k ? c : x)) });
  return (
    <Box>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        An applicant qualifies through any one route; within a route every condition must be met. With no route, the programme is open entry.
      </Typography>
      <Stack spacing={1.5}>
        {value.pathways.map((p, i) => (
          <Paper key={i} variant="outlined" sx={{ p: 1.5 }}>
            <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 1 }}>
              {i > 0 && <Chip size="small" label="or" />}
              <TextField size="small" label={`Route ${i + 1}`} value={p.label} onChange={(e) => setRoute(i, { label: e.target.value })} sx={{ flexGrow: 1 }}
                placeholder="e.g. Form Four (CSEE)" />
              <Tooltip title="Remove this route">
                <IconButton size="small" aria-label={`Remove route ${i + 1}`} onClick={() => onChange({ ...value, pathways: value.pathways.filter((_, j) => j !== i) })}>
                  <DeleteIcon fontSize="small" />
                </IconButton>
              </Tooltip>
            </Stack>
            <Stack spacing={1}>
              {p.conditions.map((c, k) => (
                <ConditionRow key={k} condition={c} first={k === 0} onChange={(x) => setCondition(i, k, x)}
                  onRemove={() => setRoute(i, { conditions: p.conditions.filter((_, j) => j !== k) })} />
              ))}
            </Stack>
            <Button size="small" startIcon={<AddIcon />} sx={{ mt: 0.5 }}
              onClick={() => setRoute(i, { conditions: [...p.conditions, blankCondition(p.conditions.length ? 'CSEE_SUBJECT' : 'CSEE_MIN_PASSES')] })}>
              Add condition
            </Button>
          </Paper>
        ))}
      </Stack>
      <Button size="small" variant="outlined" startIcon={<AddIcon />} sx={{ mt: 1 }}
        onClick={() => onChange({ ...value, pathways: [...value.pathways, { label: value.pathways.length ? 'Award holders' : 'Form Four (CSEE)',
          conditions: [blankCondition(value.pathways.length ? 'PRIOR_AWARD' : 'CSEE_MIN_PASSES')] }] })}>
        {value.pathways.length ? 'Add another route' : 'Add a route'}
      </Button>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} alignItems={{ sm: 'flex-start' }} sx={{ mt: 2 }}>
        <TextField label="Summary shown to applicants" value={value.summary} onChange={(e) => onChange({ ...value, summary: e.target.value })}
          multiline minRows={2} fullWidth required />
        <Button size="small" sx={{ whiteSpace: 'nowrap', mt: { sm: 1 } }} onClick={() => onChange({ ...value, summary: summarise(value) })}>Write from the rules</Button>
      </Stack>
    </Box>
  );
}

function ConditionRow({ condition: c, first, onChange, onRemove }: { condition: Condition; first: boolean; onChange: (c: Condition) => void; onRemove: () => void }) {
  const set = (patch: Partial<Condition>) => onChange({ ...c, ...patch });
  const subjectsType = c.type === 'CSEE_SUBJECT' || c.type === 'ACSEE_SUBJECT';
  return (
    <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} alignItems={{ md: 'center' }}>
      <Typography variant="caption" color="text.secondary" sx={{ width: 32, flexShrink: 0 }}>{first ? '' : 'and'}</Typography>
      <TextField select size="small" label="Condition" value={c.type} onChange={(e) => onChange(blankCondition(e.target.value as ConditionType))} sx={{ width: { md: 250 }, flexShrink: 0 }}>
        {TYPES.map((t) => <MenuItem key={t.value} value={t.value}>{t.label}</MenuItem>)}
      </TextField>
      {c.type === 'PRIOR_AWARD' ? (
        <>
          <TextField select size="small" label="Award" value={c.family ?? 'NVA'} onChange={(e) => set({ family: e.target.value })} sx={{ width: { md: 110 }, flexShrink: 0 }}>
            <MenuItem value="NVA">NVA</MenuItem><MenuItem value="NTA">NTA</MenuItem>
          </TextField>
          <TextField size="small" type="number" label="Min. level" value={c.minLevel ?? ''} onChange={(e) => set({ minLevel: e.target.value === '' ? null : Number(e.target.value) })}
            sx={{ width: { md: 110 }, flexShrink: 0 }} inputProps={{ min: 1, max: 8 }} />
        </>
      ) : (
        <>
          <TextField size="small" type="number" label={subjectsType ? 'Number' : 'Passes'} value={c.count ?? ''}
            onChange={(e) => set({ count: e.target.value === '' ? null : Number(e.target.value) })} sx={{ width: { md: 100 }, flexShrink: 0 }} inputProps={{ min: 1, max: 10 }} />
          <TextField select size="small" label="Min. grade" value={c.minGrade ?? (exam(c.type) === 'CSEE' ? 'D' : 'E')} onChange={(e) => set({ minGrade: e.target.value })} sx={{ width: { md: 110 }, flexShrink: 0 }}>
            {GRADES[exam(c.type)].map((g) => <MenuItem key={g} value={g}>{g}</MenuItem>)}
          </TextField>
          {subjectsType && (
            <Autocomplete multiple freeSolo size="small" options={exam(c.type) === 'CSEE' ? CSEE_SUBJECTS : ACSEE_SUBJECTS} value={c.subjects ?? []}
              onChange={(_, v) => set({ subjects: v.map((s) => s.trim().toUpperCase()).filter(Boolean) })} sx={{ flexGrow: 1, minWidth: 220 }}
              renderInput={(params) => <TextField {...params} label="Any of these subjects" placeholder="Type or choose" />} />
          )}
        </>
      )}
      <Tooltip title="Remove this condition">
        <IconButton size="small" aria-label="Remove condition" onClick={onRemove}><DeleteIcon fontSize="small" /></IconButton>
      </Tooltip>
    </Stack>
  );
}
