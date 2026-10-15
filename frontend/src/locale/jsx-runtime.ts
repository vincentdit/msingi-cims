/**
 * The JSX runtime for CIMS's own components: React's, with the back-office strings put into the chosen language on the
 * way in (see ui.ts). Only plain strings are touched: text children and a fixed set of label props; values, keys, links,
 * ids and anything that is not a string pass through unchanged. Configured as `jsxImportSource` in vite and tsconfig.
 */
import * as React from 'react/jsx-runtime';
import { translateUi, uiLanguage } from './ui';

export type { JSX } from 'react/jsx-runtime';
export const Fragment = React.Fragment;

const PROPS = new Set(['label', 'title', 'placeholder', 'helperText', 'aria-label', 'alt', 'subtitle', 'primary', 'secondary',
  'headerName', 'description', 'emptyText', 'message', 'tooltip', 'caption', 'heading', 'hint', 'noOptionsText', 'loadingText',
  'labelRowsPerPage', 'confirmLabel', 'actionLabel']);

function child(c: unknown): unknown {
  if (typeof c === 'string') return translateUi(c);
  if (Array.isArray(c)) {
    let changed = false;
    const out = c.map((x) => child(x));
    // `{n} student{n === 1 ? '' : 's'}`: the word and its plural "s" arrive as two strings; translate them as one word
    for (let i = 0; i + 1 < c.length; i++) {
      if (c[i + 1] === 's' && typeof c[i] === 'string' && /[A-Za-z]$/.test(c[i] as string)) {
        const plural = translateUi((c[i] as string) + 's');
        if (plural !== (c[i] as string) + 's') {
          out[i] = plural;
          out[i + 1] = '';
        }
      }
    }
    for (let i = 0; i < c.length; i++) if (out[i] !== c[i]) changed = true;
    return changed ? out : c;
  }
  return c;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function localise(type: unknown, props: any): any {
  if (props == null || uiLanguage() !== 'sw') return props;
  // raw text elements stay as written
  if (type === 'code' || type === 'pre' || type === 'textarea' || type === 'input' || type === 'style' || type === 'script') return props;
  let out = props;
  for (const k in props) {
    const v = props[k];
    let nv = v;
    if (k === 'children') nv = child(v);
    else if (typeof v === 'string' && PROPS.has(k)) nv = translateUi(v, k === 'label' ? 'label' : undefined);
    if (nv !== v) {
      if (out === props) out = { ...props };
      out[k] = nv;
    }
  }
  return out;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function jsx(type: any, props: any, key?: any) {
  return React.jsx(type, localise(type, props), key);
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function jsxs(type: any, props: any, key?: any) {
  return React.jsxs(type, localise(type, props), key);
}
