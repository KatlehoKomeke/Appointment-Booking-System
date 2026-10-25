package za.co.fynbos.branchbook.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** BR-12: the Idempotency-Key header must be a UUID in its canonical form. */
class IdempotencyKeyTest {

  @Test
  void acceptsTheCanonicalUuidFormInEitherCase() {
    UUID key = UUID.fromString("8c4f1a2e-1b3d-4e5f-9a6b-7c8d9e0f1a2b");

    assertThat(IdempotencyKey.parse("8c4f1a2e-1b3d-4e5f-9a6b-7c8d9e0f1a2b"))
        .map(IdempotencyKey::value)
        .contains(key);
    assertThat(IdempotencyKey.parse("8C4F1A2E-1B3D-4E5F-9A6B-7C8D9E0F1A2B"))
        .map(IdempotencyKey::value)
        .contains(key);
  }

  /** {@code UUID.fromString} alone would accept {@code 1-1-1-1-1}; the header may not. */
  @ParameterizedTest(name = "[{index}] {0}")
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "1-1-1-1-1",
        "not-a-uuid",
        "{8c4f1a2e-1b3d-4e5f-9a6b-7c8d9e0f1a2b}",
        "8c4f1a2e1b3d4e5f9a6b7c8d9e0f1a2b",
        " 8c4f1a2e-1b3d-4e5f-9a6b-7c8d9e0f1a2b"
      })
  void acceptsOnlyTheCanonicalUuidForm(String header) {
    assertThat(IdempotencyKey.parse(header)).isEmpty();
  }
}
