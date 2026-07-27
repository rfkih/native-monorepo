package id.co.nativeapp.finance.orgref.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code org_unit_ref} local read model row: a cached slice of org-service's org tree (Phase 2
 * of the outlet-scoping increment). finance-service maintains this projection by CONSUMING {@code
 * OrgUnitCreated} / {@code OrgUnitChanged} from org-service (rule 2 — no synchronous call; a
 * locally cached read model). It is the source the {@code GET /api/v1/pnl/outlets} endpoint uses to
 * resolve outlet display names via a LEFT JOIN — names may lag by up to one event-delivery cycle
 * after a rename, which the reader tolerates (a null name is "not yet known", never an error).
 *
 * <p>Extends {@link Auditable} and is under FORCE RLS (see V22 migration): a tenant only ever sees
 * its own org units. The consumer writes it inside a {@link id.co.nativeapp.tenant.TenantContext}
 * scope bound to the event's {@code company_id} (rule 5).
 *
 * <p>This entity exists solely to back the Spring Data read queries on {@link
 * id.co.nativeapp.finance.orgref.repository.OrgUnitRefRepository}. The write path is an atomic
 * {@code INSERT … ON CONFLICT … DO UPDATE} executed directly via {@code JdbcTemplate} in {@link
 * id.co.nativeapp.finance.orgref.service.OrgUnitRefWriter} (never use this entity on the write
 * path).
 *
 * <p><strong>No monetary values.</strong> This table holds no monetary amounts (rule 8 does not
 * apply here); it is a pure name/status reference.
 */
@Entity
@Table(name = "org_unit_ref")
public class OrgUnitRef extends Auditable {

  @Id
  @Column(name = "org_unit_id", nullable = false, updatable = false)
  private UUID orgUnitId;

  @Column(name = "type", nullable = false, length = 32)
  private String type;

  @Column(name = "parent_id")
  private UUID parentId;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected OrgUnitRef() {
    // for JPA
  }

  /**
   * Creates a ref row for an org unit.
   *
   * @param orgUnitId the org-unit id (the primary key, opaque UUID reference — no FK across service
   *     boundaries)
   * @param type the org-unit kind (business_unit | outlet | team, ADR 0012)
   * @param parentId the parent org-unit id, or {@code null} for a top-level node
   * @param name the org-unit display name
   * @param active whether the org unit is currently active in org-service
   */
  public OrgUnitRef(UUID orgUnitId, String type, UUID parentId, String name, boolean active) {
    this.orgUnitId = Objects.requireNonNull(orgUnitId, "orgUnitId");
    this.type = Objects.requireNonNull(type, "type");
    this.parentId = parentId;
    this.name = Objects.requireNonNull(name, "name");
    this.active = active;
  }

  /**
   * Applies the updated state from a consumed {@code OrgUnitChanged} (rename / move / deactivate /
   * reactivate all carry the new full state on the event).
   */
  public void applyChange(String newType, UUID newParentId, String newName, boolean newActive) {
    this.type = Objects.requireNonNull(newType, "type");
    this.parentId = newParentId;
    this.name = Objects.requireNonNull(newName, "name");
    this.active = newActive;
  }

  public UUID getOrgUnitId() {
    return orgUnitId;
  }

  public String getType() {
    return type;
  }

  public UUID getParentId() {
    return parentId;
  }

  public String getName() {
    return name;
  }

  public boolean isActive() {
    return active;
  }
}
