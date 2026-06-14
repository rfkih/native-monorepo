package id.co.nativeapp.tenant;

import java.util.Optional;
import org.springframework.data.domain.AuditorAware;

/**
 * Supplies the actor for the {@code created_by}/{@code updated_by} audit columns from the bound
 * {@link TenantContext}.
 *
 * <p>The actor is the JWT {@code sub} that the request edge bound via {@link
 * TenantContext#runAs(String, String, Runnable)}. When work runs outside any tenant scope — e.g. a
 * scheduled job, a Kafka consumer bootstrap, or a Flyway-driven migration — there is no human
 * principal, so the actor falls back to {@value #SYSTEM_ACTOR}, matching ARCHITECTURE.md ("the
 * actor comes from the JWT via {@code AuditorAware}, or the service name for system changes").
 *
 * <p>Returning a non-empty {@link Optional} is important: the {@code created_by}/ {@code
 * updated_by} columns are {@code NOT NULL}, so we never hand Spring Data an empty auditor.
 */
public class TenantAuditorAware implements AuditorAware<String> {

  /** Fallback actor for work performed outside any tenant scope. */
  public static final String SYSTEM_ACTOR = "system";

  @Override
  public Optional<String> getCurrentAuditor() {
    return Optional.of(TenantContext.currentActor().orElse(SYSTEM_ACTOR));
  }
}
