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
   * The immutable tenant + actor bound for the current scope, optionally carrying a consolidation
   * {@code group_id} (P3d SEAM 2).
   *
   * <p><strong>The {@code groupId} is additive and optional.</strong> The overwhelmingly common
   * binding is a single tenant with NO group — every normal company request, every existing
   * service, every member-table read/write. For those the {@code groupId} is {@code null}, {@link
   * #groupId()} is {@link Optional#empty()}, and the database session sets ONLY {@code
   * app.current_tenant} exactly as before — the single-tenant path is byte-identical. A group is
   * bound only on the narrow group-consolidation paths (a group view/close, the {@code
   * TrialBalancePublished} consumer), where the tenant is the group's LEAD company and the group
   * scopes the group tables in conjunction with that lead tenant (see {@link
   * RlsConnectionInitializer}).
   *
   * @param companyId the owning tenant ({@code company_id}); never blank. On a group binding this
   *     is the group's LEAD company (the {@code company_id} group tables carry, per Auditable rule
   *     4).
   * @param actor the acting principal (e.g. JWT {@code sub}); never blank
   * @param groupId the bound consolidation {@code group_id}, or {@code null} when no group is bound
   *     (the normal single-tenant case). The canonical record accessor {@link #groupId()} returns
   *     this nullable value; prefer {@link #groupIdOptional()} / {@link #hasGroup()} at call sites.
   */
  public record Tenant(String companyId, String actor, String groupId) {

    public Tenant {
      companyId = requireNonBlank(companyId, "companyId");
      actor = requireNonBlank(actor, "actor");
      // groupId is OPTIONAL: null means "no group bound" (the byte-identical single-tenant path).
      // When present it must be a real, non-blank id — a blank/whitespace group would be a partial
      // binding that must never be treated as "a group is bound".
      groupId = groupId == null ? null : requireNonBlank(groupId, "groupId");
    }

    /** A single-tenant binding with no group bound (the existing, byte-identical behaviour). */
    public Tenant(String companyId, String actor) {
      this(companyId, actor, null);
    }

    /** The bound consolidation group id, if a group is bound; empty for a normal single-tenant. */
    public Optional<String> groupIdOptional() {
      return Optional.ofNullable(groupId);
    }

    /** {@code true} if a consolidation group is bound alongside the tenant (a group scope). */
    public boolean hasGroup() {
      return groupId != null;
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

  /**
   * Runs {@code action} with the given tenant, actor, AND consolidation {@code groupId} bound for
   * the duration of the call (P3d SEAM 2 — the group-scoped binding). The binding is torn down (and
   * any previous binding restored) when the call returns or throws.
   *
   * <p>This is the ONLY way a group is ever bound. {@code companyId} must be the group's LEAD
   * company (the {@code company_id} group tables carry); {@code groupId} additionally scopes the
   * group tables in conjunction with that lead tenant. {@link RlsConnectionInitializer} sets {@code
   * app.current_group} on top of {@code app.current_tenant} only inside a binding made here. Use
   * {@link #runAs(String, String, Runnable)} for every non-group request — it leaves {@code
   * app.current_group} unset (byte-identical to today).
   *
   * @param companyId the group's LEAD company ({@code company_id})
   * @param groupId the bound consolidation {@code group_id}
   * @param actor the acting principal
   * @param action the work to run within the group scope
   */
  public static void runAsGroup(String companyId, String groupId, String actor, Runnable action) {
    Objects.requireNonNull(groupId, "groupId");
    Objects.requireNonNull(action, "action");
    ScopedValue.where(CURRENT, new Tenant(companyId, actor, groupId)).run(action);
  }

  /**
   * As {@link #runAsGroup(String, String, String, Runnable)}, but for work that returns a value and
   * may throw a checked exception.
   *
   * @param <T> the result type of {@code action}
   * @return the value returned by {@code action}
   * @throws Exception whatever {@code action} throws
   */
  public static <T> T callAsGroup(
      String companyId, String groupId, String actor, Callable<T> action) throws Exception {
    Objects.requireNonNull(groupId, "groupId");
    Objects.requireNonNull(action, "action");
    return ScopedValue.where(CURRENT, new Tenant(companyId, actor, groupId)).call(action::call);
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

  /**
   * The bound consolidation {@code group_id}, if a group is bound. Empty outside any scope and
   * empty inside a normal single-tenant scope (no group) — exactly the cases where {@link
   * RlsConnectionInitializer} leaves {@code app.current_group} unset.
   */
  public static Optional<String> currentGroupId() {
    return current().flatMap(Tenant::groupIdOptional);
  }
}
