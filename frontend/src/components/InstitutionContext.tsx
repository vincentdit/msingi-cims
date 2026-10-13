import PauseCircleOutlineIcon from '@mui/icons-material/PauseCircleOutline';
import { Box, CssBaseline, ThemeProvider, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, useEffect, useMemo, type ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { ApiError, api } from '../api/client';
import type { Institution } from '../api/types';
import { buildTheme } from '../theme';
import { ErrorAlert, Loading } from './common';

const Ctx = createContext<Institution | null>(null);

/** Msingi's own colours, for the platform console (which belongs to no institution). */
export const MSINGI_PRIMARY = '#0E4D4A';
export const MSINGI_ACCENT = '#C98B2B';

/**
 * Loads the institution for this address and applies its branding to the whole app. The Msingi platform console
 * (/platform) belongs to no institution: it gets Msingi's colours and no institution is loaded.
 */
export function InstitutionProvider({ children }: { children: ReactNode }) {
  const platform = useLocation().pathname.startsWith('/platform');
  const q = useQuery({ queryKey: ['institution'], enabled: !platform, queryFn: () => api<Institution>('/api/public/institution'), staleTime: 3_600_000 });
  const theme = useMemo(() => (platform ? buildTheme(MSINGI_PRIMARY, MSINGI_ACCENT) : buildTheme(q.data?.branding.primaryColor, q.data?.branding.accentColor)),
    [q.data, platform]);
  const suspended = q.error instanceof ApiError && q.error.problem.code === 'INSTITUTION_SUSPENDED';

  useEffect(() => {
    if (platform) document.title = 'Msingi CIMS — Platform console';
    else if (q.data) document.title = `${q.data.shortName ?? q.data.name} — Online Admission`;
  }, [q.data, platform]);

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      {platform ? children : (
        <>
          {q.isLoading && <Loading />}
          {suspended && <Suspended detail={(q.error as ApiError).problem.detail} />}
          {q.error && !suspended && <ErrorAlert error={q.error} onRetry={() => q.refetch()} />}
          {q.data && <Ctx.Provider value={q.data}>{children}</Ctx.Provider>}
        </>
      )}
    </ThemeProvider>
  );
}

/** Shown on every page of an institution the platform has suspended. */
function Suspended({ detail }: { detail?: string }) {
  return (
    <Box sx={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', p: 3, bgcolor: 'background.default' }}>
      <Box sx={{ maxWidth: 480, textAlign: 'center' }}>
        <PauseCircleOutlineIcon sx={{ fontSize: 56, color: 'text.secondary' }} />
        <Typography variant="h5" component="h1" sx={{ mt: 1 }}>Service temporarily unavailable</Typography>
        <Typography color="text.secondary" sx={{ mt: 1.5 }}>{detail ?? 'This institution\'s CIMS service is temporarily unavailable. Please contact the institution.'}</Typography>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>Huduma hii haipatikani kwa muda. Tafadhali wasiliana na chuo.</Typography>
      </Box>
    </Box>
  );
}

export function useInstitution(): Institution {
  const v = useContext(Ctx);
  if (!v) throw new Error('useInstitution outside InstitutionProvider');
  return v;
}
