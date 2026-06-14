package id.co.nativeapp.org.config;

import id.co.nativeapp.tenant.JpaAuditingConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Cross-cutting persistence wiring for the service, kept off the {@code @SpringBootApplication}
 * bootstrap class so web-slice tests ({@code @WebMvcTest}) don't pull in JPA infrastructure.
 *
 * <p>It brings in:
 *
 * <ul>
 *   <li>{@link JpaAuditingConfig} from {@code libs/tenant} — enables Spring Data JPA auditing so
 *       {@link id.co.nativeapp.tenant.Auditable Auditable}'s {@code created_by}/{@code updated_by}
 *       columns are stamped from the bound {@link id.co.nativeapp.tenant.TenantContext
 *       TenantContext}; and
 *   <li>{@link RlsConfig} — the {@code RlsConnectionInitializer} + {@code
 *       RlsTransactionSynchronizer} beans the {@link RlsAutoApplyAspect} drives to auto-apply the
 *       tenant GUC on every transaction.
 * </ul>
 *
 * <p>This is a user {@code @Configuration} under the application's component-scan root, so the full
 * context loads it while slice tests (which only load the controllers/curated auto-config they ask
 * for) do not.
 */
@Configuration
@EnableTransactionManagement(order = PersistenceConfig.TX_ADVISOR_ORDER)
@Import({JpaAuditingConfig.class, RlsConfig.class})
public class PersistenceConfig {

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
  static final int TX_ADVISOR_ORDER = Ordered.LOWEST_PRECEDENCE - 100;
}
