/**
 * The CAPTCHA controller: the one place the app shows the firewall's puzzle.
 *
 * It registers itself with the API client (`registerCaptchaSolver`), so that when WAF challenges a booking or
 * cancel POST the client asks it to have the puzzle solved, and then sends the same request again (`captcha.ts`).
 * The screens that book and cancel need no code of their own: a check that is not solved reaches them as a
 * `CaptchaError`, which `problemMessage` words like any other failure.
 *
 * @packageDocumentation
 */
import { useQueryClient } from '@tanstack/react-query';
import { type ReactNode, useCallback, useEffect, useRef, useState } from 'react';

import { CaptchaError, registerCaptchaSolver } from '../api/captcha';
import { type CaptchaSettings, captchaSettings } from '../api/clientConfig';
import { CaptchaDialog, type CheckOutcome } from './CaptchaDialog';

/** A check on screen: its settings, and how to end it. */
interface OpenCheck {
  settings: CaptchaSettings;
  settle: (outcome: CheckOutcome, cause?: unknown) => void;
}

/** What a screen reader hears once a check ends, beside the screen's own alert when it failed. */
const ANNOUNCEMENTS: Record<CheckOutcome, string> = {
  solved: 'Check complete. Carrying on.',
  cancelled: 'Check closed. Nothing was sent.',
  failed: 'The check did not work. Nothing was sent.',
  unavailable: 'The check could not be loaded. Nothing was sent.',
};

/** Props for {@link CaptchaProvider}. */
export interface CaptchaProviderProps {
  /** The app. */
  children: ReactNode;
}

/**
 * Shows the CAPTCHA when the API client asks for it, around the whole app. It needs the API cache, from which it
 * reads the client settings (`captchaSettings`), and so sits inside the `QueryClientProvider`.
 *
 * When a challenge arrives it reads the settings: with no CAPTCHA configured it answers at once that there is none,
 * and the 405 stands. Otherwise it opens {@link CaptchaDialog} and settles when the dialog does. Requests challenged
 * while a check is open wait for that same check rather than opening another.
 *
 * Accessibility: while the check is open, the app behind it is `inert`, so nothing there can be focused, clicked
 * or read; focus goes back on closing to the element that had it when the check opened, such as Confirm; and a
 * polite status region, outside the inert app, says how the check ended. That region is added, empty, when the first
 * check opens, so the outcome is announced, and a page that never meets a check has no extra status region.
 */
export function CaptchaProvider({ children }: CaptchaProviderProps) {
  const queryClient = useQueryClient();
  const [check, setCheck] = useState<OpenCheck>();
  const [announcement, setAnnouncement] = useState<string>();
  const inFlight = useRef<Promise<boolean>>(undefined);
  const returnFocus = useRef<HTMLElement>(null);

  const solve = useCallback((): Promise<boolean> => {
    const run = async (): Promise<boolean> => {
      const settings = await captchaSettings(queryClient);
      if (settings === null) {
        return false;
      }
      const active = document.activeElement;
      returnFocus.current = active instanceof HTMLElement ? active : null;
      setAnnouncement('');
      const { outcome, cause } = await new Promise<{ outcome: CheckOutcome; cause?: unknown }>(
        (resolve) => {
          setCheck({
            settings,
            settle: (ended, error) => {
              resolve({ outcome: ended, cause: error });
            },
          });
        },
      );
      setCheck(undefined);
      setAnnouncement(ANNOUNCEMENTS[outcome]);
      if (outcome !== 'solved') {
        throw new CaptchaError(outcome, { cause });
      }
      return true;
    };
    inFlight.current ??= run().finally(() => {
      inFlight.current = undefined;
    });
    return inFlight.current;
  }, [queryClient]);

  useEffect(() => registerCaptchaSolver(solve), [solve]);

  // Focus goes back where it was once the dialog has gone and the app is no longer inert, unless that element went
  // with the check, in which case the screen moves focus itself.
  useEffect(() => {
    if (check !== undefined) {
      return undefined;
    }
    const target = returnFocus.current;
    returnFocus.current = null;
    if (target?.isConnected === true) {
      target.focus();
    }
    return undefined;
  }, [check]);

  // An open check ends as closed if the app goes away under it, so the request waiting on it does not wait forever.
  // When a check closes normally this runs too, and does nothing: it has settled, and only its first ending counts.
  useEffect(() => {
    if (check === undefined) {
      return undefined;
    }
    return () => {
      check.settle('cancelled');
    };
  }, [check]);

  return (
    <>
      <div inert={check !== undefined}>{children}</div>
      {announcement !== undefined && (
        <p role="status" className="visually-hidden">
          {announcement}
        </p>
      )}
      {check !== undefined && <CaptchaDialog settings={check.settings} onSettle={check.settle} />}
    </>
  );
}
