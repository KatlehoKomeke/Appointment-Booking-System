/**
 * The scheduling domain: {@link za.co.fynbos.branchbook.scheduling.domain.SlotCalendar}, which
 * turns a branch's hours, a service's duration and capacity, and "now" into bookable slots, and
 * checks a requested start against the same rules. Plain Java with no Spring dependency.
 */
package za.co.fynbos.branchbook.scheduling.domain;
