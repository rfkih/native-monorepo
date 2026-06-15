package id.co.nativeapp.entitlement.entitlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;

/**
 * The {@code module_catalog} aggregate — one row per module the platform offers (e.g. {@code
 * restaurant}, {@code carwash}, {@code laundromat}, {@code hr}, {@code finance}). It is the
 * vocabulary a {@link TenantEntitlement} / {@code BillingLine} {@code module_key} is validated
 * against.
 *
 * <p><strong>Reference data, deliberately NOT {@code Auditable} and NOT tenant-scoped.</strong> The
 * catalog is global — the same set of modules for every company — so it carries no {@code
 * company_id} and is exempt from RLS and the {@code Auditable} base class (the {@code
 * module_catalog} table in the baseline has neither the Auditable columns nor an RLS policy). This
 * is the reference-data exception, alongside CDC-derived read models, to the rule-4 "every table
 * extends Auditable" guidance: it is not tenant business data. The catalog is seeded by Flyway, not
 * written at runtime, so it exposes no public mutation.
 */
@Entity
@Table(name = "module_catalog")
public class ModuleCatalog {

  @Id
  @Column(name = "module_key", nullable = false, updatable = false, length = 64)
  private String moduleKey;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "description", nullable = false, length = 1024)
  private String description;

  protected ModuleCatalog() {
    // for JPA
  }

  /** The module key (e.g. {@code "restaurant"}); the primary key. */
  public String getModuleKey() {
    return moduleKey;
  }

  /** The human-readable module name (a server-side label; UI copy is i18n on the frontend). */
  public String getName() {
    return name;
  }

  /** A short server-side description of the module. */
  public String getDescription() {
    return description;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ModuleCatalog other)) {
      return false;
    }
    return Objects.equals(moduleKey, other.moduleKey);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(moduleKey);
  }
}
