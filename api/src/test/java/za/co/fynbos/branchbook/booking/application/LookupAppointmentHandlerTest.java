package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionOperations;
import za.co.fynbos.branchbook.shared.config.BookingProperties;

/**
 * BR-13, at the handler: how a lookup's outcome reaches the limiter. The repository is a stub, so
 * the database can be made to fail on cue, and the limiter counts in a map.
 */
class LookupAppointmentHandlerTest {

  private static final String REFERENCE = "APT-7K3M-9Q2X";

  private final AppointmentRepository appointments = mock(AppointmentRepository.class);

  private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

  private final LookupAppointmentHandler handler =
      new LookupAppointmentHandler(
          transactions,
          new AppointmentAccess(
              appointments,
              new LookupAttemptLimiter(
                  new BookingProperties(
                      Duration.ofHours(2), Period.ofDays(30), 5, Duration.ofHours(1), false),
                  new InMemoryLookupAttemptStore(),
                  TransactionOperations.withoutTransaction())),
          mock(AppointmentViews.class),
          Clock.fixed(Instant.parse("2026-10-26T06:00:00Z"), ZoneOffset.UTC));

  /**
   * A read that fails, for example on the statement timeout, is the database's fault, not the
   * caller's guess. After five of them the reference is neither blocked nor held by lookups still
   * counted as in flight: the next five wrong digits are each a 404, and only then is it blocked
   * for the hour.
   */
  @Test
  void lookupWhoseReadFailsIsNeitherCountedNorLeftInFlight() {
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    doThrow(new QueryTimeoutException("statement timeout"))
        .when(appointments)
        .findByReference(any());
    for (int timeout = 0; timeout < 5; timeout++) {
      assertThatExceptionOfType(QueryTimeoutException.class)
          .isThrownBy(() -> handler.lookup(REFERENCE, "0101"));
    }

    doReturn(Optional.empty()).when(appointments).findByReference(any());
    for (int wrong = 0; wrong < 5; wrong++) {
      assertThatExceptionOfType(AppointmentNotFoundException.class)
          .isThrownBy(() -> handler.lookup(REFERENCE, "0101"));
    }
    assertThatExceptionOfType(LookupRateLimitedException.class)
        .isThrownBy(() -> handler.lookup(REFERENCE, "0101"))
        .satisfies(limited -> assertThat(limited.retryAfter()).contains(Duration.ofHours(1)));
  }
}
