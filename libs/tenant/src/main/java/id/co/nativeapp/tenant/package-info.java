/**
 * Tenancy + audit primitives shared by every Native service.
 *
 * <p>Native is multi-tenant: every row belongs to exactly one company, every query is scoped by
 * {@code company_id}, and PostgreSQL row-level security enforces that isolation a second time
 * (CLAUDE.md rule 5). Every table also carries audit columns (rule 4). This package provides the
 * building blocks both mechanisms stand on:
 *
 * <ul>
 *   <li>{@link id.co.nativeapp.tenant.TenantContext} — binds the current tenant ({@code
 *       company_id}) and actor for a unit of work using a Java 25 {@link java.lang.ScopedValue}
 *       (never a {@code ThreadLocal}), so the tenant propagates correctly across virtual threads;
 *   <li>{@link id.co.nativeapp.tenant.Auditable} — the mandatory JPA {@code @MappedSuperclass}
 *       every entity extends: {@code created_at/by}, {@code updated_at/by}, a {@code @Version}, and
 *       {@code company_id};
 *   <li>{@link id.co.nativeapp.tenant.TenantAuditorAware} + {@link
 *       id.co.nativeapp.tenant.JpaAuditingConfig} — wire Spring Data JPA auditing so the {@code
 *       *_by} columns are stamped with the actor from the tenant scope (falling back to {@code
 *       "system"});
 *   <li>{@link id.co.nativeapp.tenant.RlsConnectionInitializer} + {@link
 *       id.co.nativeapp.tenant.RlsTransactionSynchronizer} — push the bound tenant into the
 *       PostgreSQL session via {@code SET LOCAL app.current_tenant} so RLS policies keyed on {@code
 *       current_setting('app.current_tenant')} can enforce isolation.
 * </ul>
 *
 * <p>Tenant ids and actors here originate from a validated JWT; this package performs no
 * authentication and logs no PII.
 */
package id.co.nativeapp.tenant;
