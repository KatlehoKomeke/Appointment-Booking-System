/**
 * Scheduling's use cases, each in a read-only transaction: {@link
 * za.co.fynbos.branchbook.scheduling.application.AvailabilityQueries} for the slot grid, and {@link
 * za.co.fynbos.branchbook.scheduling.application.AlternativeSlots} for the free slots offered when a
 * requested one has just filled up. Bookings are read only through {@link
 * za.co.fynbos.branchbook.scheduling.application.BookedIntervalsPort}, which the booking module
 * implements.
 */
package za.co.fynbos.branchbook.scheduling.application;
