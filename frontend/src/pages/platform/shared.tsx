import CopyIcon from '@mui/icons-material/ContentCopy';
import { Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Typography } from '@mui/material';
import { useState } from 'react';
import type { TokenResponse } from '../../api/types';
import { useSession } from '../../auth/session';

export const platform = { realm: 'platform' as const };

// ============================================================ types (mirroring the platform API)

export interface PlatformMe { id: number; username: string; fullName: string; mustChangePassword: boolean }
export interface PlatformToken { accessToken: string; tokenType: string; expiresIn: number; user: PlatformMe }

export interface SystemHealth {
  system: string; label: string; mode: 'LIVE' | 'SIMULATED' | 'NOT_CONFIGURED'; status: 'OK' | 'DEGRADED' | 'DOWN' | 'IDLE';
  calls: number; failures: number; failureRate?: number; averageMs?: number; lastSuccessAt?: string; lastFailureAt?: string; lastError?: string;
}

export interface Usage {
  studentsActive: number; studentsTotal: number; applicationsThisYear: number; staffActive: number; staffTotal: number;
  programmes: number; smsBalance: number; smsPartsThisMonth: number; lastStaffSignIn?: string;
}

export interface InstitutionRow {
  id: number; code: string; name: string; shortName?: string; status: 'ACTIVE' | 'SUSPENDED' | 'ONBOARDING'; statusReason?: string;
  ownership: string; nactvetRegNo?: string; primaryHost?: string; createdAt: string; usage: Usage; setupDone: number; setupTotal: number;
  integrationFailures24h: number;
}

export interface AuditEntry {
  id: number; occurredAt: string; actorName: string; action: string; tenantId?: number; tenantCode?: string; entity: string;
  entityId?: number; details?: Record<string, unknown>; ipAddress?: string;
}

export interface InstitutionDetail {
  id: number; code: string; name: string; shortName?: string; nactvetRegNo?: string; ownership: string; status: InstitutionRow['status'];
  statusReason?: string; statusChangedAt?: string; statusChangedBy?: string; contactEmail?: string; contactPhone?: string; primaryColor: string;
  notes?: string; onboardedBy?: string; createdAt: string; staffSignInUrl?: string;
  domains: { id: number; host: string; purpose: string; createdAt: string }[];
  administrators: { id: number; username: string; fullName: string; email?: string; phone?: string; status: string; mustChangePassword: boolean; lastLoginAt?: string }[];
  usage: Usage; setup: { key: string; label: string; done: boolean; hint: string }[]; health: SystemHealth[]; recentActions: AuditEntry[];
}

export interface IssuedAdmin { institution: InstitutionDetail; username: string; temporaryPassword: string; signInUrl?: string }

export interface Overview {
  institutions: number; active: number; suspended: number; studentsActive: number; staffActive: number; applicationsThisYear: number;
  health: SystemHealth[]; attention: InstitutionRow[]; recentActions: AuditEntry[];
}

export interface PageOf<T> { content: T[]; totalElements: number; page: number; size: number }

export interface IntegrationCall {
  id: number; occurredAt: string; tenantId?: number; tenantCode?: string; system: string; operation: string; direction: 'IN' | 'OUT';
  mode: string; outcome: 'SUCCESS' | 'FAILURE'; durationMs?: number; reference?: string; detail?: string;
}

// ============================================================ session

export function usePlatformSession() {
  const { user, signIn, signOut } = useSession();
  return {
    me: user('platform') as unknown as PlatformMe | null,
    signIn: (t: PlatformToken) => signIn('platform', t as unknown as TokenResponse),
    signOut: () => signOut('platform'),
  };
}

// ============================================================ small pieces

const HEALTH_COLOUR = { OK: 'success', DEGRADED: 'warning', DOWN: 'error', IDLE: 'default' } as const;
const HEALTH_LABEL = { OK: 'Working', DEGRADED: 'Some failures', DOWN: 'Failing', IDLE: 'No calls' } as const;

