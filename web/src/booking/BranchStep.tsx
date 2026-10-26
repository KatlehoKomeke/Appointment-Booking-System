/**
 * Step 1 of the booking: the branches, narrowed by a search as the customer types.
 *
 * Each branch is a link to `?branch=CODE`, not a button: choosing one goes to the next step, which is a new history
 * entry, so Back returns to the list.
 *
 * @packageDocumentation
 */
import { useId, useState } from 'react';
import { Link } from 'react-router';

import type { ProblemMessage } from '../api/problemCopy';
import { type BranchSummary, useBranches } from '../api/queries';
import { Alert } from '../ui/Alert';
import { Badge } from '../ui/Badge';
import { Card } from '../ui/Card';
import { Field } from '../ui/Field';
import { ChevronRightIcon } from '../ui/icons';
import { PageHeading } from '../ui/PageHeading';
import { ProblemAlert } from '../ui/ProblemAlert';
import { stepDocTitle, useBookingParams } from './bookingParams';
import styles from './BranchStep.module.css';

/** Props for {@link BranchStep}. */
export interface BranchStepProps {
  /**
   * Why the customer is back on this step, such as a branch code in the URL that the API does not know. It is shown
   * above the search, since the list under it is the answer.
   */
  problem?: ProblemMessage;
}

// Case, accents and spacing do not matter: "umhlanga", "UMHLANGA " and "Umhlanga" find the same branch.
function normalise(text: string): string {
  return text
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .trim()
    .toLowerCase();
}

/**
 * The branches whose name, suburb or city contains the search.
 *
 * @param branches - every branch
 * @param search - what the customer typed
 */
function matching(branches: readonly BranchSummary[], search: string): BranchSummary[] {
  const words = normalise(search).split(/\s+/).filter(Boolean);
  return branches.filter((branch) => {
    const haystack = normalise(`${branch.name} ${branch.suburb} ${branch.city}`);
    return words.every((word) => haystack.includes(word));
  });
}

function countText(count: number): string {
  if (count === 0) {
    return 'No branch matches that search. Try a branch name, suburb or city.';
  }
  return count === 1 ? '1 branch' : `${String(count)} branches`;
}

/** The branch step: a search field, a live count, and each branch as a link to the next step. */
export function BranchStep({ problem }: BranchStepProps) {
  const branchesQuery = useBranches();
  const [search, setSearch] = useState('');
  const { hrefFor } = useBookingParams();
  const listId = useId();

  const shown = branchesQuery.data === undefined ? [] : matching(branchesQuery.data, search);

  return (
    <div className={styles.step}>
      <PageHeading title="Choose a branch" docTitle={stepDocTitle('branch')} focusKey="branch" />

      {problem !== undefined && (
        <Alert tone="error" title={problem.title}>
          {problem.body}
        </Alert>
      )}

      <Field
        id="branch-search"
        label="Find a branch"
        type="search"
        placeholder="Branch name or city"
        autoComplete="off"
        spellCheck={false}
        enterKeyHint="search"
        aria-controls={listId}
        value={search}
        onChange={(event) => {
          setSearch(event.target.value);
        }}
      />

      {/* Present and empty while the list loads, so the count is announced as the search changes it. */}
      <p role="status" className={styles.count}>
        {branchesQuery.data === undefined ? '' : countText(shown.length)}
      </p>

      {branchesQuery.isPending && <p className={styles.muted}>Loading branches…</p>}

      {branchesQuery.isError && (
        <ProblemAlert
          error={branchesQuery.error}
          onRetry={() => {
            void branchesQuery.refetch();
          }}
        />
      )}

      <ul id={listId} className={styles.list} aria-label="Branches">
        {shown.map((branch) => (
          <Card as="li" key={branch.code} shadow flush>
            <Link to={hrefFor({ branch: branch.code })} className={styles.branch}>
              <span className={styles.details}>
                <span className={styles.name}>{branch.name}</span>
                <span className={styles.place}>
                  {branch.suburb}, {branch.city}
                </span>
                <span className={styles.chips}>
                  <Badge tone="neutral">{branch.province}</Badge>
                  {/* A chip like the province, so the label size, not a status badge's. */}
                  {branch.opensSunday && (
                    <Badge tone="sent" size="label">
                      Open Sundays
                    </Badge>
                  )}
                </span>
              </span>
              <ChevronRightIcon className={styles.chevron} />
            </Link>
          </Card>
        ))}
      </ul>
    </div>
  );
}
