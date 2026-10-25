package za.co.fynbos.branchbook.booking.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import za.co.fynbos.branchbook.booking.domain.CustomerName;
import za.co.fynbos.branchbook.booking.domain.IdempotencyKey;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;

/**
 * A request to book an appointment, already parsed: every value is in its normalised form.
 *
 * <p>It holds personal data, so {@link #toString()} shows none of it.
 *
 * @param idempotencyKey the client's key, so a retry returns the same appointment
 * @param branchCode the branch code, in upper case
 * @param serviceCode the service code, in upper case
 * @param startsAt the requested start
 * @param firstName the customer's first name
 * @param lastName the customer's last name
 * @param mobile the customer's mobile number
 */
public record BookAppointmentCommand(
    IdempotencyKey idempotencyKey,
    String branchCode,
    String serviceCode,
    Instant startsAt,
    CustomerName firstName,
    CustomerName lastName,
    MobileNumber mobile) {

  /** Rejects a missing value. */
  public BookAppointmentCommand {
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Objects.requireNonNull(branchCode, "branchCode");
    Objects.requireNonNull(serviceCode, "serviceCode");
    Objects.requireNonNull(startsAt, "startsAt");
    Objects.requireNonNull(firstName, "firstName");
    Objects.requireNonNull(lastName, "lastName");
    Objects.requireNonNull(mobile, "mobile");
  }

  /**
   * Returns the SHA-256 hash, in lower-case hex, of the booking's details after normalisation
   * (BR-12). The same details typed another way, such as the number with spaces or the start with
   * another offset for the same instant, hash alike, so their retry is a replay, not a reuse.
   *
   * @return 64 hexadecimal characters
   */
  public String requestHash() {
    String details =
        String.join(
            "\n",
            branchCode,
            serviceCode,
            startsAt.toString(),
            mobile.e164(),
            firstName.value(),
            lastName.value());
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(sha256.digest(details.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Every Java platform provides SHA-256", impossible);
    }
  }

  /**
   * Shows what is booked, never who books it.
   *
   * @return the branch, service and start
   */
  @Override
  public String toString() {
    return "BookAppointmentCommand["
        + branchCode
        + ", "
        + serviceCode
        + ", "
        + startsAt
        + ", customer redacted]";
  }
}
