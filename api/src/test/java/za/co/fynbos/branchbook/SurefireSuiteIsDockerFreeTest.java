package za.co.fynbos.branchbook;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * NFR-4: {@code ./mvnw test} runs without Docker. Surefire runs the classes named {@code *Test} and
 * Failsafe those named {@code *IT}, so the split holds only while no {@code *Test} starts the
 * application or a database container. These rules fail the unit run the moment one does, and make
 * every test that starts the application end in {@code IT}, so it can never slip into the unit run
 * under another name.
 */
@AnalyzeClasses(
    packages = "za.co.fynbos.branchbook",
    importOptions = ImportOption.OnlyIncludeTests.class)
class SurefireSuiteIsDockerFreeTest {

  /** A unit test that boots the application or a container would need Docker. */
  @ArchTest
  static final ArchRule unitTestsStartNeitherTheApplicationNorAContainer =
      noClasses()
          .that()
          .haveSimpleNameEndingWith("Test")
          .and()
          .areNotAnnotations()
          .should()
          .beMetaAnnotatedWith(SpringBootTest.class)
          .orShould()
          .dependOnClassesThat()
          .resideInAPackage("org.testcontainers..")
          .because("Surefire runs every *Test, and ./mvnw test must work without Docker");

  /** A test that boots the whole application is an integration test, and is named like one. */
  @ArchTest
  static final ArchRule testsThatStartTheApplicationAreNamedIt =
      classes()
          .that()
          .areMetaAnnotatedWith(SpringBootTest.class)
          .and()
          .areNotAnnotations()
          .should()
          .haveSimpleNameEndingWith("IT")
          .because("Failsafe runs only *IT, against the shared PostgreSQL container");
}
