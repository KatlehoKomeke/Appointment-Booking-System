package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fynbos.branchbook.booking.application.Cancellation;
import za.co.fynbos.branchbook.booking.application.Confirmation;
import za.co.fynbos.branchbook.booking.application.NotificationSummary;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;
import za.co.fynbos.branchbook.support.IntegrationTest;
import za.co.fynbos.branchbook.support.SeedData;

/**
 * The outbox writer on a real database: the PENDING row it writes inside the booking transaction,
 * and the masked summary the appointment's response shows.
 */
@IntegrationTest
class NotificationOutboxIT {

  private static final UUID APPOINTMENT = UUID.fromString("e0000000-0000-4000-8000-000000000001");

  /** When the booking was made: the instant the row must carry, not the database's clock. */
  private static final Instant BOOKED = Instant.parse("2026-10-26T06:05:00Z");

  @Autowired private NotificationOutbox outbox;

  @Autowired private MessageQueries messages;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeEach
  void insertAnAppointment() {
    jdbc.update(
        """
        INSERT INTO appointments (id, reference, branch_id, service_id, seat_no, starts_at, ends_at,
                                  status, customer_first_name, customer_last_name, customer_mobile)
        VALUES (?, 'APT-7K3M-9Q2X', ?, ?, 1, ?, ?, 'CONFIRMED', 'Thandi', 'Mokoena', ?)""",
        APPOINTMENT,
        SeedData.SANDTON,
        SeedData.FICA,
        OffsetDateTime.parse("2026-10-27T09:30:00+02:00"),
        OffsetDateTime.parse("2026-10-27T09:45:00+02:00"),
        "+27825550191");
  }

  @AfterEach
  void removeIt() {
    jdbc.update("DELETE FROM notification_messages WHERE appointment_id = ?", APPOINTMENT);
    jdbc.update("DELETE FROM appointments WHERE id = ?", APPOINTMENT);
  }

  @Test
  void writesOnePendingSmsWithTheBoundInstantAndCorrelationId() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> outbox.enqueueConfirmation(confirmation()));

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT * FROM notification_messages WHERE appointment_id = ?", APPOINTMENT);
    assertThat(row)
        .containsEntry("kind", "CONFIRMATION")
        .containsEntry("channel", "SMS")
        .containsEntry("recipient", "+27825550191")
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 0)
        .containsEntry("subject", null)
        .containsEntry("failure_reason", null)
        .containsEntry("sent_at", null)
        .containsEntry(
            "body",
            "Appt confirmed: FICA update, Sandton Summit, Tue 27 Oct 09:30 SAST. Ref APT-7K3M-9Q2X."
                + " Bring ID + docs. Manage: http://localhost:8080/manage");
    assertThat(instant(row.get("created_at"))).isEqualTo(BOOKED);
    assertThat(instant(row.get("next_attempt_at"))).isEqualTo(BOOKED);
    assertThat((String) row.get("correlation_id")).startsWith("sys-");
  }

  /** A confirmation can never be written outside the transaction of its booking. */
  @Test
  void refusesToWriteOutsideATransaction() {
    assertThatExceptionOfType(IllegalTransactionStateException.class)
        .isThrownBy(() -> outbox.enqueueConfirmation(confirmation()));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_messages WHERE appointment_id = ?",
                Integer.class,
                APPOINTMENT))
        .isZero();
  }

  /**
   * BR-16, BR-24: nor can a cancellation SMS, or the superseding of the confirmation that comes
   * with it, be written outside the transaction that cancels the appointment. Each would otherwise
   * commit on its own while the appointment stayed CONFIRMED.
   */
  @Test
  void refusesToWriteTheCancellationOutsideATransaction() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> outbox.enqueueConfirmation(confirmation()));

    assertThatExceptionOfType(IllegalTransactionStateException.class)
        .isThrownBy(() -> outbox.enqueueCancellation(cancellation()));
    assertThat(
            jdbc.queryForList(
                "SELECT kind || ' ' || status FROM notification_messages WHERE appointment_id = ?",
                String.class,
                APPOINTMENT))
        .as("the confirmation is not superseded, and no cancellation is written")
        .containsExactly("CONFIRMATION PENDING");
  }

  @Test
  void summariesMaskTheRecipient() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> outbox.enqueueConfirmation(confirmation()));

    assertThat(messages.forAppointment(APPOINTMENT))
        .singleElement()
        .satisfies(
            (NotificationSummary summary) -> {
              assertThat(summary.recipientMasked()).isEqualTo("082 *** 0191");
              assertThat(summary.status()).isEqualTo("PENDING");
              assertThat(summary.attempts()).isZero();
              assertThat(summary.sentAt()).isNull();
            });
  }

  /**
   * BR-17: the per-recipient daily cap is off unless a deployment sets it, so a number outside the
   * demo block is written every SMS it is owed. RecipientDailyCapIT shows the cap switched on.
   */
  @Test
  void theDailyCapIsOffByDefault() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              for (int i = 0; i < 6; i++) {
                outbox.enqueueConfirmation(confirmationTo("+27825550200"));
              }
            });

    assertThat(
            jdbc.queryForList(
                "SELECT status FROM notification_messages WHERE appointment_id = ?",
                String.class,
                APPOINTMENT))
        .hasSize(6)
        .containsOnly("PENDING");
  }

  private static Confirmation confirmation() {
    return confirmationTo("+27825550191");
  }

  private static Confirmation confirmationTo(String e164) {
    return new Confirmation(
        APPOINTMENT,
        "APT-7K3M-9Q2X",
        MobileNumber.ofE164(e164),
        Instant.parse("2026-10-27T07:30:00Z"),
        ZoneId.of("Africa/Johannesburg"),
        "Sandton Summit",
        "FICA update",
        BOOKED);
  }

  private static Cancellation cancellation() {
    return new Cancellation(
        APPOINTMENT,
        "APT-7K3M-9Q2X",
        MobileNumber.ofE164("+27825550191"),
        Instant.parse("2026-10-27T07:30:00Z"),
        ZoneId.of("Africa/Johannesburg"),
        "Sandton Summit",
        "FICA update",
        BOOKED.plusSeconds(600));
  }

  private static Instant instant(Object timestamp) {
    return switch (timestamp) {
      case OffsetDateTime offset -> offset.toInstant();
      case java.sql.Timestamp sql -> sql.toInstant();
      default -> throw new IllegalStateException("Not a timestamp: " + timestamp.getClass());
    };
  }
}
