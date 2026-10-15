/**
 * Entry of the `cims-jsx` import source. The JSX transform imports createElement from here for elements that spread props
 * after a key (`<X {...p} key={k} />`); it localises the props exactly as jsx-runtime does.
 */
import * as React from 'react';
import { localise } from './jsx-runtime';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function createElement(type: any, props: any, ...children: any[]) {
  const p = localise(type, children.length ? { ...(props ?? {}), children: children.length === 1 ? children[0] : children } : props);
  if (children.length && p) {
    const { children: c, ...rest } = p;
    return Array.isArray(c) && children.length > 1 ? React.createElement(type, rest, ...c) : React.createElement(type, rest, c);
  }
  return React.createElement(type, p);
}

export const Fragment = React.Fragment;
