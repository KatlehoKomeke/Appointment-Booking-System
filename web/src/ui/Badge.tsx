/**
 * A short word in a pill: a delivery status, a province, "Open Sundays", "Demo", "No longer free".
 *
 * @packageDocumentation
 */
import type { HTMLAttributes } from 'react';

import styles from './Badge.module.css';
import { cx } from './cx';

/**
 * The badge's colours.
 *
 * - `pending`: warn on its tint. Pending, Sending.
 * - `sent`: primary-strong on primary's tint. Sent, and other good news such as "Open Sundays".
 * - `failed`: accent-strong on accent's tint. Failed, Not delivered.
 * - `unavailable`: as `failed`, for a time that is "No longer free".
 * - `neutral`: muted on a grey fill. A province chip.
 * - `demo`: warn on its tint. The Demo tag.
 */
export type BadgeTone = 'pending' | 'sent' | 'failed' | 'neutral' | 'demo' | 'unavailable';

/** Props for {@link Badge}. Other `<span>` attributes are passed on. */
export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  /** The colours. The words in the badge carry the meaning; the colour only repeats it. */
  tone: BadgeTone;
  /**
   * The type size. `label` is 12/16 (chips and the Demo tag, the default for `neutral` and `demo`), `badge` is
   * 13/18 (statuses, the default for the rest), and `large` is 14/20 with more padding, for the delivery status on
   * the confirmation page.
   */
  size?: 'label' | 'badge' | 'large';
}

/**
 * A badge. Its words say what it means, never its colour alone; an icon may go before the words as a child, and is
 * decorative.
 */
export function Badge({ tone, size, className, ...spanProps }: BadgeProps) {
  const resolvedSize = size ?? (tone === 'neutral' || tone === 'demo' ? 'label' : 'badge');
  return (
    <span
      className={cx(styles.badge, styles[tone], styles[resolvedSize], className)}
      {...spanProps}
    />
  );
}
