/**
 * The page's one `h1`, which also names the browser tab and takes focus when the page or step changes.
 *
 * @packageDocumentation
 */
import { useEffect, useRef, type ReactNode } from 'react';

import { useArrivedByNavigation } from './navigation';
import styles from './PageHeading.module.css';

/** What follows every tab title, as in "Step 3 of 5: Date and time - BranchBook". */
const TITLE_SUFFIX = ' - BranchBook';

/** Props for {@link PageHeading}. */
export interface PageHeadingProps {
  /** The heading, such as "Choose a date and time". */
  title: string;
  /** The tab title before " - BranchBook", when it differs from the heading: "Step 3 of 5: Date and time". */
  docTitle?: string;
  /**
   * A value that changes when the content under the heading is replaced in place, such as the booking step. Each
   * change after the first render moves focus to the heading, so a screen reader starts reading the new content.
   */
  focusKey?: string | number;
  /** A short paragraph under the heading, in the muted body style, as on the landing and privacy pages. */
  lead?: ReactNode;
}

/**
 * The page heading.
 *
 * It sets `document.title` to `${docTitle ?? title} - BranchBook`, and moves focus to itself (it is `tabIndex={-1}`,
 * so it takes focus without joining the tab order) in two cases: when it mounts after an in-app navigation, Back
 * included, so a new page or step is announced from its heading; and when {@link PageHeadingProps.focusKey} changes. On the first
 * page the browser loads, focus stays where the browser put it, so the skip link remains the first thing a
 * keyboard reaches. It needs a router, as every page has.
 */
export function PageHeading({ title, docTitle, focusKey, lead }: PageHeadingProps) {
  const heading = useRef<HTMLHeadingElement>(null);
  // Back to the first page loaded counts too: the control the customer last pressed went with the page they left.
  const arrivedByNavigation = useArrivedByNavigation();
  const previousKey = useRef(focusKey);

  const tabTitle = `${docTitle ?? title}${TITLE_SUFFIX}`;
  useEffect(() => {
    document.title = tabTitle;
  }, [tabTitle]);

  // On mount only: the location at mount decides, and later re-renders must not pull focus back.
  const focusOnMount = useRef(arrivedByNavigation);
  useEffect(() => {
    if (focusOnMount.current) {
      heading.current?.focus();
    }
  }, []);

  useEffect(() => {
    if (previousKey.current !== focusKey) {
      previousKey.current = focusKey;
      heading.current?.focus();
    }
  }, [focusKey]);

  const h1 = (
    <h1 ref={heading} tabIndex={-1} className={styles.heading}>
      {title}
    </h1>
  );

  if (lead === undefined) {
    return h1;
  }
  return (
    <div className={styles.group}>
      {h1}
      <p className={styles.lead}>{lead}</p>
    </div>
  );
}
