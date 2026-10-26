/**
 * The booking's place in its five steps, held in the `/book` URL so Back and a refresh land on the same step.
 *
 * The query string holds the choices, never the customer's details: `branch` (a branch code), `service` (a service
 * code), `date` (`YYYY-MM-DD`), `slot` (the start, `HH:MM` in SAST) and `step` (`details` or `review`). The step on
 * screen is derived from them, so a URL that skips ahead, say a `step=review` without a time, opens the first step
 * still missing an answer.
 *
 * @packageDocumentation
 */
import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';

/** The five booking steps, in order. */
export type BookingStepId = 'branch' | 'service' | 'datetime' | 'details' | 'review';

/** The steps in the order the customer meets them. */
export const BOOKING_STEPS: readonly BookingStepId[] = [
  'branch',
  'service',
  'datetime',
  'details',
  'review',
];

/** Each step's number, as the stepper shows it: "Step 3 of 5". */
export const STEP_NUMBER: Readonly<Record<BookingStepId, number>> = {
  branch: 1,
  service: 2,
  datetime: 3,
  details: 4,
  review: 5,
};

/** Each step's short name, for the stepper's Back link ("Back to branch") and the page title. */
export const STEP_LABEL: Readonly<Record<BookingStepId, string>> = {
  branch: 'Branch',
  service: 'Service',
  datetime: 'Date and time',
  details: 'Your details',
  review: 'Review',
};

/**
 * A step's tab title, before " - BranchBook", so every step names the tab the same way.
 *
 * @example `stepDocTitle('datetime')` is "Step 3 of 5: Date and time"
 * @param step - the step on screen
 */
export function stepDocTitle(step: BookingStepId): string {
  return `Step ${String(STEP_NUMBER[step])} of ${String(BOOKING_STEPS.length)}: ${STEP_LABEL[step]}`;
}

/** The value of the `step` parameter; the first three steps need none. */
export type StepParam = 'details' | 'review';

/**
 * A change to the booking's parameters. A key that is absent stays as it is; `null` removes it.
 *
 * Changing a choice clears the choices that depend on it: a new branch clears the service, date, time and step; a
 * new service clears the date, time and step; a new date clears the time and step. A new time keeps the step, so
 * choosing one of the alternatives on the review step stays there.
 */
export interface BookingParamChanges {
  /** A branch code, such as `SAN`. */
  branch?: string | null;
  /** A service code, such as `FICA`. */
  service?: string | null;
  /** `YYYY-MM-DD`. */
  date?: string | null;
  /** `HH:MM`, such as `09:30`. */
  slot?: string | null;
  /** `details` or `review`. */
  step?: StepParam | null;
}

/** Options for {@link BookingParams.setParams}. */
export interface SetParamsOptions {
  /** Replace the current history entry instead of adding one, so Back skips it. */
  replace?: boolean;
}

