/**
 * A white card: the box every grouped piece of content sits in.
 *
 * @packageDocumentation
 */
import type { HTMLAttributes } from 'react';

import styles from './Card.module.css';
import { cx } from './cx';

/** The elements a card can be. A named `section` is a region; an `li` is one card in a list. */
export type CardElement = 'div' | 'section' | 'article' | 'aside' | 'li';

/** Props for {@link Card}. Other attributes, such as `aria-labelledby`, are passed on. */
export interface CardProps extends HTMLAttributes<HTMLElement> {
  /** The element to render. Defaults to `div`. */
  as?: CardElement;
  /**
   * Adds the 6% shadow, which marks only cards in a list, the review summary and the reference card.
   */
  shadow?: boolean;
  /** Drops the 16 px padding, for a card whose content, such as a full-size link, pads itself. */
  flush?: boolean;
}

/** A card: white, a 1 px border and 6 px corners, padded 16 px. */
export function Card({
  as: Element = 'div',
  shadow = false,
  flush = false,
  className,
  ...props
}: CardProps) {
  return (
    <Element
      className={cx(styles.card, shadow && styles.shadow, flush && styles.flush, className)}
      {...props}
    />
  );
}
