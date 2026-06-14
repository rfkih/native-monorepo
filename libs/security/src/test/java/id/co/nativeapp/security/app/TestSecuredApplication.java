package id.co.nativeapp.security.app;

import id.co.nativeapp.tenant.RlsConnectionInitializer;
import id.co.nativeapp.tenant.RlsTransactionSynchronizer;
import javax.sql.DataSource;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * A minimal but faithful stand-in for a real Native business service, used to prove #16 end to end.
 *
 * <p>It boots the SAME shape every service carries — JPA over PostgreSQL, the {@code libs/tenant}
 * RLS plumbing wired exactly as each service's {@code RlsConfig} does, and the auto-RLS aspect that
 * sets {@code app.current_tenant} on every {@code @Transactional} unit of work — but adds NO
 * security code of its own. The local JWT validation and tenant binding come solely from {@code
 * libs/security}'s auto-configuration on the classpath, which is the whole point: a service gets
 * the behaviour by depending on the library.
 */
@SpringBootApplication
public class TestSecuredApplication {

  /** Wire the {@code libs/tenant} RLS plumbing exactly as each service's {@code RlsConfig} does. */
  @Bean
  RlsConnectionInitializer rlsConnectionInitializer() {
    return new RlsConnectionInitializer();
  }

  @Bean
  RlsTransactionSynchronizer rlsTransactionSynchronizer(
      DataSource dataSource, RlsConnectionInitializer initializer) {
    return new RlsTransactionSynchronizer(dataSource, initializer);
  }

  @Bean
  RlsAutoApplyAspect rlsAutoApplyAspect(RlsTransactionSynchronizer synchronizer) {
    return new RlsAutoApplyAspect(synchronizer);
  }

  /**
   * The auto-RLS aspect, identical in behaviour to each service's copy: wraps every
   * {@code @Transactional} method so the bound tenant is pushed onto the active transaction before
   * the method body runs.
   */
  @Aspect
  @Component
  static class RlsAutoApplyAspect implements Ordered {

    private final RlsTransactionSynchronizer synchronizer;

    RlsAutoApplyAspect(RlsTransactionSynchronizer synchronizer) {
      this.synchronizer = synchronizer;
    }

    @Around(
        "@annotation(org.springframework.transaction.annotation.Transactional)"
            + " || @within(org.springframework.transaction.annotation.Transactional)")
    public Object applyTenant(ProceedingJoinPoint pjp) throws Throwable {
      synchronizer.register();
      return pjp.proceed();
    }

    @Override
    public int getOrder() {
      return Ordered.LOWEST_PRECEDENCE;
    }
  }
}
