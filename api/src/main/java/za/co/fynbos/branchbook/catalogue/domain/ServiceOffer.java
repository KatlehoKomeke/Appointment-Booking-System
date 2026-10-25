package za.co.fynbos.branchbook.catalogue.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * A service as one branch offers it (a row of {@code branch_services}): how long an appointment
 * lasts, and how many customers the branch can see for it at the same time.
 *
 * @param serviceId the service's id
 * @param code the service code, for example {@code FICA}
 * @param durationMinutes how long an appointment lasts, which is also the slot grid's step (BR-1)
 * @param capacity the number of seats: consultants who can see customers for it at once (BR-7)
 */
public record ServiceOffer(UUID serviceId, String code, int durationMinutes, int capacity) {

  /** Rejects an offer that could produce no slot or no seat. */
  public ServiceOffer {
    Objects.requireNonNull(serviceId, "serviceId");
    Objects.requireNonNull(code, "code");
    if (durationMinutes <= 0 || capacity <= 0) {
      throw new IllegalArgumentException("A service needs a positive duration and capacity");
    }
  }

  /**
   * Returns how long an appointment for this service lasts.
   *
   * @return the duration
   */
  public Duration duration() {
    return Duration.ofMinutes(durationMinutes);
  }
}
