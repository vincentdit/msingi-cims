import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import NotificationsActiveIcon from '@mui/icons-material/NotificationsActive';
import {
  Alert, Box, Button, Card, CardContent, FormControlLabel, IconButton, MenuItem, Stack, Switch, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, formatDate, formatDateTime, formatTzs } from '../../api/client';
import type { AcademicYear } from '../../api/types';
import { ErrorAlert, Loading } from '../../components/common';

const staff = { realm: 'staff' as const };

interface Rules { remindersEnabled: boolean; reminderDaysBefore: number; penaltyMode: 'NONE' | 'PERCENT' | 'FIXED'; penaltyValue: number; penaltyGraceDays: number }
interface Penalty { id: number; invoiceId: number; invoiceNo: string; payerName: string; instalment: number; overdueAmount: number; amount: number; rule: string; appliedAt: string }
interface RunResult { date: string; reminders: number; penalties: number; penaltyTotal: number }

/**
 * Instalment plan, reminders and late-payment penalties (FR-FIN-07, BR-24): when each instalment falls due, the reminder and penalty
 * rules, and the penalties applied. Reminders and penalties run every morning; the Bursar can also run them now.
 */
export default function InstalmentsTab({ canEdit }: { canEdit: boolean }) {
  const qc = useQueryClient();
  const years = useQuery({ queryKey: ['staff', 'years'], queryFn: () => api<AcademicYear[]>('/api/academic/years', staff) });
  const [yearId, setYearId] = useState<number | null>(null);
  useEffect(() => { if (years.data?.length && yearId == null) setYearId((years.data.find((y) => y.current) ?? years.data[0]).id); }, [years.data, yearId]);
  const dates = useQuery({ queryKey: ['staff', 'instalment-dates', yearId], enabled: yearId != null,
    queryFn: () => api<{ instalment: number; dueDate: string }[]>(`/api/finance/instalment-dates?academicYearId=${yearId}`, staff) });
  const rules = useQuery({ queryKey: ['staff', 'collection-rules'], queryFn: () => api<Rules>('/api/finance/collection-rules', staff) });
  const penalties = useQuery({ queryKey: ['staff', 'penalties'], queryFn: () => api<Penalty[]>('/api/finance/penalties', staff) });
  const [rows, setRows] = useState<{ instalment: number; dueDate: string }[]>([]);
  const [r, setR] = useState<Rules | null>(null);
  const [result, setResult] = useState<RunResult | null>(null);
  useEffect(() => { if (dates.data) setRows(dates.data); }, [dates.data]);
  useEffect(() => { if (rules.data) setR(rules.data); }, [rules.data]);
  const saveDates = useMutation({
    mutationFn: () => api(`/api/finance/instalment-dates?academicYearId=${yearId}`, { ...staff, method: 'PUT', body: { dates: rows.filter((x) => x.dueDate) } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staff', 'instalment-dates'] }),
  });
  const saveRules = useMutation({
    mutationFn: () => api('/api/finance/collection-rules', { ...staff, method: 'PUT', body: r }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['staff', 'collection-rules'] }),
  });
  const run = useMutation({
    mutationFn: () => api<RunResult>('/api/finance/reminders/run', { ...staff, method: 'POST' }),
    onSuccess: (x) => { setResult(x); qc.invalidateQueries({ queryKey: ['staff', 'penalties'] }); },
  });
  if (years.isLoading || rules.isLoading) return <Loading />;
  const dirtyDates = JSON.stringify(rows) !== JSON.stringify(dates.data ?? []);
  return (
    <Grid container spacing={2.5}>
      <Grid size={{ xs: 12, md: 6 }}>
        <Card sx={{ height: '100%' }}>
          <CardContent>
            <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1 }}>
              <Typography variant="h6">Instalment due dates</Typography>
              <TextField select size="small" label="Academic year" value={yearId ?? ''} onChange={(e) => setYearId(Number(e.target.value))} sx={{ width: 160, flexShrink: 0, ml: 2 }}>
                {years.data?.map((y) => <MenuItem key={y.id} value={y.id}>{y.code}</MenuItem>)}
              </TextField>
            </Stack>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>Instalment numbers are those in the fee structures. Fees without a date here are not chased.</Typography>
            <Stack spacing={1}>
              {rows.map((x, i) => (
                <Stack key={i} direction="row" spacing={1} alignItems="center">
                  <Typography sx={{ width: 110 }}>Instalment {x.instalment}</Typography>
                  <TextField size="small" type="date" value={x.dueDate} disabled={!canEdit} onChange={(e) => setRows(rows.map((y, j) => (j === i ? { ...y, dueDate: e.target.value } : y)))}
                    slotProps={{ inputLabel: { shrink: true } }} />
                  {canEdit && <IconButton size="small" onClick={() => setRows(rows.filter((_, j) => j !== i))}><DeleteIcon fontSize="small" /></IconButton>}
                </Stack>
              ))}
              {rows.length === 0 && <Typography variant="body2" color="text.secondary">No due dates for this year.</Typography>}
            </Stack>
            {canEdit && (
              <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
                <Button size="small" startIcon={<AddIcon />} onClick={() => setRows([...rows, { instalment: (rows.length ? rows[rows.length - 1].instalment : 0) + 1, dueDate: '' }])}>Add instalment</Button>
                <Button size="small" variant="contained" disabled={!dirtyDates || saveDates.isPending} onClick={() => saveDates.mutate()}>Save dates</Button>
              </Stack>
            )}
            <ErrorAlert error={saveDates.error} />
          </CardContent>
        </Card>
      </Grid>
      <Grid size={{ xs: 12, md: 6 }}>
        <Card sx={{ height: '100%' }}>
          <CardContent>
            <Typography variant="h6" sx={{ mb: 1 }}>Reminders and penalties</Typography>
            {r && (
              <Stack spacing={1.5}>
                <FormControlLabel control={<Switch checked={r.remindersEnabled} disabled={!canEdit} onChange={(e) => setR({ ...r, remindersEnabled: e.target.checked })} />}
                  label="Remind payers by SMS before the due date, on the day, and once when overdue" />
                <TextField size="small" type="number" label="Days before the due date" value={r.reminderDaysBefore} disabled={!canEdit || !r.remindersEnabled}
                  onChange={(e) => setR({ ...r, reminderDaysBefore: Number(e.target.value) })} sx={{ width: 220 }} />
                <Stack direction="row" spacing={1}>
                  <TextField select size="small" label="Late payment penalty" value={r.penaltyMode} disabled={!canEdit} onChange={(e) => setR({ ...r, penaltyMode: e.target.value as Rules['penaltyMode'] })} sx={{ minWidth: 200 }}>
                    <MenuItem value="NONE">No penalty</MenuItem><MenuItem value="PERCENT">Percentage of what is overdue</MenuItem><MenuItem value="FIXED">Fixed amount</MenuItem>
                  </TextField>
                  {r.penaltyMode !== 'NONE' && <TextField size="small" type="number" label={r.penaltyMode === 'PERCENT' ? 'Percent' : 'Amount (TZS)'} value={r.penaltyValue}
                    disabled={!canEdit} onChange={(e) => setR({ ...r, penaltyValue: Number(e.target.value) })} sx={{ width: 140 }} />}
                  {r.penaltyMode !== 'NONE' && <TextField size="small" type="number" label="Grace days" value={r.penaltyGraceDays}
                    disabled={!canEdit} onChange={(e) => setR({ ...r, penaltyGraceDays: Number(e.target.value) })} sx={{ width: 120 }} />}
                </Stack>
                {r.penaltyMode !== 'NONE' && <Typography variant="caption" color="text.secondary">Added once per instalment, after the grace period, as an invoice line paid last. A penalty can be waived through Waivers.</Typography>}
                {canEdit && (
                  <Stack direction="row" spacing={1}>
                    <Button size="small" variant="contained" disabled={saveRules.isPending || JSON.stringify(r) === JSON.stringify(rules.data)} onClick={() => saveRules.mutate()}>Save rules</Button>
                    <Button size="small" variant="outlined" startIcon={<NotificationsActiveIcon />} disabled={run.isPending} onClick={() => run.mutate()}>Run now</Button>
                  </Stack>
                )}
                <ErrorAlert error={saveRules.error ?? run.error} />
                {result && <Alert severity="success" onClose={() => setResult(null)}>{formatDate(result.date)}: {result.reminders} reminder(s) sent, {result.penalties} penalty(ies) of {formatTzs(result.penaltyTotal)} added. It also runs every morning at 07:30.</Alert>}
              </Stack>
            )}
          </CardContent>
        </Card>
      </Grid>
      <Grid size={12}>
        <Card>
          <CardContent>
            <Typography variant="h6" sx={{ mb: 1 }}>Penalties applied</Typography>
            <ErrorAlert error={penalties.error} />
            <TableContainer><Table size="small">
              <TableHead><TableRow><TableCell>When</TableCell><TableCell>Invoice</TableCell><TableCell>Payer</TableCell><TableCell align="center">Instalment</TableCell><TableCell>Rule</TableCell><TableCell align="right">Penalty</TableCell></TableRow></TableHead>
              <TableBody>
                {penalties.data?.map((p) => (
                  <TableRow key={p.id}><TableCell>{formatDateTime(p.appliedAt)}</TableCell><TableCell sx={{ fontFamily: 'monospace' }}>{p.invoiceNo}</TableCell><TableCell>{p.payerName}</TableCell>
                    <TableCell align="center">{p.instalment}</TableCell><TableCell>{p.rule}</TableCell><TableCell align="right">{formatTzs(p.amount)}</TableCell></TableRow>
                ))}
                {penalties.data?.length === 0 && <TableRow><TableCell colSpan={6}><Box sx={{ color: 'text.secondary', py: 1 }}>No penalties.</Box></TableCell></TableRow>}
              </TableBody>
            </Table></TableContainer>
          </CardContent>
        </Card>
      </Grid>
    </Grid>
  );
}
