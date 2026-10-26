/**
 * The layout every page renders in: the skip link, the fictional-bank banner, the header, the page and the footer.
 *
 * @packageDocumentation
 */
import { useRef, type MouseEvent } from 'react';
import { Link, NavLink, Outlet, ScrollRestoration } from 'react-router';

import { Badge } from './Badge';
import { LeafMark } from './LeafMark';
import { NavigatedContext, useTrackNavigation } from './navigation';
import styles from './Shell.module.css';

/**
 * The app shell. Its landmarks, in order: the banner saying the bank is fictional (`aside`, "About this site"), the
 * header (`banner`), `main`, and the footer (`contentinfo`) with its `nav` named "Footer".
 *
 * Each navigation starts at the top of the new page, and Back returns to where the customer was
 * (`ScrollRestoration`); the page's heading then takes focus (see `PageHeading`).
 */
export function Shell() {
  const main = useRef<HTMLElement>(null);
  const navigated = useTrackNavigation();

  // The link still points at #main for a browser without scripts; with them, focus moves without a router
  // navigation to the hash.
  function skipToMain(event: MouseEvent<HTMLAnchorElement>) {
    event.preventDefault();
    main.current?.focus();
  }

  return (
    <div className={styles.shell}>
      <a href="#main" className={styles.skip} onClick={skipToMain}>
        Skip to main content
      </a>

      <aside aria-label="About this site" className={styles.banner}>
        <p className={styles.inner}>
          <strong>Fynbos Bank is fictional.</strong> BranchBook is an engineering assessment, not a
          real bank&apos;s service. The hosted demo texts the number you give, so use only your own;
          the demo numbers 082 555 0000 to 082 555 0199 are never texted.
        </p>
      </aside>

      <header className={styles.header}>
        <div className={styles.headerInner}>
          <Link to="/" className={styles.brand}>
            <LeafMark className={styles.mark} />
            {/* The space keeps the link's name "BranchBook by Fynbos Bank"; the flex gap does the spacing. */}
            <span className={styles.name}>BranchBook</span>{' '}
            <span className={styles.by}>by Fynbos Bank</span>
          </Link>
          <Badge tone="demo" className={styles.demo}>
            Demo
          </Badge>
        </div>
      </header>

      <main id="main" ref={main} tabIndex={-1} className={styles.main}>
        <NavigatedContext value={navigated}>
          <Outlet />
        </NavigatedContext>
      </main>

      <footer className={styles.footer}>
        <div className={styles.inner}>
          <nav aria-label="Footer">
            <ul className={styles.footerLinks}>
              <li>
                <NavLink to="/messages" className={styles.footerLink}>
                  Simulated inbox
                </NavLink>
              </li>
              <li>
                <NavLink to="/privacy" className={styles.footerLink}>
                  Privacy
                </NavLink>
              </li>
            </ul>
          </nav>
          <p className={styles.footerNote}>
            Fynbos Bank is fictional. Branch hours are representative, not official.
          </p>
        </div>
      </footer>

      <ScrollRestoration />
    </div>
  );
}
