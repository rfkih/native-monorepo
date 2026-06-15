package id.co.nativeapp.finance.group.domain;

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
 * The {@code group_ref} local read model (P3d SEAM 1) — finance's cached projection of an
 * org-service consolidation group: {@code group_id -> {lead_company_id, reporting_currency}}. Built
 * from the org-service {@code GroupDefined} event, never a synchronous call (rule 2). This is pure
 * plumbing — SEAM 1 carries NO consolidation math; later seams read this reference to drive group
 * consolidation.
 *
 * <p>It extends {@link Auditable} and is under RLS scoped to the group's LEAD company ({@code
 * company_id = lead_company_id}) — consistent with how finance keeps its other tenant read models
 * (consolidated_revenue, consolidated_pnl): an application-maintained projection (not a
 * Debezium-CDC derived one), so the rule-4 Auditable + rule-5 RLS guarantees apply uniformly. Only
 * the lead's finance scope sees its group. The consumer binds the tenant from the event's {@code
 * lead_company_id} so the auto-RLS aspect sets the GUC and the WITH CHECK passes.
 *
 * <p><strong>reporting_currency mirrors the immutable producer value</strong> — it is the group's
 * presentation currency, an ISO-4217 code (never a monetary amount, never a float). The read model
 * is keyed by {@code group_id} (the primary key), so a re-delivered {@code GroupDefined} upserts
 * the same single row; the consumer's {@code ProcessedEventStore} dedupe makes a re-delivery a
 * no-op.
 */
@Entity
@Table(name = "group_ref")
public class GroupRef extends Auditable {

  @Id
  @Column(name = "group_id", nullable = false, updatable = false)
  private UUID groupId;

  @Column(name = "lead_company_id", nullable = false, updatable = false)
  private UUID leadCompanyId;

  /**
   * The group's reporting (presentation) currency: an ISO-4217 code, mirrored from the immutable
   * producer value. Mapped {@code CHAR(3)} via {@link SqlTypes#CHAR} (keeps {@code
   * ddl-auto=validate} happy); PostgreSQL space-pads on read, so {@link #getReportingCurrency()}
   * strips. {@code updatable = false} — the group's reporting currency is immutable upstream, so
   * the projection treats it as write-once too.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "reporting_currency", nullable = false, updatable = false, length = 3)
  private String reportingCurrency;

  protected GroupRef() {
    // for JPA
  }

  /**
   * Projects a {@code GroupDefined} event into the read model.
   *
   * @param groupId the consolidation group id
   * @param leadCompanyId the lead/owner company (the read model's tenant)
   * @param reportingCurrency the group's ISO-4217 reporting currency (validated, immutable)
   */
  public GroupRef(UUID groupId, UUID leadCompanyId, String reportingCurrency) {
    this.groupId = Objects.requireNonNull(groupId, "groupId");
    this.leadCompanyId = Objects.requireNonNull(leadCompanyId, "leadCompanyId");
    this.reportingCurrency = requireValidCurrency(reportingCurrency);
  }

  private static String requireValidCurrency(String reportingCurrency) {
    Objects.requireNonNull(reportingCurrency, "reportingCurrency");
    String code = reportingCurrency.strip().toUpperCase(Locale.ROOT);
    Currency.getInstance(code); // single source of ISO-4217 validity
    return code;
  }

  public UUID getGroupId() {
    return groupId;
  }

  public UUID getLeadCompanyId() {
    return leadCompanyId;
  }

  /** The group's reporting currency (ISO-4217, stripped of any {@code CHAR} padding). */
  public String getReportingCurrency() {
    return reportingCurrency.strip();
  }
}
