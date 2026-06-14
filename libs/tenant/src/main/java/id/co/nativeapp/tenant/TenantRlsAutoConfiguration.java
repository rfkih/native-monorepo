package id.co.nativeapp.tenant;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * The single auto-configuration entry point for Native's auto-applied row-level-security and JPA
 * auditing (#6). Registered in {@code
 * META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}, so any service
 * that puts {@code libs/tenant} on its classpath gets the rule-5 guarantee with no copied config
 * and no {@code @Import} of its own — exactly the pattern {@code libs/security}'s {@code
 * NativeSecurityAutoConfiguration} established.
 *
 * <p>It provides, for every service:
 *
 * <ul>
 *   <li>the {@link RlsConnectionInitializer} + {@link RlsTransactionSynchronizer} beans (the plain,
 *       framework-light SQL/transaction adapters {@code libs/tenant} ships) bound to the service's
 *       own {@link DataSource};
 *   <li>the {@link RlsAutoApplyAspect} ({@code @Around} every {@code @Transactional}) that pushes
 *       the bound tenant onto the active transaction's connection so {@code SET LOCAL
 *       app.current_tenant} engages automatically — the load-bearing rule-5 guarantee;
 *   <li>{@link EnableTransactionManagement} with the pinned {@link #TX_ADVISOR_ORDER} so Spring's
 *       transaction interceptor is at a strictly HIGHER precedence than the aspect and therefore
 *       always wraps it (the connection is bound before {@code SET LOCAL} runs); and
 *   <li>{@link JpaAuditingConfig} (via {@code @Import}) — {@code @EnableJpaAuditing} wiring {@link
 *       TenantAuditorAware} so {@code created_by}/{@code updated_by} are stamped from the tenant
 *       scope.
 * </ul>
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}, so a service that needs to customize any
 * piece can still declare its own bean and this auto-configuration steps aside. The whole
 * configuration is gated on {@code @ConditionalOnClass} the AspectJ + DataSource types it builds
 * on, so a non-JPA / non-web module that merely depends on {@code libs/tenant} for {@link
 * Auditable}/{@link TenantContext} does not get RLS wiring it has no datasource for. The aspect
 * bean is additionally {@code @ConditionalOnBean(DataSource.class)} so a slice with no datasource
 * (e.g. a {@code @WebMvcTest}) is unaffected.
 */
@AutoConfiguration
@AutoConfigureAfter(
    name = {
      "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
      "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration"
    })
@ConditionalOnClass({DataSource.class, org.aspectj.lang.ProceedingJoinPoint.class})
@EnableTransactionManagement(order = TenantRlsAutoConfiguration.TX_ADVISOR_ORDER)
@Import(JpaAuditingConfig.class)
public class TenantRlsAutoConfiguration {

  /**
   * Order of Spring's {@code @Transactional} advisor, pinned to a strictly HIGHER precedence (lower
   * value) than {@link RlsAutoApplyAspect} (which runs at {@link Ordered#LOWEST_PRECEDENCE}). This
   * guarantees the transaction interceptor always wraps the aspect: by the time the aspect
   * executes, the transaction is open and a JDBC connection is bound, so its {@code SET LOCAL
   * app.current_tenant} lands on the very connection the method's queries use.
   *
   * <p>Without an explicit order, BOTH the transaction advisor and the aspect default to {@code
   * LOWEST_PRECEDENCE} and their relative order is undefined — a latent way for the tenant GUC to
   * be applied <em>outside</em> the transaction, after which every query fails closed (0 rows) with
   * a green build. Because this is the load-bearing rule-5 tenant isolation guarantee, the ordering
   * is pinned here rather than left to chance.
   */
  public static final int TX_ADVISOR_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

  /**
   * The plain-JDBC {@code SET LOCAL app.current_tenant} initializer, unit-testable without Spring.
   */
  @Bean
  @ConditionalOnMissingBean
  public RlsConnectionInitializer rlsConnectionInitializer() {
    return new RlsConnectionInitializer();
  }

  /** Bridges {@link TenantContext} to the initializer on the active transaction's connection. */
  @Bean
  @ConditionalOnMissingBean
  public RlsTransactionSynchronizer rlsTransactionSynchronizer(
      DataSource dataSource, RlsConnectionInitializer initializer) {
    return new RlsTransactionSynchronizer(dataSource, initializer);
  }

  /** The {@code @Around}-every-{@code @Transactional} aspect that auto-applies the tenant GUC. */
  @Bean
  @ConditionalOnBean(DataSource.class)
  @ConditionalOnMissingBean
  public RlsAutoApplyAspect rlsAutoApplyAspect(RlsTransactionSynchronizer synchronizer) {
    return new RlsAutoApplyAspect(synchronizer);
  }
}
