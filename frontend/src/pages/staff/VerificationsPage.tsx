import {
  Box, Card, CardContent, Chip, Container, MenuItem, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, formatDateTime } from '../../api/client';
import { ErrorAlert, Loading, PageTitle } from '../../components/common';

const staff = { realm: 'staff' as const };

interface Entry { verifiedAt: string; code: string; documentType?: string; documentNo?: string; result: 'VALID' | 'NOT_VALID' | 'NOT_FOUND' }
interface Summary { checks: number; valid: number; notValid: number; notFound: number; visitors: number; recent: Entry[] }

const TYPE: Record<string, string> = {
  ADMISSION_LETTER: 'Admission letter', STATEMENT_OF_RESULTS: 'Statement of results', AWARD_CERTIFICATE: 'Award certificate', STUDENT_ID_CARD: 'Student ID card',
};
const RESULT: Record<Entry['result'], { label: string; color: 'success' | 'warning' | 'default' }> = {
  VALID: { label: 'Valid', color: 'success' }, NOT_VALID: { label: 'Replaced or revoked', color: 'warning' }, NOT_FOUND: { label: 'No such code', color: 'default' },
};

/**
 * Checks made on the public verification page (employers, universities, NACTVET): which documents are being checked and whether
 * they were valid. Many "no such code" from one source can mean someone is guessing codes; the page limits each address anyway.
 */
export default function VerificationsPage() {
  const [days, setDays] = useState(30);
  const q = useQuery({ queryKey: ['document-verifications', days], queryFn: () => api<Summary>(`/api/document-verifications?days=${days}`, staff) });
  const s = q.data;
  return (
    <Container maxWidth="lg" disableGutters>
      <PageTitle title="Document checks" subtitle="Admission letters, statements of results, certificates and ID cards checked on the public verification page."
        action={
          <TextField select size="small" label="Period" value={days} onChange={(e) => setDays(Number(e.target.value))} sx={{ width: 180 }}>
            {[7, 30, 90, 365].map((d) => <MenuItem key={d} value={d}>Last {d} days</MenuItem>)}
          </TextField>
        } />
      <ErrorAlert error={q.error} />
      {q.isLoading && <Loading />}
      {s && (
        <Stack spacing={2.5}>
          <Card><CardContent>
            <Stack direction="row" spacing={4} flexWrap="wrap" useFlexGap>
              {([['Checks', s.checks], ['Valid', s.valid], ['Replaced or revoked', s.notValid], ['No such code', s.notFound], ['Different visitors', s.visitors]] as [string, number][]).map(([l, n]) => (
                <Box key={l}>
                  <Typography sx={{ fontFamily: 'Sora', fontWeight: 700, fontSize: 26, lineHeight: 1.2 }}>{n.toLocaleString()}</Typography>
                  <Typography variant="caption" color="text.secondary">{l}</Typography>
                </Box>
              ))}
            </Stack>
            <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 1.5 }}>
              Visitors are counted per day by a one-way code of their address; their address itself is not kept.
            </Typography>
          </CardContent></Card>
          <Card>
            <CardContent sx={{ pb: 0 }}><Typography variant="h6">Latest checks</Typography></CardContent>
            {s.recent.length === 0 ? (
              <CardContent><Typography variant="body2" color="text.secondary">No document was checked in this period.</Typography></CardContent>
            ) : (
              <TableContainer>
                <Table size="small" sx={{ minWidth: 640 }}>
                  <TableHead><TableRow>
                    <TableCell>When</TableCell><TableCell>Document</TableCell><TableCell>Number</TableCell><TableCell>Code entered</TableCell><TableCell>Answer</TableCell>
                  </TableRow></TableHead>
                  <TableBody>
                    {s.recent.map((e, i) => (
                      <TableRow key={i}>
                        <TableCell>{formatDateTime(e.verifiedAt)}</TableCell>
                        <TableCell>{e.documentType ? TYPE[e.documentType] ?? e.documentType : '—'}</TableCell>
                        <TableCell>{e.documentNo ?? '—'}</TableCell>
                        <TableCell sx={{ fontFamily: 'monospace' }}>{e.code}</TableCell>
                        <TableCell><Chip size="small" label={RESULT[e.result].label} color={RESULT[e.result].color} variant="outlined" /></TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Card>
        </Stack>
      )}
    </Container>
  );
}
