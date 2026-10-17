import { NO_ACCESS, useCanOpen } from './StaffLink';
import { Alert, Box, Button, CircularProgress, Stack, TableRow, Typography } from '@mui/material';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { ApiError } from '../api/client';

export function Loading({ label }: { label?: string }) {
  const { t } = useTranslation();
  return (
    <Stack alignItems="center" spacing={2} sx={{ py: 8 }}>
      <CircularProgress />
      <Typography color="text.secondary">{label ?? t('common.loading')}</Typography>
    </Stack>
  );
}

export function ErrorAlert({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { t } = useTranslation();
  if (!error) return null;
  const problem = error instanceof ApiError ? error.problem : undefined;
  return (
    <Alert
      severity="error"
      sx={{ my: 2 }}
      action={onRetry ? <Button color="inherit" size="small" onClick={onRetry}>{t('common.retry')}</Button> : undefined}
    >
      {problem?.detail ?? (error instanceof Error ? error.message : t('common.error'))}
      {problem?.errors && (
        <Box component="ul" sx={{ m: 0, pl: 2 }}>
          {Object.entries(problem.errors).map(([f, m]) => <li key={f}>{m}</li>)}
        </Box>
      )}
    </Alert>
  );
}

export function PageTitle({ title, subtitle, action }: { title: string; subtitle?: ReactNode; action?: ReactNode }) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'flex-end' }} spacing={2} sx={{ mb: 3 }}>
      <Box>
        <Typography variant="h5" component="h1">{title}</Typography>
        {subtitle && <Typography color="text.secondary" sx={{ mt: 0.5 }}>{subtitle}</Typography>}
      </Box>
      {action}
    </Stack>
  );
}

const STATUS_COLOURS: Record<string, 'default' | 'primary' | 'secondary' | 'success' | 'warning' | 'error' | 'info'> = {
  DRAFT: 'default', PAYMENT_PENDING: 'warning', SUBMITTED: 'success', UNDER_REVIEW: 'info', SELECTED: 'success',
  NOT_SELECTED: 'error', WITHDRAWN: 'default', PUBLISHED: 'success', CLOSED: 'default',
  PENDING_VERIFICATION: 'info', QUERIED: 'warning', NACTVET_REJECTED: 'error', OFFERED: 'primary', ACCEPTED: 'success',
  DECLINED: 'default', OFFER_LAPSED: 'default',
  ADMITTED: 'info', ACTIVE: 'success', POSTPONED: 'warning', SUSPENDED: 'warning', DISCONTINUED: 'error', DECEASED: 'default',
  COMPLETED: 'primary', GRADUATED: 'primary', PENDING: 'warning', APPROVED: 'success',
  ISSUED: 'warning', PARTIALLY_PAID: 'info', PAID: 'success', CANCELLED: 'default', CREDITED: 'default',
};

/** Labels where the plain status name would read badly. */
const STATUS_LABELS: Record<string, string> = {
  PENDING_VERIFICATION: 'At NACTVET', NACTVET_REJECTED: 'Not verified by NACTVET', OFFERED: 'Offer issued',
  OFFER_LAPSED: 'Offer lapsed', QUERIED: 'Queried by NACTVET', ADMITTED: 'Admitted', ACTIVE: 'Registered',
};

export function statusColour(status: string) {
  return STATUS_COLOURS[status] ?? 'default';
}

export function humanise(status: string) {
  if (STATUS_LABELS[status]) return STATUS_LABELS[status];
  return status.charAt(0) + status.slice(1).toLowerCase().replace(/_/g, ' ');
}

/**
 * A table row that opens a page, by click or by Enter. A row cannot be a link (<a> is not allowed inside <tbody>), so it
 * navigates itself and is announced as a link.
 */
export function LinkRow({ to, children, disabledReason }: { to: string; children: ReactNode; disabledReason?: string }) {
  const navigate = useNavigate();
  const allowed = useCanOpen()(to);
  const reason = disabledReason ?? (allowed ? undefined : NO_ACCESS);
  if (reason) return <TableRow title={reason} aria-disabled="true" sx={{ opacity: 0.55, cursor: 'not-allowed' }}>{children}</TableRow>;
  return (
    <TableRow hover role="link" tabIndex={0} onClick={() => navigate(to)}
      onKeyDown={(e) => { if (e.key === 'Enter') navigate(to); }} sx={{ cursor: 'pointer' }}>
      {children}
    </TableRow>
  );
}
