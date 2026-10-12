import { Autocomplete, Box, TextField, Typography } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';

export interface PrimarySchool { code: string; name: string; region: string; council: string }

/** Search Tanzania's primary schools (NECTA's PSLE list, as in NOAS); a school not on the list can be typed. */
export function PrimarySchoolPicker({ name, code, onChange }: { name?: string | null; code?: string | null; onChange: (name: string | null, code: string | null) => void }) {
  const { t } = useTranslation();
  const [input, setInput] = useState(name ?? '');
  const [query, setQuery] = useState('');
  useEffect(() => { const h = setTimeout(() => setQuery(input.trim()), 300); return () => clearTimeout(h); }, [input]);
  const search = useQuery({ queryKey: ['primary-schools', query], enabled: query.length >= 2, staleTime: 3600_000,
    queryFn: () => api<PrimarySchool[]>(`/api/public/schools/primary?q=${encodeURIComponent(query)}`) });
  const options = search.data ?? [];
  const selected: PrimarySchool | string | null = code && name ? { code, name, region: '', council: '' } : name || null;
  return (
    <Autocomplete<PrimarySchool | string, false, false, true>
      freeSolo options={options} value={selected} inputValue={input} loading={search.isFetching} filterOptions={(x) => x}
      getOptionLabel={(o) => (typeof o === 'string' ? o : o.name)}
      isOptionEqualToValue={(o, v) => typeof o !== 'string' && typeof v !== 'string' && o.code === v.code}
      onInputChange={(_, v, reason) => { setInput(v); if (reason === 'input') onChange(v.trim() || null, null); if (reason === 'clear') onChange(null, null); }}
      onChange={(_, v) => { if (v && typeof v !== 'string') onChange(v.name, v.code); else onChange(typeof v === 'string' ? v.trim() || null : null, null); }}
      renderOption={(props, o) => {
        const { key, ...rest } = props as typeof props & { key: string };
        return typeof o === 'string' ? <li key={key} {...rest}>{o}</li> : (
          <Box component="li" key={o.code} {...rest} sx={{ flexDirection: 'column', alignItems: 'flex-start !important' }}>
            <Typography variant="body2">{o.name}</Typography>
            <Typography variant="caption" color="text.secondary">{o.code} · {o.region}{o.council && o.council !== o.region ? ` / ${o.council}` : ''}</Typography>
          </Box>
        );
      }}
      noOptionsText={query.length < 2 ? t('profile.typeTwo') : t('profile.notListed')}
      renderInput={(p) => <TextField {...p} label={t('profile.primarySchool')} helperText={code ? `${t('profile.fromList')} · ${code}` : t('profile.primaryHint')} />}
    />
  );
}
