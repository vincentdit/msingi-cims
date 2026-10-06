import { CssBaseline, ThemeProvider } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { createContext, useContext, useEffect, useMemo, type ReactNode } from 'react';
import { api } from '../api/client';
import type { Institution } from '../api/types';
import { buildTheme } from '../theme';
import { ErrorAlert, Loading } from './common';

const Ctx = createContext<Institution | null>(null);

/** Loads the institution for this address and applies its branding to the whole app. */
export function InstitutionProvider({ children }: { children: ReactNode }) {
  const q = useQuery({ queryKey: ['institution'], queryFn: () => api<Institution>('/api/public/institution'), staleTime: 3_600_000 });
  const theme = useMemo(() => buildTheme(q.data?.branding.primaryColor, q.data?.branding.accentColor), [q.data]);

  useEffect(() => {
    if (q.data) document.title = `${q.data.shortName ?? q.data.name} — Online Admission`;
  }, [q.data]);

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      {q.isLoading && <Loading />}
      {q.error && <ErrorAlert error={q.error} onRetry={() => q.refetch()} />}
      {q.data && <Ctx.Provider value={q.data}>{children}</Ctx.Provider>}
    </ThemeProvider>
  );
}

export function useInstitution(): Institution {
  const v = useContext(Ctx);
  if (!v) throw new Error('useInstitution outside InstitutionProvider');
  return v;
}
