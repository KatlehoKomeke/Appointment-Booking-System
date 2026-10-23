package za.co.fynbos.branchbook;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Enforces the part of the documentation standard (CONTRIBUTING.md) that the compiler cannot: every
 * package explains its responsibility in a {@code package-info.java}. The compiler's Javadoc lint
 * then checks that the comment is present and well formed.
 */
class PackageDocumentationTest {

  private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

  @Test
  void everyPackageHasAPackageInfo() throws IOException {
    List<Path> undocumented;
    try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
      undocumented =
          files
              .filter(file -> file.toString().endsWith(".java"))
              .map(Path::getParent)
              .distinct()
              .filter(directory -> !Files.exists(directory.resolve("package-info.java")))
              .toList();
    }

    assertThat(undocumented).as("packages without a package-info.java").isEmpty();
  }
}
