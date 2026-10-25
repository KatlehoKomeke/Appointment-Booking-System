package za.co.fynbos.branchbook.booking.application;

/**
 * The outcome of a successful booking request: a new appointment, or the replay of the one its
 * {@code Idempotency-Key} already booked (BR-12). Either way the appointment is shown as it is now.
 */
public sealed interface BookingResult {

  /**
   * Returns the appointment.
   *
   * @return the appointment, rendered after the booking committed
   */
  AppointmentView appointment();

  /**
   * The request booked a new appointment.
   *
   * @param appointment the appointment
   */
  record Created(AppointmentView appointment) implements BookingResult {}

  /**
   * The request repeated one that had already booked an appointment, which is returned again.
   *
   * @param appointment the appointment the first request booked, as it is now
   */
  record Replayed(AppointmentView appointment) implements BookingResult {}
}
