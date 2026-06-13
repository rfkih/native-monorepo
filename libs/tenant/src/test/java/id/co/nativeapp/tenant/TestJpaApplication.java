package id.co.nativeapp.tenant;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * Minimal Spring Boot configuration for the JPA-auditing slice test. Component
 * scanning over {@code id.co.nativeapp.tenant} picks up {@link SampleEntity},
 * {@link SampleEntityRepository}; {@link Import} brings in the production
 * {@link JpaAuditingConfig} so {@code @EnableJpaAuditing} and the
 * {@link TenantAuditorAware} bean are wired exactly as a real service would have
 * them.
 */
@SpringBootApplication
@Import(JpaAuditingConfig.class)
class TestJpaApplication {
}
