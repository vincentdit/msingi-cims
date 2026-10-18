import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

// In development the API runs on :8085 (override with CIMS_API_URL). The proxy keeps the browser
// on one origin, and "localhost" is mapped to the DEMO institution by the backend's tenant_domains.
// 127.0.0.1 is used instead of "localhost" because Node on Windows resolves localhost to IPv6 (::1).
const apiUrl = process.env.CIMS_API_URL ?? 'http://127.0.0.1:8085';

// The React plugin pre-bundles `<jsxImportSource>/jsx-runtime` and `/jsx-dev-runtime` as if they were a package. Ours
// (src/locale) is app code that shares i18n and the dictionary with the rest of the app: bundled separately it would be
// a second copy that misses language changes and dictionary edits. Vite 8 lets the plugin's include win over
// optimizeDeps.exclude, so the entries are taken out of the include list here, after the plugin has added them.
const LOCALE_RUNTIME = /^cims-jsx(\/|$)/;
const keepLocaleRuntimeInApp: Plugin = {
  name: 'cims:keep-locale-runtime-in-app',
  enforce: 'post',
  config(config) {
    if (config.optimizeDeps?.include) config.optimizeDeps.include = config.optimizeDeps.include.filter((d) => !LOCALE_RUNTIME.test(d));
  },
};

export default defineConfig({
  // our components' JSX goes through src/locale/jsx-runtime, which puts the back-office into Kiswahili when chosen (BR-40)
  plugins: [react({ jsxImportSource: 'cims-jsx' }), keepLocaleRuntimeInApp],
  resolve: {
    alias: [
      { find: /^cims-jsx$/, replacement: fileURLToPath(new URL('./src/locale/index.ts', import.meta.url)) },
      { find: /^cims-jsx\//, replacement: fileURLToPath(new URL('./src/locale/', import.meta.url)) },
    ],
  },
  optimizeDeps: { exclude: ['cims-jsx', 'cims-jsx/jsx-runtime', 'cims-jsx/jsx-dev-runtime'] },
  server: {
    port: 5173,
    // /p/ is the short programme link people share: the backend serves its preview card (Facebook, WhatsApp)
    proxy: { '/api': { target: apiUrl, changeOrigin: false }, '/p/': { target: apiUrl, changeOrigin: false } },
  },
});
