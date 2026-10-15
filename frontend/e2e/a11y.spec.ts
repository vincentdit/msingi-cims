import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { staffSignIn, studentSignIn } from './helpers';

/** WCAG 2.1 A and AA checks (NFR-USA-03): no serious or critical violation on the main screens. */
async function check(page: Page, label: string) {
  await page.waitForLoadState('networkidle');
  const result = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const bad = result.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical');
  expect(bad.map((v) => `${label}: ${v.id} (${v.nodes.length}) ${v.help}`)).toEqual([]);
}

test('public pages', async ({ page }) => {
  for (const path of ['/', '/login', '/register', '/verify', '/student/login', '/staff/login']) {
    await page.goto(path);
    await check(page, path);
  }
});

test('student portal', async ({ page }) => {
  await studentSignIn(page);
  for (const path of ['/student', '/student/fees', '/student/results', '/student/profile']) {
    await page.goto(path);
    await check(page, path);
  }
});

test('staff back-office', async ({ page }) => {
  await staffSignIn(page, 'admin');
  for (const path of ['/staff', '/staff/approvals', '/staff/applications', '/staff/students', '/staff/reports', '/staff/users',
    '/staff/security', '/staff/windows']) {
    await page.goto(path);
    await check(page, path);
  }
});
