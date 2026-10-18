import AccountBalanceIcon from '@mui/icons-material/AccountBalance';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import PhoneAndroidIcon from '@mui/icons-material/PhoneAndroid';
import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Stack, Typography } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { formatTzs } from '../api/client';
import type { PaymentChannel } from '../api/types';
import { useInstitution } from './InstitutionContext';

/** True for a public institution (GePG control numbers); false for a private or faith-based one (its own accounts, CIMS payment references). */
export const useGepg = () => (useInstitution().payment?.mode ?? 'GEPG') === 'GEPG';

/** The words for the number an invoice is paid with: a GePG control number, or the college's own payment reference. */
export function usePayWords() {
  const { t } = useTranslation();
  const gepg = (useInstitution().payment?.mode ?? 'GEPG') === 'GEPG';
  return {
    gepg,
    number: gepg ? t('pay.controlNo') : t('pay.reference'),
    getNumber: gepg ? t('pay.getControlNo') : t('pay.getReference'),
  };
}

/**
 * How to pay with a control number or payment reference. Public institutions: GePG through any bank or mobile money. Private and
 * faith-based institutions: step-by-step instructions for each mobile money business number and bank account the college lists.
 */
export function HowToPay({ reference, amount }: { reference: string; amount?: number }) {
  const { t } = useTranslation();
  const inst = useInstitution();
  const mode = inst.payment?.mode ?? 'GEPG';
  if (mode === 'GEPG') {
    return <Typography variant="body2" color="text.secondary">{t('pay.gepgHow')} {t('fees.payWith')}</Typography>;
  }
  const channels = inst.payment?.channels ?? [];
  if (channels.length === 0) return <Alert severity="info" sx={{ mt: 1 }}>{t('pay.none')}</Alert>;
  const ref = reference.replace(/\s/g, '');
  return (
    <Box sx={{ mt: 1.5 }}>
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
