/**
 * The {@code scheduling} module: which slots exist for a service at a branch, and the rules a
 * requested start must meet (BR-1 to BR-5, BR-21).
 *
 * <p>It depends on {@code catalogue} for the branch's hours and the service's duration, and on
 * {@code shared}. It owns no table: it reads bookings through a port the booking module implements,
 * so booking depends on scheduling and never the other way round (docs/architecture.md, section 3).
 */
package za.co.fynbos.branchbook.scheduling;
