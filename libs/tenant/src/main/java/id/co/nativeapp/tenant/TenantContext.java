package id.co.nativeapp.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Holds the current tenant ({@code company_id}) and acting principal for the duration of a unit of
 * work, using a Java 25 {@link ScopedValue}.
 *
 * <p><strong>Not a {@code ThreadLocal}.</strong> CLAUDE.md rule 5 and ARCHITECTURE.md require
 * tenant propagation via scoped values: a {@link ScopedValue} binding is immutable for the scope,
 * is structurally inherited by child tasks forked with structured concurrency, and is the correct
 * primitive for the virtual-thread-per-request model. A {@code ThreadLocal} would leak across
 * pooled platform threads and would not be inherited cleanly by virtual threads.
 *
 * <p>The {@link Tenant} carried here is the single source of truth for:
 *
 * <ul>
 *   <li>the {@code company_id} every query is scoped by (and that {@link RlsConnectionInitializer}
 *       pushes into the PostgreSQL session so row-level security can enforce it a second time); and
 *   <li>the actor that {@link TenantAuditorAware} writes into the {@code created_by}/{@code
 *       updated_by} audit columns.
 * </ul>
 *
 * <p>Typical usage at the request edge (after the gateway/JWT has been validated) wraps the
 * downstream work in a scope:
 *
 * <pre>{@code
 * TenantContext.runAs(companyId, actor, () -> service.handleRequest(...));
 * }</pre>
 *
 * <p>This class is purely a context carrier — it performs no authentication and trusts that {@code
 * companyId} and {@code actor} were derived from a validated JWT. It is final and holds only static
 * state.
 */
public final class TenantContext {

  /**
   * The single binding point for the current unit of work. Unbound outside any {@code runAs(...)} /
   * {@code callAs(...)} scope, which is the correct, safe default: code that needs a tenant must be
   * inside a scope, and there is no ambient fallback that could silently attribute work to the
   * wrong company.
   */
  private static final ScopedValue<Tenant> CURRENT = ScopedValue.newInstance();

  private TenantContext() {}

  /**
   * The immutable tenant + actor bound for the current scope.
   *
   * @param companyId the owning tenant ({@code company_id}); never blank
   * @param actor the acting principal (e.g. JWT {@code sub}); never blank
   */
  public record Tenant(String companyId, String actor) {

    public Tenant {
      companyId = requireNonBlank(companyId, "companyId");
      actor = requireNonBlank(actor, "actor");
    }

    private static String requireNonBlank(String value, String field) {
      Objects.requireNonNull(value, field);
      String trimmed = value.strip();
      if (trimmed.isEmpty()) {
        throw new IllegalArgumentException(field + " must not be blank");
      }
      return trimmed;
    }
  }

  /**
   * Runs {@code action} with the given tenant and actor bound for the duration of the call. The
   * binding is automatically torn down (and any previous binding restored) when the call returns or
   * throws.
   *
   * @param companyId the owning tenant ({@code company_id})
   * @param actor the acting principal
   * @param action the work to run within the scope
   */
  public static void runAs(String companyId, String actor, Runnable action) {
    Objects.requireNonNull(action, "action");
    ScopedValue.where(CURRENT, new Tenant(companyId, actor)).run(action);
  }

  /**
   * As {@link #runAs(String, String, Runnable)}, but for work that returns a value and may throw a
   * checked exception.
   *
   * @param <T> the result type of {@code action}
   * @return the value returned by {@code action}
   * @throws Exception whatever {@code action} throws
   */
  public static <T> T callAs(String companyId, String actor, Callable<T> action) throws Exception {
    Objects.requireNonNull(action, "action");
    return ScopedValue.where(CURRENT, new Tenant(companyId, actor)).call(action::call);
  }

  /** {@code true} if a tenant is bound in the current scope. */
  public static boolean isBound() {
    return CURRENT.isBound();
  }

  /**
   * The tenant bound in the current scope.
   *
   * @throws IllegalStateException if no tenant is bound — callers that require a tenant should fail
   *     loudly rather than operate without one
   */
  public static Tenant require() {
    if (!CURRENT.isBound()) {
      throw new IllegalStateException(
          "No tenant bound in the current scope; wrap the work in TenantContext.runAs(...)");
    }
    return CURRENT.get();
  }

  /** The bound tenant, if any. Empty outside a {@code runAs}/{@code callAs} scope. */
  public static Optional<Tenant> current() {
    return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
  }

  /** The bound {@code company_id}, if any. */
  public static Optional<String> currentCompanyId() {
    return current().map(Tenant::companyId);
  }

  /** The bound actor, if any. */
  public static Optional<String> currentActor() {
    return current().map(Tenant::actor);
  }
}
