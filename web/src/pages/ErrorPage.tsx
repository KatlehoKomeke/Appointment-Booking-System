/**
 * The page shown when a page fails to render. Without it the router shows its own developer error page, which is
 * not the design and names the error to the customer.
 *
 * @packageDocumentation
 */
import { Link } from 'react-router';

import { messageForCode } from '../api/problemCopy';
import { ButtonLink } from '../ui/Button';
import { PageHeading } from '../ui/PageHeading';
import styles from './ErrorPage.module.css';

/** The error page, inside the shell, so the fictional-bank banner and the footer stay on screen. */
export function ErrorPage() {
  const { title, body } = messageForCode('INTERNAL_ERROR');
  return (
    <>
      <PageHeading title={title} lead={body} />
      <div className={styles.actions}>
        <ButtonLink to="/book" fullWidth reloadDocument>
          Start a booking again
        </ButtonLink>
        <p>
          Or go to the <Link to="/">BranchBook home page</Link>.
        </p>
      </div>
    </>
  );
}
