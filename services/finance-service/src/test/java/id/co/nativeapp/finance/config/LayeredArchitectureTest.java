package id.co.nativeapp.finance.config;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Enforces the Native LAYERED architecture (controller -> service -> repository -> domain) over the
 * feature's LAYER SUB-PACKAGES.
 *
 * <p>Each feature package {@code id.co.nativeapp.finance.<feature>} holds its classes in a layer
 * sub-package ({@code .controller}, {@code .service}, {@code .repository}, {@code .domain}, {@code
 * .dto}, {@code .messaging}). The layered-direction rule is expressed with ArchUnit's {@link
 * com.tngtech.archunit.library.Architectures#layeredArchitecture() layeredArchitecture()}, matching
 * layers by package-name suffix (e.g. {@code "..controller.."}) and {@code
 * consideringOnlyDependenciesInLayers()} so the cross-cutting {@code config} package and the shared
 * libs are out of scope. The remaining rules are the service-specific invariants (no-float money,
 * the RLS/auditing-wiring drift guard, the {@code @Entity}-at-the-boundary and
 * {@code @Transactional}-in-the-service-layer guards, naming-matches-stereotype) — retargeted to
 * the new layout, never weakened.
 */
class LayeredArchitectureTest {

  private static final String BASE_PACKAGE = "id.co.nativeapp.finance";

