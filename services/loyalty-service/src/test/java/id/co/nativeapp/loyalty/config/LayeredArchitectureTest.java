package id.co.nativeapp.loyalty.config;

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
 * feature's LAYER SUB-PACKAGES. Copied from barbershop-service's {@code LayeredArchitectureTest}
 * with the package renamed (ADR 0027, Phase 4).
 *
 * <p>Each feature package {@code id.co.nativeapp.loyalty.<feature>} holds its classes in a layer
 * sub-package ({@code .controller}, {@code .service}, {@code .repository}, {@code .domain}, {@code
 * .dto}, {@code .messaging}, {@code .projection}). The layered-direction rule is expressed with
 * ArchUnit's {@link com.tngtech.archunit.library.Architectures#layeredArchitecture()
 * layeredArchitecture()}, matching layers by package-name suffix and {@code
 * consideringOnlyDependenciesInLayers()} so the cross-cutting {@code config} package and the shared
 * libs are out of scope.
 *
 * <p><strong>Projection is a REQUIRED layer</strong> (earnrule/giftcard use it for their read
 * models), even though the {@code member} feature's PII-bearing reads deliberately load the full
 * entity instead (documented in {@code member.repository.LoyaltyMemberRepository}).
 */
class LayeredArchitectureTest {

  private static final String BASE_PACKAGE = "id.co.nativeapp.loyalty";

  /**
   * Fully-qualified names of types that must NEVER hold a monetary amount on a persistent
   * {@code @Entity}/{@code @Embeddable} (HR-8): the boxed floating types and {@code BigDecimal},
   * plus the primitive {@code float}/{@code double}.
   */
  private static final Set<String> BANNED_MONEY_FIELD_TYPES =
      Set.of("float", "double", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal");

  /** Simple names of the RLS wiring beans whose single source of truth is libs/tenant. */
  private static final Set<String> RLS_WIRING_TYPES =
      Set.of("RlsConnectionInitializer", "RlsTransactionSynchronizer", "RlsAutoApplyAspect");

  /** The Spring request-mapping annotations that mark a controller method as an HTTP handler. */
  private static final Set<Class<? extends java.lang.annotation.Annotation>> HANDLER_MAPPINGS =
      Set.of(
          org.springframework.web.bind.annotation.RequestMapping.class,
          org.springframework.web.bind.annotation.GetMapping.class,
          org.springframework.web.bind.annotation.PostMapping.class,
          org.springframework.web.bind.annotation.PutMapping.class,
          org.springframework.web.bind.annotation.PatchMapping.class,
          org.springframework.web.bind.annotation.DeleteMapping.class);

  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);
  }

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
            .layer("Projection")
            .definedBy("..projection..")
            .whereLayer("Controller")
            .mayNotBeAccessedByAnyLayer()
            .whereLayer("Messaging")
            .mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Service")
            .mayOnlyBeAccessedByLayers("Controller", "Messaging")
            .whereLayer("Repository")
            .mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Dto")
            .mayOnlyBeAccessedByLayers("Controller", "Service", "Messaging")
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Controller", "Messaging", "Service", "Repository", "Dto")
            .whereLayer("Projection")
            .mayOnlyBeAccessedByLayers("Service", "Repository")
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
  void apiHandlersAreDocumentedWithAnOperation() {
    ArchRule rule =
        classes()
            .that()
            .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .and()
            .resideOutsideOfPackage("..config..")
            .should(documentEveryHandlerWithAnOperation())
            .as(
                "every @RequestMapping handler on a business @RestController carries an @Operation"
                    + " (OpenAPI docs — ENGINEERING-STANDARDS §1.3)")
            .allowEmptyShould(true);
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
  void repositoryQueriesAreNative() {
    ArchRule rule =
        classes()
            .that()
            .areAssignableTo(org.springframework.data.repository.Repository.class)
            .and()
            .resideInAPackage(BASE_PACKAGE + "..")
            .should(haveOnlyNativeAtQueryMethods())
            .as(
                "every @Query on a repository is a native query (CLAUDE.md native-query convention)");
    rule.check(classes);
  }

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

  private static ArchCondition<JavaClass> haveOnlyNativeAtQueryMethods() {
    return new ArchCondition<>("have only native @Query methods") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (JavaMethod method : item.getMethods()) {
          if (method.isAnnotatedWith(org.springframework.data.jpa.repository.Query.class)) {
            org.springframework.data.jpa.repository.Query query =
                method.getAnnotationOfType(org.springframework.data.jpa.repository.Query.class);
            if (!query.nativeQuery()) {
              events.add(
                  SimpleConditionEvent.violated(
                      method,
                      String.format(
                          "%s.%s carries a non-native @Query (JPQL) — repository queries must be"
                              + " native (nativeQuery = true) per the CLAUDE.md native-query"
                              + " convention",
                          item.getName(), method.getName())));
            }
          }
        }
      }
    };
  }

  private static ArchCondition<JavaClass> documentEveryHandlerWithAnOperation() {
    return new ArchCondition<>("document every request-mapping handler with @Operation") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (JavaMethod method : item.getMethods()) {
          boolean isHandler = HANDLER_MAPPINGS.stream().anyMatch(a -> method.isAnnotatedWith(a));
          if (isHandler && !method.isAnnotatedWith(io.swagger.v3.oas.annotations.Operation.class)) {
            events.add(
                SimpleConditionEvent.violated(
                    method,
                    String.format(
                        "%s.%s is an HTTP handler with no @Operation — add an OpenAPI"
                            + " io.swagger.v3.oas.annotations.Operation summary"
                            + " (ENGINEERING-STANDARDS §1.3)",
                        item.getName(), method.getName())));
          }
        }
      }
    };
  }

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
