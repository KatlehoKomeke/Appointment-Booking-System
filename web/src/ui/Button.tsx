/**
 * Buttons, and links that look like buttons.
 *
 * A button acts on the page (submit, confirm, refresh, copy); going to another page is a link, even when the design
 * draws it as a button, such as "Book an appointment" on the landing page. {@link ButtonLink} keeps that rule
 * without changing the look.
 *
 * @packageDocumentation
 */
import type { ComponentPropsWithRef } from 'react';
import { Link, type LinkProps } from 'react-router';

import styles from './Button.module.css';
import { cx } from './cx';

/** How a button looks: `primary` for the one main action on a screen, `secondary` for the others. */
export type ButtonVariant = 'primary' | 'secondary';

/** The look shared by {@link Button} and {@link ButtonLink}. */
export interface ButtonLookProps {
  /** Defaults to `primary`. */
  variant?: ButtonVariant;
  /**
   * `regular` (the default) is 48 px high, as every primary button is. `compact` is 44 px, the smallest target,
   * for a button beside text such as Refresh in the stale-times banner.
   */
  size?: 'regular' | 'compact';
  /** Stretches the button across its container, as the main actions are on a phone. */
  fullWidth?: boolean;
}

/** Props for {@link Button}: the look, and everything a `<button>` takes. */
export interface ButtonProps extends ComponentPropsWithRef<'button'>, ButtonLookProps {}

/** Props for {@link ButtonLink}: the look, and everything a router `<Link>` takes. */
export interface ButtonLinkProps extends LinkProps, ButtonLookProps {}

function lookClasses({
  variant = 'primary',
  size = 'regular',
  fullWidth = false,
}: ButtonLookProps) {
  return cx(
    styles.button,
    styles[variant],
    size === 'compact' && styles.compact,
    fullWidth && styles.fullWidth,
  );
}

/**
 * A button. It is `type="button"` unless told otherwise, so a button inside a form submits it only when it is
 * meant to (`type="submit"`).
 */
export function Button({
  variant,
  size,
  fullWidth,
  className,
  type = 'button',
  ...buttonProps
}: ButtonProps) {
  return (
    <button
      type={type}
      className={cx(lookClasses({ variant, size, fullWidth }), className)}
      {...buttonProps}
    />
  );
}

/** A router link drawn as a button, for an action that goes to another page. */
export function ButtonLink({ variant, size, fullWidth, className, ...linkProps }: ButtonLinkProps) {
  return (
    <Link className={cx(lookClasses({ variant, size, fullWidth }), className)} {...linkProps} />
  );
}
