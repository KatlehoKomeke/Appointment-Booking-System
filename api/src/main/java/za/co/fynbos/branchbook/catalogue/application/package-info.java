/**
 * The catalogue's use cases: read-only queries over branches, services and holidays, each in its
 * own read-only transaction.
 *
 * <p>{@link za.co.fynbos.branchbook.catalogue.application.BranchQueries} serves the API. {@link
 * za.co.fynbos.branchbook.catalogue.application.BranchCalendarProvider} is what the scheduling and
 * booking modules use to decide what can be booked, and {@link
 * za.co.fynbos.branchbook.catalogue.application.CatalogueLookup} what booking uses to show the
 * branch and service of an appointment. {@link
 * za.co.fynbos.branchbook.catalogue.application.HolidayCoverageCheck} warns before bookings outrun
 * the holiday data. Storage is reached only through {@link
 * za.co.fynbos.branchbook.catalogue.application.CatalogueReadPort}.
 */
package za.co.fynbos.branchbook.catalogue.application;
