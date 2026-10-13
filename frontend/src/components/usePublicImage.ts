import { useQuery } from '@tanstack/react-query';
import { publicImageUrl } from '../api/client';

/** An <img> address for a public API image (the address changes when the image does, so it is cached by it). */
export function usePublicImage(path?: string | null) {
  return useQuery({ queryKey: ['public-image', path], enabled: !!path, staleTime: Infinity, retry: false,
    queryFn: () => publicImageUrl(path!) }).data;
}
