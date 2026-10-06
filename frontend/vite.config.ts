import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the API runs on :8080. The proxy keeps the browser on one origin,
// and "localhost" is mapped to the DEMO institution by the backend's tenant_domains.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: { '/api': { target: 'http://127.0.0.1:8085', changeOrigin: false } },
  },
});
