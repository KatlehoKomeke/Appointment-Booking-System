package za.co.fynbos.branchbook.shared.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which correlation ids a caller may choose, so a caller cannot write arbitrary text to the logs.
 */
class CorrelationIdsTest {

  private static final String UUID_PATTERN =
      "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

  @ParameterizedTest
  @ValueSource(strings = {"abc-123", "3f6d2c0e-7a1b-4d7e-9f0c-2b1a9e8d7c6b", "A"})
  void lettersDigitsAndHyphensAreKept(String requested) {
    assertThat(CorrelationIds.acceptOrCreate(requested)).isEqualTo(requested);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "has space", "line\nbreak", "semi;colon", "café"})
  void anythingElseIsReplacedWithANewId(String requested) {
    assertThat(CorrelationIds.acceptOrCreate(requested)).matches(UUID_PATTERN);
  }

  @ParameterizedTest
  @ValueSource(ints = {64, 65})
  void idsLongerThan64CharactersAreReplaced(int length) {
    String requested = "a".repeat(length);

    assertThat(CorrelationIds.acceptOrCreate(requested).equals(requested)).isEqualTo(length <= 64);
  }
}
