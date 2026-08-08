package id.co.nativeapp.restaurant.menu.repository;

import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link MenuItem}.
 *
 * <p>Carries <em>no</em> manual {@code WHERE company_id} and no call to apply the tenant GUC:
 * {@link RlsAutoApplyAspect} sets {@code app.current_tenant} on the connection automatically for
 * every {@code @Transactional} method, and the PostgreSQL RLS policy restricts results to the bound
 * company (rule 5). Read paths use a native query + projection (never {@code SELECT *}).
 */
public interface MenuItemRepository extends JpaRepository<MenuItem, UUID> {

  /**
   * Active items for a business, projected to {@link MenuItemView}. RLS-scoped — no manual {@code
   * WHERE company_id}. Items belonging to an inactive category are excluded (a hidden category
   * hides all its items). Returns only the business columns the response needs, ordered by category
   * display_order then item name.
   */
  @Query(
      value =
          """
          SELECT m.id              AS id,
                 m.business_id     AS business_id,
                 m.name            AS name,
                 m.category        AS category,
                 m.category_id     AS category_id,
                 m.price_minor     AS price_minor,
                 m.currency        AS currency,
                 m.active          AS active,
                 m.available       AS available,
                 m.stock_quantity  AS stock_quantity,
                 m.image_url       AS image_url,
                 m.image_key       AS image_key,
                 m.unit_cost_minor AS unit_cost_minor
            FROM menu_item m
            LEFT JOIN menu_category mc ON mc.id = m.category_id
           WHERE m.business_id = :businessId
             AND m.active = TRUE
             AND (mc.id IS NULL OR mc.active = TRUE)
           ORDER BY COALESCE(mc.display_order, 0), mc.name, m.name, m.id
          """,
      nativeQuery = true)
  List<MenuItemView> findActiveByBusiness(@Param("businessId") UUID businessId);

  /**
   * Loads items by id for the checkout validation path, projected to {@link MenuItemView} —
   * checkout only needs each item's business, name, price, currency, active, available flags, and
   * stock_quantity to validate the request and snapshot the line, never the full aggregate. The
   * image columns are deliberately NOT selected (checkout never renders an image; pre-ADR-0048 this
   * query dragged every base64 payload through the JVM per checkout) — {@code
   * MenuItemView.getImageUrl()/getImageKey()} are {@code null} on this path. RLS-scoped; callers
   * chunk the id list to at most 1 000 per call (CLAUDE.md IN-clause convention).
   */
  @Query(
      value =
          """
          SELECT m.id              AS id,
                 m.business_id     AS business_id,
                 m.name            AS name,
                 m.category        AS category,
                 m.category_id     AS category_id,
                 m.price_minor     AS price_minor,
                 m.currency        AS currency,
                 m.active          AS active,
                 m.available       AS available,
                 m.stock_quantity  AS stock_quantity,
                 m.unit_cost_minor AS unit_cost_minor
            FROM menu_item m
           WHERE m.id IN (:ids)
          """,
      nativeQuery = true)
  List<MenuItemView> findViewsByIds(@Param("ids") List<UUID> ids);

  /**
   * Ids of this tenant's items still carrying a LEGACY inline base64 image (pre-ADR-0048 data URL
   * in {@code image_url}) — the work list for the owner-triggered {@code POST
   * /api/v1/menu/images/migrate} backfill. RLS-scoped: the query runs inside a normal tenant-bound
   * transaction, so it can only ever see (and the backfill only ever converts) the caller's own
   * rows — deliberately NOT a Flyway backfill (a migration cannot reach the object store, and under
   * FORCE RLS its UPDATE would silently match 0 rows — the V6/V7 lesson).
   */
  @Query(
      value =
          """
          SELECT m.id AS id
            FROM menu_item m
           WHERE m.image_url LIKE 'data:image/%'
          """,
      nativeQuery = true)
  List<UUID> findLegacyInlineImageItemIds();

  /**
   * Atomically deducts {@code qty} from a <em>tracked</em> item's stock in a single UPDATE, using
   * an optimistic-lock-style guard to prevent overselling under concurrency.
   *
   * <p>The UPDATE is gated on:
   *
   * <ul>
   *   <li>{@code stock_quantity IS NOT NULL} — skips untracked (infinite) items silently; callers
   *       should treat a 0-row update on a known-tracked item as an insufficient-stock signal.
   *   <li>{@code stock_quantity >= :qty} — ensures the deduction cannot make stock negative.
   * </ul>
   *
   * <p>Returns the number of rows updated: 1 on success, 0 if the item is untracked OR if stock is
   * too low. Callers distinguish the two cases by checking the item's current tracked state before
   * calling this method.
   *
   * <p>RLS is enforced by the connection's tenant GUC (set by {@link RlsAutoApplyAspect}), so no
   * explicit {@code WHERE company_id} is needed.
   *
   * @param id the menu item id
   * @param qty units to deduct (&ge; 1)
   * @return 1 if the deduction succeeded; 0 if the item is untracked or stock &lt; qty
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE menu_item
             SET stock_quantity = stock_quantity - :qty,
                 updated_at     = NOW(),
                 version        = version + 1
           WHERE id             = :id
             AND stock_quantity IS NOT NULL
             AND stock_quantity >= :qty
          """,
      nativeQuery = true)
  int deductStock(@Param("id") UUID id, @Param("qty") int qty);

  /**
   * Phase 5 (ADR 0028): the offline-replay stock policy — deducts {@code qty} from a
   * <em>tracked</em> item's stock WITHOUT the {@code stock_quantity >= :qty} guard, so the level is
   * allowed to go negative. Used ONLY for a replayed offline sale, which must never be rejected for
   * insufficient stock (the cash is already in the drawer); the caller records a discrepancy for
   * repair by count. Online checkout keeps using the guarded {@link #deductStock}.
   *
   * <p>Still skips an untracked item ({@code stock_quantity IS NULL}) silently — the WHERE clause
   * excludes it, so a 0-row result on a caller-confirmed-tracked item id would indicate the row was
   * concurrently deleted, not a stock shortfall.
   *
   * @param id the menu item id
   * @param qty units to deduct (&ge; 1)
   * @return 1 if the deduction applied; 0 if the item is untracked or no longer exists
   */
  @Modifying
  @Query(
      value =
          """
          UPDATE menu_item
             SET stock_quantity = stock_quantity - :qty,
                 updated_at     = NOW(),
                 version        = version + 1
           WHERE id             = :id
             AND stock_quantity IS NOT NULL
          """,
      nativeQuery = true)
  int forceDeductStock(@Param("id") UUID id, @Param("qty") int qty);
}
