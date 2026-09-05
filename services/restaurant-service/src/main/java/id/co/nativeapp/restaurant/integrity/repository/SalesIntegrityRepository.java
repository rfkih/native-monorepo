package id.co.nativeapp.restaurant.integrity.repository;

import id.co.nativeapp.restaurant.integrity.projection.DarkHourView;
import id.co.nativeapp.restaurant.integrity.projection.IngredientShortfallView;
import id.co.nativeapp.restaurant.integrity.projection.MissingTrackedItemView;
import id.co.nativeapp.restaurant.integrity.projection.OutsideSessionSalesView;
import id.co.nativeapp.restaurant.integrity.projection.RecipeConsumerView;
import id.co.nativeapp.restaurant.integrity.projection.RegisterSessionHygieneView;
import id.co.nativeapp.restaurant.integrity.projection.SoldItemCoverageView;
import id.co.nativeapp.restaurant.integrity.projection.UnclosedTradingDayView;
import id.co.nativeapp.restaurant.sale.domain.Sale;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The read-only analytical queries behind the sales-leak report (ADR 0074).
 *
 * <p><strong>Bound to {@link Sale} but never writing it.</strong> Spring Data needs a domain type
 * to anchor a repository; native SQL then joins whatever it needs regardless of that binding (the
 * established precedent is {@code SaleRepository#findHistory}, which joins {@code restaurant_order}
 * and {@code payment}). Binding here keeps every leak query inside the {@code integrity} feature
 * instead of scattering report concerns across five other features' repositories. Nothing in this
 * interface mutates: there is no {@code @Modifying} method and there never should be — the estimate
 * is an inference and inferences do not get written down as facts.
 *
 * <p>No manual {@code WHERE company_id} anywhere — every method is transactional, so {@link
 * RlsAutoApplyAspect} binds the tenant GUC and the FORCE-RLS policies scope every joined table
 * (rule 5). Columns are named explicitly (never {@code SELECT *}) and each read maps to a
 * projection interface, per ADR 0002.
 *
 * <p><strong>Outlet-local dates are Asia/Jakarta.</strong> Where a query buckets by calendar day it
 * shifts {@code occurred_at} (a {@code TIMESTAMPTZ}) into that zone FIRST — the DB session runs in
 * UTC, so a plain cast would push every sale rung after 17:00 WIB into the following day and
 * misalign it with the register's own {@code business_date}. Same convention as {@code
 * ingredient_stock_day} and the channel summary.
 */
public interface SalesIntegrityRepository extends JpaRepository<Sale, UUID> {

