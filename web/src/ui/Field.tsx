/**
 * A labelled text input with an optional hint and error, wired for screen readers.
 *
 * @packageDocumentation
 */
import type { ComponentPropsWithRef, ReactNode } from 'react';

import { cx } from './cx';
import styles from './Field.module.css';
import { AlertIcon } from './icons';

/** Props for {@link Field}: its words, and everything an `<input>` takes, `ref` included. */
export interface FieldProps extends Omit<ComponentPropsWithRef<'input'>, 'id'> {
  /** The input's id; the label points at it, and the hint and error ids are made from it. */
  id: string;
  /** The visible label, such as "Cellphone number". */
  label: ReactNode;
  /** Help shown between the label and the input, such as "We SMS your confirmation to this number". */
  hint?: ReactNode;
  /**
   * What is wrong, shown under the input. While it is set, the input is marked invalid and has a 2 px accent
   * border. Pass the copy, never the server's `detail`.
   */
  error?: ReactNode;
}

function isShown(node: ReactNode) {
  return node !== undefined && node !== null && node !== false && node !== '';
}

/**
 * A text field: the label, the hint above the input, the input, and the error below it.
 *
 * The input is described by the hint and the error (`aria-describedby`), and marked `aria-invalid` while there is
 * an error, so a screen reader reads both when the input is focused. The error is not a live region: a field is
 * checked when it is left, and react-hook-form moves focus to the first invalid field on submit, where it is read.
 * `className` styles the wrapper; every other prop, `ref` included, goes to the input, so react-hook-form's
 * `register` can be spread onto it.
 */
export function Field({
  id,
  label,
  hint,
  error,
  className,
  'aria-describedby': describedBy,
  ...inputProps
}: FieldProps) {
  const hintId = isShown(hint) ? `${id}-hint` : undefined;
  const errorId = isShown(error) ? `${id}-error` : undefined;
  const description = [describedBy, hintId, errorId].filter(Boolean).join(' ');
  const invalid = errorId !== undefined;

  return (
    <div className={cx(styles.field, className)}>
      <label htmlFor={id} className={styles.label}>
        {label}
      </label>
      {hintId !== undefined && (
        <p id={hintId} className={styles.hint}>
          {hint}
        </p>
      )}
      <input
        {...inputProps}
        id={id}
        className={cx(styles.input, invalid && styles.invalid)}
        aria-invalid={invalid ? true : undefined}
        aria-describedby={description === '' ? undefined : description}
      />
      {errorId !== undefined && (
        <p id={errorId} className={styles.error}>
          <AlertIcon size={18} className={styles.errorIcon} />
          <span>{error}</span>
        </p>
      )}
    </div>
  );
}
