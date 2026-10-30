/**
 * The CAPTCHA check: a modal dialog that loads AWS's puzzle and says how it ended.
 *
 * `CaptchaProvider` opens it when the firewall challenges a request, and closes it when it settles. It is not drawn
 * in the mockups, so it keeps to the cancel step's parts and words: a card, a heading that takes focus, and a
 * secondary button that backs out, as Escape does.
 *
 * @packageDocumentation
 */
import { useEffect, useId, useRef, useState } from 'react';

import type { CaptchaFailure } from '../api/captcha';
import type { CaptchaSettings } from '../api/clientConfig';
import { Button } from '../ui/Button';
import { loadCaptchaScript } from './awsWafCaptcha';
import styles from './CaptchaDialog.module.css';

/** How a check ended: solved, or one of the ways it can fail. */
export type CheckOutcome = 'solved' | CaptchaFailure;

/** Props for {@link CaptchaDialog}. */
export interface CaptchaDialogProps {
  /** Where to load the puzzle from, and its key. */
  settings: CaptchaSettings;
  /**
   * Called when the check ends: solved, closed by the customer, failed by the puzzle, or unable to load. Only the
   * first call counts.
   *
   * @param outcome - how it ended
   * @param cause - the underlying error, when there was one
   */
  onSettle: (outcome: CheckOutcome, cause?: unknown) => void;
}

/**
 * The dialog. AWS's puzzle is rendered into it only once its container is in the document and the script has
 * loaded (`loadCaptchaScript`).
 *
 * Accessibility: it is a `role="dialog"` with `aria-modal`, named by its heading and described by its first
 * sentence. A native `<dialog>` with `showModal()` was not used because jsdom, where the tests run, does not
 * implement it; `CaptchaProvider` makes the page behind `inert` instead, which keeps focus, clicks and a screen
 * reader's reading inside the dialog, as `showModal()` would.
 */
export function CaptchaDialog({ settings, onSettle }: CaptchaDialogProps) {
  const headingId = useId();
  const leadId = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  const puzzle = useRef<HTMLDivElement>(null);
  const [ready, setReady] = useState(false);

  // The latest callback, so the puzzle is never drawn twice just because the parent re-rendered.
  const settle = useRef(onSettle);
  useEffect(() => {
    settle.current = onSettle;
  }, [onSettle]);

  // Focus starts on the heading (tabIndex -1, so it is not a tab stop), as on the cancel step: a screen reader reads
  // the dialog's name and what it asks, and the next Tab reaches the puzzle. Where focus goes back to on closing is
  // the provider's job, since this dialog is gone by then.
  useEffect(() => {
    heading.current?.focus();
  }, []);

  // Escape closes the check from anywhere. The listener is on the document rather than the dialog because the page
  // behind is inert, so any key pressed is meant for the check, even when focus has fallen to the body.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        settle.current('cancelled');
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
    };
  }, []);

  const { scriptUrl, apiKey } = settings;
  useEffect(() => {
    const container = puzzle.current;
    if (container === null) {
      return undefined;
    }
    // Aborted once this effect is cleaned up, so a puzzle that loads after the dialog closed is never drawn, and a
    // callback from one that was removed is ignored.
    const closed = new AbortController();
    void (async () => {
      let captcha;
      try {
        captcha = await loadCaptchaScript(scriptUrl);
      } catch (error) {
        if (!closed.signal.aborted) {
          settle.current('unavailable', error);
        }
        return;
      }
      if (closed.signal.aborted) {
        return;
      }
      try {
        captcha.renderCaptcha(container, {
          apiKey,
          onSuccess: () => {
            if (!closed.signal.aborted) {
              settle.current('solved');
            }
          },
          onError: (error) => {
            if (!closed.signal.aborted) {
              settle.current('failed', error);
            }
          },
        });
        setReady(true);
      } catch (error) {
        settle.current('failed', error);
      }
    })();
    return () => {
      closed.abort();
      container.replaceChildren();
    };
  }, [scriptUrl, apiKey]);

  return (
    <div className={styles.backdrop}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={headingId}
        aria-describedby={leadId}
        className={styles.dialog}
      >
        <h2 id={headingId} ref={heading} tabIndex={-1} className={styles.heading}>
          A quick check first
        </h2>
        <p id={leadId} className={styles.lead}>
          To keep out automated requests, please solve the puzzle below. Nothing has been sent yet;
          we carry on as soon as it is solved.
        </p>
        {/*
          A polite status region, on the page from the start, so a screen reader hears when the puzzle is ready to
          be reached with Tab. How the check ended is said by the provider, since this dialog is gone by then.
        */}
        <p role="status" className={styles.status}>
          {ready ? 'The puzzle is ready.' : 'Loading the puzzle…'}
        </p>
        <div ref={puzzle} className={styles.puzzle} />
        <Button
          variant="secondary"
          fullWidth
          // "Close" is completed for a screen reader; the visible word starts the name, as it must.
          aria-label="Close the check"
          onClick={() => {
            settle.current('cancelled');
          }}
        >
          Close
        </Button>
      </div>
    </div>
  );
}
