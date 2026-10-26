package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import za.co.fynbos.branchbook.booking.domain.Appointment;
import za.co.fynbos.branchbook.booking.domain.AppointmentReference;
import za.co.fynbos.branchbook.booking.domain.CustomerDetails;
import za.co.fynbos.branchbook.booking.domain.CustomerName;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.catalogue.application.CatalogueLookup;
import za.co.fynbos.branchbook.catalogue.domain.Address;
import za.co.fynbos.branchbook.catalogue.domain.Branch;
import za.co.fynbos.branchbook.catalogue.domain.Province;
import za.co.fynbos.branchbook.catalogue.domain.ServiceType;
import za.co.fynbos.branchbook.shared.config.BookingProperties;
import za.co.fynbos.branchbook.shared.problem.ProblemCode;
import za.co.fynbos.branchbook.shared.problem.ProblemException;

/**
 * BR-14, at the handler: the one re-run after a version conflict. Two concurrent cancels are proven
 * on the database by {@code CancelAppointmentIT}; a second conflict cannot happen there, because
 * cancel is the only API writer of an appointment, so the repository is a stub that makes every
 * flush fail. The values are synthetic.
 */
class CancelAppointmentHandlerTest {

  private static final String REFERENCE = "APT-7K3M-9Q2X";

  private static final Instant NOW = Instant.parse("2026-10-26T06:00:00Z");

  private final AppointmentRepository appointments = mock(AppointmentRepository.class);

  private final CatalogueLookup catalogue = mock(CatalogueLookup.class);

  private final ConfirmationOutbox outbox = mock(ConfirmationOutbox.class);

  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  private final AppointmentViews views = mock(AppointmentViews.class);

  private final CancelAppointmentHandler handler =
      new CancelAppointmentHandler(
          transactions,
          new AppointmentAccess(
              appointments,
              new LookupAttemptLimiter(
                  new BookingProperties(
                      Duration.ofHours(2), Period.ofDays(30), 5, Duration.ofHours(1), false))),
          appointments,
          catalogue,
          outbox,
          views,
          Clock.fixed(NOW, ZoneOffset.UTC));

  /**
   * The response is rendered inside the cancel's transaction, before it commits, and nothing is
   * read after it, so the retention purge cannot delete the appointment between the commit and the
   * read and turn a done cancel into a 500.
   */
  @Test
  void responseIsRenderedInsideTheTransactionBeforeItCommits() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(appointments.findByReference(any())).thenAnswer(call -> Optional.of(confirmed()));
    when(catalogue.branch(any())).thenReturn(branch());
    when(catalogue.service(any())).thenReturn(service());

    handler.cancel(REFERENCE, "0101");

    InOrder order = inOrder(appointments, outbox, views, transactions);
    order.verify(appointments).update(any());
    order.verify(outbox).enqueueCancellation(any());
    order.verify(views).of(any(), eq(NOW));
    order.verify(transactions).commit(any());
    verify(views, never()).byId(any(), any());
  }

  /**
   * A version conflict on the re-run as well is {@code 503 TRY_AGAIN} with {@code Retry-After: 2},
   * never a third attempt, and nothing is queued.
   */
  @Test
  void secondVersionConflictReturns503TryAgain() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(appointments.findByReference(any())).thenAnswer(call -> Optional.of(confirmed()));
    when(catalogue.branch(any())).thenReturn(branch());
    when(catalogue.service(any())).thenReturn(service());
    doThrow(new OptimisticLockingFailureException("test: changed by another transaction"))
        .when(appointments)
        .update(any());

    assertThatExceptionOfType(ProblemException.class)
        .isThrownBy(() -> handler.cancel(REFERENCE, "0101"))
        .satisfies(
            refused -> {
              assertThat(refused.code()).isEqualTo(ProblemCode.TRY_AGAIN);
              assertThat(refused.retryAfter()).contains(Duration.ofSeconds(2));
            });
    verify(appointments, times(2)).update(any());
    verifyNoInteractions(outbox);
  }

  /** A fresh copy each time, as each transaction loads its own. */
  private static Appointment confirmed() {
    return Appointment.confirm(
        new AppointmentReference(REFERENCE),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Duration.ofMinutes(15),
        1,
        NOW.plus(Duration.ofDays(1)),
        CustomerDetails.of(
            CustomerName.parse("Thandi").orElseThrow(),
            CustomerName.parse("Mokoena").orElseThrow(),
            MobileNumber.parse("082 555 0101").orElseThrow()),
        NOW);
  }

  private static Branch branch() {
    return new Branch(
        UUID.randomUUID(),
        "TST",
        "Test Branch",
        "Test Branch",
        new Address("1 Test Road", "Testville", "Test City", Province.GAUTENG, "2000"),
        ZoneId.of("Africa/Johannesburg"),
        false);
  }

  private static ServiceType service() {
    return new ServiceType(
        UUID.randomUUID(), "FICA", "Update personal details", "FICA update", "Test", 15, List.of());
  }
}
