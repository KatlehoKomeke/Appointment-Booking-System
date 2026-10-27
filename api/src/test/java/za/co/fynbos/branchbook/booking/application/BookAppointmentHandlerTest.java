package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import za.co.fynbos.branchbook.booking.application.IdempotencyKeyStore.Replay;
import za.co.fynbos.branchbook.booking.domain.CustomerName;
import za.co.fynbos.branchbook.booking.domain.IdempotencyKey;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.catalogue.application.BranchCalendarProvider;
import za.co.fynbos.branchbook.scheduling.application.AlternativeSlots;
import za.co.fynbos.branchbook.shared.config.BookingProperties;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;

/**
 * BR-12, BR-14, at the handler: a replay is rendered after its transaction commits, so a cancel can
 * delete the appointment in between. That replay is refused as any replay of a cancelled booking
 * is, never a 500, and nothing is booked. The window is too narrow to force on the database, so the
 * key store and the views are stubs. The values are synthetic.
 */
class BookAppointmentHandlerTest {

  private static final Instant NOW = Instant.parse("2026-10-26T06:00:00Z");

  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  private final IdempotencyKeyStore keys = mock(IdempotencyKeyStore.class);

  private final AppointmentRepository appointments = mock(AppointmentRepository.class);

  private final ConfirmationOutbox outbox = mock(ConfirmationOutbox.class);

  private final AppointmentViews views = mock(AppointmentViews.class);

  private final BookAppointmentHandler handler =
      new BookAppointmentHandler(
          transactions,
          keys,
          mock(BranchCalendarProvider.class),
          mock(BookingLocks.class),
          appointments,
          outbox,
          mock(ReferenceGenerator.class),
          mock(AlternativeSlots.class),
          views,
          Clock.fixed(NOW, ZoneOffset.UTC),
          new BookingProperties(
              Duration.ofHours(2), Period.ofDays(30), 5, Duration.ofHours(1), false));

  @Test
  void replayWhoseAppointmentACancelDeletesBeforeItIsReadIsRefusedAsCancelled() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(keys.reserve(any(), any(), any())).thenReturn(new Replay(UUID.randomUUID()));
    when(views.findById(any(), any())).thenReturn(Optional.empty());

    assertThatExceptionOfType(IdempotencyKeyReusedException.class)
        .isThrownBy(() -> handler.handle(command()))
        .satisfies(
            refused -> {
              assertThat(refused.code()).isEqualTo(ProblemCode.IDEMPOTENCY_KEY_REUSED);
              assertThat(refused.getMessage())
                  .isEqualTo(IdempotencyKeyReusedException.CANCELLED_DETAIL);
            });
    verify(views, never()).byId(any(), any());
    verifyNoInteractions(appointments, outbox);
  }

  private static BookAppointmentCommand command() {
    return new BookAppointmentCommand(
        new IdempotencyKey(UUID.randomUUID()),
        "SAN",
        "FICA",
        NOW.plus(Duration.ofDays(1)),
        CustomerName.parse("Nomvula").orElseThrow(),
        CustomerName.parse("Dlamini").orElseThrow(),
        MobileNumber.parse("082 555 0101").orElseThrow());
  }
}
