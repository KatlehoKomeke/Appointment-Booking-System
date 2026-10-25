package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * BR-20: application SQL never reads the database's clock. Every time-dependent statement binds the
 * instant from the application's {@link java.time.Clock}, so the database and the JVM can never
 * disagree about "now" and tests control time. The migrations' {@code DEFAULT now()} columns are a
 * convenience for raw inserts only, so they are not scanned.
 *
 * <p>The test reads every string literal and text block in the main sources, skipping comments and
 * Java code, and fails on any of PostgreSQL's ways of reading the current date or time.
 */
class NativeSqlUsesBoundNowTest {

  private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

  /**
   * PostgreSQL's current-time functions and special values (PostgreSQL 17 manual, sections 9.9.5
   * and 8.5.1.4), in any letter case.
   */
  private static final Pattern DATABASE_CLOCK =
      Pattern.compile(
          "\\b(now|clock_timestamp|statement_timestamp|transaction_timestamp|timeofday)\\s*\\("
              + "|\\bcurrent_(date|time|timestamp)\\b"
              + "|\\blocal(time|timestamp)\\b"
              + "|'\\s*(now|today|tomorrow|yesterday)\\s*'",
          Pattern.CASE_INSENSITIVE);

  @Test
  void noJavaSqlLiteralCallsNow() throws IOException {
    List<String> literals = new ArrayList<>();
    List<String> offences = new ArrayList<>();
    try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        for (String literal : stringLiterals(Files.readString(file))) {
          literals.add(literal);
          if (DATABASE_CLOCK.matcher(literal).find()) {
            offences.add(file + ": " + literal.strip());
          }
        }
      }
    }

    // The scan must have seen the real statements, or an empty result would prove nothing.
    assertThat(literals).anyMatch(literal -> literal.contains("FROM appointments"));
    assertThat(offences).as("string literals that read the database's clock").isEmpty();
  }

  /** The scan itself: it finds the clock in strings and text blocks, never in comments or code. */
  @Test
  void scanFindsTheClockInStringsAndTextBlocksOnly() {
    String source =
        """
        // A comment may say SELECT now().
        /* So may CURRENT_TIMESTAMP in a block comment, with a quote " in it. */
        class Sample {
          Instant at = Instant.now();
          char quote = '"';
          String escaped = "a \\" quote, then localtimestamp";
          String block = \"""
              SELECT 1 FROM appointments WHERE starts_at > Now ()\""";
          String bound = "SELECT 1 FROM appointments WHERE starts_at > :now";
          String special = "SELECT 'today'::date";
        }
        """;

    List<String> found =
        stringLiterals(source).stream()
            .filter(literal -> DATABASE_CLOCK.matcher(literal).find())
            .map(String::strip)
            .toList();

    assertThat(found)
        .containsExactly(
            "a \\\" quote, then localtimestamp",
            "SELECT 1 FROM appointments WHERE starts_at > Now ()",
            "SELECT 'today'::date");
  }

  /**
   * Returns the raw contents of every string literal and text block in a Java source, escapes left
   * as written. Comments and character literals are skipped, so a quote inside them cannot open a
   * string.
   */
  private static List<String> stringLiterals(String source) {
    List<String> literals = new ArrayList<>();
    int i = 0;
    while (i < source.length()) {
      if (source.startsWith("//", i)) {
        int end = source.indexOf('\n', i);
        i = end < 0 ? source.length() : end + 1;
      } else if (source.startsWith("/*", i)) {
        int end = source.indexOf("*/", i + 2);
        i = end < 0 ? source.length() : end + 2;
      } else if (source.startsWith("\"\"\"", i)) {
        int end = closing(source, i + 3, "\"\"\"");
        literals.add(source.substring(i + 3, end));
        i = end + 3;
      } else if (source.charAt(i) == '"') {
        int end = closing(source, i + 1, "\"");
        literals.add(source.substring(i + 1, end));
        i = end + 1;
      } else if (source.charAt(i) == '\'') {
        i = closing(source, i + 1, "'") + 1;
      } else {
        i++;
      }
    }
    return literals;
  }

  /** The index of the terminator that closes a literal, stepping over backslash escapes. */
  private static int closing(String source, int from, String terminator) {
    int i = from;
    while (i < source.length() && !source.startsWith(terminator, i)) {
      i += source.charAt(i) == '\\' ? 2 : 1;
    }
    return Math.min(i, source.length());
  }
}
