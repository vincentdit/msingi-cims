import AccountBalanceIcon from '@mui/icons-material/AccountBalance';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import PhoneAndroidIcon from '@mui/icons-material/PhoneAndroid';
import PhonelinkRingIcon from '@mui/icons-material/PhonelinkRing';
import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Button, CircularProgress, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, formatTzs } from '../api/client';
import { ErrorAlert } from './common';
import type { PaymentChannel } from '../api/types';
import { useInstitution } from './InstitutionContext';

/** True for a public institution (GePG control numbers); false for a private or faith-based one (its own accounts, CIMS payment references). */
export const useGepg = () => (useInstitution().payment?.mode ?? 'GEPG') === 'GEPG';

/** The words for the number an invoice is paid with: a GePG control number, or the college's own payment reference. */
export function usePayWords() {
  const { t } = useTranslation();
  const gepg = (useInstitution().payment?.mode ?? 'GEPG') === 'GEPG';
  const words = (g: boolean) => ({ gepg: g, number: g ? t('pay.controlNo') : t('pay.reference'), getNumber: g ? t('pay.getControlNo') : t('pay.getReference') });
  return {
    ...words(gepg),
    /** The words for one invoice: government payments such as NHIF always go through GePG, even in a private college. */
    forInvoice: (purpose?: string) => words(gepg || alwaysGepg(purpose)),
  };
}

/** Payments to government bodies that always go through GePG, whoever owns the institution. */
export const alwaysGepg = (purpose?: string) => purpose === 'NHIF_CONTRIBUTION';

/**
 * How to pay with a control number or payment reference. Public institutions: GePG through any bank or mobile money. Private and
 * faith-based institutions: step-by-step instructions for each mobile money business number and bank account the college lists.
 */
export function HowToPay({ reference, amount, gepg, payByPhone }: {
  reference: string; amount?: number; gepg?: boolean;
  /** The invoice and the payer's realm: offers a prompt on the payer's phone when the institution's aggregator allows it. */
  payByPhone?: { realm: 'applicant' | 'student'; invoiceId: number; phone?: string; onPaid?: () => void };
}) {
  const { t } = useTranslation();
  const inst = useInstitution();
  const mode = gepg ? 'GEPG' : inst.payment?.mode ?? 'GEPG';
  if (mode === 'GEPG') {
    return <Typography variant="body2" color="text.secondary">{t('pay.gepgHow')} {t('fees.payWith')}</Typography>;
  }
  const channels = inst.payment?.channels ?? [];
  if (channels.length === 0 && !(payByPhone && inst.payment?.phonePrompt?.enabled)) return <Alert severity="info" sx={{ mt: 1 }}>{t('pay.none')}</Alert>;
  const ref = reference.replace(/\s/g, '');
  const prompt = inst.payment?.phonePrompt;
  return (
    <Box sx={{ mt: 1.5 }}>
      {payByPhone && prompt?.enabled && !!amount && amount > 0 && (
        <PhonePay {...payByPhone} amount={amount} needsNetwork={prompt.needsNetwork} provider={prompt.provider} />
      )}
      <Typography variant="subtitle2">{t('pay.howTo')}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>{t('pay.choose')} {t('fees.payWith')}</Typography>
      {channels.map((c, i) => <ChannelSteps key={c.id} c={c} reference={ref} amount={amount} defaultExpanded={i === 0} />)}
    </Box>
  );
}

function ChannelSteps({ c, reference, amount, defaultExpanded }: { c: PaymentChannel; reference: string; amount?: number; defaultExpanded: boolean }) {
  const { t } = useTranslation();
  const mobile = c.kind === 'MOBILE_MONEY';
  const amt = amount && amount > 0 ? ` (${formatTzs(amount)})` : '';
  const steps = mobile
    ? [c.ussd ? t('pay.m1', { ussd: c.ussd, label: c.label }) : t('pay.m1NoUssd', { label: c.label }), t('pay.m2'), t('pay.m3', { number: c.accountNumber }),
      t('pay.m4', { reference }), t('pay.m5', { amount: amt, name: c.accountName })]
    : [t('pay.b1', { label: c.label }), t('pay.b2', { number: c.accountNumber }) + ' ' + t('pay.accountName', { name: c.accountName }) + '.',
      t('pay.b3', { reference }), t('pay.b4')];
  return (
    <Accordion defaultExpanded={defaultExpanded} disableGutters variant="outlined" sx={{ '&:before': { display: 'none' }, mb: 0.75, borderRadius: 1 }}>
      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
        <Stack direction="row" spacing={1.25} alignItems="center">
          {mobile ? <PhoneAndroidIcon fontSize="small" color="primary" /> : <AccountBalanceIcon fontSize="small" color="primary" />}
          <Typography sx={{ fontWeight: 600 }}>{c.label}</Typography>
          <Typography color="text.secondary" sx={{ fontFamily: 'monospace' }}>{c.accountNumber}</Typography>
        </Stack>
      </AccordionSummary>
      <AccordionDetails sx={{ pt: 0 }}>
        <Box component="ol" sx={{ m: 0, pl: 2.5, '& li': { mb: 0.4 } }}>
          {steps.map((s) => <Typography component="li" variant="body2" key={s}>{s}</Typography>)}
        </Box>
        {c.branch && <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>{t('pay.branch', { branch: c.branch })}</Typography>}
        {c.instructions && <Typography variant="body2" sx={{ mt: 0.5 }}>{c.instructions}</Typography>}
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.75 }}>{mobile ? t('pay.mobileWhen') : t('pay.bankWhen')}</Typography>
      </AccordionDetails>
    </Accordion>
  );
}

