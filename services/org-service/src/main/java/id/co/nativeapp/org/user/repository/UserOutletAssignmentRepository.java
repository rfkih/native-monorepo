package id.co.nativeapp.org.user.repository;

import id.co.nativeapp.org.user.domain.UserOutletAssignment;
import id.co.nativeapp.org.user.projection.OrgUnitUserView;
import id.co.nativeapp.org.user.projection.UserOutletAssignmentView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Spring Data repository for {@link UserOutletAssignment}.
 *
 * <p>Carries no manual {@code WHERE company_id = ...}: every method is transactional, so {@link
 * RlsAutoApplyAspect} sets {@code app.current_tenant} automatically and the PostgreSQL RLS policy
 * restricts results to the bound company (rule 5). RLS fails closed — an unscoped read returns zero
 * rows rather than all rows.
 *
 * <p><strong>Two call paths.</strong>
 *
 * <ul>
 *   <li><em>Write path</em> — inherited {@code findById} / {@code save} load and persist the full
 *       entity when the assignment must be mutated (close, reopen).
 *   <li><em>Read path</em> — {@link #findActiveByUserId} projects only {@code org_unit_id} and
 *       {@code name} into {@link UserOutletAssignmentView}, never {@code SELECT *} of the entity
 *       (CLAUDE.md §3.3).
 * </ul>
 */
public interface UserOutletAssignmentRepository extends JpaRepository<UserOutletAssignment, UUID> {

  /**
   * Whether ANY login is assigned to the given outlet (RLS-scoped — rule 5). Used as a delete
   * guard: an outlet with an assigned cashier login could have rung sales, so it is never
   * hard-deletable (it must be deactivated instead). A derived existence check — no {@code WHERE
   * company_id}, RLS constrains it to the bound tenant.
   */
  boolean existsByOrgUnitId(UUID orgUnitId);

  /**
   * Active assignments for the given user, joined to {@code org_unit} for the name, ordered by
   * outlet name. No {@code WHERE company_id} — RLS constrains the result to the bound company (rule
   * 5). Projection columns: {@code org_unit_id} alias → {@code getOrgUnitId()}, {@code name} alias
   * → {@code getName()}.
   *
   * <p>Used on the GET read path (list outlets for a specific user). The full entity is never
   * loaded on this path.
   */
  @Query(
      value =
          """
          SELECT ua.org_unit_id AS org_unit_id,
                 ou.name        AS name
            FROM user_outlet_assignment ua
            JOIN org_unit ou ON ou.id = ua.org_unit_id
           WHERE ua.user_id = :userId
             AND ua.active  = true
           ORDER BY ou.name
          """,
      nativeQuery = true)
  List<UserOutletAssignmentView> findActiveByUserId(String userId);

  /**
   * Active assignments for one org unit. Since ADR 0070 the tree is flat ({@code company >
   * outlet}), so "under a unit" means the unit itself — the {@code OR ou.parent_id = :orgUnitId}
   * disjunct this query used to carry (rolling a business unit up over its child outlets) is gone
   * with the division level, along with the only kind of node that could ever have matched it.
   *
   * <p>Joined to {@code org_unit} for the outlet name. Served by {@code
   * idx_user_outlet_assignment_outlet (company_id, org_unit_id)} — the index created for this admin
   * view. No {@code WHERE company_id} — RLS scopes both tables (rule 5). Used by the org-unit hub's
   * People tab ({@code GET /api/v1/org-units/{id}/users}).
   */
  @Query(
      value =
          """
          SELECT ua.user_id     AS user_id,
                 ua.org_unit_id AS org_unit_id,
                 ou.name        AS outlet_name
            FROM user_outlet_assignment ua
            JOIN org_unit ou ON ou.id = ua.org_unit_id
           WHERE ua.active = true
             AND ua.org_unit_id = :orgUnitId
           ORDER BY ou.name, ua.user_id
          """,
      nativeQuery = true)
  List<OrgUnitUserView> findActiveUnderUnit(UUID orgUnitId);

  /**
   * All (active + inactive) assignment rows for the given user. Used on the write path (PUT
   * replace-set) to determine which rows to close, reopen, or insert. Loads full entities because
   * close/reopen mutates them. No {@code WHERE company_id} — RLS scopes the result (rule 5).
   */
  @Query(
      value =
          """
          SELECT ua.*
            FROM user_outlet_assignment ua
           WHERE ua.user_id = :userId
          """,
      nativeQuery = true)
  List<UserOutletAssignment> findAllByUserId(String userId);

  /**
   * One assignment row (any active state) for the given (user, outlet) pair. Used in the PUT
   * replace-set to look up an existing row before deciding to reopen vs. insert. No {@code WHERE
   * company_id} — RLS scopes the result (rule 5).
   */
  @Query(
      value =
          """
          SELECT ua.*
            FROM user_outlet_assignment ua
           WHERE ua.user_id    = :userId
             AND ua.org_unit_id = :orgUnitId
          """,
      nativeQuery = true)
  Optional<UserOutletAssignment> findByUserIdAndOrgUnitId(String userId, UUID orgUnitId);

  /**
   * Count of active assignments per user, grouped by user_id, for the users whose ids are in the
   * provided list. Used by the Team list to append {@code outletCount} per member in one query
   * (avoids N+1). No {@code WHERE company_id} — RLS constrains the result to the bound company.
   *
   * <p>Caller must chunk the id list to ≤ 1000 entries (Lists.partition, CLAUDE.md rule).
   */
  @Query(
      value =
          """
          SELECT ua.user_id  AS user_id,
                 count(*)    AS outlet_count
            FROM user_outlet_assignment ua
           WHERE ua.user_id IN (:userIds)
             AND ua.active  = true
           GROUP BY ua.user_id
          """,
      nativeQuery = true)
  List<UserOutletCountView> countActiveByUserIds(List<String> userIds);

  /** Slim projection for the per-user outlet count — only what the Team list needs. */
  interface UserOutletCountView {
    String getUserId();

    long getOutletCount();
  }
}
