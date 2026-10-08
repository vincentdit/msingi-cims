import '@fontsource/inter/400.css';
import '@fontsource/inter/600.css';
import '@fontsource/sora/600.css';
import '@fontsource/sora/700.css';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { SessionProvider } from './auth/session';
import { InstitutionProvider } from './components/InstitutionContext';
import './i18n';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <BrowserRouter>
          <InstitutionProvider>
            <App />
          </InstitutionProvider>
        </BrowserRouter>
      </SessionProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);

// Installable app (FR-STU-01): the service worker is registered in production builds only, so development always runs fresh code.
if (import.meta.env.PROD && 'serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => { /* the portal works without it */ });
  });
}
