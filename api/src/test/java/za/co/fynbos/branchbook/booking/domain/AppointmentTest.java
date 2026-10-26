package za.co.fynbos.branchbook.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** How a booking becomes an appointment, when it can still be cancelled, and cancelling it. */
class AppointmentTest {

  private static final Instant BOOKED = Instant.parse("2026-10-26T06:00:00Z");
  private static final Instant STARTS = Instant.parse("2026-10-27T07:30:00Z");

  /** BR-2: the end is computed from the service's duration; a caller never chooses it. */
  @Test
  void endsAtIsStartPlusServiceDuration() {
    Appointment appointment = confirm(Duration.ofMinutes(45), 1);

    assertThat(appointment.startsAt()).isEqualTo(STARTS);
    assertThat(appointment.endsAt()).isEqualTo(Instant.parse("2026-10-27T08:15:00Z"));
  }

  @Test
  void newAppointmentIsConfirmedWithEveryDetail() {
    Appointment appointment = confirm(Duration.ofMinutes(15), 3);

    assertThat(appointment.status()).isEqualTo(AppointmentStatus.CONFIRMED);
    assertThat(appointment.reference()).isEqualTo("APT-7K3M-9Q2X");
    assertThat(appointment.seatNo()).isEqualTo(3);
    assertThat(appointment.createdAt()).isEqualTo(BOOKED);
    assertThat(appointment.cancelledAt()).isNull();
    assertThat(appointment.mobile().e164()).isEqualTo("+27825550101");
    assertThat(appointment.customer().firstName()).isEqualTo("Thandi");
    assertThat(appointment.customer().email()).isNull();
  }

  /** BR-25: an appointment can be cancelled until it starts, and never after. */
  @Test
  void canCancelOnlyBeforeTheStart() {
    Appointment appointment = confirm(Duration.ofMinutes(15), 1);

    assertThat(appointment.canCancel(STARTS.minusSeconds(1))).isTrue();
    assertThat(appointment.canCancel(STARTS)).isFalse();
    assertThat(appointment.canCancel(STARTS.plus(Duration.ofDays(1)))).isFalse();
  }

  /** BR-14: a cancel before the start cancels, stamps the time, and ends further cancelling. */
  @Test
  void cancelBeforeTheStartCancelsAndRecordsWhen() {
    Appointment appointment = confirm(Duration.ofMinutes(15), 1);
    Instant now = STARTS.minusSeconds(1);

    assertThat(appointment.cancel(now)).isEqualTo(CancelOutcome.CANCELLED);

    assertThat(appointment.status()).isEqualTo(AppointmentStatus.CANCELLED);
    assertThat(appointment.cancelledAt()).isEqualTo(now);
    assertThat(appointment.canCancel(now)).isFalse();
  }

  /** BR-14, BR-25: at the start, or after it, it is too late, and the appointment is unchanged. */
  @ParameterizedTest
  @ValueSource(longs = {0, 1, 86_400})
  void cancelAtOrAfterTheStartIsTooLateAndChangesNothing(long secondsAfterTheStart) {
    Appointment appointment = confirm(Duration.ofMinutes(15), 1);

    assertThat(appointment.cancel(STARTS.plusSeconds(secondsAfterTheStart)))
        .isEqualTo(CancelOutcome.TOO_LATE);

    assertThat(appointment.status()).isEqualTo(AppointmentStatus.CONFIRMED);
    assertThat(appointment.cancelledAt()).isNull();
  }

  /**
   * BR-14: cancelling again is a no-op the caller can tell apart from a cancel, even after the
   * start, and keeps the first cancellation's time.
   */
  @Test
  void repeatCancelIsANoOpThatKeepsTheFirstCancellation() {
    Appointment appointment = confirm(Duration.ofMinutes(15), 1);
    Instant first = STARTS.minus(Duration.ofHours(2));
    appointment.cancel(first);

    assertThat(appointment.cancel(first.plusSeconds(60)))
        .isEqualTo(CancelOutcome.ALREADY_CANCELLED);
    assertThat(appointment.cancel(STARTS.plusSeconds(60)))
        .isEqualTo(CancelOutcome.ALREADY_CANCELLED);
    assertThat(appointment.status()).isEqualTo(AppointmentStatus.CANCELLED);
    assertThat(appointment.cancelledAt()).isEqualTo(first);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 21})
  void seatOutsideOneToTwentyIsRefused(int seat) {
    assertThatIllegalArgumentException().isThrownBy(() -> confirm(Duration.ofMinutes(15), seat));
  }

  @Test
  void zeroDurationIsRefused() {
    assertThatIllegalArgumentException().isThrownBy(() -> confirm(Duration.ZERO, 1));
  }

  private static Appointment confirm(Duration duration, int seat) {
    CustomerDetails customer =
        CustomerDetails.of(
            CustomerName.parse("Thandi").orElseThrow(),
            CustomerName.parse("Mokoena").orElseThrow(),
            MobileNumber.parse("082 555 0101").orElseThrow());
    return Appointment.confirm(
        new AppointmentReference("APT-7K3M-9Q2X"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        duration,
        seat,
        STARTS,
        customer,
        BOOKED);
  }
}
