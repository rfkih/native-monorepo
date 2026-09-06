package id.co.nativeapp.restaurant.integrity.repository;

import id.co.nativeapp.restaurant.integrity.projection.CancelledBillView;
import id.co.nativeapp.restaurant.integrity.projection.DarkHourView;
import id.co.nativeapp.restaurant.integrity.projection.IngredientShortfallView;
import id.co.nativeapp.restaurant.integrity.projection.MissingTrackedItemView;
import id.co.nativeapp.restaurant.integrity.projection.OperatorActivityView;
import id.co.nativeapp.restaurant.integrity.projection.OperatorRefundView;
import id.co.nativeapp.restaurant.integrity.projection.OutsideSessionSalesView;
import id.co.nativeapp.restaurant.integrity.projection.RecipeEdgeView;
import id.co.nativeapp.restaurant.integrity.projection.RegisterSessionHygieneView;
import id.co.nativeapp.restaurant.integrity.projection.SoldItemCoverageView;
import id.co.nativeapp.restaurant.integrity.projection.SoldQuantityView;
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
 * ingredient_usage_day} and the channel summary.
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
                 i.display_unit               AS display_unit,
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
           GROUP BY isl.ingredient_id, i.name, i.unit, i.display_unit
           ORDER BY SUM(-isl.variance_value_minor) DESC
          """,
      nativeQuery = true)
  List<IngredientShortfallView> findIngredientShortfalls(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Every menu item whose BASE recipe consumes one of {@code ingredientIds}, and how much of that
   * ingredient one portion takes.
   *
   * <p>Recipe structure ONLY — the sold quantities that weight the allocation come from {@link
   * #findSoldQuantities}, fetched once per report. Embedding that aggregate here would re-scan and
   * re-group every {@code order_line} and {@code bill_line} of the period for each 1000-id chunk
   * and then discard all but the chunk's items, on top of the identical roll-up {@link
   * #findSoldItemCoverage} already performs in the same request.
   *
   * <p>Only BASE recipe lines ({@code modifier_option_id IS NULL}) participate. Per-option deltas
   * are deliberately excluded: they are signed adjustments to a specific order's portion, and
   * attributing a shortfall through them would require knowing which options were chosen on the
   * sales that were never recorded — which is precisely what is unknown.
   *
   * <p>Callers must chunk {@code ingredientIds} to ≤ 1000 per call (the {@code IN}-clause
   * convention).
   */
  @Query(
      value =
          """
          SELECT rl.ingredient_id   AS ingredient_id,
                 rl.menu_item_id    AS menu_item_id,
                 m.name             AS name,
                 m.price_minor      AS unit_price_minor,
                 m.currency         AS currency,
                 rl.qty_per_portion AS qty_per_portion
            FROM recipe_line rl
            JOIN menu_item m ON m.id = rl.menu_item_id
           WHERE rl.business_id = :businessId
             AND rl.modifier_option_id IS NULL
             AND rl.ingredient_id IN (:ingredientIds)
          """,
      nativeQuery = true)
  List<RecipeEdgeView> findRecipeEdges(
      @Param("businessId") UUID businessId, @Param("ingredientIds") Collection<UUID> ingredientIds);

  /**
   * Units sold per menu item in {@code [from, to)} — the sales mix every allocation is weighted by.
   *
   * <p>The union of the two paths a menu item can sell through, which never overlap: an
   * order-then-checkout writes {@code order_line} rows under a {@code restaurant_order} carrying
   * the sale, while a bill/tab check writes {@code bill_line} rows stamped with {@code
   * paid_sale_id}. Counting only one would systematically under-weight whichever way the outlet
   * actually trades and skew every allocation built on it. Both are joined THROUGH {@code sale} so
   * the window is the sale's own {@code occurred_at}, the same clock every other figure in this
   * report uses.
   */
  @Query(
      value =
          """
          SELECT u.menu_item_id AS menu_item_id, SUM(u.qty) AS sold_qty
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
          """,
      nativeQuery = true)
  List<SoldQuantityView> findSoldQuantities(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

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
   * <p><strong>The baseline counts the quiet days too.</strong> {@code hourly} only has a row where
   * a sale happened, so taking the median straight from it would average over the days the outlet
   * DID sell at that hour and silently discard every day it sold nothing — an outlet that sells at
   * 21:00 on three Mondays in eight would get a baseline of "about five sales", and the five silent
   * Mondays would each be reported as a hole. The baseline therefore builds the full (baseline day
   * x hour) grid and LEFT JOINs the counts, so an hour that saw nothing contributes a genuine zero.
   * That is what makes {@code minExpected} mean "normally busy" rather than "busy whenever it was
   * busy at all".
   *
   * <p><strong>Only elapsed hours can be judged.</strong> The window's {@code to} is whatever the
   * caller asked for and is routinely in the FUTURE (the console's default period is the current
   * month, so {@code to} is month-end). Without the {@code observedTo} bound, every remaining hour
   * of today — and of every day left in the month — has no rows, satisfies {@code NOT EXISTS}, and
   * is reported as a dark hour. An hour is eligible only once it has completely passed: an hour
   * still in progress has not had its chance to record anything.
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
                 AND s.occurred_at <  :observedTo
               GROUP BY 1, 2, 3
          ),
          baseline_days AS (
              SELECT DISTINCT h.biz_date AS biz_date, h.dow AS dow
                FROM hourly h
               WHERE h.biz_date < :windowStart
          ),
          hours AS (
              SELECT generate_series(0, 23) AS hr
          ),
          baseline AS (
              SELECT d.dow AS dow,
                     x.hr  AS hr,
                     percentile_disc(0.5) WITHIN GROUP (ORDER BY COALESCE(h.sale_count, 0))
                                          AS median_count
                FROM baseline_days d
                CROSS JOIN hours x
                LEFT JOIN hourly h ON h.biz_date = d.biz_date AND h.hr = x.hr
               GROUP BY d.dow, x.hr
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
             AND (t.biz_date + make_interval(hours => b.hr + 1))
                   AT TIME ZONE 'Asia/Jakarta' <= :observedTo
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
      @Param("observedTo") Instant observedTo,
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
            FROM ingredient_usage_day d
           WHERE d.business_id = :businessId
             AND d.usage_date >= :fromDate
             AND d.usage_date <  :toDateExclusive
          """,
      nativeQuery = true)
  long countManualStockCorrections(
      @Param("businessId") UUID businessId,
      @Param("fromDate") LocalDate fromDate,
      @Param("toDateExclusive") LocalDate toDateExclusive);

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

  /**
   * Per-operator till activity over {@code [from, to)} — the counts behind the void, discount and
   * tender-mix checks.
   *
   * <p>The operator is {@code COALESCE(sold_by_user_id, created_by)}. On an outlet terminal the
   * verified operator rings the sale while the DEVICE credential owns the audit column, so {@code
   * created_by} alone would attribute an entire shift to a kiosk; on an ordinary console login
   * there is no operator session and {@code created_by} IS the person. Neither column alone is the
   * answer, which is why this is a fallback rather than a choice.
   *
   * <p><strong>Only revenue-bearing payments count.</strong> {@code ck_payment_status} also allows
   * PENDING, ABANDONED and FAILED — tenders where no money ever moved. They belong in no rate on
   * either side of the fraction: counting them inflates the denominator of an operator who
   * generates many abandoned QRIS attempts (diluting a genuine void rate below the bar) while
   * leaving a cash-only till's denominator small (making ordinary behaviour look like an outlier),
   * and they add money to {@code gross_minor} that was never taken.
   *
   * <p>Returns COUNTS, never rates. Every rate is derived in the service against the rest of the
   * outlet, which the SQL cannot express per-row without recomputing the whole aggregate for each
   * actor.
   */
  @Query(
      value =
          """
          SELECT COALESCE(p.sold_by_user_id, p.created_by)                        AS actor,
                 COUNT(*)                                                         AS payment_count,
                 COUNT(*) FILTER (WHERE p.status = 'VOIDED')                      AS void_count,
                 COALESCE(SUM(p.amount_minor) FILTER (WHERE p.status = 'VOIDED'), 0)
                                                                                  AS void_minor,
                 COALESCE(SUM(p.discount_minor), 0)                               AS discount_minor,
                 COUNT(*) FILTER (WHERE COALESCE(p.discount_minor, 0) > 0)        AS discount_count,
                 COALESCE(SUM(p.amount_minor), 0)                                 AS gross_minor,
                 COUNT(*) FILTER (WHERE p.tender_type = 'CASH')                   AS cash_count,
                 MAX(p.currency)                                                  AS currency
            FROM payment p
           WHERE p.business_id = :businessId
             AND p.occurred_at >= :from
             AND p.occurred_at <  :to
             AND p.status IN ('CAPTURED', 'VOIDED', 'REFUNDED', 'PARTIALLY_REFUNDED')
           GROUP BY COALESCE(p.sold_by_user_id, p.created_by)
           ORDER BY COALESCE(p.sold_by_user_id, p.created_by)
          """,
      nativeQuery = true)
  List<OperatorActivityView> findOperatorActivity(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Per-operator refunds over {@code [from, to)}, attributed to whoever took the ORIGINAL payment.
   *
   * <p>A separate query from {@link #findOperatorActivity} because a refund has its OWN timestamp
   * on its own append-only row (V22): a payment taken in March and refunded in April belongs to
   * March's activity and April's refunds. One query would force a single window onto two different
   * events and misattribute every refund that crossed a period boundary.
   *
   * <p>{@code payment_refund} carries no actor column, so the operator comes through the join —
   * which is also the right attribution: the question a refund raises is about who took the money,
   * not who happened to process the reversal.
   */
  @Query(
      value =
          """
          SELECT COALESCE(p.sold_by_user_id, p.created_by) AS actor,
                 COUNT(*)                                  AS refund_count,
                 COALESCE(SUM(r.amount_minor), 0)          AS refund_minor,
                 MAX(r.currency)                           AS currency
            FROM payment_refund r
            JOIN payment p ON p.id = r.payment_id
           WHERE r.business_id = :businessId
             AND r.refunded_at >= :from
             AND r.refunded_at <  :to
           GROUP BY COALESCE(p.sold_by_user_id, p.created_by)
           ORDER BY COALESCE(p.sold_by_user_id, p.created_by)
          """,
      nativeQuery = true)
  List<OperatorRefundView> findOperatorRefunds(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);

  /**
   * Bills cancelled in {@code [from, to)} that still had lines on them.
   *
   * <p>An EMPTY bill cancelled is a wrong table opened — routine, and the open-bill lockdown lets
   * anyone do it. A bill cancelled with items already on it is a different event: in a restaurant
   * those items were plausibly cooked and served, and then the tab disappeared without a sale.
   *
   * <p>Windowed on {@code updated_at} rather than {@code created_at}: the cancel is what this
   * detector is about, and a tab opened in one period and cancelled in the next belongs to the
   * period it was cancelled in. {@code updated_by} names the canceller for the same reason — a
   * cancel is the last write to the row.
   */
  @Query(
      value =
          """
          SELECT b.id                                  AS bill_id,
                 b.updated_by                          AS actor,
                 b.updated_at                          AS cancelled_at,
                 COUNT(bl.id)                          AS line_count,
                 COALESCE(SUM(bl.line_total_minor), 0) AS total_minor,
                 b.currency                            AS currency
            FROM bill b
            JOIN bill_line bl ON bl.bill_id = b.id
           WHERE b.business_id = :businessId
             AND b.status = 'CANCELLED'
             AND b.updated_at >= :from
             AND b.updated_at <  :to
           GROUP BY b.id, b.updated_by, b.updated_at, b.currency
           ORDER BY COALESCE(SUM(bl.line_total_minor), 0) DESC
          """,
      nativeQuery = true)
  List<CancelledBillView> findCancelledBillsWithLines(
      @Param("businessId") UUID businessId, @Param("from") Instant from, @Param("to") Instant to);
}
