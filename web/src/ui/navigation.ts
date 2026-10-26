/**
 * Whether the customer has moved within the app since the browser loaded it, so a page's heading knows to take
 * focus (`PageHeading`).
 *
 * The router gives the first location it loads the key "default", and only that one, so a key alone tells a new page
 * from the first. But Back to that first page brings "default" back, and its heading must still take focus then,
 * because the button the customer last pressed has gone with the page they left. The shell, which outlives every
 * page, remembers that a navigation has happened and passes it down.
 *
 * @packageDocumentation
 */
import { createContext, useContext, useState } from 'react';
import { useLocation } from 'react-router';

/** True once the app has shown a second location; false on the first page loaded, and outside the shell. */
export const NavigatedContext = createContext(false);

/**
 * For the shell: whether the location has changed since it mounted. Once true it stays true, Back included.
 */
export function useTrackNavigation(): boolean {
  const { key } = useLocation();
  const [firstKey] = useState(key);
  const [navigated, setNavigated] = useState(false);
  if (!navigated && key !== firstKey) {
    // Set during render, so the page that the navigation brings already sees it.
    setNavigated(true);
  }
  return navigated;
}

/**
 * Whether the page on screen was reached by moving within the app, Back and Forward included, rather than being the
 * first page the browser loaded.
 */
export function useArrivedByNavigation(): boolean {
  const { key } = useLocation();
  const navigated = useContext(NavigatedContext);
  return navigated || key !== 'default';
}
