package id.co.nativeapp.finance.config;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Enforces the Native LAYERED architecture (controller -> service -> repository -> domain). */
class LayeredArchitectureTest {

  private static final String BASE_PACKAGE = "id.co.nativeapp.finance";
  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);
  }

  @Test
  void controllersMustNotDependOnRepositories() {
    ArchRule rule =
        noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .areAssignableTo(org.springframework.data.repository.Repository.class)
            .as("controllers go through the service layer, never the repository");
    rule.check(classes);
  }

  @Test
  void repositoriesAreAccessedOnlyByTheServiceLayer() {
    ArchRule rule =
        classes()
            .that()
            .areAssignableTo(org.springframework.data.repository.Repository.class)
            .and()
            .resideInAPackage(BASE_PACKAGE + "..")
            .should()
            .onlyHaveDependentClassesThat(
                describe(
                    "are in the service layer (*Service/*Writer/*Reader) or are repositories",
                    javaClass ->
                        javaClass.getSimpleName().endsWith("Service")
                            || javaClass.getSimpleName().endsWith("Writer")
                            || javaClass.getSimpleName().endsWith("Reader")
                            || javaClass.isAssignableTo(
                                org.springframework.data.repository.Repository.class)))
            .as("repositories are a data port used only from the service layer");
    rule.check(classes);
  }

  @Test
  void repositoriesMustNotDependOnServicesOrControllers() {
    ArchRule rule =
        noClasses()
            .that()
            .haveSimpleNameEndingWith("Repository")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Service")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Writer")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Controller")
            .as("repositories sit below the service layer and never depend upward");
    rule.check(classes);
  }

  @Test
  void servicesMustNotDependOnControllers() {
    ArchRule rule =
        noClasses()
            .that()
            .haveSimpleNameEndingWith("Service")
            .or()
            .haveSimpleNameEndingWith("Writer")
            .or()
            .haveSimpleNameEndingWith("Reader")
            .should()
            .dependOnClassesThat()
            .haveSimpleNameEndingWith("Controller")
            .as("the service layer never depends on the HTTP layer above it");
    rule.check(classes);
  }

  @Test
  void controllersMustNotDependOnEntities() {
    ArchRule rule =
        noClasses()
            .that()
            .haveSimpleNameEndingWith("Controller")
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(jakarta.persistence.Entity.class)
            .as("controllers expose DTOs, never JPA entities (no lazy-load/PII leaks)");
    rule.check(classes);
  }

  @Test
  void restControllersAreNamedController() {
    ArchRule rule =
        classes()
            .that()
            .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should()
            .haveSimpleNameEndingWith("Controller")
            .as("@RestController classes are named *Controller");
    rule.check(classes);
  }

  @Test
  void springServicesAreNamedService() {
    ArchRule rule =
        classes()
            .that()
            .areAnnotatedWith(org.springframework.stereotype.Service.class)
            .should()
            .haveSimpleNameEndingWith("Service")
            .orShould()
            .haveSimpleNameEndingWith("Reader")
            .as(
                "@Service classes are named *Service or *Reader (the read-side service stereotype)");
    rule.check(classes);
  }

  @Test
  void transactionalLivesOnlyInTheServiceLayer() {
    ArchRule rule =
        classes()
            .that()
            .containAnyMethodsThat(
                describe(
                    "are annotated with @Transactional",
                    method ->
                        method.isAnnotatedWith(
                            org.springframework.transaction.annotation.Transactional.class)))
            .should()
            .haveSimpleNameEndingWith("Service")
            .orShould()
            .haveSimpleNameEndingWith("Writer")
            .orShould()
            .haveSimpleNameEndingWith("Reader")
            .as("tx boundaries live in the service layer so the tx proxy and RLS aspect engage");
    rule.check(classes);
  }

  @Test
  void entitiesHaveNoDecimalMoneyFields() {
    ArchRule rule =
        noClasses()
            .that()
            .areAnnotatedWith(jakarta.persistence.Entity.class)
            .or()
            .areAnnotatedWith(jakarta.persistence.Embeddable.class)
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.math.BigDecimal")
            .as("money is libs/money Money (minor units + currency), never a float or BigDecimal");
    rule.check(classes);
  }
}
