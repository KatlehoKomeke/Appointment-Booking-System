/**
 * A failed request as the customer reads it: the copy for its code (`problemCopy.ts`) in an error alert, with Try
 * again when asking once more could help. Every screen shows a failed load this one way.
 *
 * @packageDocumentation
 */
import { problemMessage } from '../api/problemCopy';
import { Alert } from './Alert';
import { Button } from './Button';

/** Props for {@link ProblemAlert}. */
export interface ProblemAlertProps {
  /** What the request threw, as TanStack Query passes it on. */
  error: unknown;
  /** Asks again; without it the alert has no Try again button. */
  onRetry?: () => void;
  /** Extra classes, such as the screen's spacing. */
  className?: string;
}

/** An error alert worded by the failure's code, with an optional Try again. */
export function ProblemAlert({ error, onRetry, className }: ProblemAlertProps) {
  const message = problemMessage(error);
  return (
    <Alert
      tone="error"
      title={message.title}
      className={className}
      actions={
        onRetry === undefined ? undefined : (
          <Button variant="secondary" size="compact" onClick={onRetry}>
            Try again
          </Button>
        )
      }
    >
      {message.body}
    </Alert>
  );
}
