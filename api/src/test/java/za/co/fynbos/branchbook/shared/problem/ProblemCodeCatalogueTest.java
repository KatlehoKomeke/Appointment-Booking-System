package za.co.fynbos.branchbook.shared.problem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Keeps docs/errors.md and {@link ProblemCode} in step: every code in the enum is documented with
 * the same HTTP status, and the documentation lists no code that does not exist.
 */
class ProblemCodeCatalogueTest {

  /** The catalogue, relative to api/, where Maven runs the tests. */
  private static final Path ERRORS = Path.of("..", "docs", "errors.md");

  /** A row of the catalogue table: {@code | `SLOT_FULL` | 409 | ...}. */
  private static final Pattern ROW = Pattern.compile("^\\| `([A-Z_]+)` \\| (\\d{3}) \\|");

  @Test
  void everyCodeIsDocumentedWithItsStatus() throws IOException {
    Map<String, Integer> documented =
        Files.readAllLines(ERRORS).stream()
            .map(ROW::matcher)
            .filter(Matcher::find)
            .collect(Collectors.toMap(row -> row.group(1), row -> Integer.parseInt(row.group(2))));
    Map<String, Integer> implemented =
        Arrays.stream(ProblemCode.values())
            .collect(Collectors.toMap(Enum::name, ProblemCode::status));

    assertThat(documented).containsExactlyInAnyOrderEntriesOf(implemented);
  }
}
