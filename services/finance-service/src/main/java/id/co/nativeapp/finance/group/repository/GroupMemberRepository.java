package id.co.nativeapp.finance.group.repository;

import id.co.nativeapp.finance.group.domain.GroupMember;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for the {@link GroupMember} read model.
 *
 * <p>Carries <em>no</em> manual {@code WHERE company_id = ...}: RLS scopes every read/write to the
 * bound lead company (rule 5). The consumer binds the tenant from the owning group's {@code
 * lead_company_id} (resolved from the local {@code group_ref}, never a sync call) before upserting.
 */
public interface GroupMemberRepository extends JpaRepository<GroupMember, UUID> {

  /**
   * The member-set row for a {@code (group, member)} pair, if any. The consumer upserts on this: an
   * {@code ADDED} re-opens or creates it, a {@code REMOVED} closes it (idempotent mirror of the
   * event's post-change window).
   */
  Optional<GroupMember> findByGroupIdAndMemberCompanyId(UUID groupId, UUID memberCompanyId);

  /**
   * The members ACTIVE in a group at a specific instant — the DETERMINISTIC effective-dating gate
   * the SEAM-3a consolidation close resolves its member set with (the design-critique Critical: the
   * consolidation must use the members active AT PERIOD-END, never the current set, so a member
   * that joined or left mid-period is included/excluded correctly and reproducibly). The window is
   * half-open: {@code effective_from <= asOf AND asOf < effective_to} (with the far-future {@code
   * 9999-12-31} sentinel for an open membership), so a membership that closes exactly at {@code
   * asOf} is already out, and one that opens exactly at {@code asOf} is in. Scoped to the bound
   * lead tenant by RLS; {@code group_id} narrows to the one group.
   */
  @Query(
      """
      SELECT m FROM GroupMember m
       WHERE m.groupId = :groupId
         AND m.effectiveFrom <= :asOf
         AND :asOf < m.effectiveTo
      """)
  List<GroupMember> findActiveAt(@Param("groupId") UUID groupId, @Param("asOf") LocalDate asOf);
}
