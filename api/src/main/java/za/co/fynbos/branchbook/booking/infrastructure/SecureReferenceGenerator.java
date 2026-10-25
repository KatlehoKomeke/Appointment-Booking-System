package za.co.fynbos.branchbook.booking.infrastructure;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;
import za.co.fynbos.branchbook.booking.application.ReferenceGenerator;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;

/** Draws references from {@link SecureRandom}, so they cannot be guessed (BR-11). */
@Component
class SecureReferenceGenerator implements ReferenceGenerator {

  private final SecureRandom random = new SecureRandom();

  @Override
  public AppointmentReference next() {
    return AppointmentReference.generate(random);
  }
}
