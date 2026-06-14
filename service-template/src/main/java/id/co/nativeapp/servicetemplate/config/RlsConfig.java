package id.co.nativeapp.servicetemplate.config;

import id.co.nativeapp.tenant.RlsConnectionInitializer;
import id.co.nativeapp.tenant.RlsTransactionSynchronizer;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the row-level-security plumbing from {@code libs/tenant} into the service context so {@link
 * RlsAutoApplyAspect} can drive it.
 *
 * <p>{@code libs/tenant} ships these as plain (non-Spring-managed) classes on purpose, so they stay
 * framework-light and unit-testable; each service declares the beans here, bound to its own {@link
 * DataSource}. Together with the aspect this gives every {@code @Transactional} unit of work an
 * automatic {@code SET LOCAL app.current_tenant} from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}.
 */
@Configuration
public class RlsConfig {

  @Bean
  public RlsConnectionInitializer rlsConnectionInitializer() {
    return new RlsConnectionInitializer();
  }

  @Bean
  public RlsTransactionSynchronizer rlsTransactionSynchronizer(
      DataSource dataSource, RlsConnectionInitializer initializer) {
    return new RlsTransactionSynchronizer(dataSource, initializer);
  }
}
