/**
 * The app's icons, drawn inline so they need no image request and take their colour from the text around them.
 *
 * Every icon is decorative: hidden from screen readers, because the words beside it say the same thing. The one
 * control that shows only an icon, the date pager, carries its own label.
 *
 * @packageDocumentation
 */
import type { ReactNode } from 'react';

/** Props every icon takes. */
export interface IconProps {
  /** The rendered width and height, in px. Defaults to 20. */
  size?: number;
  /** A class for placement, such as a margin. The colour comes from `color` (`currentColor`). */
  className?: string;
}

/** The shared frame: a 24-unit square, stroked in the current text colour. */
function Icon({ size = 20, className, children }: IconProps & { children: ReactNode }) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      className={className}
    >
      {children}
    </svg>
  );
}

/** A tick: sent, done, copied. */
export function CheckIcon({ size, className }: IconProps) {
  return (
    <Icon size={size} className={className}>
      <path d="M5 12.5l4.5 4.5L19 7.5" />
    </Icon>
  );
}

/** A cross: failed. */
export function CrossIcon({ size, className }: IconProps) {
  return (
    <Icon size={size} className={className}>
      <path d="M6.5 6.5l11 11M17.5 6.5l-11 11" />
    </Icon>
  );
}

/** A chevron pointing left: back, the previous seven days. */
export function ChevronLeftIcon({ size, className }: IconProps) {
  return (
    <Icon size={size} className={className}>
      <path d="M14.5 5.5L8 12l6.5 6.5" />
    </Icon>
  );
}

/** A chevron pointing right: a link card, the next seven days. */
export function ChevronRightIcon({ size, className }: IconProps) {
  return (
    <Icon size={size} className={className}>
      <path d="M9.5 5.5L16 12l-6.5 6.5" />
    </Icon>
  );
}

/** Two overlapping sheets: copy the reference. */
export function CopyIcon({ size, className }: IconProps) {
  return (
    <Icon size={size} className={className}>
      <rect x="9" y="9" width="11" height="11" rx="2" />
      <path d="M15 9V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1h4" />
    </Icon>
  );
}

/** An exclamation mark in a circle: an error. */
export function AlertIcon({ size, className }: IconProps) {
  return (
    <Icon size={size} className={className}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7.5v5.5M12 16.5h.01" />
    </Icon>
  );
}

/** An "i" in a circle: information that never changes, such as the inbox banner. */
export function InfoIcon({ size, className }: IconProps) {
  return (
    <Icon size={size} className={className}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5.5M12 7.5h.01" />
    </Icon>
  );
}

/** A clock: times that may have changed. */
export function ClockIcon({ size, className }: IconProps) {
  return (
    <Icon size={size} className={className}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </Icon>
  );
}

/** A cellphone. */
export function PhoneIcon({ size, className }: IconProps) {
  return (
    <Icon size={size} className={className}>
      <rect x="7" y="2.5" width="10" height="19" rx="2" />
      <path d="M11 18.5h2" />
    </Icon>
  );
}

/** A speech bubble: an SMS. */
export function MessageIcon({ size, className }: IconProps) {
  return (
    <Icon size={size} className={className}>
      <path d="M4.5 5h15v11h-10l-5 4z" />
    </Icon>
  );
}

/** An open circle: a message still being sent. */
export function PendingIcon({ size, className }: IconProps) {
  return (
    <Icon size={size} className={className}>
      <path d="M19 12a7 7 0 1 1-2.05-4.95" />
    </Icon>
  );
}

/** A calendar with a cross: a date the branch is closed. */
export function CalendarClosedIcon({ size, className }: IconProps) {
  return (
    <Icon size={size} className={className}>
      <rect x="3.5" y="5" width="17" height="15.5" rx="2" />
      <path d="M3.5 10h17M8 3v4M16 3v4M10 13l4 4.5M14 13l-4 4.5" />
    </Icon>
  );
}
