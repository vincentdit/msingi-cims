import { createElement } from 'react';
import { createTheme } from '@mui/material/styles';
import { translateUi, uiLanguage } from './locale/ui';

// MUI's own wording (pagination, autocomplete) is not in our JSX, so it is put into Kiswahili here, at render time
const RowsPerPage = () => translateUi('Rows per page:');
const Ui = ({ text }: { text: string }) => translateUi(text);
const rows = ({ from, to, count }: { from: number; to: number; count: number }) =>
  `${from}–${to} ${uiLanguage() === 'sw' ? 'kati ya' : 'of'} ${count !== -1 ? count : uiLanguage() === 'sw' ? `zaidi ya ${to}` : `more than ${to}`}`;

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
      MuiTablePagination: { defaultProps: { labelRowsPerPage: createElement(RowsPerPage), labelDisplayedRows: rows } },
      MuiAutocomplete: { defaultProps: { noOptionsText: createElement(Ui, { text: 'No options' }), loadingText: createElement(Ui, { text: 'Loading…' }) } },
    },
  });
}
