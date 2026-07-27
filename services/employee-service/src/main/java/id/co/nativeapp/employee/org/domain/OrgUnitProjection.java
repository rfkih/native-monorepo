package id.co.nativeapp.employee.org.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The local org read model row: {@code org_unit_id -> {company_id, legal_employer_id, type,
 * active}}. employee-service maintains this projection by CONSUMING {@code OrgUnitCreated} / {@code
 * OrgUnitChanged} from org-service (rule 2 — no synchronous call; a locally cached read model). It
 * is the authoritative source the same-legal-employer assignment invariant (ARCHITECTURE.md §2) is
 * checked against: on creating an assignment, the target org-unit's {@code legal_employer_id} is
 * looked up here.
 *
 * <p>It is an <em>app-maintained</em> read model (NOT a Debezium-CDC-derived projection), so — like
 * finance's {@code consolidated_revenue} — the rule-4 Auditable + rule-5 RLS guarantees apply
 * uniformly: it extends {@link Auditable} and is under the {@code org_unit_projection} RLS policy.
 * The consumer writes it inside a {@link id.co.nativeapp.tenant.TenantContext} scope bound to the
 * EVENT's {@code company_id}, so the RLS {@code WITH CHECK} passes.
 *
 * <p>{@code company_id} comes from {@link Auditable} (the tenant the org unit belongs to); a single
 * {@code legal_employer_id} per company today means the projected value is authoritative for the
 * invariant.
 */
@Entity
@Table(name = "org_unit_projection")
public class OrgUnitProjection extends Auditable {

  @Id
  @Column(name = "org_unit_id", nullable = false, updatable = false)
  private UUID orgUnitId;

  @Column(name = "legal_employer_id", nullable = false)
  private UUID legalEmployerId;

  @Column(name = "type", nullable = false, length = 32)
  private String type;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected OrgUnitProjection() {
    // for JPA
  }

  /**
   * Creates a projection row for an org unit (from a consumed {@code OrgUnitCreated}).
   *
   * @param orgUnitId the org-unit id (the projection key)
   * @param legalEmployerId the org-unit's legal employer
   * @param type the org-unit kind (business_unit | outlet | team, ADR 0012)
   * @param active whether the org unit is active
   */
  public OrgUnitProjection(UUID orgUnitId, UUID legalEmployerId, String type, boolean active) {
    this.orgUnitId = Objects.requireNonNull(orgUnitId, "orgUnitId");
    this.legalEmployerId = Objects.requireNonNull(legalEmployerId, "legalEmployerId");
    this.type = Objects.requireNonNull(type, "type");
    this.active = active;
  }

  /**
   * Applies the new state from a consumed {@code OrgUnitChanged} (rename/move/deactivate carry it).
   */
  public void applyChange(String newType, boolean newActive) {
    this.type = Objects.requireNonNull(newType, "type");
    this.active = newActive;
  }

  public UUID getOrgUnitId() {
    return orgUnitId;
  }

  public UUID getLegalEmployerId() {
    return legalEmployerId;
  }

  public String getType() {
    return type;
  }

  public boolean isActive() {
    return active;
  }
}
