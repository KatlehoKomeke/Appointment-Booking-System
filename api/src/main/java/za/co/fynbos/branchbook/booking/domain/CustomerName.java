package za.co.fynbos.branchbook.booking.domain;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A customer's first or last name, as BranchBook stores it.
 *
 * <p>{@link #parse} normalises what a phone keyboard produces before it checks anything: it
 * composes accents (NFC), turns the curly apostrophes iOS types into {@code '}, collapses every
 * kind of space to one ordinary space, and trims ordinary spaces at both ends. The result must then
 * be 1 to {@value #MAX_CODE_POINTS} characters of letters, combining marks, spaces, hyphens and
 * apostrophes, starting with a letter. That is always within the database's {@code
 * appointments_customer_names_trimmed} check, so a parsed name can never fail it.
 *
 * <p>It is personal data, so {@link #toString()} hides it.
 */
public final class CustomerName {

  /** The most characters a name may have: the size of the database columns. */
  public static final int MAX_CODE_POINTS = 60;

  /** Longer input is refused before any normalisation, so a huge value costs nothing. */
  private static final int MAX_INPUT_LENGTH = 240;

  /** A letter, then letters, combining marks, spaces, hyphens and apostrophes. */
  private static final Pattern ALLOWED = Pattern.compile("^\\p{L}[\\p{L}\\p{M} '\\-]*$");

  /** U+2019 RIGHT SINGLE QUOTATION MARK: the apostrophe iOS and macOS type by default. */
  private static final char RIGHT_SINGLE_QUOTATION_MARK = (char) 0x2019;

  /** U+2018 LEFT SINGLE QUOTATION MARK, which smart punctuation can also produce. */
  private static final char LEFT_SINGLE_QUOTATION_MARK = (char) 0x2018;

  /** U+02BC MODIFIER LETTER APOSTROPHE, used as a letter in some orthographies. */
  private static final char MODIFIER_LETTER_APOSTROPHE = (char) 0x02BC;

  /** Every Unicode space character, such as a no-break space. */
  private static final Pattern SPACES = Pattern.compile("\\p{Zs}+");

  private final String value;

  private CustomerName(String value) {
    this.value = value;
  }

  /**
   * Reads a name as a customer typed it.
   *
   * @param input the name, or {@code null}
   * @return the normalised name, or empty when it is missing, too long, or has a character a name
   *     cannot have, such as a digit, punctuation other than a hyphen or apostrophe, or a line
   *     break
   */
  public static Optional<CustomerName> parse(@Nullable String input) {
    if (input == null || input.length() > MAX_INPUT_LENGTH) {
      return Optional.empty();
    }
    String name =
        Normalizer.normalize(input, Normalizer.Form.NFC)
            .replace(RIGHT_SINGLE_QUOTATION_MARK, '\'')
            .replace(LEFT_SINGLE_QUOTATION_MARK, '\'')
            .replace(MODIFIER_LETTER_APOSTROPHE, '\'');
    name = trimSpaces(SPACES.matcher(name).replaceAll(" "));
    int length = name.codePointCount(0, name.length());
    if (length == 0 || length > MAX_CODE_POINTS || !ALLOWED.matcher(name).matches()) {
      return Optional.empty();
    }
    return Optional.of(new CustomerName(name));
  }

  /**
   * Trims ordinary spaces at both ends only. {@link String#strip()} would also remove line breaks,
   * which must make the name invalid instead.
   */
  private static String trimSpaces(String text) {
    int start = 0;
    int end = text.length();
    while (start < end && text.charAt(start) == ' ') {
      start++;
    }
    while (end > start && text.charAt(end - 1) == ' ') {
      end--;
    }
    return text.substring(start, end);
  }

  /**
   * Returns the normalised name.
   *
   * @return the name, as stored
   */
  public String value() {
    return value;
  }

  /**
   * Tells whether two names are the same after normalisation.
   *
   * @param other the object to compare with
   * @return {@code true} if both hold the same normalised name
   */
  @Override
  public boolean equals(@Nullable Object other) {
    return other instanceof CustomerName name && value.equals(name.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  /**
   * Hides the name, so it cannot reach a log through string concatenation.
   *
   * @return a fixed text without personal data
   */
  @Override
  public String toString() {
    return "CustomerName[redacted]";
  }
}
