package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import org.springframework.transaction.support.TransactionOperations;
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

/**
 * BR-14, at the handler: the order of the cancel's steps, and what a lost version check answers.
 * Both are proven on the database by {@code CancelAppointmentIT}; here the repository and the
 * outbox are stubs, so the order is exact and the conflict certain. The values are synthetic.
 */
class CancelAppointmentHandlerTest {

  private static final String REFERENCE = "APT-7K3M-9Q2X";

  private static final Instant NOW = Instant.parse("2026-10-26T06:00:00Z");

  private final AppointmentRepository appointments = mock(AppointmentRepository.class);

  private final CatalogueLookup catalogue = mock(CatalogueLookup.class);

  private final ConfirmationOutbox outbox = mock(ConfirmationOutbox.class);

  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  private final AppointmentViews views = mock(AppointmentViews.class);

  private final LookupAttemptLimiter limiter =
      new LookupAttemptLimiter(
          new BookingProperties(
              Duration.ofHours(2), Period.ofDays(30), 5, Duration.ofHours(1), false),
          new InMemoryLookupAttemptStore(),
          TransactionOperations.withoutTransaction());

  private final CancelAppointmentHandler handler =
      new CancelAppointmentHandler(
          transactions,
          new AppointmentAccess(appointments, limiter),
          appointments,
          catalogue,
          outbox,
          views,
          Clock.fixed(NOW, ZoneOffset.UTC));

  /**
   * The row is locked, version-checked, before anything is written; the response is rendered from
   * the appointment in memory and its one cancellation SMS, before the delete; and nothing is read
   * after the commit, which would find the appointment gone.
   */
  @Test
  void responseIsRenderedInsideTheTransactionBeforeItCommits() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(appointments.findByReference(any())).thenAnswer(call -> Optional.of(confirmed()));
    when(catalogue.branch(any())).thenReturn(branch());
    when(catalogue.service(any())).thenReturn(service());
    NotificationSummary sms = cancellationSms();
    when(outbox.enqueueCancellation(any())).thenReturn(sms);

    handler.cancel(REFERENCE, "0101");

    InOrder order = inOrder(appointments, outbox, views, transactions);
    order.verify(appointments).lockForDeletion(any());
    order.verify(outbox).enqueueCancellation(any());
    order.verify(views).of(any(), eq(NOW), eq(List.of(sms)));
    order.verify(appointments).delete(any());
    order.verify(transactions).commit(any());
    verify(views, never()).byId(any(), any());
    verify(views, never()).of(any(), any());
  }

  /**
   * A cancel that loses the version check, to another cancel or to the purge, is {@code 404
   * APPOINTMENT_NOT_FOUND} that counts as a wrong try, exactly as for an unknown reference, and
   * writes nothing: four of them and a fifth wrong try block the reference.
   */
  @Test
  void versionConflictReturns404CountedAndWritesNothing() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    when(appointments.findByReference(any())).thenAnswer(call -> Optional.of(confirmed()));
    doThrow(new OptimisticLockingFailureException("test: deleted by another transaction"))
        .when(appointments)
        .lockForDeletion(any());

    for (int lost = 1; lost <= 5; lost++) {
      assertThatExceptionOfType(AppointmentNotFoundException.class)
          .isThrownBy(() -> handler.cancel(REFERENCE, "0101"));
    }

    assertThatExceptionOfType(LookupRateLimitedException.class)
        .as("each lost cancel counted as a wrong try")
        .isThrownBy(() -> handler.cancel(REFERENCE, "0101"));
    verifyNoInteractions(outbox);
    verify(appointments, never()).delete(any());
    verify(transactions, never()).commit(any());
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

  private static NotificationSummary cancellationSms() {
    return new NotificationSummary(
        UUID.randomUUID(),
        "SMS",
        "CANCELLATION",
        "PENDING",
        0,
        null,
        null,
        "082 *** 0101",
        "Appt cancelled: FICA update, Test Branch, Tue 27 Oct 08:00 SAST. Ref " + REFERENCE);
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
