/**
 * The catalogue's domain: branches, their trading hours, the services they offer and South Africa's
 * public holidays, as plain Java with no Spring dependency.
 *
 * <p>These are reference data. Flyway seeds them, nothing changes them at runtime, and they are
 * read as immutable records rather than JPA entities. The rules that combine them live in {@link
 * za.co.fynbos.branchbook.catalogue.domain.BranchCalendar} and {@link
 * za.co.fynbos.branchbook.catalogue.domain.PublicHolidayCalendar}, which other modules use to
 * decide on which days a branch takes appointments.
 */
package za.co.fynbos.branchbook.catalogue.domain;
