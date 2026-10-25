package za.co.fynbos.branchbook.booking.domain;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A South African mobile number: how the confirmation reaches the customer, and half of what a
 * customer shows to open an appointment (BR-13, BR-19).
 *
 * <p>{@link #parse} accepts the forms people type, such as {@code 082 555 0101}, {@code +27 82 555
 * 0101} or {@code 0027825550101}, and keeps a number only when libphonenumber reports it as a
 * mobile and its E.164 form matches {@link #E164_PATTERN}, the pattern of the database's {@code
 * appointments_customer_mobile_e164} check. Landlines, VoIP and toll-free numbers are refused,
 * because an SMS could not reach them.
 *
 * <p>It is personal data. It is stored only in E.164 form, shown {@linkplain #masked() masked} only
 * to a caller who has just proved the number, and {@linkplain #redacted() redacted} everywhere
 * else; {@link #toString()} is the redacted form, so it cannot reach a log by accident.
 */
public final class MobileNumber {

  /**
   * The E.164 form of a South African mobile number: {@code +27}, then a 6x, 7x or 81 to 85 prefix,
   * then seven digits. The same text as the {@code appointments_customer_mobile_e164} check, which
   * SchemaConstraintIT compares with this constant.
   */
  public static final String E164_PATTERN = "^\\+27(6[0-9]|7[0-9]|8[1-5])[0-9]{7}$";

  private static final Pattern E164 = Pattern.compile(E164_PATTERN);

  /**
   * What a typed number may contain before libphonenumber reads it: digits and the punctuation
   * people use, up to 32 characters. This refuses letters, so a vanity number, a {@code tel:} link
   * or an {@code ext} never reaches the parser.
   */
  private static final Pattern TYPED = Pattern.compile("[0-9+()\\-. ]{1,32}");

  private static final PhoneNumberUtil PHONE_NUMBERS = PhoneNumberUtil.getInstance();

  /** The region whose national format a number without a country code is read in. */
  private static final String SOUTH_AFRICA = "ZA";

  private final String e164;

  private MobileNumber(String e164) {
    this.e164 = e164;
  }

  /**
   * Reads a mobile number as a customer typed it.
   *
   * @param input the number in any of the documented formats, or {@code null}
   * @return the number, or empty when it is missing, malformed, foreign, has an extension, or is
   *     not a mobile number
   */
  public static Optional<MobileNumber> parse(@Nullable String input) {
    if (input == null || input.isBlank() || !TYPED.matcher(input).matches()) {
      return Optional.empty();
    }
    try {
      PhoneNumber number = PHONE_NUMBERS.parse(input, SOUTH_AFRICA);
      if (number.hasExtension() || PHONE_NUMBERS.getNumberType(number) != PhoneNumberType.MOBILE) {
        return Optional.empty();
      }
      String e164 = PHONE_NUMBERS.format(number, PhoneNumberFormat.E164);
      return E164.matcher(e164).matches() ? Optional.of(new MobileNumber(e164)) : Optional.empty();
    } catch (NumberParseException unreadable) {
      return Optional.empty();
    }
  }

  /**
   * Wraps a number read back from the database. It checks the stored form only, never the number
   * type again, so an update to libphonenumber's metadata cannot make a stored number unreadable.
   *
   * @param stored the number in E.164 form, as the {@code customer_mobile} column holds it
   * @return the number
   * @throws IllegalArgumentException if the value is not a stored South African mobile number
   */
  public static MobileNumber ofE164(String stored) {
    if (stored == null || !E164.matcher(stored).matches()) {
      throw new IllegalArgumentException("Not a stored South African mobile number");
    }
    return new MobileNumber(stored);
  }

  /**
   * Returns the number in the form it is stored and sent in.
   *
   * @return for example {@code +27825550101}
   */
  public String e164() {
    return e164;
  }

  /**
   * Returns the number as South Africans write it. Shown only to the customer it belongs to.
   *
   * @return for example {@code 082 555 0101}
   */
  public String display() {
    String national = national();
    return national.substring(0, 3) + " " + national.substring(3, 6) + " " + national.substring(6);
  }

  /**
   * Returns the number with its middle hidden, for a caller who has just proved it.
   *
   * @return for example {@code 082 *** 0101}
   */
  public String masked() {
    return national().substring(0, 3) + " *** " + last4();
  }

  /**
   * Returns the number with everything but its prefix hidden, for every other surface.
   *
   * @return for example {@code 082 *** ****}
   */
  public String redacted() {
    return national().substring(0, 3) + " *** ****";
  }

  /**
   * Returns the last four digits, which a customer gives with the reference to open an appointment.
   *
   * @return for example {@code 0101}
   */
  public String last4() {
    return e164.substring(e164.length() - 4);
  }

  /** The national form: 0 and the nine digits after the country code. */
  private String national() {
    return "0" + e164.substring(3);
  }

  /**
   * Tells whether two numbers are the same, whatever form they were typed in.
   *
   * @param other the object to compare with
   * @return {@code true} if both are the same number
   */
  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof MobileNumber number && e164.equals(number.e164);
  }

  @Override
  public int hashCode() {
    return e164.hashCode();
  }

  /**
   * Returns the redacted form, so the number cannot reach a log through string concatenation.
   *
   * @return for example {@code 082 *** ****}
   */
  @Override
  public String toString() {
    return redacted();
  }
}
