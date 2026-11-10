/**
 * The student app's copy for when there is no network (FR-STU-01). Only when the portal runs as the installed app — the student's
 * own phone — the last answers for the home page, fees, results, timetable, attendance, notices and profile are kept in this
 * browser's storage. When the server cannot be reached the copy is shown instead, read-only, with the time it was saved. Signing
 * out (by hand) deletes it; a shared or borrowed phone that only opened the portal in a browser keeps nothing.
 */

const PREFIX = 'cims.offline.';
const VIEW_FLAG = 'cims.student.offlineView';
const PATHS = ['/api/student/dashboard', '/api/student/account', '/api/student/results', '/api/student/timetable', '/api/student/attendance', '/api/student/modules', '/api/student/library',
  '/api/student/notices', '/api/student/profile'];

interface Saved { at: number; data: unknown }

let servedSince: number | null = null;

/** Running as the installed app (home-screen icon), not in a browser tab. */
export function isInstalled(): boolean {
  try {
    return window.matchMedia('(display-mode: standalone)').matches || (navigator as Navigator & { standalone?: boolean }).standalone === true;
  } catch {
    return false;
  }
}

export function keepsOfflineCopy(path: string): boolean {
  const p = path.split('?')[0];
  return PATHS.some((x) => p === x || (x === '/api/student/timetable' && p.startsWith(x)));
}

export function saveOffline(path: string, data: unknown) {
  if (!isInstalled() || !keepsOfflineCopy(path)) return;
  try {
    localStorage.setItem(PREFIX + path, JSON.stringify({ at: Date.now(), data } satisfies Saved));
  } catch { /* storage full or blocked: nothing kept */ }
  if (servedSince !== null) {
    servedSince = null;
    window.dispatchEvent(new Event('cims:offline-change'));
  }
}

export function readOffline<T>(path: string): T | undefined {
  if (!keepsOfflineCopy(path)) return undefined;
  try {
    const raw = localStorage.getItem(PREFIX + path);
    if (!raw) return undefined;
    const s = JSON.parse(raw) as Saved;
    if (servedSince === null || s.at < servedSince) {
      servedSince = s.at;
      window.dispatchEvent(new Event('cims:offline-change'));
    }
    return s.data as T;
  } catch {
    return undefined;
  }
}

/** When the copy now on screen was saved, or null while the app is talking to the server. */
export function offlineSince(): number | null {
  return servedSince;
}

/** The time of the newest saved answer, or null when this phone keeps no copy. */
export function savedCopyAt(): number | null {
  let newest: number | null = null;
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (!k?.startsWith(PREFIX)) continue;
      const at = (JSON.parse(localStorage.getItem(k) ?? '{}') as Saved).at;
      if (at && (newest === null || at > newest)) newest = at;
    }
  } catch { /* no storage */ }
  return newest;
}

export function clearOffline() {
  try {
    const keys: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k?.startsWith(PREFIX)) keys.push(k);
    }
    keys.forEach((k) => localStorage.removeItem(k));
    sessionStorage.removeItem(VIEW_FLAG);
  } catch { /* nothing to clear */ }
  servedSince = null;
}

/** Opening the saved copy without signing in (no network, so no sign-in is possible); ends at the next sign-in. */
export function startOfflineView() {
  try { sessionStorage.setItem(VIEW_FLAG, '1'); } catch { /* ignore */ }
}

export function endOfflineView() {
  try { sessionStorage.removeItem(VIEW_FLAG); } catch { /* ignore */ }
}

export function inOfflineView(): boolean {
  try { return sessionStorage.getItem(VIEW_FLAG) === '1'; } catch { return false; }
}
