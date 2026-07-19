package id.co.nativeapp.org.group.repository;

import id.co.nativeapp.org.group.domain.GroupMembership;
import id.co.nativeapp.org.group.projection.GroupMembershipView;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link GroupMembership}.
 *
 * <p>Deliberately carries <em>no</em> manual {@code WHERE company_id = ...}: every Spring Data
 * method is transactional, so {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} sets {@code
 * app.current_tenant} automatically and the RLS policy restricts results to the bound lead company
 * (rule 5). The derived queries below are still implicitly tenant-scoped by RLS — a member company
 * can never read another lead's memberships.
 */
public interface GroupMembershipRepository extends JpaRepository<GroupMembership, UUID> {

  /**
   * The currently-active (open-window) membership for a company in a group, if any. Used to make
   * add-member idempotent (an already-active member is not re-added) and remove-member find the row
   * whose window to close. {@code effective_to} of the open-ended sentinel marks the active row.
   */
  Optional<GroupMembership> findByGroupIdAndMemberCompanyIdAndEffectiveTo(
      UUID groupId, UUID memberCompanyId, java.time.LocalDate effectiveTo);

  /**
   * All memberships for a given group, projected to {@link GroupMembershipView}, ordered by
   * effective_from then member_company_id. No {@code WHERE company_id} — the result set is
   * constrained solely by the auto-applied RLS policy (rule 5): the bound tenant is the lead, so
   * only the lead's groups are visible, and the group_id equality filter further narrows to one
   * group. Used on pure read paths where the entity is never mutated or saved.
   *
   * @param groupId the group to list members for
   * @return the membership projections for the group
   */
  @Query(
      value =
          """
          SELECT gm.member_company_id AS member_company_id,
                 gm.effective_from    AS effective_from,
                 gm.effective_to      AS effective_to
            FROM group_membership gm
           WHERE gm.group_id = :groupId
           ORDER BY gm.effective_from, gm.member_company_id
          """,
      nativeQuery = true)
  List<GroupMembershipView> findMemberViews(@Param("groupId") UUID groupId);
}
