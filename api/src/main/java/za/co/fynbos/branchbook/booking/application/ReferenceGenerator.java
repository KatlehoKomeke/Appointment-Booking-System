package za.co.fynbos.branchbook.booking.application;

import za.co.fynbos.branchbook.booking.domain.AppointmentReference;

/**
 * Draws the reference of a new appointment (BR-11). The application draws from {@link
 * java.security.SecureRandom}; tests can script the references to force a collision.
 */
public interface ReferenceGenerator {

  /**
   * Draws a reference.
   *
   * @return a reference that is very probably not taken
   */
  AppointmentReference next();
}
