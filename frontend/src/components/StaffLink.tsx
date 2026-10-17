/**
 * A back-office link that knows whether the person may follow it. A link to a page their role cannot open, or to a step that
 * is not ready yet (`disabledReason`), is drawn faint, says why on hover, and does nothing when clicked or pressed — so nobody
 * lands on "This page is not part of your role" or an empty step. Use it wherever RouterLink was used, including as the
 * `component` of a Button, Link, Chip, Card or Typography.
 */
import { alpha, GlobalStyles } from '@mui/material';
import { forwardRef, type CSSProperties, type ReactNode, type Ref } from 'react';
import { Link as RouterLink, type LinkProps } from 'react-router-dom';
import { canOpen } from '../auth/access';
import { useSession } from '../auth/session';

export const NO_ACCESS = 'Your role cannot open this page. Ask the institution administrator if you need it.';
export const DISABLED_CLASS = 'cims-link-disabled';

/** Whether the signed-in staff member may open an address. */
export function useCanOpen() {
  const { user } = useSession();
  const roles = user('staff')?.roles;
  return (to: string) => canOpen(roles, to);
}

/** The look of a link that cannot be followed: faint, no hover effect, no pointer (once, in the staff layout). */
export const disabledLinkStyles = (
  <GlobalStyles styles={(theme) => ({
    [`.${DISABLED_CLASS}`]: { opacity: 0.45, cursor: 'not-allowed !important', textDecoration: 'none !important', boxShadow: 'none !important' },
    [`.${DISABLED_CLASS}:hover`]: { backgroundColor: 'transparent !important', textDecoration: 'none !important' },
    // an outlined button keeps its resting border on hover, so nothing suggests it can be clicked
    [`.${DISABLED_CLASS}.MuiButton-outlinedPrimary:hover`]: { borderColor: `${alpha(theme.palette.primary.main, 0.5)} !important` },
    [`.${DISABLED_CLASS} .MuiTouchRipple-root`]: { display: 'none' },
  })} />
);

type Props = LinkProps & { disabledReason?: string; children?: ReactNode };

// the props a Material UI button or chip hands its root element to make it clickable: none of them reach a link that is off
const INTERACTIVE = ['onClick', 'onFocus', 'onBlur', 'onMouseDown', 'onMouseUp', 'onMouseLeave', 'onKeyDown', 'onKeyUp', 'onTouchStart', 'onTouchEnd',
  'onTouchMove', 'onContextMenu', 'onDragLeave', 'onFocusVisible', 'replace', 'state', 'reloadDocument', 'preventScrollReset', 'relative',
  'viewTransition', 'discover', 'prefetch', 'href', 'type'];

export const StaffLink = forwardRef<HTMLAnchorElement, Props>(function StaffLink({ to, disabledReason, children, ...rest }, ref) {
  const allowed = useCanOpen();
  const path = typeof to === 'string' ? to : `${to.pathname ?? ''}${to.search ?? ''}`;
  const reason = disabledReason ?? (allowed(path) ? undefined : NO_ACCESS);
  if (!reason) return <RouterLink ref={ref} to={to} {...rest}>{children}</RouterLink>;
  const props: Record<string, unknown> = { ...rest };
  for (const k of INTERACTIVE) delete props[k];
  const className = [props.className as string | undefined, DISABLED_CLASS].filter(Boolean).join(' ');
  return (
    <span {...props} ref={ref as Ref<HTMLSpanElement>} className={className} style={props.style as CSSProperties} role="link"
      aria-disabled="true" tabIndex={-1} title={reason}
      onMouseDown={(e) => e.preventDefault()} onClick={(e) => e.preventDefault()}>
      {children}
    </span>
  );
});