  /**
   * Tracked menu items counted SHORT at any stocktake in {@code [from, to)} — units that left the
   * shelf with no sale behind them. The quantity is negated to a positive "missing" figure so no
   * reader has to remember the sign convention; only shortfall lines are considered, because a line
   * that found MORE than expected is a different problem (a mis-count or an unrecorded receipt) and
   * netting the two would let a surplus on one item silently cancel a theft of another.
   */
  @Query(
      value =
          """
          SELECT sl.menu_item_id     AS menu_item_id,
                 m.name              AS name,
                 SUM(-sl.variance_qty) AS missing_qty,
                 m.price_minor       AS unit_price_minor,
                 m.currency          AS currency,
                 MAX(st.counted_at)  AS last_counted_at
            FROM stocktake_line sl
            JOIN stocktake st ON st.id = sl.stocktake_id
            JOIN menu_item m  ON m.id = sl.menu_item_id
           WHERE st.business_id = :businessId
             AND st.counted_at >= :from
             AND st.counted_at <  :to
             AND sl.variance_qty < 0
           GROUP BY sl.menu_item_id, m.name, m.price_minor, m.currency
           ORDER BY SUM(-sl.variance_qty) * m.price_minor DESC
          """,
      nativeQuery = true)
  List<MissingTrackedItemView> findMissingTrackedItems(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Ingredients counted SHORT at any stock opname in {@code [from, to)}. Shortfall lines only, for
   * the same reason as {@link #findMissingTrackedItems}.
   *
   * <p>{@code variance_value_minor} is already the moving-average-costed value of the variance (ADR
   * 0056) and is negative on a shortfall, so it is negated here into a positive cost. An uncosted
   * ingredient contributes 0 to the value but still carries its quantity — it can still be turned
   * into estimated revenue through its recipes, which is the figure that matters here.
   */
  @Query(
      value =
          """
          SELECT isl.ingredient_id            AS ingredient_id,
                 i.name                       AS name,
                 i.unit                       AS unit,
                 SUM(-isl.variance_qty)       AS missing_qty,
                 SUM(-isl.variance_value_minor) AS missing_cost_minor,
                 MAX(ist.currency)            AS currency,
                 MAX(ist.counted_at)          AS last_counted_at
            FROM ingredient_stocktake_line isl
            JOIN ingredient_stocktake ist ON ist.id = isl.ingredient_stocktake_id
            JOIN ingredient i            ON i.id = isl.ingredient_id
           WHERE ist.business_id = :businessId
             AND ist.counted_at >= :from
             AND ist.counted_at <  :to
             AND isl.variance_qty < 0
           GROUP BY isl.ingredient_id, i.name, i.unit
           ORDER BY SUM(-isl.variance_value_minor) DESC
          """,
      nativeQuery = true)
  List<IngredientShortfallView> findIngredientShortfalls(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Every menu item whose BASE recipe consumes one of {@code ingredientIds}, with the quantity of
   * that item actually sold in {@code [from, to)}.
   *
   * <p>Sold quantity is the union of the two paths a menu item can sell through, which never
   * overlap: an order-then-checkout writes {@code order_line} rows under a {@code restaurant_order}
   * carrying the sale, while a bill/tab check writes {@code bill_line} rows stamped with {@code
   * paid_sale_id}. Counting only one would systematically under-weight whichever way the outlet
   * actually trades and skew every allocation built on it. Both are joined THROUGH {@code sale} so
   * the window is the sale's own {@code occurred_at}, the same clock every other figure in this
   * report uses.
   *
   * <p>{@code LEFT JOIN} on the sold aggregate: an item with a recipe that sold nothing in the
   * window still comes back, at {@code sold_qty = 0}, so the caller can see that an ingredient's
   * only consumers were dormant — which is itself the case where a proportional allocation has
   * nothing to go on.
   *
   * <p>Callers must chunk {@code ingredientIds} to ≤ 1000 per call (the {@code IN}-clause
   * convention).
   */
  @Query(
      value =
          """
          SELECT rl.ingredient_id     AS ingredient_id,
                 rl.menu_item_id      AS menu_item_id,
                 m.name               AS name,
                 m.price_minor        AS unit_price_minor,
                 m.currency           AS currency,
                 rl.qty_per_portion   AS qty_per_portion,
                 COALESCE(sold.qty, 0) AS sold_qty
            FROM recipe_line rl
            JOIN menu_item m ON m.id = rl.menu_item_id
            LEFT JOIN (
                 SELECT u.menu_item_id AS menu_item_id, SUM(u.qty) AS qty
                   FROM (
                        SELECT ol.menu_item_id AS menu_item_id, ol.qty AS qty
                          FROM order_line ol
                          JOIN restaurant_order o ON o.id = ol.order_id
                          JOIN sale s            ON s.id = o.sale_id
                         WHERE s.business_id = :businessId
                           AND s.occurred_at >= :from
                           AND s.occurred_at <  :to
                        UNION ALL
                        SELECT bl.menu_item_id AS menu_item_id, bl.qty AS qty
                          FROM bill_line bl
                          JOIN sale s ON s.id = bl.paid_sale_id
                         WHERE s.business_id = :businessId
                           AND s.occurred_at >= :from
                           AND s.occurred_at <  :to
                   ) u
                  GROUP BY u.menu_item_id
            ) sold ON sold.menu_item_id = rl.menu_item_id
           WHERE rl.business_id = :businessId
             AND rl.modifier_option_id IS NULL
             AND rl.ingredient_id IN (:ingredientIds)
          """,
      nativeQuery = true)
  List<RecipeConsumerView> findRecipeConsumers(
      @Param("businessId") UUID businessId,
      @Param("ingredientIds") Collection<UUID> ingredientIds,
      @Param("from") Instant from,
      @Param("to") Instant to);

  /**
   * The sales rung while no register session was open. A till trading outside a session has a
   * drawer that is never counted against what it took, which is the condition under which skimming
   * leaves no trace anywhere else.
   *
   * <p>A session with {@code closed_at IS NULL} is still open, so it covers everything from {@code
   * opened_at} onward — hence the null-tolerant upper bound rather than a plain {@code BETWEEN},
   * which would report every sale on a still-open shift as unsessioned.
   *
   * <p>Returns an empty {@link Optional} when no sale fell outside a session ({@code HAVING} drops
   * the all-zero row rather than returning a misleading "0 sales, IDR" tuple with no currency).
   */
  @Query(
      value =
          """
          SELECT COUNT(*)              AS sale_count,
                 SUM(s.amount_minor)   AS total_minor,
                 MAX(s.currency)       AS currency
            FROM sale s
           WHERE s.business_id = :businessId
             AND s.occurred_at >= :from
             AND s.occurred_at <  :to
             AND NOT EXISTS (
                   SELECT 1
                     FROM cash_register_session c
                    WHERE c.business_id = s.business_id
                      AND s.occurred_at >= c.opened_at
                      AND (c.closed_at IS NULL OR s.occurred_at < c.closed_at))
          HAVING COUNT(*) > 0
          """,
      nativeQuery = true)
  Optional<OutsideSessionSalesView> findSalesOutsideAnySession(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Outlet-local days that recorded sales but never had a register session CLOSED — a trading day
   * with no Z-report, so nothing ever reconciled the drawer against the system's figure.
   *
   * <p>The day is derived by shifting {@code occurred_at} into Asia/Jakarta BEFORE casting to a
   * date, so it lines up with {@code cash_register_session.business_date} (which the caller sets
   * from outlet-local time). Comparing a UTC-derived date against that column would report a false
   * unclosed day for every evening shift.
   */
  @Query(
      value =
          """
          SELECT d.business_date AS business_date,
                 d.sale_count    AS sale_count,
                 d.total_minor   AS total_minor,
                 d.currency      AS currency
            FROM (
                 SELECT (s.occurred_at AT TIME ZONE 'Asia/Jakarta')::date AS business_date,
                        COUNT(*)            AS sale_count,
                        SUM(s.amount_minor) AS total_minor,
                        MAX(s.currency)     AS currency
                   FROM sale s
                  WHERE s.business_id = :businessId
                    AND s.occurred_at >= :from
                    AND s.occurred_at <  :to
                  GROUP BY 1
            ) d
           WHERE NOT EXISTS (
                 SELECT 1
                   FROM cash_register_session c
                  WHERE c.business_id = :businessId
                    AND c.status = 'CLOSED'
                    AND c.business_date = d.business_date)
           ORDER BY d.business_date
          """,
      nativeQuery = true)
  List<UnclosedTradingDayView> findTradingDaysWithoutClose(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Hours inside the reported window that recorded NOTHING, on a day the outlet was otherwise
   * trading, at a weekday-and-hour its OWN history says is normally busy.
   *
   * <p>The baseline is the outlet compared against itself — {@code percentile_disc(0.5)} of the
   * sale count for that same ISO weekday and hour over {@code [baselineFrom, windowStart)}. A fixed
   * threshold cannot work here: a quiet Tuesday 15:00 and a packed Saturday 19:00 are not
   * comparable, and any single number would either flag the first or miss the second.
   *
   * <p>{@code percentile_disc} (not {@code percentile_cont}) is deliberate: it returns an ACTUAL
   * observed value of the same type as its input, so the median stays an integer count. {@code
   * percentile_cont} interpolates and returns {@code double precision}, dragging a float into a
   * codebase that bans them.
   *
   * <p>The day must have traded — an outlet that was simply closed on a public holiday recorded no
   * sales all day and must not produce fourteen "dark hour" findings. Only a day that demonstrably
   * had a till running, with a hole in the middle of it, is interesting.
   */
  @Query(
      value =
          """
          WITH hourly AS (
              SELECT (s.occurred_at AT TIME ZONE 'Asia/Jakarta')::date              AS biz_date,
                     EXTRACT(ISODOW FROM s.occurred_at AT TIME ZONE 'Asia/Jakarta')::int AS dow,
                     EXTRACT(HOUR   FROM s.occurred_at AT TIME ZONE 'Asia/Jakarta')::int AS hr,
                     COUNT(*)                                                       AS sale_count
                FROM sale s
               WHERE s.business_id = :businessId
                 AND s.occurred_at >= :baselineFrom
                 AND s.occurred_at <  :to
               GROUP BY 1, 2, 3
          ),
          baseline AS (
              SELECT h.dow AS dow,
                     h.hr  AS hr,
                     percentile_disc(0.5) WITHIN GROUP (ORDER BY h.sale_count) AS median_count
                FROM hourly h
               WHERE h.biz_date < :windowStart
               GROUP BY h.dow, h.hr
          ),
          traded_days AS (
              SELECT DISTINCT h.biz_date AS biz_date, h.dow AS dow
                FROM hourly h
               WHERE h.biz_date >= :windowStart
          )
          SELECT t.biz_date      AS business_date,
                 b.hr            AS hour_of_day,
                 b.median_count  AS expected_count
            FROM traded_days t
            JOIN baseline b ON b.dow = t.dow
           WHERE b.median_count >= :minExpected
             AND NOT EXISTS (
                   SELECT 1
                     FROM hourly h
                    WHERE h.biz_date = t.biz_date
                      AND h.hr = b.hr)
           ORDER BY t.biz_date, b.hr
          """,
      nativeQuery = true)
  List<DarkHourView> findDarkHours(
      @Param("businessId") UUID businessId,
      @Param("baselineFrom") Instant baselineFrom,
      @Param("windowStart") LocalDate windowStart,
      @Param("to") Instant to,
      @Param("minExpected") long minExpected);

  /**
   * Every register session opened in {@code [from, to)}, oldest first — the raw rows the
   * closing-hygiene checks fold over.
   *
   * <p>The checks are derived in the service, not here, because the interesting ones are statements
   * about a SEQUENCE (a run of closes that came out to exactly zero) rather than about any single
   * row, and a fold over ordered rows says that far more legibly — and tests far more precisely —
   * than a window function buried in SQL.
   *
   * <p>{@code closed_by} reads {@code updated_by}: a close is the last write to the row, so the
   * audit column already holds the actor who closed it. While the session is still OPEN it holds
   * the opener, which is exactly who a "left open" finding should name.
   */
  @Query(
      value =
          """
          SELECT c.id               AS session_id,
                 c.business_date    AS business_date,
                 c.opened_at        AS opened_at,
                 c.closed_at        AS closed_at,
                 c.status           AS status,
                 c.over_short_minor AS over_short_minor,
                 c.currency         AS currency,
                 c.updated_by       AS closed_by
            FROM cash_register_session c
           WHERE c.business_id = :businessId
             AND c.opened_at >= :from
             AND c.opened_at <  :to
           ORDER BY c.opened_at
          """,
      nativeQuery = true)
  List<RegisterSessionHygieneView> findSessionsInWindow(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * How much of what the outlet sold in the window was backed by a recipe at all — the report's own
   * blind-spot disclosure.
   *
   * <p>Sold quantity is the same order-plus-bill union {@link #findRecipeConsumers} uses, so the
   * coverage figure and the allocation it qualifies are computed over the identical population; a
   * coverage number derived from a different denominator would quietly misdescribe the estimate it
   * is meant to qualify.
   */
  @Query(
      value =
          """
          SELECT COALESCE(SUM(u.qty), 0) AS total_sold_qty,
                 COALESCE(SUM(u.qty) FILTER (
                     WHERE EXISTS (SELECT 1 FROM recipe_line rl
                                    WHERE rl.menu_item_id = u.menu_item_id)), 0)
                                       AS recipe_backed_sold_qty
            FROM (
                 SELECT ol.menu_item_id AS menu_item_id, ol.qty AS qty
                   FROM order_line ol
                   JOIN restaurant_order o ON o.id = ol.order_id
                   JOIN sale s            ON s.id = o.sale_id
                  WHERE s.business_id = :businessId
                    AND s.occurred_at >= :from
                    AND s.occurred_at <  :to
                 UNION ALL
                 SELECT bl.menu_item_id AS menu_item_id, bl.qty AS qty
                   FROM bill_line bl
                   JOIN sale s ON s.id = bl.paid_sale_id
                  WHERE s.business_id = :businessId
                    AND s.occurred_at >= :from
                    AND s.occurred_at <  :to
            ) u
          """,
      nativeQuery = true)
  SoldItemCoverageView findSoldItemCoverage(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * How many separate manual stock corrections were made in the window, across every ingredient —
   * context, not a verdict.
   *
   * <p>A lot of hand-correcting is worth an owner's attention regardless of the net quantity, which
   * can average out to nearly nothing while the figure was being adjusted every other day. Reads
   * the V47 ledger's {@code adjustment_count}, which counts each correction ACT (an opname line or
   * a manual "set stok"), not the quantity moved.
   */
  @Query(
      value =
          """
          SELECT COALESCE(SUM(d.adjustment_count), 0)
            FROM ingredient_stock_day d
           WHERE d.business_id = :businessId
             AND d.stock_date >= :from
             AND d.stock_date <= :to
          """,
      nativeQuery = true)
  long countManualStockCorrections(
      @Param("businessId") UUID businessId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * When this outlet last counted its ingredients, across all time — not just the reported window.
   *
   * <p>Deliberately unbounded by the window: "the last count was three months ago" is exactly the
   * fact that makes a small shortfall figure meaningless, and a window-scoped query would report
   * "never counted" for an outlet that counted the day before the window opened. Empty when the
   * outlet has genuinely never counted, which the report must show as "never" rather than as 0
   * days.
   */
  @Query(
      value =
          """
          SELECT MAX(ist.counted_at)
            FROM ingredient_stocktake ist
           WHERE ist.business_id = :businessId
          """,
      nativeQuery = true)
  Optional<Instant> findLastIngredientCountAt(@Param("businessId") UUID businessId);

  /**
   * When this outlet last counted its tracked menu items. See {@link #findLastIngredientCountAt}.
   */
  @Query(
      value =
          """
          SELECT MAX(st.counted_at)
            FROM stocktake st
           WHERE st.business_id = :businessId
          """,
      nativeQuery = true)
  Optional<Instant> findLastItemCountAt(@Param("businessId") UUID businessId);
}