export function HealthChip({ status }: { status: SystemHealth['status'] }) {
  return <Chip size="small" color={HEALTH_COLOUR[status]} label={HEALTH_LABEL[status]} variant={status === 'IDLE' ? 'outlined' : 'filled'} />;
}

export function StatusChip({ status }: { status: InstitutionRow['status'] }) {
  return <Chip size="small" color={status === 'ACTIVE' ? 'success' : status === 'SUSPENDED' ? 'error' : 'info'}
    label={status === 'ACTIVE' ? 'Active' : status === 'SUSPENDED' ? 'Suspended' : 'Onboarding'} />;
}

export const MODE_LABEL: Record<string, string> = { LIVE: 'Live', SIMULATED: 'Simulated', NOT_CONFIGURED: 'Not configured' };

/** Readable names for the platform's audited actions. */
export const ACTION_LABEL: Record<string, string> = {
  LOGIN: 'Signed in', LOGIN_FAILED: 'Failed sign-in', ACCOUNT_LOCKED: 'Account locked', PASSWORD_CHANGED: 'Changed own password',
  INSTITUTION_ONBOARDED: 'Onboarded institution', INSTITUTION_UPDATED: 'Changed institution details', INSTITUTION_SUSPENDED: 'Suspended institution',
  INSTITUTION_REACTIVATED: 'Reactivated institution', INSTITUTION_ADMIN_CREATED: 'Added administrator',
  INSTITUTION_ADMIN_PASSWORD_RESET: 'Reset administrator password', DOMAIN_ADDED: 'Added address', DOMAIN_REMOVED: 'Removed address',
  SMS_BUNDLE_CREDITED: 'Credited SMS bundle', PLATFORM_USER_CREATED: 'Added platform user', PLATFORM_USER_PASSWORD_RESET: 'Reset platform user password',
  PLATFORM_USER_DISABLED: 'Disabled platform user', PLATFORM_USER_ENABLED: 'Enabled platform user',
};

export const actionLabel = (a: string) => ACTION_LABEL[a] ?? a.charAt(0) + a.slice(1).toLowerCase().replace(/_/g, ' ');

export function detailsText(d?: Record<string, unknown>) {
  if (!d) return '';
  return Object.entries(d).map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(', ') : String(v)}`).join(' · ');
}

export const n = (v: number | undefined | null) => (v ?? 0).toLocaleString('en-GB');

/** A temporary password, shown once with a copy button. */
export function IssuedPasswordDialog({ open, title, username, password, note, onClose }: {
  open: boolean; title: string; username: string; password: string; note?: React.ReactNode; onClose: () => void;
}) {
  const [copied, setCopied] = useState(false);
  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Typography sx={{ mb: 1.5 }}>Give these to <strong>{username}</strong> privately. They choose their own password at first sign-in. The password is not shown again.</Typography>
        <Stack spacing={1} sx={{ p: 1.5, bgcolor: 'action.hover', borderRadius: 2 }}>
          <Typography variant="body2" color="text.secondary">Username</Typography>
          <Typography sx={{ fontFamily: 'ui-monospace, Menlo, Consolas, monospace', fontSize: 17 }}>{username}</Typography>
          <Typography variant="body2" color="text.secondary">Temporary password</Typography>
          <Stack direction="row" alignItems="center" spacing={1}>
            <Typography data-testid="temporary-password" sx={{ fontFamily: 'ui-monospace, Menlo, Consolas, monospace', fontSize: 20, letterSpacing: 1, flex: 1 }}>{password}</Typography>
            <Button size="small" startIcon={<CopyIcon />} onClick={() => { navigator.clipboard?.writeText(password); setCopied(true); }}>{copied ? 'Copied' : 'Copy'}</Button>
          </Stack>
        </Stack>
        {note && <Box sx={{ mt: 1.5 }}>{note}</Box>}
      </DialogContent>
      <DialogActions><Button variant="contained" onClick={onClose}>Done</Button></DialogActions>
    </Dialog>
  );
}
