package id.co.nativeapp.tenant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Wires Spring Data JPA auditing for every Native service.
 *
 * <p>{@link EnableJpaAuditing} activates the {@code @CreatedDate}/
 * {@code @LastModifiedDate}/{@code @CreatedBy}/{@code @LastModifiedBy} processing
 * on {@link Auditable}; {@code auditorAwareRef} points the {@code *_by} columns at
 * {@link TenantAuditorAware}, which reads the actor from {@link TenantContext}.
 *
 * <p>A service imports this configuration (e.g. via {@code @Import} or component
 * scanning of {@code id.co.nativeapp.tenant}) to get audit columns populated
 * automatically; it does not need its own {@code @EnableJpaAuditing}.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "tenantAuditorAware")
public class JpaAuditingConfig {

    /** The auditor that stamps {@code created_by}/{@code updated_by} from the tenant scope. */
    @Bean
    public AuditorAware<String> tenantAuditorAware() {
        return new TenantAuditorAware();
    }
}
