/**
 * JSON in the tab's `sessionStorage`, which never fails the page.
 *
 * Storage can be missing or refuse every call (a private window, blocked site data, a full quota), and a value can
 * be left over from an older version of the app. Each function here catches that and behaves as if nothing were
 * stored, so the booking still works, only without surviving a refresh. Values are read back as `unknown`: callers
 * check the shape before trusting it.
 *
 * @packageDocumentation
 */

function sessionStore(): Storage | undefined {
  try {
    return globalThis.sessionStorage;
  } catch {
    // Reading the property itself throws when the browser blocks site data.
    return undefined;
  }
}

/**
 * Reads a value stored with {@link setJson}.
 *
 * @param key - the storage key
 * @returns the parsed value, or `undefined` when nothing readable is stored
 */
export function getJson(key: string): unknown {
  try {
    const text = sessionStore()?.getItem(key);
    return text === null || text === undefined ? undefined : (JSON.parse(text) as unknown);
  } catch {
    return undefined;
  }
}

/**
 * Stores a value as JSON.
 *
 * @param key - the storage key
 * @param value - anything `JSON.stringify` accepts
 * @returns whether it was stored
 */
export function setJson(key: string, value: unknown): boolean {
  try {
    const store = sessionStore();
    if (store === undefined) {
      return false;
    }
    store.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

/**
 * Removes a stored value; removing one that is not there is fine.
 *
 * @param key - the storage key
 */
export function removeJson(key: string): void {
  try {
    sessionStore()?.removeItem(key);
  } catch {
    // Nothing could have been stored, so there is nothing to remove.
  }
}
