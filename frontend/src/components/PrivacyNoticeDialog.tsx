import { Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Link, Typography, useMediaQuery, useTheme } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState, type MouseEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';
import { Loading } from './common';

interface Facts { version: string; institution: string; contactEmail?: string; contactPhone?: string; applicantRetentionMonths: number }

const SECTIONS = ['who', 'collect', 'why', 'basis', 'share', 'keep', 'security', 'rights', 'changes'] as const;

/**
 * The applicants' privacy notice (Personal Data Protection Act, 2022), in the portal's language, with the institution's
 * name, contacts and how long it keeps applications. {@code onAgree} adds an "I agree" button (the sign-up form ticks its box).
 */
export function PrivacyNoticeDialog({ open, onClose, onAgree }: { open: boolean; onClose: () => void; onAgree?: () => void }) {
  const { t } = useTranslation();
  const theme = useTheme();
  const phone = useMediaQuery(theme.breakpoints.down('sm'));
  const q = useQuery({ queryKey: ['public', 'privacy-notice'], enabled: open, staleTime: 5 * 60_000,
    queryFn: () => api<Facts>('/api/public/privacy-notice') });
  const f = q.data;
  const contact = f ? [f.contactEmail, f.contactPhone].filter(Boolean).join(' · ') || f.institution : '';
  const values = f ? { institution: f.institution, contact, months: f.applicantRetentionMonths } : {};
  return (
    <Dialog open={open} onClose={onClose} fullScreen={phone} maxWidth="md" fullWidth scroll="paper" aria-labelledby="privacy-notice-title">
      <DialogTitle id="privacy-notice-title">
        {t('privacyNotice.title')}
        {f && <Typography variant="caption" color="text.secondary" display="block">{t('privacyNotice.version', { version: f.version })}</Typography>}
      </DialogTitle>
      <DialogContent dividers>
        {!f ? <Loading /> : (
          <>
            <Typography sx={{ mb: 2 }}>{t('privacyNotice.intro', values)}</Typography>
            {SECTIONS.map((k, i) => (
              <Box key={k} sx={{ mb: 2 }}>
                <Typography variant="subtitle1" component="h3" sx={{ fontWeight: 700 }}>{`${i + 1}. ${t(`privacyNotice.${k}.title`)}`}</Typography>
                <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>{t(`privacyNotice.${k}.text`, values)}</Typography>
              </Box>
            ))}
          </>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('privacyNotice.close')}</Button>
        {onAgree && <Button variant="contained" disabled={!f} onClick={() => { onAgree(); onClose(); }}>{t('privacyNotice.agree')}</Button>}
      </DialogActions>
    </Dialog>
  );
}

/** "privacy notice" as a link inside a sentence; opens the notice without ticking or unticking the box around it. */
export function PrivacyNoticeLink({ onAgree, children }: { onAgree?: () => void; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const show = (e: MouseEvent) => { e.preventDefault(); e.stopPropagation(); setOpen(true); };
  return (
    <>
      <Link component="button" type="button" variant="body2" onClick={show} sx={{ verticalAlign: 'baseline', fontWeight: 600 }}>{children}</Link>
      <PrivacyNoticeDialog open={open} onClose={() => setOpen(false)} onAgree={onAgree} />
    </>
  );
}
