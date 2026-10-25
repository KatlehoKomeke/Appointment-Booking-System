package za.co.fynbos.branchbook.scheduling.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import za.co.fynbos.branchbook.scheduling.domain.BookedInterval;

/**
 * The seats that active appointments hold. Scheduling declares this port and the booking module
 * implements it, so scheduling never reads booking's table and never depends on booking: the
 * dependency runs from booking to scheduling (docs/architecture.md, section 3).
 */
public interface BookedIntervalsPort {

  /**
   * Lists the active appointments of one service at one branch that overlap a period.
   *
   * @param branchId the branch's id
   * @param serviceId the service's id
   * @param from the period's start, included
   * @param to the period's end, excluded
   * @return the seat and the time of each appointment that holds its seat during the period
   */
  List<BookedInterval> activeBetween(UUID branchId, UUID serviceId, Instant from, Instant to);
}
