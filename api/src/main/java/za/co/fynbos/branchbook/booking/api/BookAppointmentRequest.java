package za.co.fynbos.branchbook.booking.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import za.co.fynbos.branchbook.booking.application.BookAppointmentCommand;
import za.co.fynbos.branchbook.booking.application.DemoNumberRequiredException;
import za.co.fynbos.branchbook.booking.application.MobileNumberRequiredException;
import za.co.fynbos.branchbook.booking.domain.CustomerName;
import za.co.fynbos.branchbook.booking.domain.IdempotencyKey;
import za.co.fynbos.branchbook.booking.domain.MobileNumber;

/**
 * The body of {@code POST /api/v1/appointments}. A property it does not declare, such as {@code
 * endsAt}, is refused: the end is always computed from the service's duration (BR-2).
 *
 * @param branchCode the branch code; lower case is accepted
 * @param serviceCode the service code; lower case is accepted
 * @param startsAt the requested start, with its offset, for example {@code
 *     2026-10-27T09:30:00+02:00}
 * @param customer who the appointment is for
 */
@Schema(name = "BookingRequest")
public record BookAppointmentRequest(
    @Schema(example = "SAN")
        @NotNull(message = "is required")
        @Pattern(regexp = "^[A-Za-z]{2,8}$", message = "must be 2 to 8 letters")
        String branchCode,
    @Schema(example = "FICA")
        @NotNull(message = "is required")
        @Pattern(regexp = "^[A-Za-z]{2,12}$", message = "must be 2 to 12 letters")
        String serviceCode,
    @Schema(format = "date-time", example = "2026-10-27T09:30:00+02:00")
        @NotNull(message = "is required")
        @IsoOffsetDateTime
        String startsAt,
    @NotNull(message = "is required") @Valid CustomerRequest customer) {

  /**
   * Turns the request into the booking's command. The number is checked here, before the booking
   * transaction, so a refused number writes nothing: no appointment, no message and no idempotency
   * key.
   *
   * @param key the parsed {@code Idempotency-Key}
   * @param demoNumbersOnly whether this deployment takes only the published demo numbers
   * @return the command, every value in its normalised form
   * @throws MobileNumberRequiredException if the mobile number is missing or is not a South African
   *     mobile
   * @throws DemoNumberRequiredException if only demo numbers are taken and this South African
   *     mobile is not one
   */
  BookAppointmentCommand toCommand(IdempotencyKey key, boolean demoNumbersOnly) {
    MobileNumber mobile =
        MobileNumber.parse(customer.mobile()).orElseThrow(MobileNumberRequiredException::new);
    if (demoNumbersOnly && !mobile.isPublishedDemoNumber()) {
      throw new DemoNumberRequiredException();
    }
    return new BookAppointmentCommand(
        key,
        branchCode.toUpperCase(Locale.ROOT),
        serviceCode.toUpperCase(Locale.ROOT),
        OffsetDateTime.parse(startsAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant(),
        name(customer.firstName()),
        name(customer.lastName()),
        mobile);
  }

  /** Parses a name the request's validation has already accepted. */
  private static CustomerName name(String validated) {
    return CustomerName.parse(validated)
        .orElseThrow(() -> new IllegalStateException("A validated name did not parse"));
  }

  /**
   * Who the appointment is for. Only a name and a mobile number are collected (ADR-0004).
   *
   * @param firstName the first name
   * @param lastName the last name
   * @param mobile a South African mobile number in any of the usual formats; anything else is
   *     {@code 422 MOBILE_NUMBER_REQUIRED}, and where only demo numbers are taken, one outside them
   *     is {@code 422 DEMO_NUMBER_REQUIRED}
   */
  @Schema(name = "BookingCustomer")
  public record CustomerRequest(
      @Schema(example = "Nomvula") @NotBlank(message = "is required") @PersonName String firstName,
      @Schema(example = "Dlamini") @NotBlank(message = "is required") @PersonName String lastName,
      @Schema(
              example = "082 555 0101",
              description =
                  "A South African mobile number, such as 082 555 0101 or +27 82 555 0101")
          @Nullable String mobile) {

    /**
     * Hides the customer's details, so the request cannot reach a log with them.
     *
     * @return a fixed text without personal data
     */
    @Override
    public String toString() {
      return "CustomerRequest[redacted]";
    }
  }
}
