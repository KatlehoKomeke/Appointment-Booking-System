package za.co.fynbos.branchbook.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static za.co.fynbos.branchbook.support.Bookings.body;
import static za.co.fynbos.branchbook.support.Bookings.book;

import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import za.co.fynbos.branchbook.support.BookingPause;
import za.co.fynbos.branchbook.support.DatabaseReset;
import za.co.fynbos.branchbook.support.IntegrationTest;

/**
 * BR-16, ADR-0003: a booking and its confirmation SMS become visible together or not at all. The
 * booking is held at step 10, with both rows written and not committed; nothing of it can be seen
 * from outside, and once it commits both rows are there.
 */
@IntegrationTest
@ExtendWith(DatabaseReset.class)
class ConfirmationOutboxIT {

  @Autowired private MockMvcTester mvc;

  @Autowired private JdbcTemplate jdbc;

  @Autowired private DataSource dataSource;

  @Test
  void bookingAndPendingMessagesCommitAtomically() throws Exception {
    CompletableFuture<MvcTestResult> booking;
    try (BookingPause pause = BookingPause.hold(dataSource, jdbc)) {
      booking =
          CompletableFuture.supplyAsync(
              () -> book(mvc, body("SAN", "FICA", "2026-10-27T09:30:00+02:00", "082 555 0191")));
      pause.awaitWaiting(1, 0);

      assertThat(count("appointments")).as("the appointment is not visible yet").isZero();
      assertThat(count("notification_messages")).as("nor is its SMS").isZero();

      pause.release();
      assertThat(booking.get()).hasStatus(HttpStatus.CREATED);
    }

    assertThat(count("appointments")).isOne();
    assertThat(count("notification_messages")).isOne();
    assertThat(
            jdbc.queryForObject(
                "SELECT bool_and(a.xmin::text = m.xmin::text) FROM appointments a"
                    + " JOIN notification_messages m ON m.appointment_id = a.id",
                Boolean.class))
        .as("written by one transaction, not by a second one after the commit")
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_messages m JOIN appointments a"
                    + " ON a.id = m.appointment_id WHERE m.status = 'PENDING'",
                Integer.class))
        .isOne();
  }

  private int count(String table) {
    Integer rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    return rows == null ? 0 : rows;
  }
}
