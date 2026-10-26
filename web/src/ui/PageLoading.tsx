/**
 * The line the shell shows where a page will be, while that page's code is still on its way.
 *
 * @packageDocumentation
 */
import styles from './PageLoading.module.css';

/**
 * "Loading the page…", in the muted style of the app's other loading lines. The router shows it only when the
 * browser opens a page whose code it has not loaded yet; moving between pages keeps the old one on screen until the
 * new one is ready. It appears after a short wait, so a fast load shows nothing at all.
 */
export function PageLoading() {
  return <p className={styles.loading}>Loading the page…</p>;
}