/** What {@link useBookingParams} returns. */
export interface BookingParams {
  /** The chosen branch code. */
  branch: string | undefined;
  /** The chosen service code. */
  service: string | undefined;
  /** The chosen date, `YYYY-MM-DD`; a malformed one reads as not chosen. */
  date: string | undefined;
  /** The chosen start, `HH:MM`; a malformed one reads as not chosen. */
  slot: string | undefined;
  /** The `step` parameter as the URL has it. */
  stepParam: StepParam | undefined;
  /** The step to show, derived from the choices made so far. */
  step: BookingStepId;
  /** Changes the parameters, adding a history entry unless `replace` is asked. */
  setParams: (changes: BookingParamChanges, options?: SetParamsOptions) => void;
  /** Goes to a step, keeping the earlier choices; going back to a choice clears it and what depends on it. */
  goToStep: (step: BookingStepId) => void;
  /** The `/book` link that {@link BookingParams.setParams} would navigate to, for an Edit or Change link. */
  hrefFor: (changes: BookingParamChanges) => string;
  /** The `/book` link that {@link BookingParams.goToStep} would navigate to. */
  hrefForStep: (step: BookingStepId) => string;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const SLOT = /^([01]\d|2[0-3]):[0-5]\d$/;

/** The parameters in their URL order, which is also the order in which they depend on each other. */
const ORDER = ['branch', 'service', 'date', 'slot', 'step'] as const;

type ParamName = (typeof ORDER)[number];

/** The choices that later parameters depend on. */
const CHOICES = ['branch', 'service', 'date'] as const;

/**
 * Reads the booking's parameters from a query string.
 *
 * @param search - the query string's parameters
 */
export function readBookingParams(search: URLSearchParams) {
  const value = (name: ParamName) => {
    const text = search.get(name)?.trim();
    return text === undefined || text === '' ? undefined : text;
  };
  const date = value('date');
  const slot = value('slot');
  const stepText = value('step');
  const params = {
    branch: value('branch'),
    service: value('service'),
    date: date !== undefined && DATE.test(date) ? date : undefined,
    slot: slot !== undefined && SLOT.test(slot) ? slot : undefined,
    stepParam: stepText === 'details' || stepText === 'review' ? stepText : undefined,
  } satisfies Omit<BookingParams, 'step' | 'setParams' | 'goToStep' | 'hrefFor' | 'hrefForStep'>;
  return { ...params, step: deriveStep(params) };
}

/** What {@link deriveStep} reads: the choices made so far. */
export interface StepInputs {
  /** The branch code. */
  branch?: string | undefined;
  /** The service code. */
  service?: string | undefined;
  /** `YYYY-MM-DD`. */
  date?: string | undefined;
  /** `HH:MM`. */
  slot?: string | undefined;
  /** The `step` parameter. */
  stepParam?: StepParam | undefined;
}

/**
 * The step to show: the first one whose answer is missing, or the one the URL names once every choice is made.
 *
 * @param params - the parameters read from the URL
 */
export function deriveStep(params: StepInputs): BookingStepId {
  if (params.branch === undefined) {
    return 'branch';
  }
  if (params.service === undefined) {
    return 'service';
  }
  if (params.date === undefined || params.slot === undefined || params.stepParam === undefined) {
    return 'datetime';
  }
  return params.stepParam;
}

/**
 * Applies a change to a query string, clearing what depends on a changed choice.
 *
 * @param current - the current parameters
 * @param changes - what to change
 * @returns the new parameters; other parameters in `current` are kept
 */
export function applyBookingChanges(
  current: URLSearchParams,
  changes: BookingParamChanges,
): URLSearchParams {
  const next = new URLSearchParams(current);
  // The earliest choice the change alters invalidates every later parameter, unless the change sets it too. A new
  // time is not in this list: it keeps the step.
  const altered = CHOICES.findIndex(
    (name) => name in changes && (changes[name] ?? null) !== current.get(name),
  );
  if (altered !== -1) {
    for (const name of ORDER.slice(altered + 1)) {
      if (!(name in changes)) {
        next.delete(name);
      }
    }
  }
  for (const name of ORDER) {
    if (name in changes) {
      const value = changes[name];
      if (value === undefined || value === null) {
        next.delete(name);
      } else {
        next.set(name, value);
      }
    }
  }
  return next;
}

// The change that goToStep makes for each step.
function stepChanges(step: BookingStepId): BookingParamChanges {
  switch (step) {
    case 'branch':
      return { branch: null };
    case 'service':
      return { service: null };
    case 'datetime':
      return { step: null };
    case 'details':
    case 'review':
      return { step };
  }
}

function href(search: URLSearchParams): string {
  const query = search.toString();
  return query === '' ? '/book' : `/book?${query}`;
}

/**
 * Reads and changes the booking's parameters in the `/book` URL.
 *
 * @example
 * ```tsx
 * const { step, branch, setParams } = useBookingParams();
 * setParams({ branch: 'SAN' }); // ?branch=SAN, which opens the service step
 * ```
 */
export function useBookingParams(): BookingParams {
  const [search, setSearch] = useSearchParams();
  const params = useMemo(() => readBookingParams(search), [search]);

  const setParams = useCallback(
    (changes: BookingParamChanges, options: SetParamsOptions = {}) => {
      setSearch((current) => applyBookingChanges(current, changes), {
        replace: options.replace ?? false,
      });
    },
    [setSearch],
  );

  const goToStep = useCallback(
    (step: BookingStepId) => {
      setParams(stepChanges(step));
    },
    [setParams],
  );

  const hrefFor = useCallback(
    (changes: BookingParamChanges) => href(applyBookingChanges(search, changes)),
    [search],
  );

  const hrefForStep = useCallback((step: BookingStepId) => hrefFor(stepChanges(step)), [hrefFor]);

  return { ...params, setParams, goToStep, hrefFor, hrefForStep };
}
