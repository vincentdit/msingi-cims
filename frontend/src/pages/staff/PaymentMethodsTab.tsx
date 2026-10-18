import AccountBalanceIcon from '@mui/icons-material/AccountBalance';
import AddIcon from '@mui/icons-material/Add';
import DeleteIcon from '@mui/icons-material/DeleteOutline';
import EditIcon from '@mui/icons-material/EditOutlined';
import PhoneAndroidIcon from '@mui/icons-material/PhoneAndroid';
import {
  Alert, Box, Button, Card, CardContent, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, IconButton, ListSubheader,
  MenuItem, Stack, Switch, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Tooltip, Typography,
} from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../api/client';
import type { PaymentChannel } from '../../api/types';
import { ErrorAlert, Loading } from '../../components/common';

const staff = { realm: 'staff' as const };

interface Provider { code: string; label: string; operator?: string; kind: 'MOBILE_MONEY' | 'BANK'; ussd?: string }
interface PaymentSetup { mode: 'GEPG' | 'COLLECTION'; ownership: string; channels: PaymentChannel[]; providers: Provider[] }
type Form = { id?: number; provider: string; accountName: string; accountNumber: string; branch: string; instructions: string; active: boolean };

/**
 * Where fees are paid. Public institutions use GePG control numbers. Private and faith-based institutions are paid into their own
 * mobile money business numbers and bank accounts, quoting the CIMS payment reference; the Bursar keeps the list here.
 */
