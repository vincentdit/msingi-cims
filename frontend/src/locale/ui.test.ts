import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import i18n from '../i18n';
import { localise } from './jsx-runtime';
import { translateUi, UI_DICTIONARY_SIZE } from './ui';

describe('back-office Kiswahili (BR-40)', () => {
  beforeAll(async () => { await i18n.changeLanguage('sw'); });
  afterAll(async () => { await i18n.changeLanguage('en'); });

  it('has a dictionary', () => {
    expect(UI_DICTIONARY_SIZE).toBeGreaterThan(3000);
  });

  it('translates exact strings and keeps the surrounding spaces', () => {
    expect(translateUi('Sign in')).toBe('Ingia');
    expect(translateUi('  Sign in ')).toBe('  Ingia ');
  });

  it('fills patterns, translating the values that are UI text themselves', () => {
    expect(translateUi('3 result amendment(s) wait for the administrator.')).toBe('Marekebisho 3 ya matokeo yanamsubiri msimamizi.');
    expect(translateUi('Head of Department: Information and Communication Technology'))
      .toBe('Mkuu wa Idara: Information and Communication Technology');
  });

  it('applies "{0} of {1}" to numbers only', () => {
    expect(translateUi('3 of 10')).toBe('3 kati ya 10');
    expect(translateUi('Head of Department')).toBe('Mkuu wa Idara');
    expect(translateUi('Bank of Tanzania branch')).toBe('Bank of Tanzania branch');
  });

  it('translates the label of "Label: 6" and keeps the count', () => {
    expect(translateUi('Department approved: 6')).toBe('Imeidhinishwa na idara: 6');
  });

  it('leaves unknown text, values and codes alone', () => {
    expect(translateUi('Ordinary Diploma in Information Technology')).toBe('Ordinary Diploma in Information Technology');
    expect(translateUi('DEMO/NTA6/27/0001')).toBe('DEMO/NTA6/27/0001');
    expect(translateUi('42')).toBe('42');
  });

  it('localises children and label props, not values or raw text elements', () => {
    const p = localise('div', { children: ['Sign in', 7], title: 'Sign in', value: 'Sign in', id: 'Sign in' });
    expect(p.children).toEqual(['Ingia', 7]);
    expect(p.title).toBe('Ingia');
    expect(p.value).toBe('Sign in');
    expect(p.id).toBe('Sign in');
    expect(localise('code', { children: 'Sign in' }).children).toBe('Sign in');
  });

  it('words a status on a chip differently from a button', () => {
    expect(localise('button', { children: 'Open' }).children).toBe('Fungua');
    expect(localise('Chip', { label: 'Open' }).label).toBe('Wazi');
  });

  it('translates a word and its plural "s" together', () => {
    const p = localise('span', { children: [4, ' student', 's'] });
    expect(p.children).toEqual([4, ' wanafunzi', '']);
  });

  it('changes nothing in English', async () => {
    await i18n.changeLanguage('en');
    const props = { children: 'Sign in' };
    expect(localise('div', props)).toBe(props);
    expect(translateUi('Sign in')).toBe('Sign in');
    await i18n.changeLanguage('sw');
  });
});
