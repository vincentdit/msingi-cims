import { Box, Card, CardContent, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api, type Realm } from '../api/client';

interface Semester {
  academicYear: string; semester: number; yearOfStudy: number; gpa?: number; cgpa?: number; decision?: string;
  modules: { code: string; title: string; credits: number; marks?: number; grade: string; points?: number }[];
}

/** Results from before CIMS, brought in by data migration (BR-39). Shows nothing when there are none. */
export default function EarlierResults({ path, realm }: { path: string; realm: Realm }) {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['earlier-results', path], queryFn: () => api<Semester[]>(path, { realm }) });
  if (!q.data?.length) return null;
  return (
    <Card sx={{ borderRadius: realm === 'staff' ? undefined : 3 }}>
      <CardContent>
        <Typography variant="h6">{t('earlier.title')}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>{t('earlier.intro')}</Typography>
        {q.data.map((s) => (
          <Box key={`${s.academicYear}-${s.semester}`} sx={{ mt: 1.5 }}>
            <Typography sx={{ fontWeight: 600 }}>
              {s.academicYear} · {t('earlier.semester', { n: s.semester })} · {t('earlier.year', { n: s.yearOfStudy })}
              {s.gpa != null ? ` · GPA ${s.gpa}` : ''}{s.cgpa != null ? ` · CGPA ${s.cgpa}` : ''}{s.decision ? ` · ${s.decision}` : ''}
            </Typography>
            <Table size="small">
              <TableHead><TableRow><TableCell>{t('earlier.module')}</TableCell><TableCell align="right">{t('earlier.credits')}</TableCell><TableCell align="right">{t('earlier.marks')}</TableCell><TableCell>{t('earlier.grade')}</TableCell></TableRow></TableHead>
              <TableBody>
                {s.modules.map((m) => (
                  <TableRow key={m.code}><TableCell>{m.code} {m.title}</TableCell><TableCell align="right">{m.credits}</TableCell><TableCell align="right">{m.marks ?? '—'}</TableCell><TableCell>{m.grade}</TableCell></TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        ))}
      </CardContent>
    </Card>
  );
}
