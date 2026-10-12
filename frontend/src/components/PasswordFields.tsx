import CheckIcon from '@mui/icons-material/CheckCircle';
import CircleIcon from '@mui/icons-material/RadioButtonUnchecked';
import VisibilityIcon from '@mui/icons-material/Visibility';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOff';
import { Box, IconButton, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

/** The same rules as the server (and NOAS): 8+ characters, upper and lower case, a digit and a special character. */
export const PASSWORD_RULES = [
  { key: 'length', test: (v: string) => v.length >= 8 },
  { key: 'upper', test: (v: string) => /[A-Z]/.test(v) },
  { key: 'lower', test: (v: string) => /[a-z]/.test(v) },
  { key: 'digit', test: (v: string) => /\d/.test(v) },
  { key: 'special', test: (v: string) => /[^A-Za-z0-9]/.test(v) },
] as const;

export const passwordOk = (v: string) => PASSWORD_RULES.every((r) => r.test(v));

const METER = ['#D14343', '#E07A2E', '#E0A82E', '#8DB33A', '#2E8B57'];

function Secret({ label, value, onChange, error, helperText, autoFocus }: {
  label: string; value: string; onChange: (v: string) => void; error?: boolean; helperText?: React.ReactNode; autoFocus?: boolean;
}) {
  const { t } = useTranslation();
  const [show, setShow] = useState(false);
  return (
    <TextField
      label={label} value={value} onChange={(e) => onChange(e.target.value)} type={show ? 'text' : 'password'} required fullWidth
      autoComplete="new-password" error={error} helperText={helperText} autoFocus={autoFocus}
      slotProps={{ input: { endAdornment: (
        <InputAdornment position="end">
          <IconButton size="small" onClick={() => setShow(!show)} aria-label={show ? t('auth.hidePassword') : t('auth.showPassword')} edge="end">
            {show ? <VisibilityOffIcon fontSize="small" /> : <VisibilityIcon fontSize="small" />}
          </IconButton>
        </InputAdornment>
      ) } }}
    />
  );
}

/** A new password with a strength meter and the rules ticked off as they are met, then the password typed again. */
export function NewPasswordFields({ password, confirm, onPassword, onConfirm, label }: {
  password: string; confirm: string; onPassword: (v: string) => void; onConfirm: (v: string) => void; label?: string;
}) {
  const { t } = useTranslation();
  const results = PASSWORD_RULES.map((r) => ({ key: r.key, ok: r.test(password) }));
  const met = results.filter((r) => r.ok).length;
  const ready = met === PASSWORD_RULES.length;
  const mismatch = confirm.length > 0 && confirm !== password;
  return (
    <Stack spacing={2}>
      <Box>
        <Secret label={label ?? t('auth.password')} value={password} onChange={onPassword} error={password.length > 0 && !ready}
          helperText={password.length > 0 && !ready ? t('auth.pwNotMet') : undefined} />
        {password.length > 0 && (
          <Box sx={{ mt: 1, px: 0.5 }} aria-live="polite">
            <Box role="meter" aria-valuemin={0} aria-valuemax={5} aria-valuenow={met} aria-label={t('auth.pwStrength', { met, total: 5 })}
              sx={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 0.5, mb: 1 }}>
              {PASSWORD_RULES.map((r, i) => (
                <Box key={r.key} sx={{ height: 5, borderRadius: 3, bgcolor: i < met ? METER[met - 1] : 'action.disabledBackground', transition: 'background-color .2s' }} />
              ))}
            </Box>
            <Typography variant="caption" color="text.secondary">{t('auth.pwMustContain')}</Typography>
            <Box component="ul" sx={{ listStyle: 'none', p: 0, m: 0, mt: 0.25, display: 'grid', gridTemplateColumns: { sm: '1fr 1fr' }, columnGap: 2 }}>
              {results.map((r) => (
                <Box component="li" key={r.key} sx={{ display: 'flex', alignItems: 'center', gap: 0.75, py: 0.15,
                  color: r.ok ? 'success.main' : 'text.secondary', fontSize: 13 }}>
                  {r.ok ? <CheckIcon sx={{ fontSize: 15 }} /> : <CircleIcon sx={{ fontSize: 15 }} />}
                  {t(`auth.pwRule_${r.key}`)}
                </Box>
              ))}
            </Box>
          </Box>
        )}
      </Box>
      <Secret label={t('auth.confirmPassword')} value={confirm} onChange={onConfirm} error={mismatch}
        helperText={mismatch ? t('auth.pwMismatch') : confirm.length > 0 && ready ? <Box component="span" sx={{ color: 'success.main' }}>{t('auth.pwMatch')}</Box> : undefined} />
    </Stack>
  );
}
