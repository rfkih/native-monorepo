package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.rlswiringfixture.SampleEntity;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.RlsConnectionInitializer;
import id.co.nativeapp.tenant.RlsTransactionSynchronizer;
import id.co.nativeapp.tenant.TenantRlsAutoConfiguration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Defense-in-depth (rule 5): the load-bearing {@link RlsAutoApplyAspect} — the {@code @Around}
 * every {@code @Transactional} that sets the tenant GUC so Postgres RLS engages — must actually be
 * wired into THIS service's context. Until now the RLS wiring was proven only in aggregate (the
 * {@code libs/tenant} {@code RlsOrderingGuardTest} + the per-service Testcontainers isolation
 * suites); a service that dropped {@code libs/tenant} from its classpath, or shadowed the aspect
 * with its own bean, could regress the guarantee with a green build elsewhere.
 *
 * <p>Fast and container-free: an {@link ApplicationContextRunner} stands up a realistic DEFAULT
 * finance-service persistence context — an in-memory H2 {@link DataSource} + JPA — and loads the
 * exact {@link TenantRlsAutoConfiguration} the service inherits from {@code libs/tenant}, then
 * asserts the {@code rlsAutoApplyAspect} bean (plus its synchronizer/initializer collaborators) is
 * contributed. JPA is present because the auto-config {@code @Imports} {@code JpaAuditingConfig}
 * ({@code @EnableJpaAuditing} needs a non-empty JPA metamodel); a TRIVIAL test-local entity is
 * scanned so the persistence unit builds without touching the service's real (PostgreSQL-specific)
 * entities. No PostgreSQL, no Spring Boot boot — pure wiring assertion. RLS *behaviour* stays the
 * Testcontainers isolation suites' job.
 */
class RlsAspectWiredTest {

  @Test
  void rlsAutoApplyAspectBeanIsWiredFromLibsTenant() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                TenantRlsAutoConfiguration.class))
        .withUserConfiguration(EntityScanConfig.class)
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:finance-rls-aspect-wired;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context)
                  .as("the auto-applied RLS aspect (rule 5) must be wired into finance-service")
                  .hasSingleBean(RlsAutoApplyAspect.class)
                  .hasBean("rlsAutoApplyAspect");
              assertThat(context).hasSingleBean(RlsTransactionSynchronizer.class);
              assertThat(context).hasSingleBean(RlsConnectionInitializer.class);
            });
  }

  /**
   * Scans ONLY the out-of-app-base-package {@link SampleEntity} fixture so the persistence unit
   * builds, WITHOUT making the fixture discoverable by the real {@code @SpringBootTest} entity scan
   * (which would fail {@code ddl-auto: validate} on a missing table).
   */
  @Configuration(proxyBeanMethods = false)
  @EntityScan(basePackageClasses = SampleEntity.class)
  static class EntityScanConfig {}
}
