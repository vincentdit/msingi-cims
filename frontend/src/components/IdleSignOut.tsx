import { Button, Dialog, DialogActions, DialogContent, DialogTitle, Typography } from '@mui/material';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useInstitution } from './InstitutionContext';

const WARN_SECONDS = 60;
const EVENTS = ['mousemove', 'mousedown', 'keydown', 'touchstart', 'scroll', 'wheel'] as const;

/**
 * Signs the person out after the institution's idle time for their kind of user (NFR-SEC-02), with a minute's warning.
 * Activity in any tab of the same browser counts.
 */
export default function IdleSignOut({ kind, onSignOut }: { kind: 'staff' | 'student' | 'applicant'; onSignOut: () => void }) {
  const { t } = useTranslation();
  const inst = useInstitution();
  const minutes = inst.idleMinutes?.[kind] ?? (kind === 'staff' ? 30 : 60);
  const storageKey = `cims.${kind}.lastActive`;
  const last = useRef(Date.now());
  const [left, setLeft] = useState<number | null>(null);

  useEffect(() => {
    const mark = () => {
      last.current = Date.now();
      try { localStorage.setItem(storageKey, String(last.current)); } catch { /* storage may be blocked */ }
    };
    mark();
    let throttled = 0;
    const onActivity = () => { const now = Date.now(); if (now - throttled > 5000) { throttled = now; mark(); } };
    EVENTS.forEach((e) => window.addEventListener(e, onActivity, { passive: true }));
    const timer = window.setInterval(() => {
      let shared = 0;
      try { shared = Number(localStorage.getItem(storageKey) ?? 0); } catch { /* ignore */ }
      const lastActive = Math.max(last.current, shared);
      const remaining = Math.round((lastActive + minutes * 60_000 - Date.now()) / 1000);
      if (remaining <= 0) {
        window.clearInterval(timer);
        setLeft(null);
        onSignOut();
      } else {
        setLeft(remaining <= WARN_SECONDS ? remaining : null);
      }
    }, 1000);
    return () => { EVENTS.forEach((e) => window.removeEventListener(e, onActivity)); window.clearInterval(timer); };
  }, [minutes, storageKey, onSignOut]);

  return (
    <Dialog open={left !== null}>
      <DialogTitle>{t('idle.title')}</DialogTitle>
      <DialogContent><Typography>{t('idle.body', { seconds: left ?? 0 })}</Typography></DialogContent>
      <DialogActions>
        <Button onClick={onSignOut}>{t('idle.out')}</Button>
        <Button variant="contained" onClick={() => { last.current = Date.now(); try { localStorage.setItem(storageKey, String(last.current)); } catch { /* ignore */ } setLeft(null); }}>
          {t('idle.stay')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
