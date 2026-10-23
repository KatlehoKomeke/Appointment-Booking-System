/**
 * The catalogue's use cases: read-only queries over branches, services and holidays, each in its
 * own read-only transaction.
 *
 * <p>{@link za.co.fynbos.branchbook.catalogue.application.BranchQueries} serves the API, and {@link
 * za.co.fynbos.branchbook.catalogue.application.BranchCalendarProvider} is what the scheduling and
 * booking modules use. Storage is reached only through {@link
 * za.co.fynbos.branchbook.catalogue.application.CatalogueReadPort}.
 */
package za.co.fynbos.branchbook.catalogue.application;
