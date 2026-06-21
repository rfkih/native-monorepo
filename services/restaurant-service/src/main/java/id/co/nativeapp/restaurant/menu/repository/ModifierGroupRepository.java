package id.co.nativeapp.restaurant.menu.repository;

import id.co.nativeapp.restaurant.menu.domain.ModifierGroup;
import id.co.nativeapp.restaurant.menu.projection.ModifierGroupView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link ModifierGroup}.
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} applies RLS (rule 5). Read
 * paths use native query + projection.
 */
public interface ModifierGroupRepository extends JpaRepository<ModifierGroup, UUID> {

  /**
   * All modifier groups for a menu item, ordered by display_order. RLS-scoped; no explicit
   * company_id filter.
   */
  @Query(
      value =
          """
          SELECT mg.id             AS id,
                 mg.menu_item_id   AS menu_item_id,
                 mg.business_id    AS business_id,
                 mg.name           AS name,
                 mg.selection_type AS selection_type,
                 mg.required       AS required,
                 mg.min_select     AS min_select,
                 mg.max_select     AS max_select,
                 mg.display_order  AS display_order
            FROM menu_item_modifier_group mg
           WHERE mg.menu_item_id = :menuItemId
           ORDER BY mg.display_order, mg.id
          """,
      nativeQuery = true)
  List<ModifierGroupView> findViewsByMenuItemId(@Param("menuItemId") UUID menuItemId);

  /**
   * Loads groups by their ids (used for checkout validation — validates all selected options'
   * groups exist and belong to the item). RLS-scoped; callers chunk to ≤ 1 000 per call.
   */
  @Query(
      value =
          """
          SELECT mg.id             AS id,
                 mg.menu_item_id   AS menu_item_id,
                 mg.business_id    AS business_id,
                 mg.name           AS name,
                 mg.selection_type AS selection_type,
                 mg.required       AS required,
                 mg.min_select     AS min_select,
                 mg.max_select     AS max_select,
                 mg.display_order  AS display_order
            FROM menu_item_modifier_group mg
           WHERE mg.id IN (:ids)
          """,
      nativeQuery = true)
  List<ModifierGroupView> findViewsByIds(@Param("ids") List<UUID> ids);

  /**
   * All required modifier groups for a menu item — used at checkout to verify that required groups
   * have been satisfied.
   */
  @Query(
      value =
          """
          SELECT mg.id             AS id,
                 mg.menu_item_id   AS menu_item_id,
                 mg.business_id    AS business_id,
                 mg.name           AS name,
                 mg.selection_type AS selection_type,
                 mg.required       AS required,
                 mg.min_select     AS min_select,
                 mg.max_select     AS max_select,
                 mg.display_order  AS display_order
            FROM menu_item_modifier_group mg
           WHERE mg.menu_item_id = :menuItemId
             AND mg.required = TRUE
          """,
      nativeQuery = true)
  List<ModifierGroupView> findRequiredViewsByMenuItemId(@Param("menuItemId") UUID menuItemId);
}
