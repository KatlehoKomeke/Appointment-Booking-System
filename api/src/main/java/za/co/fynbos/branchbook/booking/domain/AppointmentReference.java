package za.co.fynbos.branchbook.booking.domain;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The reference a customer uses to find an appointment, such as {@code APT-7K3M-9Q2X} (BR-11).
 *
 * <p>The eight characters come from a 31-character alphabet without I, L, O, 0 and 1, which are
 * easily misread when a reference is read out or typed from an SMS. They are drawn with {@link
 * SecureRandom}, so references cannot be guessed: there are 31<sup>8</sup>, about 852 billion. The
 * database's unique and format checks back both properties.
 *
 * @param value the reference in its stored form, for example {@code APT-7K3M-9Q2X}
 */
public record AppointmentReference(String value) {

  /** The characters a reference is made of. */
  public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

  /**
   * The stored form. The same text as the {@code appointments_reference_format} check, which
   * SchemaConstraintIT compares with this constant.
   */
  public static final String PATTERN = "^APT-[A-HJKMNP-Z2-9]{4}-[A-HJKMNP-Z2-9]{4}$";

  private static final Pattern STORED = Pattern.compile(PATTERN);

  /** What a customer may type: any letter case, with or without the hyphens. */
  private static final Pattern TYPED =
      Pattern.compile("^APT-?([A-HJKMNP-Z2-9]{4})-?([A-HJKMNP-Z2-9]{4})$");

  /** Longer input is refused before it is read. */
  private static final int MAX_INPUT_LENGTH = 32;

  private static final int RANDOM_CHARACTERS = 8;

  /** Rejects a value that is not in the stored form. */
  public AppointmentReference {
    Objects.requireNonNull(value, "value");
    if (!STORED.matcher(value).matches()) {
      throw new IllegalArgumentException("Not an appointment reference");
    }
  }

  /**
   * Draws a new reference.
   *
   * @param random the source of randomness, a {@link SecureRandom} so references cannot be guessed
   * @return a reference such as {@code APT-7K3M-9Q2X}
   */
  public static AppointmentReference generate(SecureRandom random) {
    StringBuilder reference = new StringBuilder("APT-");
    for (int i = 0; i < RANDOM_CHARACTERS; i++) {
      if (i == RANDOM_CHARACTERS / 2) {
        reference.append('-');
      }
      reference.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return new AppointmentReference(reference.toString());
  }

  /**
   * Reads a reference as a customer typed it: surrounding spaces, lower case and missing hyphens
   * are all accepted.
   *
   * @param input the reference, or {@code null}
   * @return the reference in its stored form, or empty when the input cannot be one
   */
  public static Optional<AppointmentReference> parse(@Nullable String input) {
    if (input == null || input.length() > MAX_INPUT_LENGTH) {
      return Optional.empty();
    }
    Matcher typed = TYPED.matcher(input.strip().toUpperCase(Locale.ROOT));
    if (!typed.matches()) {
      return Optional.empty();
    }
    return Optional.of(new AppointmentReference("APT-" + typed.group(1) + "-" + typed.group(2)));
  }

  /**
   * Hides the reference, which is half of what opens an appointment, so one passed to a logger or
   * put in a message by mistake shows nothing (BR-19). {@link #value()} is the stored form.
   *
   * @return {@code APT-****-****}
   */
  @Override
  public String toString() {
    return "APT-****-****";
  }
}
