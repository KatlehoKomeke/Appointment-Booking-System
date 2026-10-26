/**
 * The BranchBook leaf mark: a fynbos bud, a leaf rising out of a cupped flower.
 *
 * @packageDocumentation
 */
import { cx } from './cx';
import styles from './LeafMark.module.css';

/** Props for {@link LeafMark}. */
export interface LeafMarkProps {
  /** The rendered height, in px; the width follows. Defaults to 24. */
  size?: number;
  /** A class for placement. */
  className?: string;
}

/**
 * The leaf mark. It is decorative, so it is hidden from screen readers; the wordmark beside it names the product.
 */
export function LeafMark({ size = 24, className }: LeafMarkProps) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      aria-hidden="true"
      focusable="false"
      className={cx(styles.mark, className)}
    >
      <path
        className={styles.cup}
        d="M4 15.5C4 12.5 5.2 10.2 7 8.5L12 13l5-4.5c1.8 1.7 3 4 3 7a8 8 0 0 1-16 0z"
      />
      <path className={styles.leaf} d="M12 1.5c2 2 2.2 6 0 9-2.2-3-2-7 0-9z" />
    </svg>
  );
}
