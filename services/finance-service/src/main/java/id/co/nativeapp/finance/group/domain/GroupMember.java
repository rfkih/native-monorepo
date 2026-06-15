package id.co.nativeapp.finance.group.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code group_member} local read model (P3d SEAM 1) — finance's cached projection of the
 * effective-dated member set of a consolidation group: {@code group_id -> the active member-company
 * set}. Built from the org-service {@code GroupMembershipChanged} event, never a synchronous call
 * (rule 2).
 *
 * <p>One row per {@code (group_id, member_company_id)}; the effective window mirrors the producer's
 * ({@code effective_from} / {@code effective_to} with the far-future {@code 9999-12-31} sentinel
 * for an active membership). An {@code ADDED} event opens (or re-opens) the window; a {@code
 * REMOVED} event closes it (stamps {@code effective_to}) — never a hard-delete, so the membership
 * history is preserved here too.
 *
 * <p>It extends {@link Auditable} and is under RLS scoped to the group's LEAD company ({@code
 * company_id = lead_company_id}) — the same tenancy as {@link GroupRef}, resolved from the local
 * {@code group_ref} (no sync call). A re-delivered event is a no-op via {@code
 * ProcessedEventStore}.
 */
@Entity
@Table(name = "group_member")
public class GroupMember extends Auditable {

  /** The far-future open-ended sentinel for {@code effective_to} (an active membership). */
  public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "group_id", nullable = false, updatable = false)
  private UUID groupId;

  @Column(name = "member_company_id", nullable = false, updatable = false)
  private UUID memberCompanyId;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  @Column(name = "effective_to", nullable = false)
  private LocalDate effectiveTo;

  protected GroupMember() {
    // for JPA
  }

  /**
   * Opens a member-set row from an {@code ADDED} membership event.
   *
   * @param groupId the consolidation group
   * @param memberCompanyId the member company
   * @param effectiveFrom the date the membership becomes effective
   * @param effectiveTo the date it ceases to be effective (the sentinel for an active membership)
   */
  public GroupMember(
      UUID groupId, UUID memberCompanyId, LocalDate effectiveFrom, LocalDate effectiveTo) {
    this.id = UUID.randomUUID();
    this.groupId = Objects.requireNonNull(groupId, "groupId");
    this.memberCompanyId = Objects.requireNonNull(memberCompanyId, "memberCompanyId");
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = Objects.requireNonNull(effectiveTo, "effectiveTo");
  }

  /** Whether this membership row is currently open (active) — its window is the sentinel. */
  public boolean isActive() {
    return OPEN_ENDED.equals(effectiveTo);
  }

  /**
   * Applies the membership state carried by an event (idempotent set of the effective window). The
   * event carries the post-change state, so the consumer simply mirrors it.
   */
  public void applyWindow(LocalDate newEffectiveFrom, LocalDate newEffectiveTo) {
    this.effectiveFrom = Objects.requireNonNull(newEffectiveFrom, "effectiveFrom");
    this.effectiveTo = Objects.requireNonNull(newEffectiveTo, "effectiveTo");
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

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }
}
