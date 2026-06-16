package id.co.nativeapp.rlswiringfixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/**
 * A throwaway JPA entity used ONLY by {@code RlsAspectWiredTest}'s {@link
 * org.springframework.boot.test.context.runner.ApplicationContextRunner} so the in-memory JPA
 * metamodel is non-empty (so {@code @EnableJpaAuditing}, which {@code TenantRlsAutoConfiguration}
 * imports, builds).
 *
 * <p>It deliberately lives OUTSIDE the service's {@code id.co.nativeapp.employee} base package, so
 * the real {@code @SpringBootTest} entity scan never discovers it (and Hibernate never tries to
 * wire the service's {@code @Converter} {@code PiiAttributeConverter} for it) — otherwise the real
 * persistence unit's {@code ddl-auto: validate} would fail on a missing table for this fixture
 * entity. The wiring test reaches it explicitly via {@code @EntityScan(basePackageClasses)}.
 */
@Entity
public class SampleEntity {
  @Id Long id;
}
