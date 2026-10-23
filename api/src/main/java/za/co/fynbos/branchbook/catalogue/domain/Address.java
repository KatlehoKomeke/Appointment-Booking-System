package za.co.fynbos.branchbook.catalogue.domain;

import java.util.Objects;

/**
 * A branch's street address.
 *
 * @param line1 the street address, for example {@code 12 Fynbos Street}
 * @param suburb the suburb
 * @param city the city or town
 * @param province the province
 * @param postalCode the four-digit postal code
 */
public record Address(
    String line1, String suburb, String city, Province province, String postalCode) {

  /** Rejects a missing part, so an incomplete address never reaches a response. */
  public Address {
    Objects.requireNonNull(line1, "line1");
    Objects.requireNonNull(suburb, "suburb");
    Objects.requireNonNull(city, "city");
    Objects.requireNonNull(province, "province");
    Objects.requireNonNull(postalCode, "postalCode");
  }
}