export default function PaymentMethodsTab({ canManage }: { canManage: boolean }) {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['staff', 'payment-channels'], queryFn: () => api<PaymentSetup>('/api/finance/payment-channels', staff) });
  const [form, setForm] = useState<Form | null>(null);
  const refresh = () => { qc.invalidateQueries({ queryKey: ['staff', 'payment-channels'] }); qc.invalidateQueries({ queryKey: ['institution'] }); };
  const save = useMutation({
    mutationFn: (f: Form) => {
      const body = { provider: f.provider, accountName: f.accountName, accountNumber: f.accountNumber, branch: f.branch || null, instructions: f.instructions || null, active: f.active };
      return f.id ? api(`/api/finance/payment-channels/${f.id}`, { method: 'PUT', ...staff, body }) : api('/api/finance/payment-channels', { method: 'POST', ...staff, body });
    },
    onSuccess: () => { setForm(null); refresh(); },
  });
  const remove = useMutation({ mutationFn: (id: number) => api(`/api/finance/payment-channels/${id}`, { method: 'DELETE', ...staff }), onSuccess: refresh });

  if (q.isLoading) return <Loading />;
  if (!q.data) return <ErrorAlert error={q.error} />;
  const d = q.data;
  if (d.mode === 'GEPG') {
    return (
      <Alert severity="info">
        This is a public institution, so fees are paid with <strong>GePG control numbers</strong> through any bank or mobile money service. No accounts are
        listed here. Private and faith-based institutions list their own mobile money and bank accounts instead.
      </Alert>
    );
  }
  const provider = (code: string) => d.providers.find((p) => p.code === code);
  const mobile = d.providers.filter((p) => p.kind === 'MOBILE_MONEY');
  const banks = d.providers.filter((p) => p.kind === 'BANK');
  const chosen = form ? provider(form.provider) : undefined;
  const active = d.channels.filter((c) => c.active);

  return (
    <>
      <Alert severity="success" icon={false} sx={{ mb: 2 }}>
        <strong>{d.ownership === 'FBO' ? 'Faith-based institution: fees are paid into your own accounts.' : 'Private institution: fees are paid into your own accounts.'}</strong> Every invoice gets a 12-digit CIMS <strong>payment reference</strong>.
        Payers quote it when they pay into one of the accounts below; the portals and the SMS show them how. Mobile money payments are confirmed by the
        collector as they happen; bank payments when the Accountant imports the day’s statement under <em>Reconciliation</em>, or records a remittance.
      </Alert>
      {active.length === 0 && <Alert severity="warning" sx={{ mb: 2 }}>No active payment account yet. Applicants and students are told to ask the finance office where to pay.</Alert>}
      <Card>
        <CardContent>
          <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 1.5 }}>
            <Box>
              <Typography variant="h6">Payment accounts</Typography>
              <Typography variant="body2" color="text.secondary">Shown to payers in this order. Inactive accounts are hidden from them.</Typography>
            </Box>
            {canManage && <Button variant="contained" startIcon={<AddIcon />}
              onClick={() => setForm({ provider: 'MPESA', accountName: '', accountNumber: '', branch: '', instructions: '', active: true })}>Add account</Button>}
          </Stack>
          <TableContainer>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Service</TableCell><TableCell>Business / account number</TableCell><TableCell>Account name</TableCell>
                  <TableCell>Branch</TableCell><TableCell>Status</TableCell>{canManage && <TableCell align="right" />}
                </TableRow>
              </TableHead>
              <TableBody>
                {d.channels.length === 0 && <TableRow><TableCell colSpan={6}><Typography color="text.secondary">None yet.</Typography></TableCell></TableRow>}
                {d.channels.map((c) => (
                  <TableRow key={c.id} hover>
                    <TableCell>
                      <Stack direction="row" spacing={1} alignItems="center">
                        {c.kind === 'BANK' ? <AccountBalanceIcon fontSize="small" color="action" /> : <PhoneAndroidIcon fontSize="small" color="action" />}
                        <Box><Typography sx={{ fontWeight: 600 }}>{c.label}</Typography>
                          <Typography variant="caption" color="text.secondary">{c.kind === 'BANK' ? 'Bank' : `Mobile money${c.ussd ? ` · ${c.ussd}` : ''}`}</Typography></Box>
                      </Stack>
                    </TableCell>
                    <TableCell sx={{ fontFamily: 'monospace' }}>{c.accountNumber}</TableCell>
                    <TableCell>{c.accountName}</TableCell>
                    <TableCell>{c.branch ?? '—'}</TableCell>
                    <TableCell><Chip size="small" color={c.active ? 'success' : 'default'} label={c.active ? 'Active' : 'Inactive'} /></TableCell>
                    {canManage && (
                      <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>
                        <Tooltip title="Edit"><IconButton size="small" onClick={() => setForm({ id: c.id, provider: c.provider, accountName: c.accountName, accountNumber: c.accountNumber,
                          branch: c.branch ?? '', instructions: c.instructions ?? '', active: c.active })}><EditIcon fontSize="small" /></IconButton></Tooltip>
                        <Tooltip title="Remove"><IconButton size="small" disabled={remove.isPending} onClick={() => remove.mutate(c.id)}><DeleteIcon fontSize="small" /></IconButton></Tooltip>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
          <ErrorAlert error={remove.error} />
        </CardContent>
      </Card>

      <Dialog open={!!form} onClose={() => setForm(null)} maxWidth="sm" fullWidth>
        <DialogTitle>{form?.id ? 'Edit payment account' : 'Add payment account'}</DialogTitle>
        {form && (
          <DialogContent>
            <Grid container spacing={2} sx={{ mt: 0.5 }}>
              <Grid size={12}>
                <TextField select label="Service" fullWidth value={form.provider} onChange={(e) => setForm({ ...form, provider: e.target.value })}>
                  <ListSubheader>Mobile money</ListSubheader>
                  {mobile.map((p) => <MenuItem key={p.code} value={p.code}>{p.label}{p.operator ? ` — ${p.operator}` : ''}</MenuItem>)}
                  <ListSubheader>Banks</ListSubheader>
                  {banks.map((p) => <MenuItem key={p.code} value={p.code}>{p.label}{p.operator && p.operator !== p.label ? ` — ${p.operator}` : ''}</MenuItem>)}
                </TextField>
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <TextField label={chosen?.kind === 'BANK' ? 'Account number' : 'Business number (namba ya kampuni)'} required fullWidth value={form.accountNumber}
                  onChange={(e) => setForm({ ...form, accountNumber: e.target.value })} inputProps={{ maxLength: 40, style: { fontFamily: 'monospace' } }}
                  helperText={chosen?.kind === 'BANK' ? '6–30 letters and digits' : 'A pay-bill number that takes a reference, not a till (Lipa namba) that does not'} />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <TextField label="Account name" required fullWidth value={form.accountName} onChange={(e) => setForm({ ...form, accountName: e.target.value })}
                  inputProps={{ maxLength: 150 }} helperText="The name payers see before confirming" />
              </Grid>
              {chosen?.kind === 'BANK' && (
                <Grid size={12}><TextField label="Branch" fullWidth value={form.branch} onChange={(e) => setForm({ ...form, branch: e.target.value })} inputProps={{ maxLength: 100 }} /></Grid>
              )}
              <Grid size={12}>
                <TextField label="Extra instructions (optional)" fullWidth multiline minRows={2} value={form.instructions}
                  onChange={(e) => setForm({ ...form, instructions: e.target.value })} inputProps={{ maxLength: 500 }}
                  helperText="Shown after the standard steps, e.g. a SWIFT code for payers abroad" />
              </Grid>
              <Grid size={12}>
                <FormControlLabel control={<Switch checked={form.active} onChange={(e) => setForm({ ...form, active: e.target.checked })} />} label="Active (shown to payers)" />
              </Grid>
            </Grid>
            <ErrorAlert error={save.error} />
          </DialogContent>
        )}
        <DialogActions>
          <Button onClick={() => setForm(null)}>Cancel</Button>
          <Button variant="contained" disabled={!form?.accountName.trim() || !form?.accountNumber.trim() || save.isPending} onClick={() => form && save.mutate(form)}>Save</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
