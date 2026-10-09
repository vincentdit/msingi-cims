import type { Problem } from './types';

/**
 * Minimal API client.
 * - The institution is resolved by the backend from the host name. For local testing of another
 *   institution set VITE_TENANT (sent as the X-Tenant header).
 * - Applicant, student and staff sessions are kept separately in sessionStorage.
 */
export type Realm = 'applicant' | 'staff' | 'student';

const TENANT = import.meta.env.VITE_TENANT as string | undefined;
const API_BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? '';

const key = (realm: Realm) => `cims.${realm}.session`;

export function getToken(realm: Realm): string | null {
  try {
    const raw = sessionStorage.getItem(key(realm));
    if (!raw) return null;
    const s = JSON.parse(raw) as { token: string; expiresAt: number };
    if (Date.now() > s.expiresAt) {
      sessionStorage.removeItem(key(realm));
      return null;
    }
    return s.token;
  } catch {
    return null;
  }
}

export function setSession(realm: Realm, token: string, expiresInSeconds: number, user: unknown) {
  sessionStorage.setItem(key(realm), JSON.stringify({ token, expiresAt: Date.now() + expiresInSeconds * 1000 - 30_000, user }));
}

export function getSessionUser<T>(realm: Realm): T | null {
  try {
    const raw = sessionStorage.getItem(key(realm));
    return raw && getToken(realm) ? (JSON.parse(raw).user as T) : null;
  } catch {
    return null;
  }
}

export function clearSession(realm: Realm) {
  sessionStorage.removeItem(key(realm));
}

const UNREACHABLE: Problem = {
  status: 503,
  code: 'SERVER_UNREACHABLE',
  title: 'Cannot reach the CIMS server',
  detail: 'Cannot reach the CIMS server. Please check your connection and try again. (Developers: is the backend running, and does the frontend proxy point at it?)',
};

export class ApiError extends Error {
  constructor(public problem: Problem) {
    super(problem.detail ?? problem.title ?? 'Request failed');
  }
}

export async function api<T>(path: string, options: { method?: string; body?: unknown; realm?: Realm } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (TENANT) headers['X-Tenant'] = TENANT;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.realm) {
    const token = getToken(options.realm);
    if (token) headers.Authorization = `Bearer ${token}`;
  }
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      method: options.method ?? 'GET',
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    });
  } catch {
    throw new ApiError(UNREACHABLE);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  let data: unknown;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    // An HTML page or empty proxy error instead of JSON: the request never reached the CIMS API.
    throw new ApiError(UNREACHABLE);
  }
  if (!res.ok && data === undefined && res.status >= 500) {
    throw new ApiError(UNREACHABLE);
  }
  if (!res.ok) {
    if (res.status === 401 && options.realm) clearSession(options.realm);
    throw new ApiError((data as Problem) ?? { status: res.status, title: res.statusText });
  }
  return data as T;
}

/** Downloads a file from the API with the session token (e.g. CSV exports). */
export async function download(path: string, realm: Realm, filename: string) {
  const headers: Record<string, string> = {};
  if (TENANT) headers['X-Tenant'] = TENANT;
  const token = getToken(realm);
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(`${API_BASE}${path}`, { headers });
  if (!res.ok) throw new ApiError({ status: res.status, title: 'Download failed', detail: 'The file could not be downloaded.' });
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

/** Sends a file as the raw request body (e.g. a filled Excel template) and returns the JSON response. */
export async function upload<T>(path: string, realm: Realm, file: File): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json', 'Content-Type': file.type || 'application/octet-stream' };
  if (TENANT) headers['X-Tenant'] = TENANT;
  const token = getToken(realm);
  if (token) headers.Authorization = `Bearer ${token}`;
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, { method: 'POST', headers, body: file });
  } catch {
    throw new ApiError(UNREACHABLE);
  }
  const data = await res.json().catch(() => undefined);
  if (!res.ok) throw new ApiError((data as Problem) ?? { status: res.status, title: res.statusText });
  return data as T;
}

export const formatTzs = (n: number | undefined | null) =>
  n == null ? '—' : `TZS ${Number(n).toLocaleString('en-TZ', { maximumFractionDigits: 0 })}`;

export const formatDate = (iso?: string) =>
  iso ? new Date(iso).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }) : '—';

export const formatDateTime = (iso?: string) =>
  iso ? new Date(iso).toLocaleString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) : '—';
