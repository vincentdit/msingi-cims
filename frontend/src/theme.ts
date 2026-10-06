import { createTheme } from '@mui/material/styles';

/** Builds the portal theme from the institution's branding colours. */
export function buildTheme(primary = '#1F4E8C', accent = '#12715B') {
  return createTheme({
    palette: {
      primary: { main: primary },
      secondary: { main: accent },
      warning: { main: '#E3A72F' },
      background: { default: '#F6F7F9', paper: '#FFFFFF' },
      text: { primary: '#0F1B2D', secondary: '#4A5568' },
    },
    shape: { borderRadius: 10 },
    typography: {
      fontFamily: '"Inter", system-ui, -apple-system, "Segoe UI", Roboto, sans-serif',
      h1: { fontFamily: '"Sora", "Inter", sans-serif', fontWeight: 700 },
      h2: { fontFamily: '"Sora", "Inter", sans-serif', fontWeight: 700 },
      h3: { fontFamily: '"Sora", "Inter", sans-serif', fontWeight: 700 },
      h4: { fontFamily: '"Sora", "Inter", sans-serif', fontWeight: 700 },
      h5: { fontFamily: '"Sora", "Inter", sans-serif', fontWeight: 600 },
      h6: { fontFamily: '"Sora", "Inter", sans-serif', fontWeight: 600 },
      button: { textTransform: 'none', fontWeight: 600 },
    },
    components: {
      MuiButton: { defaultProps: { disableElevation: true }, styleOverrides: { root: { borderRadius: 8, paddingInline: 18 } } },
      MuiCard: { defaultProps: { variant: 'outlined' }, styleOverrides: { root: { borderColor: '#E2E6EC' } } },
      MuiTextField: { defaultProps: { fullWidth: true, size: 'medium' } },
      MuiChip: { styleOverrides: { root: { fontWeight: 600 } } },
    },
  });
}
