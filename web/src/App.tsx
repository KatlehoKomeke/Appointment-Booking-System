/**
 * The app shell: the fictional-bank notice, the header and the page content.
 *
 * Today the content is a placeholder. The booking flow replaces it, behind the same shell, as it is built.
 *
 * @packageDocumentation
 */

/**
 * The whole app.
 *
 * The fictional-bank notice comes first in the page, so a screen reader announces it before anything else and no
 * visitor can mistake the site for a real bank's.
 */
export function App() {
  return (
    <>
      <aside className="notice" aria-label="About this site">
        <p>
          <strong>Fynbos Bank is fictional.</strong> BranchBook is an engineering assessment, not a
          real bank&apos;s service. No real messages are sent.
        </p>
      </aside>
      <header className="header">
        <p className="brand">
          BranchBook <span className="brand-owner">by Fynbos Bank</span>
        </p>
      </header>
      <main className="main">
        <h1>Book a branch appointment</h1>
        <p>
          Choose a branch, a service and a time that suits you, and get an SMS confirmation in
          seconds.
        </p>
        <p className="placeholder">This page is a placeholder while the booking flow is built.</p>
      </main>
    </>
  );
}