  /**
   * Fully-qualified names of types that must NEVER hold a monetary amount on a persistent
   * {@code @Entity}/{@code @Embeddable} (HR-8): the boxed floating types and {@code BigDecimal},
   * plus the primitive {@code float}/{@code double} (a primitive is not a class "dependency"
   * ArchUnit's {@code dependOnClassesThat} can see, so a field-type scan is required to cover
   * {@code double amount}).
   */
  private static final Set<String> BANNED_MONEY_FIELD_TYPES =
      Set.of("float", "double", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal");

  /** Simple names of the RLS wiring beans whose single source of truth is libs/tenant. */
  private static final Set<String> RLS_WIRING_TYPES =
      Set.of("RlsConnectionInitializer", "RlsTransactionSynchronizer", "RlsAutoApplyAspect");

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);
  }

  /**
   * The layered direction over the feature's layer sub-packages, matched by package-name suffix.
   * Dependencies point downward only: controller/messaging -> service -> repository -> domain, with
   * dto as the boundary-translation layer. {@code consideringOnlyDependenciesInLayers()} scopes the
   * check to dependencies whose target is one of these layers, so the cross-cutting {@code config}
   * package (the {@code @Configuration}/filter/advice set) and the shared libs are out of scope.
   */
  @Test
  void featureLayersRespectTheLayeredArchitecture() {
    ArchRule rule =
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Controller")
            .definedBy("..controller..")
            .layer("Messaging")
            .definedBy("..messaging..")
            .layer("Service")
            .definedBy("..service..")
            .layer("Repository")
            .definedBy("..repository..")
            .layer("Domain")
            .definedBy("..domain..")
            .layer("Dto")
            .definedBy("..dto..")
            // Controller is the HTTP entry point: no in-scope layer may depend on it.
            .whereLayer("Controller")
            .mayNotBeAccessedByAnyLayer()
            // Messaging holds the listeners (entry points) AND the producer-side Avro *Schema
            // holders the service layer builds outbox payloads with (CODE-STRUCTURE §3.2 lists
            // messaging as an allowed service dependency), so only the service layer may reach it.
            .whereLayer("Messaging")
            .mayOnlyBeAccessedByLayers("Service")
            // Service is reached only from the entry points (controllers + messaging listeners).
            .whereLayer("Service")
            .mayOnlyBeAccessedByLayers("Controller", "Messaging")
            // Repositories are a data port reached only from the service layer.
            .whereLayer("Repository")
            .mayOnlyBeAccessedByLayers("Service")
            // Dto is the boundary-translation layer used by the edges + the service.
            .whereLayer("Dto")
            .mayOnlyBeAccessedByLayers("Controller", "Service", "Messaging")
            // Domain is the floor: every layer may depend on it; it depends on none of these.
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Controller", "Messaging", "Service", "Repository", "Dto")
            .as(
                "controller/messaging -> service -> repository -> domain (no upward or skip edges)");
    rule.check(classes);
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

  /**
   * HR-8: no monetary amount on a persistent {@code @Entity}/{@code @Embeddable} may be a floating
   * type ({@code float}/{@code double}, primitive OR boxed) or {@code BigDecimal} — money is the
   * libs/money {@code Money} (integer minor units + ISO-4217 currency), persisted as the two-column
   * {@code MoneyEmbeddable}. This covers BOTH {@code @Embeddable} (where the money columns live)
   * and {@code @Entity}, and BOTH primitive and boxed floating types (a primitive field is not a
   * class dependency, so a field-type scan is required — {@code dependOnClassesThat} alone would
   * miss {@code double amount}).
   */
  @Test
  void entitiesHaveNoDecimalMoneyFields() {
    ArchRule rule =
        classes()
            .that()
            .areAnnotatedWith(jakarta.persistence.Entity.class)
            .or()
            .areAnnotatedWith(jakarta.persistence.Embeddable.class)
            .should(haveNoFloatingPointOrDecimalFields())
            .as(
                "money is libs/money Money (minor units + currency), never a float/double/BigDecimal"
                    + " field (HR-8)");
    rule.check(classes);
  }

  /**
   * Drift guard: the RLS + JPA-auditing wiring has a SINGLE source — libs/tenant's {@code
   * TenantRlsAutoConfiguration}. No service class may re-declare it by carrying
   * {@code @EnableTransactionManagement} / {@code @EnableJpaAuditing}, or by declaring a
   * {@code @Bean} of {@code RlsConnectionInitializer} / {@code RlsTransactionSynchronizer} / {@code
   * RlsAutoApplyAspect}. A per-service copy would drift from (and could mis-order) the load-bearing
   * rule-5 aspect, so it is banned statically here rather than discovered later in review.
   */
  @Test
  void servicesDoNotRedeclareTheRlsOrAuditingWiring() {
    ArchRule rule =
        classes()
            .that()
            .resideInAPackage(BASE_PACKAGE + "..")
            .should(notRedeclareTheRlsOrAuditingWiring())
            .as(
                "the RLS + JPA-auditing wiring is owned solely by libs/tenant"
                    + " (TenantRlsAutoConfiguration); a service must not redeclare it (HR-5 drift)");
    rule.check(classes);
  }

  /**
   * Positive condition (violated == bad): a field-type scan flagging any {@code float}/{@code
   * double}/{@code Float}/{@code Double}/{@code BigDecimal} field. Covers primitive AND boxed — a
   * primitive field is not a class dependency, so {@code dependOnClassesThat} would miss {@code
   * double amount}.
   */
  private static ArchCondition<JavaClass> haveNoFloatingPointOrDecimalFields() {
    return new ArchCondition<>("have no float/double/BigDecimal field") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (JavaField field : item.getFields()) {
          String typeName = field.getRawType().getName();
          if (BANNED_MONEY_FIELD_TYPES.contains(typeName)) {
            events.add(
                SimpleConditionEvent.violated(
                    field,
                    String.format(
                        "Field %s.%s is of banned monetary type %s (use libs/money Money /"
                            + " MoneyEmbeddable, HR-8)",
                        item.getName(), field.getName(), typeName)));
          }
        }
      }
    };
  }

  /**
   * Positive condition (violated == bad): flags a class that carries
   * {@code @EnableTransactionManagement} / {@code @EnableJpaAuditing}, or declares a {@code @Bean}
   * whose return type is one of the libs/tenant RLS wiring types.
   */
  private static ArchCondition<JavaClass> notRedeclareTheRlsOrAuditingWiring() {
    return new ArchCondition<>("not redeclare the libs/tenant RLS / JPA-auditing wiring") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        if (item.isAnnotatedWith(
            org.springframework.transaction.annotation.EnableTransactionManagement.class)) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getName()
                      + " is annotated @EnableTransactionManagement — that wiring lives ONLY in"
                      + " libs/tenant's TenantRlsAutoConfiguration (HR-5 drift)"));
        }
        if (item.isAnnotatedWith(
            org.springframework.data.jpa.repository.config.EnableJpaAuditing.class)) {
          events.add(
              SimpleConditionEvent.violated(
                  item,
                  item.getName()
                      + " is annotated @EnableJpaAuditing — that wiring lives ONLY in libs/tenant"
                      + " (TenantRlsAutoConfiguration)"));
        }
        for (JavaMethod method : item.getMethods()) {
          if (method.isAnnotatedWith(org.springframework.context.annotation.Bean.class)
              && RLS_WIRING_TYPES.contains(method.getRawReturnType().getSimpleName())) {
            events.add(
                SimpleConditionEvent.violated(
                    method,
                    String.format(
                        "%s.%s declares an RLS-wiring @Bean (%s) — owned by libs/tenant only"
                            + " (HR-5 drift)",
                        item.getName(),
                        method.getName(),
                        method.getRawReturnType().getSimpleName())));
          }
        }
      }
    };
  }
}
