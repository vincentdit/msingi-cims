/**
 * The Kiswahili dictionary of the back-office (BR-40), loaded only when Kiswahili is chosen: it is a separate chunk of about
 * 90 kB, so people using CIMS in English never download it. setLanguage('sw') loads it before switching, and a saved choice
 * of Kiswahili loads it before the first screen (main.tsx), so nobody sees a half-translated page.
 * English is the key; entries with {0}, {1} … are patterns for text built from values, e.g. "{0} students" → "wanafunzi {0}".
 */
export interface Pattern { re: RegExp; to: string; hint: string; numeric: boolean }

export const exact = new Map<string, string>();
export const patterns: Pattern[] = [];
let loading: Promise<void> | null = null;
let version = 0;

/** Increases each time the dictionary changes, so cached translations made before are dropped. */
export function dictionaryVersion(): number {
  return version;
}

export function compileDictionary(sw: Record<string, string>): void {
  exact.clear();
  patterns.length = 0;
  for (const [en, s] of Object.entries(sw)) {
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
  version++;
}

/** Fetches and compiles the dictionary once; later calls return the same promise. */
export function loadUiDictionary(): Promise<void> {
  if (!loading) {
    loading = import('./staff-sw.json').then((m) => compileDictionary((m.default ?? m) as Record<string, string>))
      .catch((e) => {
        loading = null;   // a later switch to Kiswahili tries again; until then the screens stay in English
        console.warn('Kiswahili dictionary could not be loaded', e);
      });
  }
  return loading;
}

export function uiDictionarySize(): number {
  return exact.size + patterns.length;
}
