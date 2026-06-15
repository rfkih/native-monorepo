package id.co.nativeapp.org.group.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code consolidation_group} aggregate (P3d SEAM 1) — a named group of companies whose books a
 * lead company consolidates. It is owned by the <strong>lead company</strong>: only the lead
 * administers the group, and the group is tenant-scoped to that lead ({@code company_id =
 * lead_company_id}), so a member company can neither enumerate nor mutate the membership.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns ({@code
 * created_at}/{@code created_by}, {@code updated_at}/{@code updated_by}, {@code version}, {@code
 * company_id}) and is covered by the {@code consolidation_group} RLS policy in the Flyway migration
 * (rule 4 + rule 5). {@code company_id} is stamped to {@code lead_company_id} by the writer from
 * the bound {@link id.co.nativeapp.tenant.TenantContext}, so RLS confines the group to its lead.
 *
 * <p><strong>reporting_currency is immutable (write-once).</strong> The group's reporting
 * (presentation) currency is set once, at creation, and can NEVER change — the exact same pattern
 * the {@link id.co.nativeapp.org.company.domain.Company} base currency uses ("Settings live at
 * creation"; CLAUDE.md). The field is mapped {@code updatable = false} (so even a Hibernate flush
 * cannot rewrite the column), there is NO setter that reassigns it, and the only way to set it is
 * the all-args constructor — which validates it is a real ISO-4217 code via {@link
 * Currency#getInstance(String)} (never a float; an unknown code throws {@code
 * IllegalArgumentException}, mapped to a {@code 400}).
 */
@Entity
@Table(name = "consolidation_group")
public class ConsolidationGroup extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /**
   * The lead/owner company that administers this group; equals the group's tenant {@code
   * company_id}.
   */
  @Column(name = "lead_company_id", nullable = false, updatable = false)
  private UUID leadCompanyId;

  @Column(name = "name", nullable = false)
  private String name;

  /**
   * The group's reporting (presentation) currency: an ISO-4217 alphabetic code (e.g. {@code "IDR"}
   * / {@code "USD"}). Mapped as a fixed-width {@code CHAR(3)} (PostgreSQL {@code bpchar}); {@link
   * SqlTypes#CHAR} makes Hibernate's {@code ddl-auto=validate} agree with the migrated column type
   * (same trick as {@code Company.base_currency} / {@code MoneyEmbeddable}). PostgreSQL space-pads
   * {@code CHAR} on read, so {@link #getReportingCurrency()} strips before returning.
   * <strong>{@code updatable = false}</strong> makes the column write-once at the persistence layer
   * — there is no path, in code or via Hibernate dirty-checking, to change it after insert.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "reporting_currency", nullable = false, updatable = false, length = 3)
  private String reportingCurrency;

  protected ConsolidationGroup() {
    // for JPA
  }

  /**
   * Creates a consolidation group with a freshly generated id, owned by {@code leadCompanyId}.
   * Validates its invariants: non-null lead, non-blank name, a real ISO-4217 {@code
   * reportingCurrency}.
   *
   * @param leadCompanyId the lead/owner company (also the group's tenant); must be non-null
   * @param name the group name; must be non-blank
   * @param reportingCurrency the ISO-4217 reporting currency code; validated via {@link Currency};
   *     IMMUTABLE
   */
  public ConsolidationGroup(UUID leadCompanyId, String name, String reportingCurrency) {
    this.id = UUID.randomUUID();
    this.leadCompanyId = Objects.requireNonNull(leadCompanyId, "leadCompanyId");
    this.name = requireNonBlank(name, "name");
    this.reportingCurrency = requireValidCurrency(reportingCurrency);
  }

  /**
   * Renames this group. The new name must be non-blank.
   *
   * @return {@code true} if the name actually changed, {@code false} if it was identical
   */
  public boolean rename(String newName) {
    String trimmed = requireNonBlank(newName, "name");
    if (trimmed.equals(this.name)) {
      return false;
    }
    this.name = trimmed;
    return true;
  }

  /**
   * Validates {@code reportingCurrency} is a real ISO-4217 code (never a float — a currency is a
   * code, not an amount). Returns the normalized upper-case code; the persistence column is {@code
   * CHAR(3)}.
   */
  private static String requireValidCurrency(String reportingCurrency) {
    Objects.requireNonNull(reportingCurrency, "reportingCurrency");
    String code = reportingCurrency.strip().toUpperCase(Locale.ROOT);
    // Currency.getInstance throws IllegalArgumentException for an unknown code; this is
    // the single source of ISO-4217 validity, exactly as Company.base_currency does.
    Currency.getInstance(code);
    return code;
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    String trimmed = value.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }

  public UUID getId() {
    return id;
  }

  public UUID getLeadCompanyId() {
    return leadCompanyId;
  }

  public String getName() {
    return name;
  }

  /** The immutable ISO-4217 reporting currency code (stripped of any {@code CHAR} padding). */
  public String getReportingCurrency() {
    return reportingCurrency.strip();
  }

  // NOTE: there is deliberately NO setReportingCurrency(...) and NO setLeadCompanyId(...).
  // reporting_currency is write-once (CLAUDE.md "Settings live at creation"); exposing a setter —
  // or
  // a mutable column (it is updatable=false) — would violate that invariant, exactly like
  // Company.base_currency.
}
