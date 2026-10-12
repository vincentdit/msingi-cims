import { Autocomplete, TextField } from '@mui/material';
import Grid from '@mui/material/Grid2';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../api/client';

export interface Area { region?: string | null; district?: string | null; ward?: string | null; street?: string | null }

const list = (path: string) => api<string[]>(path);
const q = (p: Record<string, string>) => new URLSearchParams(p).toString();

/**
 * Region → district → ward → village/street, each chosen from Tanzania's areas (as in NOAS). Choosing a level clears
 * the ones below it. Where the data has no list for a level, the field can be typed instead.
 */
export function AreaPicker({ value, onChange, requireRegion }: { value: Area; onChange: (a: Area) => void; requireRegion?: boolean }) {
  const { t } = useTranslation();
  const { region, district, ward, street } = value;
  const regions = useQuery({ queryKey: ['geo', 'regions'], queryFn: () => list('/api/public/geo/regions'), staleTime: Infinity });
  const districts = useQuery({ queryKey: ['geo', 'districts', region], enabled: !!region, staleTime: Infinity,
    queryFn: () => list(`/api/public/geo/districts?${q({ region: region! })}`) });
  const wards = useQuery({ queryKey: ['geo', 'wards', region, district], enabled: !!region && !!district, staleTime: Infinity,
    queryFn: () => list(`/api/public/geo/wards?${q({ region: region!, district: district! })}`) });
  const streets = useQuery({ queryKey: ['geo', 'streets', region, district, ward], enabled: !!region && !!district && !!ward, staleTime: Infinity,
    queryFn: () => list(`/api/public/geo/streets?${q({ region: region!, district: district!, ward: ward! })}`) });

  const level = (label: string, current: string | null | undefined, options: string[] | undefined, loading: boolean, parent: string | null | undefined,
    parentLabel: string, set: (v: string | null) => void, required = false) => {
    // match what is stored to the list's spelling, so "dar es salaam" shows as "Dar es Salaam"
    const match = current ? options?.find((o) => o.toLowerCase() === current.toLowerCase()) ?? current : null;
    if (parent !== undefined && !parent) {
      return <TextField fullWidth label={label} disabled value="" helperText={t('profile.chooseFirst', { level: parentLabel.toLowerCase() })} />;
    }
    if (!loading && options && options.length === 0 && parent) {
      return <TextField fullWidth label={label} value={current ?? ''} onChange={(e) => set(e.target.value || null)} />;
    }
    return (
      <Autocomplete
        options={options ?? []} value={match} loading={loading} onChange={(_, v) => set(v)} autoHighlight
        renderInput={(p) => <TextField {...p} label={label} required={required} />}
      />
    );
  };

  return (
    <Grid container spacing={2}>
      <Grid size={{ xs: 12, sm: 6 }}>
        {level(t('profile.region'), region, regions.data, regions.isLoading, undefined, '',
          (v) => onChange({ region: v, district: null, ward: null, street: null }), requireRegion)}
      </Grid>
      <Grid size={{ xs: 12, sm: 6 }}>
        {level(t('profile.district'), district, districts.data, districts.isLoading, region ?? null, t('profile.region'),
          (v) => onChange({ ...value, district: v, ward: null, street: null }))}
      </Grid>
      <Grid size={{ xs: 12, sm: 6 }}>
        {level(t('profile.ward'), ward, wards.data, wards.isLoading, district ?? null, t('profile.district'),
          (v) => onChange({ ...value, ward: v, street: null }))}
      </Grid>
      <Grid size={{ xs: 12, sm: 6 }}>
        {level(t('profile.street'), street, streets.data, streets.isLoading, ward ?? null, t('profile.ward'),
          (v) => onChange({ ...value, street: v }))}
      </Grid>
    </Grid>
  );
}
