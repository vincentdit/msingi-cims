/** Development build of the localising JSX runtime (see jsx-runtime.ts). */
import * as React from 'react/jsx-dev-runtime';
import { localise } from './jsx-runtime';

export type { JSX } from 'react/jsx-dev-runtime';
export const Fragment = React.Fragment;

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function jsxDEV(type: any, props: any, key: any, isStatic: boolean, source: any, self: any) {
  return React.jsxDEV(type, localise(type, props), key, isStatic, source, self);
}
