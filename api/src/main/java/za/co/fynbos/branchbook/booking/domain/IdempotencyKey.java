package za.co.fynbos.branchbook.booking.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The {@code Idempotency-Key} a client sends with a booking, so that a retry of the same request
 * returns the same appointment instead of booking twice (BR-12).
 *
 * @param value the key
 */
public record IdempotencyKey(UUID value) {

  /**
   * The only accepted form: a UUID in its canonical 8-4-4-4-12 hexadecimal layout. {@link
   * UUID#fromString} alone would also accept short forms such as {@code 1-1-1-1-1}.
   */
  private static final Pattern CANONICAL =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

  /** Rejects a missing key. */
  public IdempotencyKey {
    Objects.requireNonNull(value, "value");
  }

  /**
   * Reads the header a client sent.
   *
   * @param header the {@code Idempotency-Key} header, or {@code null}
   * @return the key, or empty when the header is missing or not a canonical UUID
   */
  public static Optional<IdempotencyKey> parse(@Nullable String header) {
    if (header == null || !CANONICAL.matcher(header).matches()) {
      return Optional.empty();
    }
    return Optional.of(new IdempotencyKey(UUID.fromString(header.toLowerCase(Locale.ROOT))));
  }
}
