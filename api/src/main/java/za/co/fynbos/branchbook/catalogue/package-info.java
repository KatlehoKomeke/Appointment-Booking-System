/**
 * The {@code catalogue} module: branches, the services they offer, their trading hours and
 * capacities, and South Africa's public holidays.
 *
 * <p>All of it is reference data, seeded by Flyway and read-only at runtime. The module answers two
 * kinds of question: the API's "which branches are there, and what does this one offer?" ({@code
 * GET /api/v1/branches}), and the other modules' "on which days, and between which hours, does this
 * branch take appointments?" ({@link
 * za.co.fynbos.branchbook.catalogue.application.BranchCalendarProvider}).
 *
 * <p>It depends only on {@code shared}. Scheduling and booking depend on it; it never depends on
 * them (docs/architecture.md, section 3).
 */
package za.co.fynbos.branchbook.catalogue;
