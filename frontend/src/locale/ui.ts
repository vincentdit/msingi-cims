import i18n from '../i18n';
import sw from './staff-sw.json';

/**
 * Kiswahili for the back-office (BR-40). The staff screens are written in English; when Kiswahili is chosen every string
 * React renders from our own components (text, and labels such as `label`, `title`, `placeholder`) is looked up here,
 * English being the key. A string with no entry stays in English, so a new screen is never blank, only untranslated.
 * Entries with {0}, {1} … are patterns for text built from values, e.g. "{0} students" → "wanafunzi {0}".
 */
const exact = new Map<string, string>();
interface Pattern { re: RegExp; to: string; hint: string; numeric: boolean }
const patterns: Pattern[] = [];

for (const [en, s] of Object.entries(sw as Record<string, string>)) {
  if (!s) continue;
  if (/\{\d\}/.test(en)) {
    const parts = en.split(/\{(\d)\}/);
    let re = '^';
    let longest = '';
    parts.forEach((p, i) => {
      if (i % 2 === 1) re += '([\\s\\S]+?)';
      else {
        re += p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        if (p.trim().length > longest.length) longest = p.trim();
      }
    });
    // the placeholder numbers in the translation refer to the order in the English text
    const order = parts.filter((_, i) => i % 2 === 1).map(Number);
    // "{0} of {1}" and the like carry too little text of their own to tell "3 of 10" from "Head of Department": numbers only
    const numeric = /^\{\d\}/.test(en) && /\{\d\}$/.test(en) && longest.length <= 3;
    patterns.push({ re: new RegExp(re + '$'), to: s.replace(/\{(\d)\}/g, (_, d) => `{@${order.indexOf(Number(d))}}`), hint: longest, numeric });
  } else {
    exact.set(en, s);
  }
}
// most specific first, so "{0} of {1} accepted" is tried before "{0} accepted"
patterns.sort((a, b) => b.hint.length - a.hint.length);

const cache = new Map<string, string>();

export function uiLanguage(): string {
  return i18n.language === 'sw' ? 'sw' : 'en';
}

/**
 * The Kiswahili for an English UI string (keeping its surrounding spaces), or the string itself. A context such as
 * 'label' first tries an entry written for it ("[label] Open" = Wazi, a status on a chip; plain "Open" = Fungua, a button).
 */
export function translateUi(text: string, context?: string): string {
  if (uiLanguage() !== 'sw' || text.length < 2 || text.length > 400) return text;
  if (context) {
    const special = exact.get(`[${context}] ${text.trim()}`);
    if (special !== undefined) return special;
  }
  const hit = cache.get(text);
  if (hit !== undefined) return hit;
  const lead = text.match(/^\s*/)![0];
  const trail = text.match(/\s*$/)![0];
  const core = text.slice(lead.length, text.length - trail.length).replace(/\s+/g, ' ');
  let result = text;
  if (core && /[A-Za-z]/.test(core)) {
    const e = exact.get(core);
    if (e !== undefined) result = lead + e + trail;
    else {
      for (const p of patterns) {
        if (p.hint && !core.includes(p.hint)) continue;
        const m = p.re.exec(core);
        if (m && p.numeric && !m.slice(1).every((v) => /^[\d\s.,:/%–-]+$/.test(v))) continue;
        if (m) {
          result = lead + p.to.replace(/\{@(\d)\}/g, (_, i) => translateUi(m[Number(i) + 1])) + trail;
          break;
        }
      }
      // "Label: 6" (a label with its count): the label in Kiswahili, the number as it is
      const counted = result === text ? /^(.+): ([\d,.]+)$/.exec(core) : null;
      if (counted && exact.has(counted[1])) result = `${lead}${exact.get(counted[1])}: ${counted[2]}${trail}`;
    }
  }
  if (cache.size > 20000) cache.clear();
  cache.set(text, result);
  return result;
}

/** For text built outside JSX (window.confirm, document titles): the same lookup. */
export const tr = (text: string, context?: string): string => translateUi(text, typeof context === 'string' ? context : undefined);

export const UI_DICTIONARY_SIZE = exact.size + patterns.length;
