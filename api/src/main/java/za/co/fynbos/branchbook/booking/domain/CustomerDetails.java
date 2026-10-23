package za.co.fynbos.branchbook.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.jspecify.annotations.Nullable;

/**
 * What BranchBook knows about the customer who booked an appointment: a name and a South African
 * mobile number, and an e-mail address only if the optional e-mail channel is added (POPIA section
 * 10; ADR-0004). There is no customer table; the details live on the appointment.
 *
 * <p>These are personal data. They are never logged, never put in an error body, and shown only
 * masked; {@link #toString()} therefore reveals nothing.
 *
 * @param firstName the customer's first name, trimmed
 * @param lastName the customer's last name, trimmed
 * @param mobile the mobile number in E.164 form, for example {@code +27825550101}
 * @param email an e-mail address, only if the e-mail channel is added; otherwise {@code null}
 */
@Embeddable
public record CustomerDetails(
    @Column(name = "customer_first_name") String firstName,
    @Column(name = "customer_last_name") String lastName,
    @Column(name = "customer_mobile") String mobile,
    @Column(name = "customer_email") @Nullable String email) {

  /**
   * Hides every value, so the details cannot reach a log through string concatenation.
   *
   * @return a fixed text without personal data
   */
  @Override
  public String toString() {
    return "CustomerDetails[redacted]";
  }
}
