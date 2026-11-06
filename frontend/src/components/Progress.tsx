import { Box, CircularProgress, LinearProgress, Stack, Typography } from '@mui/material';
import { useIsFetching, useIsMutating } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

/** A thin bar across the top of every page while anything is being fetched or saved (after a short moment, so quick calls do not flicker). */
export function GlobalProgress() {
  const [other, setOther] = useState(0);
  useEffect(() => {
    const on = (e: Event) => setOther((n) => Math.max(0, n + (e as CustomEvent<number>).detail));
    window.addEventListener('cims-busy', on);
    return () => window.removeEventListener('cims-busy', on);
  }, []);
  const busy = useIsFetching() + useIsMutating() + other > 0;
  const [show, setShow] = useState(false);
  useEffect(() => {
    if (!busy) { setShow(false); return; }
    const t = setTimeout(() => setShow(true), 250);
    return () => clearTimeout(t);
  }, [busy]);
  if (!show) return null;
  return (
    <Box role="progressbar" aria-label="Working" sx={{ position: 'fixed', top: 0, left: 0, right: 0, zIndex: (t) => t.zIndex.modal + 10 }}>
      <LinearProgress sx={{ height: 3 }} />
    </Box>
  );
}

/** Seconds since `active` became true (0 while not active). */
function useElapsed(active: boolean) {
  const [s, setS] = useState(0);
  useEffect(() => {
    if (!active) { setS(0); return; }
    const start = Date.now();
    const t = setInterval(() => setS(Math.floor((Date.now() - start) / 1000)), 1000);
    return () => clearInterval(t);
  }, [active]);
  return s;
}

/** A button's label while it works: a small spinner and the word given. */
export function Busy({ label }: { label: string }) {
  return (
    <Stack direction="row" spacing={1} alignItems="center" component="span">
      <CircularProgress size={16} color="inherit" thickness={5} />
      <span>{label}</span>
    </Stack>
  );
}

/**
 * Under a form while an outside system (NECTA, NACTVET, an aggregator…) is asked: a moving bar, what is happening, and after a few
 * seconds how long it has taken so far, so the person knows CIMS is still at work.
 */
export function Waiting({ active, message, slowAfter = 6 }: { active: boolean; message: string; slowAfter?: number }) {
  const { t } = useTranslation();
  const s = useElapsed(active);
  if (!active) return null;
  return (
    <Box aria-live="polite" sx={{ py: 0.5 }}>
      <LinearProgress sx={{ borderRadius: 1, mb: 0.75 }} />
      <Typography variant="body2" color="text.secondary">{message}</Typography>
      {s >= slowAfter && <Typography variant="caption" color="text.secondary">{t('common.stillWorking', { s })}</Typography>}
    </Box>
  );
}
