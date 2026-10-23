package za.co.fynbos.branchbook;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The structure of the modular monolith, enforced on every build (docs/architecture.md, section 3).
 *
 * <p>Modules are the top-level packages ({@code catalogue}, {@code booking}, {@code shared}, and
 * later {@code scheduling} and {@code notification}). Inside each, {@code api} handles HTTP, {@code
 * application} holds the use cases, {@code domain} the business rules, and {@code infrastructure}
 * the adapters to the database and the outside world.
 */
@AnalyzeClasses(
    packages = "za.co.fynbos.branchbook",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /** A dependency cycle between modules would make them impossible to separate later. */
  @ArchTest
  static final ArchRule modulesAreFreeOfCycles =
      slices().matching("za.co.fynbos.branchbook.(*)..").should().beFreeOfCycles();

  /** Business rules are plain Java, testable without a framework and portable to another one. */
  @ArchTest
  static final ArchRule domainHasNoSpringImports =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("org.springframework..");

  /** Controllers go through the use cases; they never reach the database adapters directly. */
  @ArchTest
  static final ArchRule controllersNeverReachInfrastructure =
      noClasses()
          .that()
          .resideInAPackage("..api..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("..infrastructure..");

  /** Transaction boundaries are use-case decisions, so only the application layer declares them. */
  @ArchTest
  static final ArchRule onlyApplicationOpensTransactions =
      noClasses()
          .that()
          .resideOutsideOfPackage("..application..")
          .should()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.transaction.annotation.Transactional")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("org.springframework.transaction.support.TransactionTemplate")
          .orShould()
          .dependOnClassesThat()
          .haveFullyQualifiedName("jakarta.transaction.Transactional");
}
