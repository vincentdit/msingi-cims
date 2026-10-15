import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end smoke and accessibility tests (NFR-CMP-01, NFR-USA-03) against a running CIMS with the demo institution
 * (backend with the dev profile, web app on E2E_BASE_URL). CI runs them in Chromium, Firefox and WebKit (Safari's engine).
 * Locally: start the backend and `npm run dev`, then `npm run e2e -- --project=chromium`.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
    { name: 'mobile', use: { ...devices['Pixel 7'] }, testMatch: /portal\.spec\.ts/ },
  ],
});
