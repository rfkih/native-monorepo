package id.co.nativeapp.org.group;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A {@code group_membership} row (P3d SEAM 1) — one company's effective-dated membership in a
 * {@link ConsolidationGroup}. Like the group itself, it is owned by the <strong>lead
 * company</strong> and tenant-scoped to it ({@code company_id = lead_company_id}); a member company
 * cannot enumerate or mutate membership.
 *
 * <p><strong>Effective-dated.</strong> Each membership carries {@code effective_from}/{@code
 * effective_to}; an open (active) membership uses the far-future sentinel {@link #OPEN_ENDED}
 * ({@code 9999-12-31}), never {@code NULL} (ENGINEERING-STANDARDS §2.5). Removing a member does NOT
 * hard-delete the row — it <em>closes the window</em> by stamping {@code effective_to} to the
 * removal date, preserving the membership history for re-runnable consolidation.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns and is
 * covered by the {@code group_membership} RLS policy in the Flyway migration (rule 4 + rule 5).
 */
@Entity
@Table(name = "group_membership")
public class GroupMembership extends Auditable {

  /** The far-future open-ended sentinel for {@code effective_to} (an active membership). */
  public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "group_id", nullable = false, updatable = false)
  private UUID groupId;

  @Column(name = "member_company_id", nullable = false, updatable = false)
  private UUID memberCompanyId;

  /**
   * The owning group's LEAD company — the membership's tenant (equals {@code company_id}). Known at
   * insert time and write-once, it structurally pins the tenant via the {@code
   * ck_group_membership_lead_is_tenant} CHECK, mirroring {@link ConsolidationGroup}.
   */
  @Column(name = "lead_company_id", nullable = false, updatable = false)
  private UUID leadCompanyId;

  @Column(name = "effective_from", nullable = false, updatable = false)
  private LocalDate effectiveFrom;

  @Column(name = "effective_to", nullable = false)
  private LocalDate effectiveTo;

  protected GroupMembership() {
    // for JPA
  }

  /**
   * Opens a new active membership for {@code memberCompanyId} in {@code groupId}, owned by {@code
   * leadCompanyId}, effective from {@code effectiveFrom}, open-ended (its {@code effective_to} is
   * the {@link #OPEN_ENDED} sentinel).
   *
   * @param groupId the consolidation group this membership is in; must be non-null
   * @param memberCompanyId the member company; must be non-null
   * @param leadCompanyId the owning group's lead company (the membership's tenant); must be
   *     non-null
   * @param effectiveFrom the date the membership becomes effective; must be non-null
   */
  public GroupMembership(
      UUID groupId, UUID memberCompanyId, UUID leadCompanyId, LocalDate effectiveFrom) {
    this.id = UUID.randomUUID();
    this.groupId = Objects.requireNonNull(groupId, "groupId");
    this.memberCompanyId = Objects.requireNonNull(memberCompanyId, "memberCompanyId");
    this.leadCompanyId = Objects.requireNonNull(leadCompanyId, "leadCompanyId");
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = OPEN_ENDED;
  }

  /** Whether this membership is currently open (active) — i.e. its window has not been closed. */
  public boolean isActive() {
    return OPEN_ENDED.equals(effectiveTo);
  }

  /**
   * Closes this membership's effective window as of {@code asOf} (the removal date), replacing the
   * open-ended sentinel. A no-op if the window is already closed (idempotent remove).
   *
   * @param asOf the date the membership ceases to be effective
   * @return {@code true} if this call closed an open window, {@code false} if it was already closed
   */
  public boolean close(LocalDate asOf) {
    Objects.requireNonNull(asOf, "asOf");
    if (!isActive()) {
      return false;
    }
    this.effectiveTo = asOf;
    return true;
  }

  public UUID getId() {
    return id;
  }

  public UUID getGroupId() {
    return groupId;
  }

  public UUID getMemberCompanyId() {
    return memberCompanyId;
  }

  /** The owning group's lead company — the membership's tenant ({@code company_id}). */
  public UUID getLeadCompanyId() {
    return leadCompanyId;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }
}
