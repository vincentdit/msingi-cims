import { describe, expect, it } from 'vitest';
import { canOpen } from './access';

describe('canOpen', () => {
  it('follows the roles of the page a link points to', () => {
    expect(canOpen(['LECTURER'], '/staff/my-teaching')).toBe(true);
    expect(canOpen(['LECTURER'], '/staff/finance')).toBe(false);
    expect(canOpen(['BURSAR'], '/staff/finance?tab=fees')).toBe(true);
    expect(canOpen(['LECTURER'], '/staff/students/12')).toBe(false);
  });

  it('applies the narrower roles of a step inside a page', () => {
    expect(canOpen(['AUDITOR'], '/staff/windows/3')).toBe(true);
    expect(canOpen(['AUDITOR'], '/staff/windows/3/selection')).toBe(false);
    expect(canOpen(['ADMISSIONS_OFFICER'], '/staff/windows/3/selection')).toBe(true);
  });

  it('leaves other portals and the account pages alone', () => {
    expect(canOpen(['LECTURER'], '/student/fees')).toBe(true);
    expect(canOpen([], '/staff/password')).toBe(true);
    expect(canOpen(undefined, '/')).toBe(true);
  });
});
