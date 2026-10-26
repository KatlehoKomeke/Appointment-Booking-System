/**
 * Joins class names, skipping the ones a condition turned off.
 *
 * @packageDocumentation
 */

/**
 * Joins class names into one `className`, skipping `false`, `null`, `undefined` and empty strings.
 *
 * A CSS Module's `styles.foo` is `string | undefined` under `noUncheckedIndexedAccess`, so it can be passed as is.
 *
 * @param classes - class names, or a falsy value where a condition is off: `cx(styles.tile, chosen && styles.chosen)`
 * @returns the names separated by single spaces
 */
export function cx(...classes: (string | false | null | undefined)[]): string {
  return classes
    .filter((name): name is string => typeof name === 'string' && name !== '')
    .join(' ');
}