interface PhonePayment { id: number; status: 'PENDING' | 'PAID' | 'FAILED' | 'EXPIRED'; phone: string; amount: number; message?: string; receiptNo?: string }

const NETWORKS: { code: string; label: string; prefixes: string[] }[] = [
  { code: 'MPESA', label: 'M-Pesa', prefixes: ['074', '075', '076'] },
  { code: 'MIXX_BY_YAS', label: 'Mixx by Yas', prefixes: ['065', '067', '071', '077'] },
  { code: 'AIRTEL_MONEY', label: 'Airtel Money', prefixes: ['068', '069', '078'] },
  { code: 'HALOPESA', label: 'HaloPesa', prefixes: ['061', '062'] },
];

const local = (p: string) => { const d = p.replace(/\D/g, ''); return d.startsWith('255') ? '0' + d.slice(3) : d; };
const guessNetwork = (p: string) => NETWORKS.find((n) => n.prefixes.some((x) => local(p).startsWith(x)))?.code ?? '';

/** A prompt on the payer's phone through the institution's aggregator: the payer enters their PIN, and this box follows the outcome. */
function PhonePay({ realm, invoiceId, phone, onPaid, amount, needsNetwork, provider }: {
  realm: 'applicant' | 'student'; invoiceId: number; phone?: string; onPaid?: () => void; amount: number; needsNetwork: boolean; provider?: string;
}) {
  const { t } = useTranslation();
  const [number, setNumber] = useState(phone ? local(phone) : '');
  const [network, setNetwork] = useState(phone ? guessNetwork(phone) : '');
  const [requestId, setRequestId] = useState<number | null>(null);
  const send = useMutation({
    mutationFn: () => api<PhonePayment>(`/api/${realm}/invoices/${invoiceId}/phone-payment`, { realm, method: 'POST', body: { phone: number, network: needsNetwork ? network : null } }),
    onSuccess: (r) => setRequestId(r.id),
  });
  const status = useQuery({
    queryKey: ['phone-payment', realm, requestId], enabled: requestId != null,
    queryFn: () => api<PhonePayment>(`/api/${realm}/phone-payments/${requestId}`, { realm }),
    refetchInterval: (q) => (q.state.data?.status === 'PENDING' || !q.state.data ? 3000 : false),
  });
  const r = status.data ?? send.data;
  useEffect(() => { if (r?.status === 'PAID') onPaid?.(); }, [r?.status]); // eslint-disable-line react-hooks/exhaustive-deps
  const waiting = r?.status === 'PENDING';
  return (
    <Box sx={{ p: 1.5, mb: 1.5, borderRadius: 2, border: 1, borderColor: 'primary.light', bgcolor: 'background.paper' }}>
      <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 0.5 }}>
        <PhonelinkRingIcon color="primary" fontSize="small" />
        <Typography sx={{ fontWeight: 600, flexGrow: 1 }}>{t('phone.title')}</Typography>
        {provider && <Typography variant="caption" color="text.secondary">{t('phone.via', { provider })}</Typography>}
      </Stack>
      {r?.status === 'PAID' ? (
        <Alert severity="success">{r.receiptNo ? t('phone.paid', { receipt: r.receiptNo }) : t('phone.paidNoReceipt')}</Alert>
      ) : waiting ? (
        <Stack direction="row" spacing={1.5} alignItems="center"><CircularProgress size={20} /><Typography variant="body2">{t('phone.waiting', { amount: formatTzs(amount) })}</Typography></Stack>
      ) : (
        <>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1.25 }}>{t('phone.intro', { amount: formatTzs(amount) })}</Typography>
          {r?.status === 'FAILED' && <Alert severity="warning" sx={{ mb: 1 }}>{t('phone.failed', { reason: r.message ?? '' })}</Alert>}
          {r?.status === 'EXPIRED' && <Alert severity="info" sx={{ mb: 1 }}>{t('phone.expired')}</Alert>}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
            <TextField size="small" label={t('phone.number')} value={number} inputProps={{ inputMode: 'tel' }}
              onChange={(e) => { setNumber(e.target.value); if (needsNetwork && !network) setNetwork(guessNetwork(e.target.value)); }} />
            {needsNetwork && (
              <TextField select size="small" label={t('phone.network')} value={network} onChange={(e) => setNetwork(e.target.value)} sx={{ minWidth: 170 }}>
                {NETWORKS.map((n) => <MenuItem key={n.code} value={n.code}>{n.label}</MenuItem>)}
              </TextField>
            )}
            <Button variant="contained" sx={{ whiteSpace: 'nowrap', flexShrink: 0 }} disabled={send.isPending || !number || (needsNetwork && !network)} onClick={() => { setRequestId(null); send.mutate(); }}>
              {send.isPending ? t('phone.sending') : r ? t('phone.again') : t('phone.send')}
            </Button>
          </Stack>
          <ErrorAlert error={send.error} />
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>{t('phone.or')}</Typography>
        </>
      )}
    </Box>
  );
}
