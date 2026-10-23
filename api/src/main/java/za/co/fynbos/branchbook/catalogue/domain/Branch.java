package za.co.fynbos.branchbook.catalogue.domain;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * A branch of the bank where customers can book appointments. Branches are reference data: Flyway
 * seeds them and nothing changes them at runtime.
 *
 * @param id the branch's id
 * @param code the short code customers and URLs use, for example {@code SAN}
 * @param name the full name, for example {@code Sandton Summit Mall}
 * @param smsName the short name used in the confirmation SMS, at most 20 characters
 * @param address the street address
 * @param timezone the time zone that the trading hours are expressed in
 * @param openOnPublicHolidays whether the branch trades on public holidays
 */
public record Branch(
    UUID id,
    String code,
    String name,
    String smsName,
    Address address,
    ZoneId timezone,
    boolean openOnPublicHolidays) {

  /**
   * Rejects a missing value, so an incomplete branch fails when it is read, not when it is used.
   */
  public Branch {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(smsName, "smsName");
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(timezone, "timezone");
  }
}
