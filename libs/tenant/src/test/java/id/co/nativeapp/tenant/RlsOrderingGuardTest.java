package id.co.nativeapp.tenant;

import static org.assertj.core.api.Assertions.assertThat;

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
 * FAST guards protecting the load-bearing rule-5 ordering invariant, catching a precedence or
 * condition regression at the unit level — long before (and far cheaper than) the Testcontainers
 * RLS isolation read in {@link RlsIsolationTest} would surface it as a silent "0 rows" failure.
 *
 * <p>Two things must hold for auto-applied RLS to actually engage:
 *
 * <ol>
 *   <li><strong>Ordering.</strong> Spring's {@code @Transactional} advisor must run at a strictly
 *       HIGHER precedence (a strictly LOWER {@code order} value) than {@link RlsAutoApplyAspect},
 *       so the transaction interceptor always WRAPS the aspect: by the time the aspect's {@code SET
 *       LOCAL app.current_tenant} runs, the transaction is open and a connection is bound, so the
 *       GUC lands on the very connection the method's queries use. If the aspect ever ran outside
 *       the transaction, every query would fail closed (0 rows) with a green build — exactly the
 *       latent failure this test makes loud.
 *   <li><strong>Wiring presence.</strong> In a default (no-override) context the auto-configuration
 *       must actually contribute the {@link RlsAutoApplyAspect} and {@link
 *       RlsTransactionSynchronizer} beans. A future {@code @Conditional} regression that silently
 *       drops either bean would disable auto-RLS; this fails the build instead.
 * </ol>
 */
class RlsOrderingGuardTest {

  /**
   * The tx advisor's pinned order must be strictly less than the aspect's order, so the tx
   * interceptor wraps the aspect (lower value = higher precedence = runs first/outermost).
   */
  @Test
  void txAdvisorOrderIsStrictlyHigherPrecedenceThanTheRlsAspect() {
    int aspectOrder = new RlsAutoApplyAspect(null).getOrder();

    assertThat(TenantRlsAutoConfiguration.TX_ADVISOR_ORDER)
        .as(
            "the @Transactional advisor must out-prioritise (lower order than) the RLS aspect so it "
                + "wraps it; otherwise SET LOCAL could run outside the transaction and every query "
                + "fails closed")
        .isLessThan(aspectOrder);
  }

  /**
   * In a default context (a DataSource present, no user override) the auto-configuration
   * contributes both the aspect and the synchronizer — the beans auto-RLS depends on. A
   * condition/precedence regression that drops either is caught here at the unit level.
   */
  @Test
  void defaultContextContributesTheRlsAspectAndSynchronizerBeans() {
    new ApplicationContextRunner()
        // A realistic DEFAULT service context: an (H2) DataSource + JPA, plus the auto-config under
        // test. JPA is included because TenantRlsAutoConfiguration @Imports JpaAuditingConfig
        // (@EnableJpaAuditing needs the JPA mapping context); the SampleEntity in this package is
        // scanned so the persistence unit builds. The point is to assert WIRING in a no-override
        // context — RLS *behaviour* is RlsIsolationTest's job against real PostgreSQL.
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                TenantRlsAutoConfiguration.class))
        .withUserConfiguration(EntityScanConfig.class)
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:rls-ordering-guard;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.hibernate.ddl-auto=create-drop")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).hasSingleBean(DataSource.class);
              assertThat(context).hasSingleBean(RlsAutoApplyAspect.class);
              assertThat(context).hasSingleBean(RlsTransactionSynchronizer.class);
              assertThat(context).hasSingleBean(RlsConnectionInitializer.class);
            });
  }

  /** Makes the package's {@link SampleEntity} discoverable so the JPA persistence unit builds. */
  @Configuration(proxyBeanMethods = false)
  @EntityScan(basePackageClasses = SampleEntity.class)
  static class EntityScanConfig {}
}
