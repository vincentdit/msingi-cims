import { expect, test } from '@playwright/test';
import { expectPageWorks, studentSignIn, watchErrors } from './helpers';

test('the admission portal home works in English and Kiswahili', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/');
  await expectPageWorks(page);
  await expect(page.getByRole('button', { name: 'SW', exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'SW', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'sw');
  await page.getByRole('button', { name: 'EN', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  expect(errors).toEqual([]);
});

test('sign-up starts with the Form Four index number', async ({ page }) => {
  await page.goto('/register');
  await expectPageWorks(page);
  await expect(page.getByRole('textbox').first()).toBeVisible();
});

test('the help page answers questions and takes a request without an account', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/login');
  await page.getByRole('link', { name: 'Stuck? Ask the institution for help' }).click();
  await expect(page).toHaveURL(/\/help$/);
  await expectPageWorks(page);
  await expect(page.getByRole('heading', { name: 'Ask for help' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Follow a request' })).toBeVisible();
  await page.getByLabel('Request number').fill('HD-2000-00001');
  await page.getByLabel('Your code').fill('ABCDEFGH');
  await page.getByRole('button', { name: 'Open my request' }).click();
  await expect(page.getByText('The request number or code is not right', { exact: false })).toBeVisible();
  expect(errors).toEqual([]);
});

test('a programme page shows the fee and requirements, and its short link opens it in Kiswahili', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/');
  await page.getByRole('link', { name: 'Details and fees' }).first().click();
  await expect(page).toHaveURL(/\/programmes\//);
  await expectPageWorks(page);
  await expect(page.getByRole('heading', { name: 'Entry requirements' })).toBeVisible();
  const code = new URL(page.url()).pathname.split('/').pop();
  await page.goto(`/p/${code}?lang=sw&src=e2e`);
  await expect(page).toHaveURL(new RegExp(`/programmes/${code}\\?lang=sw`));
  await expect(page.getByRole('heading', { name: 'Sifa za kujiunga' })).toBeVisible();
  await page.getByRole('button', { name: 'EN', exact: true }).click();
  expect(errors).toEqual([]);
});

test('the document verification page opens', async ({ page }) => {
  await page.goto('/verify');
  await expectPageWorks(page);
});

test('a student signs in and opens each tab', async ({ page }) => {
  const errors = watchErrors(page);
  await studentSignIn(page);
  for (const path of ['/student/fees', '/student/timetable', '/student/results', '/student/register', '/student/notices', '/student/profile', '/student/help']) {
    await page.goto(path);
    await expectPageWorks(page);
  }
  expect(errors).toEqual([]);
});
