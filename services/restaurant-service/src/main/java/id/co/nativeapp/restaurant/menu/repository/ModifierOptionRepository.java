package id.co.nativeapp.restaurant.menu.repository;

import id.co.nativeapp.restaurant.menu.domain.ModifierOption;
import id.co.nativeapp.restaurant.menu.projection.ModifierOptionView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link ModifierOption}.
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} applies RLS (rule 5). Read
 * paths use native query + projection.
 */
public interface ModifierOptionRepository extends JpaRepository<ModifierOption, UUID> {

  /**
   * All available options for a modifier group, ordered by display_order. For the admin view pass a
   * separate query that omits the {@code available = TRUE} filter.
   */
  @Query(
      value =
          """
          SELECT mo.id                AS id,
                 mo.group_id          AS group_id,
                 mo.business_id       AS business_id,
                 mo.name              AS name,
                 mo.price_delta_minor AS price_delta_minor,
                 mo.available         AS available,
                 mo.display_order     AS display_order
            FROM menu_item_modifier_option mo
           WHERE mo.group_id = :groupId
             AND mo.available = TRUE
           ORDER BY mo.display_order, mo.id
          """,
      nativeQuery = true)
  List<ModifierOptionView> findAvailableViewsByGroupId(@Param("groupId") UUID groupId);

  /** All options (including unavailable) for a modifier group — for the admin/management view. */
  @Query(
      value =
          """
          SELECT mo.id                AS id,
                 mo.group_id          AS group_id,
                 mo.business_id       AS business_id,
                 mo.name              AS name,
                 mo.price_delta_minor AS price_delta_minor,
                 mo.available         AS available,
                 mo.display_order     AS display_order
            FROM menu_item_modifier_option mo
           WHERE mo.group_id = :groupId
           ORDER BY mo.display_order, mo.id
          """,
      nativeQuery = true)
  List<ModifierOptionView> findAllViewsByGroupId(@Param("groupId") UUID groupId);

  /**
   * Loads options by their ids (used at checkout to validate selected options). RLS-scoped; callers
   * chunk to ≤ 1 000 per call.
   */
  @Query(
      value =
          """
          SELECT mo.id                AS id,
                 mo.group_id          AS group_id,
                 mo.business_id       AS business_id,
                 mo.name              AS name,
                 mo.price_delta_minor AS price_delta_minor,
                 mo.available         AS available,
                 mo.display_order     AS display_order
            FROM menu_item_modifier_option mo
           WHERE mo.id IN (:ids)
          """,
      nativeQuery = true)
  List<ModifierOptionView> findViewsByIds(@Param("ids") List<UUID> ids);

  /**
   * Batch-loads all available options for a set of modifier group ids. Used by the cashier
   * menu-list read to embed available options in one query rather than one-per-group. RLS-scoped;
   * callers must chunk {@code groupIds} to at most 1 000 per call (IN-clause convention,
   * CLAUDE.md).
   *
   * <p>Only options with {@code available = TRUE} are returned (unavailable / 86'd options are
   * excluded, consistent with the cashier view).
   */
  @Query(
      value =
          """
          SELECT mo.id                AS id,
                 mo.group_id          AS group_id,
                 mo.business_id       AS business_id,
                 mo.name              AS name,
                 mo.price_delta_minor AS price_delta_minor,
                 mo.available         AS available,
                 mo.display_order     AS display_order
            FROM menu_item_modifier_option mo
           WHERE mo.group_id IN (:groupIds)
             AND mo.available = TRUE
           ORDER BY mo.group_id, mo.display_order, mo.id
          """,
      nativeQuery = true)
  List<ModifierOptionView> findAvailableViewsByGroupIds(@Param("groupIds") List<UUID> groupIds);

  /**
   * Scalar ids of ALL options belonging to a menu item (via its modifier groups) — the recipe
   * feature's option-ownership validation (ADR 0050). Scalar list, not a projection (house rule:
   * count/exists/id scalars are not entity reads).
   */
  @Query(
      value =
          """
          SELECT mo.id
            FROM menu_item_modifier_option mo
            JOIN menu_item_modifier_group mg ON mg.id = mo.group_id
           WHERE mg.menu_item_id = :menuItemId
          """,
      nativeQuery = true)
  List<UUID> findIdsByMenuItemId(@Param("menuItemId") UUID menuItemId);

  /**
   * Scalar ids of a group's options — collected BEFORE {@link #deleteByGroupId} so the recipe
   * feature can cascade-delete the per-option deltas in the same transaction (ADR 0050).
   */
  @Query(
      value = "SELECT mo.id FROM menu_item_modifier_option mo WHERE mo.group_id = :groupId",
      nativeQuery = true)
  List<UUID> findIdsByGroupId(@Param("groupId") UUID groupId);

  /**
   * Hard-deletes all options belonging to a modifier group. Called as part of group deletion. RLS
   * is active — only rows visible to the current tenant are deleted. Must be invoked within an
   * active {@link org.springframework.transaction.annotation.Transactional} context.
   */
  @Modifying
  @Query(
      value = "DELETE FROM menu_item_modifier_option WHERE group_id = :groupId",
      nativeQuery = true)
  void deleteByGroupId(@Param("groupId") UUID groupId);
}
