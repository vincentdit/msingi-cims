import { expect, test } from '@playwright/test';
import { expectPageWorks, studentSignIn, watchErrors } from './helpers';

test('the admission portal home works in English and Kiswahili', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto('/');
  await expectPageWorks(page);
  await expect(page.getByRole('button', { name: 'SW' })).toBeVisible();
  await page.getByRole('button', { name: 'SW' }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'sw');
  await page.getByRole('button', { name: 'EN' }).click();
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  expect(errors).toEqual([]);
});

test('sign-up starts with the Form Four index number', async ({ page }) => {
  await page.goto('/register');
  await expectPageWorks(page);
  await expect(page.getByRole('textbox').first()).toBeVisible();
});

test('the document verification page opens', async ({ page }) => {
  await page.goto('/verify');
  await expectPageWorks(page);
});

test('a student signs in and opens each tab', async ({ page }) => {
  const errors = watchErrors(page);
  await studentSignIn(page);
  for (const path of ['/student/fees', '/student/timetable', '/student/results', '/student/register', '/student/notices', '/student/profile']) {
    await page.goto(path);
    await expectPageWorks(page);
  }
  expect(errors).toEqual([]);
});
