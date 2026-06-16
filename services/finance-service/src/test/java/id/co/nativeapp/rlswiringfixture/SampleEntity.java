package id.co.nativeapp.rlswiringfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * A throwaway JPA entity used ONLY by {@code RlsAspectWiredTest}'s {@link
 * org.springframework.boot.test.context.runner.ApplicationContextRunner} so the in-memory JPA
 * metamodel is non-empty (so {@code @EnableJpaAuditing}, which {@code TenantRlsAutoConfiguration}
 * imports, builds).
 *
 * <p>It deliberately lives OUTSIDE the service's {@code id.co.nativeapp.finance} base package, so
 * the real {@code @SpringBootTest} entity scan never discovers it — otherwise Hibernate's {@code
 * ddl-auto: validate} against the migrated PostgreSQL schema would fail on a missing table for this
 * fixture entity. The wiring test reaches it explicitly via
 * {@code @EntityScan(basePackageClasses)}.
 */
@Entity
public class SampleEntity {
  @Id Long id;
}
