/**
 * The page for an address the app has no route for. It is not in the mockups, so it keeps to their parts: a
 * heading, a calm sentence, and the two ways on.
 *
 * @packageDocumentation
 */
import { Link } from 'react-router';

import { ButtonLink } from '../ui/Button';
import { PageHeading } from '../ui/PageHeading';
import styles from './NotFoundPage.module.css';

/** The not-found page, for any path no other route matches. */
export function NotFoundPage() {
  return (
    <>
      <PageHeading
        title="Page not found"
        lead="There is no page at this address. The link may be out of date, or the address may have a typing mistake."
      />
      <div className={styles.actions}>
        <ButtonLink to="/book" fullWidth>
          Book an appointment
        </ButtonLink>
        <p>
          Or go to the <Link to="/">BranchBook home page</Link>.
        </p>
      </div>
    </>
  );
}
