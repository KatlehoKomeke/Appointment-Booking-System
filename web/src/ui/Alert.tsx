/**
 * A message boxed off from the page: an error, a status that has changed, or information that never changes.
 *
 * @packageDocumentation
 */
import type { HTMLAttributes, ReactNode } from 'react';

import styles from './Alert.module.css';
import { cx } from './cx';
import { AlertIcon, ClockIcon, InfoIcon } from './icons';

/**
 * What an alert is for.
 *
 * - `error`: something failed or must change, such as "That time was just taken". It has `role="alert"`, so a
 *   screen reader announces it as soon as it appears.
 * - `status`: something has changed but nothing failed, such as "Times may have changed". It has no role of its
 *   own: render it inside a `role="status"` region that is present, and empty, before the status appears, so the
 *   change is announced.
 * - `info`: plain text that never changes, such as the simulated inbox's banner. Never announced.
 */
export type AlertTone = 'error' | 'status' | 'info';

/** Props for {@link Alert}. Other `<div>` attributes, such as `id`, are passed on. */
export interface AlertProps extends Omit<HTMLAttributes<HTMLDivElement>, 'title' | 'role'> {
  /** What the alert is for; it sets the colours, the icon and the role. */
  tone: AlertTone;
  /**
   * The headline. On an error it is a line of its own, in the alert-title style; on a status or info alert it is
   * bold at the start of the text, as the inbox banner's "Demo operator view."
   */
  title?: ReactNode;
  /** The body: a sentence or two. */
  children?: ReactNode;
  /**
   * Buttons or links that answer the alert. On an error or info alert they sit below the text, across its width
   * (the three later times after `SLOT_FULL`); on a status alert, beside it (Refresh). A secondary button inside
   * takes the alert's colours.
   */
  actions?: ReactNode;
  /** Replaces the tone's icon. */
  icon?: ReactNode;
}

const TONE_ICON: Record<AlertTone, ReactNode> = {
  error: <AlertIcon />,
  status: <ClockIcon />,
  info: <InfoIcon />,
};

/** An alert. See {@link AlertTone} for which tone to use and how each one is announced. */
export function Alert({
  tone,
  title,
  children,
  actions,
  icon,
  className,
  ...divProps
}: AlertProps) {
  const hasTitle = title !== undefined && title !== null && title !== false;
  const sideActions = tone === 'status';

  return (
    <div
      {...divProps}
      role={tone === 'error' ? 'alert' : undefined}
      className={cx(styles.alert, styles[tone], className)}
    >
      <div className={styles.row}>
        <span className={styles.icon}>{icon ?? TONE_ICON[tone]}</span>
        <div className={styles.body}>
          {hasTitle && tone === 'error' ? (
            <>
              <p className={styles.title}>{title}</p>
              {children !== undefined && <div className={styles.text}>{children}</div>}
            </>
          ) : (
            <div className={styles.text}>
              {hasTitle && <strong>{title}</strong>} {children}
            </div>
          )}
        </div>
        {sideActions && actions !== undefined && (
          <div className={styles.sideActions}>{actions}</div>
        )}
      </div>
      {!sideActions && actions !== undefined && <div className={styles.actions}>{actions}</div>}
    </div>
  );
}
