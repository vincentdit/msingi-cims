import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the API runs on :8085 (override with CIMS_API_URL). The proxy keeps the browser
// on one origin, and "localhost" is mapped to the DEMO institution by the backend's tenant_domains.
// 127.0.0.1 is used instead of "localhost" because Node on Windows resolves localhost to IPv6 (::1).
const apiUrl = process.env.CIMS_API_URL ?? 'http://127.0.0.1:8085';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': { target: apiUrl, changeOrigin: false } },
  },
});
