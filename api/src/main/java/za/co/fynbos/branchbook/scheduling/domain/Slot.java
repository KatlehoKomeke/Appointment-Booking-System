package za.co.fynbos.branchbook.scheduling.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A time a service can be booked in at a branch, and how many of its seats are still free.
 *
 * @param startsAt when the appointment would start
 * @param endsAt when it would end: the start plus the service's duration
 * @param seatsAvailable how many seats are free for the whole slot; 0 means full, and the slot is
 *     still listed so the booking page can show it as taken
 */
public record Slot(Instant startsAt, Instant endsAt, int seatsAvailable) {

  /** Rejects a slot that ends before it starts or has a negative number of seats. */
  public Slot {
    Objects.requireNonNull(startsAt, "startsAt");
    Objects.requireNonNull(endsAt, "endsAt");
    if (!endsAt.isAfter(startsAt) || seatsAvailable < 0) {
      throw new IllegalArgumentException(
          "A slot must end after it starts and cannot have fewer than 0 seats");
    }
  }
}
