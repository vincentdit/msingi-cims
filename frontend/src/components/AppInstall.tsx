import CloseIcon from '@mui/icons-material/Close';
import CloudOffIcon from '@mui/icons-material/CloudOffOutlined';
import InstallIcon from '@mui/icons-material/InstallMobileOutlined';
import { Alert, Box, Button, Card, CardContent, IconButton, Stack, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { isInstalled, offlineSince } from '../api/offline';

/** The browser's install offer, kept from the moment it fires (main.tsx) until the student taps Install. */
interface InstallPrompt extends Event { prompt(): Promise<void>; userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }> }

let deferred: InstallPrompt | null = null;
const listeners = new Set<() => void>();

export function captureInstallPrompt() {
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferred = e as InstallPrompt;
    listeners.forEach((l) => l());
  });
  window.addEventListener('appinstalled', () => {
    deferred = null;
    listeners.forEach((l) => l());
  });
}

const DISMISSED = 'cims.install.dismissedAt';

function dismissedRecently(): boolean {
  try {
    const at = Number(localStorage.getItem(DISMISSED) ?? 0);
    return Date.now() - at < 30 * 86_400_000;
  } catch {
    return false;
  }
}

const iOS = () => /iphone|ipad|ipod/i.test(navigator.userAgent) && !/crios|fxios/i.test(navigator.userAgent);

/**
 * The offer to install the student app on the phone's home screen: the browser's own Install where it has one (Android, desktop
 * Chrome and Edge), the Share › Add to Home Screen steps on an iPhone. Hidden once installed, and for 30 days after Not now.
 */
export function InstallAppCard() {
  const { t } = useTranslation();
  const [, setTick] = useState(0);
  const [hidden, setHidden] = useState(dismissedRecently());
  useEffect(() => {
    const l = () => setTick((x) => x + 1);
    listeners.add(l);
    return () => { listeners.delete(l); };
  }, []);
  if (hidden || isInstalled() || (!deferred && !iOS())) return null;
  const close = () => {
    try { localStorage.setItem(DISMISSED, String(Date.now())); } catch { /* ignore */ }
    setHidden(true);
  };
  const install = async () => {
    if (!deferred) return;
    await deferred.prompt();
    await deferred.userChoice;
    deferred = null;
    setHidden(true);
  };
  return (
    <Card sx={{ borderRadius: 3 }}>
      <CardContent sx={{ display: 'flex', gap: 1.5, alignItems: 'flex-start', p: 2, '&:last-child': { pb: 2 } }}>
        <InstallIcon color="primary" sx={{ mt: 0.25 }} aria-hidden />
        <Box sx={{ flexGrow: 1 }}>
          <Typography variant="subtitle2">{t('student.installTitle')}</Typography>
          <Typography variant="body2" color="text.secondary">{deferred ? t('student.installHint') : t('student.installIos')}</Typography>
          <Stack direction="row" spacing={1} sx={{ mt: 1.25 }}>
            {deferred && <Button size="small" variant="contained" onClick={install}>{t('student.install')}</Button>}
            <Button size="small" onClick={close}>{t('student.notNow')}</Button>
          </Stack>
        </Box>
        <IconButton size="small" aria-label={t('student.notNow')} onClick={close}><CloseIcon fontSize="small" /></IconButton>
      </CardContent>
    </Card>
  );
}

/** "No connection — showing what was saved on …" while the student app shows its saved copy. */
export function OfflineBanner() {
  const { t, i18n } = useTranslation();
  const [since, setSince] = useState(offlineSince());
  useEffect(() => {
    const l = () => setSince(offlineSince());
    window.addEventListener('cims:offline-change', l);
    return () => window.removeEventListener('cims:offline-change', l);
  }, []);
  if (since === null) return null;
  const when = new Date(since).toLocaleString(i18n.language === 'sw' ? 'sw-TZ' : 'en-GB', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  return (
    <Alert severity="warning" icon={<CloudOffIcon />} sx={{ mb: 2 }}>{t('student.offlineShowing', { when })}</Alert>
  );
}
