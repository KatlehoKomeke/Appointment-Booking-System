package za.co.fynbos.branchbook.catalogue.domain;

import java.util.Arrays;

/** The nine provinces of South Africa, with the names the database stores and the API returns. */
public enum Province {
  /** Eastern Cape. */
  EASTERN_CAPE("Eastern Cape"),
  /** Free State. */
  FREE_STATE("Free State"),
  /** Gauteng. */
  GAUTENG("Gauteng"),
  /** KwaZulu-Natal. */
  KWAZULU_NATAL("KwaZulu-Natal"),
  /** Limpopo. */
  LIMPOPO("Limpopo"),
  /** Mpumalanga. */
  MPUMALANGA("Mpumalanga"),
  /** North West. */
  NORTH_WEST("North West"),
  /** Northern Cape. */
  NORTHERN_CAPE("Northern Cape"),
  /** Western Cape. */
  WESTERN_CAPE("Western Cape");

  private final String displayName;

  Province(String displayName) {
    this.displayName = displayName;
  }

  /**
   * Returns the province's official name.
   *
   * @return for example {@code KwaZulu-Natal}
   */
  public String displayName() {
    return displayName;
  }

  /**
   * Finds a province by its official name, as stored in {@code branches.province}.
   *
   * @param displayName the official name, for example {@code Western Cape}
   * @return the province
   * @throws IllegalArgumentException if no province has that name
   */
  public static Province fromDisplayName(String displayName) {
    return Arrays.stream(values())
        .filter(province -> province.displayName.equals(displayName))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown province: " + displayName));
  }
}
