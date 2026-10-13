import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import CheckIcon from '@mui/icons-material/CheckCircleOutline';
import HourglassIcon from '@mui/icons-material/HourglassEmpty';
import { Box, Button, Card, Chip, Container, Divider, Stack, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { Link as RouterLink } from 'react-router-dom';
import { api, formatDateTime } from '../../api/client';
import type { ApprovalInbox, ApprovalItem } from '../../api/types';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';

const ORDER = ['Academic Committee', 'Governing Board', 'Head of Department', 'Department Board', 'Administrator'];
const age = (iso?: string) => {
  if (!iso) return null;
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000);
  return days <= 0 ? 'today' : days === 1 ? 'yesterday' : `${days} days ago`;
};

/** Everything waiting for the signed-in person's decision, grouped by the body the decision is made for. */
export default function ApprovalsPage() {
  const q = useQuery({ queryKey: ['staff', 'approvals'], refetchInterval: 60_000, queryFn: () => api<ApprovalInbox>('/api/approvals', { realm: 'staff' }) });
  const d = q.data;
  const groups = ORDER.map((body) => ({ body, items: d?.items.filter((i) => i.body === body) ?? [] })).filter((g) => g.items.length);
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Approvals"
        subtitle={d ? (d.actionable ? `${d.actionable} waiting for your decision, oldest first. Open an item to decide it where the full record is.`
          : 'Nothing is waiting for your decision.') : 'What waits for your decision.'} />
      <ErrorAlert error={q.error} onRetry={() => q.refetch()} />
      {q.isLoading && <Loading />}
      {d && !d.total && (
        <Card sx={{ p: 3 }}>
          <Stack direction="row" spacing={1.5} alignItems="center">
            <CheckIcon sx={{ color: 'success.main' }} aria-hidden />
            <Typography>You are up to date. New selection rounds, curricula, results, exemptions and amendments will appear here.</Typography>
          </Stack>
        </Card>
      )}
      <Stack spacing={3}>
        {groups.map((g) => (
          <Box component="section" key={g.body}>
            <Typography variant="h6" component="h2" sx={{ mb: 1 }}>{g.body} <Typography component="span" color="text.secondary">· {g.items.length}</Typography></Typography>
            <Card>
              {g.items.map((i, n) => (
                <Box key={`${i.kind}-${i.id}`}>
                  {n > 0 && <Divider />}
                  <Line i={i} />
                </Box>
              ))}
            </Card>
          </Box>
        ))}
      </Stack>
    </Container>
  );
}

function Line({ i }: { i: ApprovalItem }) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'center' }} sx={{ px: 2, py: 1.5, opacity: i.canAct ? 1 : 0.75 }}>
      <Box sx={{ flexGrow: 1, minWidth: 0 }}>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          <Chip size="small" label={i.label} variant="outlined" />
          <Typography variant="body1" sx={{ fontWeight: 600 }}>{i.title}</Typography>
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 0.25 }}>
          {i.detail}{i.submittedBy ? ` · ${i.submittedBy}` : ''}{i.submittedAt ? ` · ${age(i.submittedAt)}` : ''}
        </Typography>
        {i.note && (
          <Stack direction="row" spacing={0.5} alignItems="center" sx={{ mt: 0.5, color: 'text.secondary' }}>
            <HourglassIcon sx={{ fontSize: 16 }} aria-hidden /><Typography variant="caption">{i.note}</Typography>
          </Stack>
        )}
      </Box>
      <Stack alignItems={{ sm: 'flex-end' }} spacing={0.5} sx={{ flexShrink: 0 }}>
        <Button component={RouterLink} to={i.link} variant={i.canAct ? 'contained' : 'outlined'} size="small" endIcon={<ArrowForwardIcon />}>
          {i.canAct ? 'Review' : 'View'}
        </Button>
        {i.submittedAt && <Typography variant="caption" color="text.secondary">{formatDateTime(i.submittedAt)}</Typography>}
      </Stack>
    </Stack>
  );
}
