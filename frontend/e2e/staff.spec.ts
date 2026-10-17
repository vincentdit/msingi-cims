import { expect, test } from '@playwright/test';
import { expectPageWorks, STAFF_PASSWORD, staffSignIn, watchErrors } from './helpers';

const PAGES: Record<string, string[]> = {
  admin: ['/staff', '/staff/approvals', '/staff/reports', '/staff/applications', '/staff/students', '/staff/users',
    '/staff/audit', '/staff/security', '/staff/integrations', '/staff/imports', '/staff/letters', '/staff/institution', '/staff/timetable',
    '/staff/exam-timetable', '/staff/health-insurance'],
  bursar: ['/staff/finance-overview', '/staff/finance', '/staff/sponsors', '/staff/waivers', '/staff/health-insurance'],
  exams: ['/staff/exams-office', '/staff/exam-timetable', '/staff/marks', '/staff/results', '/staff/eligibility', '/staff/regulations'],
  lecturer: ['/staff/my-teaching', '/staff/teaching', '/staff/timetable'],
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

test('a class is added to the timetable, shown on the week and removed', async ({ page, request }, info) => {
  // each browser uses its own Saturday hours in the workshop, so runs in parallel never meet
  const start = { chromium: '08:00', firefox: '11:00', webkit: '14:00' }[info.project.name] ?? '17:00';
  const login = await request.post('/api/auth/login', { headers: { 'X-Tenant': 'DEMO' }, data: { username: 'academic', password: STAFF_PASSWORD.academic } });
  const headers = { 'X-Tenant': 'DEMO', Authorization: `Bearer ${(await login.json()).accessToken}` };
  // the modules the curricula place in each semester, before students register (does nothing the second time)
  for (const s of await (await request.get('/api/academic/semesters', { headers })).json()) {
    await request.post('/api/exams/offerings/from-curricula', { headers, data: { semesterId: s.id } });
    // a class left behind by an earlier failed run
    const t = await (await request.get(`/api/timetable/semesters/${s.id}`, { headers })).json();
    for (const x of t.slots ?? []) if (x.venueCode === 'WS1' && x.dayOfWeek === 6 && x.startTime.startsWith(start)) await request.delete(`/api/timetable/slots/${x.id}`, { headers });
  }
  await staffSignIn(page, 'academic');
  await page.goto('/staff/timetable');
  await expect(page.getByRole('heading', { name: 'Timetable' })).toBeVisible();
  await page.getByRole('button', { name: 'Add a class' }).first().click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Module').click();
  await page.getByRole('option').first().click();
  await dialog.getByLabel('Day').click();
  await page.getByRole('option', { name: 'Saturday' }).click();
  await dialog.getByLabel('Starts').fill(start);
  await dialog.getByLabel('Ends').fill(`${String(Number(start.slice(0, 2)) + 2).padStart(2, '0')}:00`);
  await dialog.getByLabel('Venue').click();
  await page.getByRole('option', { name: /^WS1/ }).click();
  await dialog.getByRole('button', { name: 'Save' }).click();
  await expect(dialog).toBeHidden();
  const cls = page.getByRole('button', { name: new RegExp(`Saturday ${start}`) });
  await expect(cls).toBeVisible();
  await cls.click();
  await page.getByRole('dialog').getByRole('button', { name: 'Remove' }).click();
  await expect(page.getByRole('button', { name: new RegExp(`Saturday ${start}`) })).toHaveCount(0);
});
