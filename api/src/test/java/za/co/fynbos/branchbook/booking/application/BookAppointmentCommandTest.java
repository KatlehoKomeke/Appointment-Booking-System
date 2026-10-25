package za.co.fynbos.branchbook.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import za.co.fynbos.branchbook.booking.domain.CustomerName;
import za.co.fynbos.branchbook.booking.domain.IdempotencyKey;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;

/** BR-12: the hash that tells a retry from a different booking with the same key. */
class BookAppointmentCommandTest {

  private static final IdempotencyKey KEY = new IdempotencyKey(UUID.randomUUID());

  @Test
  void sameDetailsInAnotherFormatHashAlike() {
    BookAppointmentCommand typed = command("082 555 0101", "Nomvula", "2026-10-27T07:30:00Z");
    BookAppointmentCommand retyped =
        command("+27 82 555 0101", "Nomvula", "2026-10-27T07:30:00.000Z");

    assertThat(typed.requestHash()).isEqualTo(retyped.requestHash()).matches("[0-9a-f]{64}");
  }

  @Test
  void everyDetailChangesTheHash() {
    String original = command("082 555 0101", "Nomvula", "2026-10-27T07:30:00Z").requestHash();

    assertThat(command("082 555 0102", "Nomvula", "2026-10-27T07:30:00Z").requestHash())
        .isNotEqualTo(original);
    assertThat(command("082 555 0101", "Nomsa", "2026-10-27T07:30:00Z").requestHash())
        .isNotEqualTo(original);
    assertThat(command("082 555 0101", "Nomvula", "2026-10-27T07:45:00Z").requestHash())
        .isNotEqualTo(original);
  }

  @Test
  void toStringShowsNoCustomer() {
    assertThat(command("082 555 0101", "Nomvula", "2026-10-27T07:30:00Z").toString())
        .doesNotContain("Nomvula")
        .doesNotContain("0101");
  }

  private static BookAppointmentCommand command(String mobile, String firstName, String startsAt) {
    return new BookAppointmentCommand(
        KEY,
        "SAN",
        "FICA",
        Instant.parse(startsAt),
        CustomerName.parse(firstName).orElseThrow(),
        CustomerName.parse("Dlamini").orElseThrow(),
        MobileNumber.parse(mobile).orElseThrow());
  }
}
