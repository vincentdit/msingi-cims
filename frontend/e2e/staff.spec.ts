import { expect, test } from '@playwright/test';
import { expectPageWorks, staffSignIn, watchErrors } from './helpers';

const PAGES: Record<string, string[]> = {
  admin: ['/staff', '/staff/approvals', '/staff/reports', '/staff/applications', '/staff/students', '/staff/users',
    '/staff/audit', '/staff/security', '/staff/integrations', '/staff/imports', '/staff/letters', '/staff/institution'],
  bursar: ['/staff/finance-overview', '/staff/finance', '/staff/sponsors', '/staff/waivers'],
  exams: ['/staff/exams-office', '/staff/marks', '/staff/results', '/staff/eligibility', '/staff/regulations'],
  lecturer: ['/staff/my-teaching', '/staff/teaching'],
};

for (const [user, pages] of Object.entries(PAGES)) {
  test(`${user}: every page opens without errors`, async ({ page }) => {
    const errors = watchErrors(page);
    await staffSignIn(page, user);
    for (const path of pages) {
      await page.goto(path);
      await expectPageWorks(page);
    }
    expect(errors).toEqual([]);
  });
}

test('the back-office switches to Kiswahili at once and back', async ({ page }) => {
  await staffSignIn(page, 'bursar');
  await page.goto('/staff/finance-overview');
  await expect(page.getByRole('heading', { name: 'Finance overview' })).toBeVisible();
  await page.getByRole('button', { name: 'Kiswahili' }).click();
  await expect(page.getByRole('heading', { name: 'Muhtasari wa fedha' })).toBeVisible();
  await page.getByRole('button', { name: 'Kiingereza' }).click();
  await expect(page.getByRole('heading', { name: 'Finance overview' })).toBeVisible();
});

test('the staff sign-in page offers Kiswahili', async ({ page }) => {
  await page.goto('/staff/login');
  await page.getByRole('button', { name: 'SW' }).click();
  await expect(page.getByLabel('Jina la mtumiaji')).toBeVisible();
});
