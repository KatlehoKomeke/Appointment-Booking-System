/**
 * The booking flow at `/book`: five steps on one route, the step on screen derived from the URL's query string.
 *
 * The page draws the stepper and the step; steps 3 and 4 draw the booking so far under their own heading. It also
 * corrects a URL that cannot be followed, since a shared link or a refresh can carry one: a review without the
 * details this tab saved shows the details step; a branch the API does not know shows the branch list; a service
 * the branch does not offer shows its services. Each correction says why, in the API's own copy for that refusal.
 *
 * @packageDocumentation
 */
import { useEffect, useMemo } from 'react';

import { ApiProblem } from '../api/client';
import { messageForCode, type ProblemMessage } from '../api/problemCopy';
import { useBranch } from '../api/queries';
import { type StepNumber, Stepper } from '../ui/Stepper';
import { type BookingStepId, STEP_LABEL, STEP_NUMBER, useBookingParams } from './bookingParams';
import { loadDetails } from './bookingSession';
import { BranchStep } from './BranchStep';
import { DateTimeStep } from './DateTimeStep';
import { DetailsStep } from './DetailsStep';
import { ReviewStep } from './ReviewStep';
import { ServiceStep } from './ServiceStep';

/** Each step's Back link: the step before it. Step 1's goes home, which the stepper does by itself. */
const PREVIOUS: Readonly<Record<BookingStepId, BookingStepId | undefined>> = {
  branch: undefined,
  service: 'branch',
  datetime: 'service',
  details: 'datetime',
  review: 'details',
};

// A code the API refuses: unknown (BRANCH_NOT_FOUND), or not a code at all, which breaks the path's pattern
// (VALIDATION_FAILED). Chosen by code, as every failure is; a 5xx stays the step's own retryable error.
function isUnknownBranch(error: unknown): boolean {
  return (
    error instanceof ApiProblem &&
    (error.code === 'BRANCH_NOT_FOUND' || error.code === 'VALIDATION_FAILED')
  );
}

/** The booking flow. */
export function BookPage() {
  const { step, branch, service, setParams, hrefForStep } = useBookingParams();
  const branchQuery = useBranch(branch);

  // The review needs the details this tab saved; a refreshed or shared link to it may have none. Read once per step,
  // not per render: a booking clears them on its way to the confirmation page, and must not be sent back here.
  const missingDetails = useMemo(() => step === 'review' && loadDetails() === undefined, [step]);
  useEffect(() => {
    if (missingDetails) {
      // Replace, so the URL says the step on screen and Back does not return to a review that cannot show.
      setParams({ step: 'details' }, { replace: true });
    }
  }, [missingDetails, setParams]);

  let shown: BookingStepId = missingDetails ? 'details' : step;
  let problem: ProblemMessage | undefined;
  const detail = branchQuery.data;
  if (shown !== 'branch' && isUnknownBranch(branchQuery.error)) {
    shown = 'branch';
    problem = messageForCode('BRANCH_NOT_FOUND');
  } else if (
    STEP_NUMBER[shown] > STEP_NUMBER.service &&
    detail !== undefined &&
    !detail.services.some((s) => s.code === service)
  ) {
    shown = 'service';
    problem = messageForCode('SERVICE_NOT_OFFERED_AT_BRANCH');
  }

  const previous = PREVIOUS[shown];

  return (
    <>
      <Stepper
        current={STEP_NUMBER[shown] as StepNumber}
        backTo={previous === undefined ? undefined : hrefForStep(previous)}
        backLabel={previous === undefined ? undefined : STEP_LABEL[previous]}
      />
      <StepContent step={shown} problem={problem} />
    </>
  );
}

function StepContent({ step, problem }: { step: BookingStepId; problem?: ProblemMessage }) {
  switch (step) {
    case 'branch':
      return <BranchStep problem={problem} />;
    case 'service':
      return <ServiceStep problem={problem} />;
    case 'datetime':
      return <DateTimeStep />;
    case 'details':
      return <DetailsStep />;
    case 'review':
      return <ReviewStep />;
  }
}
