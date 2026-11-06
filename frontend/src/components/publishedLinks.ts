import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { AlmanacYear, OrgStructure } from '../api/types';

/** Whether the footer should link to the almanac and the organisation structure (only once published). */
export function usePublishedLinks() {
  const almanac = useQuery({ queryKey: ['public', 'almanac-years'], staleTime: 600_000, retry: false,
    queryFn: () => api<AlmanacYear[]>('/api/public/almanac/years') });
  const org = useQuery({ queryKey: ['public', 'organisation'], staleTime: 600_000, retry: false,
    queryFn: () => api<OrgStructure>('/api/public/organisation') });
  return { almanac: (almanac.data?.length ?? 0) > 0, organisation: !!org.data };
}
