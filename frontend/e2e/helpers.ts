import { expect, type Page } from '@playwright/test';

/** Demo accounts seeded by the backend's dev profile (README › Demo data). */
export const STAFF_PASSWORD: Record<string, string> = {
  admin: 'Admin@2026', bursar: 'Bursar@2026', registry: 'Registry@2026', exams: 'Exams@2026', lecturer: 'Lecturer@2026',
};
/** The student made by global-setup.ts (signs in with the mobile number). */
export const STUDENT = {
  get login() { return process.env.E2E_STUDENT_LOGIN ?? '0754990004'; },
  get password() { return process.env.E2E_STUDENT_PASSWORD ?? 'Kifaru#Mto47'; },
};

/** Fails the test on any uncaught error in the page. */
export function watchErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(e.message));
  return errors;
}

export async function staffSignIn(page: Page, user: keyof typeof STAFF_PASSWORD | string, language: 'en' | 'sw' = 'en') {
  await page.goto('/staff/login');
  await page.evaluate((l) => localStorage.setItem('cims.lang', l), language);
  await page.reload();
  await page.getByLabel(language === 'sw' ? 'Jina la mtumiaji' : 'Username').fill(user);
  await page.getByLabel(language === 'sw' ? 'Nenosiri' : 'Password').fill(STAFF_PASSWORD[user]);
  await page.getByRole('button', { name: language === 'sw' ? 'Ingia' : 'Sign in' }).click();
  await expect(page.getByRole('navigation', { name: language === 'sw' ? 'Menyu kuu' : 'Main menu' })).toBeVisible();
}

export async function studentSignIn(page: Page) {
  await page.goto('/student/login');
  await page.getByLabel('Registration number or mobile number').fill(STUDENT.login);
  await page.locator('input[type=password]').fill(STUDENT.password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  // signed in: the sign-in form is gone and the portal shows the student's registration number
  await expect(page.getByRole('button', { name: 'Sign in' })).toHaveCount(0);
  await expect(page.getByText(/DEMO\/NTA\d\/\d{2}\/\d{4}/).first()).toBeVisible();
}

/** No error screen and some content. */
export async function expectPageWorks(page: Page) {
  await page.waitForLoadState('networkidle');
  const body = await page.locator('body').innerText();
  expect(body).not.toMatch(/Something went wrong|Kuna hitilafu|Unexpected Application Error/);
  expect(body.trim().length).toBeGreaterThan(20);
}
