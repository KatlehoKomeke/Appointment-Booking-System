/**
 * A text message drawn as a phone draws it: a bubble with 16 px corners and one 4 px corner, where the message
 * came in. The confirmation page and the simulated inbox both show the SMS this way, so the customer reads the
 * same text the gateway would have sent.
 *
 * @packageDocumentation
 */
import { cx } from '../ui/cx';
import styles from './SmsBubble.module.css';

/** Props for {@link SmsBubble}. */
export interface SmsBubbleProps {
  /** The message as sent, line breaks kept. */
  body: string;
  /**
   * A line under the bubble, such as "BRANCHBOOK · 10:42 · simulated". With it, the bubble sits white in a grey
   * frame, as on a phone screen (the confirmation page); without it, the bubble alone is grey (the inbox card).
   */
  meta?: string;
}

/** An SMS as a phone bubble. */
export function SmsBubble({ body, meta }: SmsBubbleProps) {
  if (meta === undefined) {
    return (
      <blockquote className={cx(styles.bubble, styles.muted)}>
        <p className={styles.body}>{body}</p>
      </blockquote>
    );
  }
  return (
    <figure className={styles.frame}>
      <blockquote className={styles.bubble}>
        <p className={styles.body}>{body}</p>
      </blockquote>
      <figcaption className={styles.meta}>{meta}</figcaption>
    </figure>
  );
}
