package id.co.nativeapp.restaurant.inventory.repository;

import id.co.nativeapp.restaurant.inventory.domain.IngredientStockDay;
import id.co.nativeapp.restaurant.inventory.projection.IngredientStockDayView;
import id.co.nativeapp.restaurant.inventory.projection.IngredientStockSummaryView;
import id.co.nativeapp.restaurant.inventory.projection.IngredientUsageView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link IngredientStockDay} — the per-day ingredient stock ledger (V42
 * as usage-only, widened to the full movement ledger by V47).
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} sets the tenant GUC and the
 * RLS policy applies the tenant scope (rule 5); each UPSERT carries an explicit {@code company_id}
 * because the FORCE-RLS {@code WITH CHECK} rejects an insert whose company does not match the
 * session tenant (the {@code IngredientWriter#create} note).
 *
 * <p><strong>Every bucket writer shares one shape</strong> — {@code INSERT … SELECT FROM ingredient
 * i … ON CONFLICT (ingredient_id, stock_date) DO UPDATE}:
 *
 * <ul>
 *   <li>{@code business_id} AND {@code closing_qty} are sourced from the {@code ingredient} row
 *       itself, so no caller has to plumb either one, and {@code closing_qty} can never disagree
 *       with the stock figure it is supposed to mirror.
 *   <li>{@code flushAutomatically = true} is what makes that sourcing correct: a caller that moved
 *       stock through the ENTITY ({@code setStock}/{@code addStock}/an opname line) has a dirty,
 *       unflushed persistence context, and a native query would otherwise read the PRE-movement
 *       {@code stock_qty}. The flush pushes the pending UPDATE first. (The depletion path moves
 *       stock with a native UPDATE, so its row is already current either way.)
 *   <li>0 rows when the ingredient was concurrently hard-deleted — nothing to record, matching the
 *       depletion's own no-op.
 *   <li>{@code ON CONFLICT} makes concurrent movements ADDITIVE, never lost — the same guarantee
 *       {@code depleteStockFloorZero}'s single-row UPDATE gives the stock figure.
 * </ul>
 *
 * <p>Callers must invoke these one ingredient at a time in ascending ingredient-UUID order (the
 * depletion writer's established discipline) — a single multi-row batch UPSERT would let Postgres
 * pick its own row-lock order and reintroduce the cross-sale deadlock that ordering prevents.
 */
public interface IngredientStockDayRepository extends JpaRepository<IngredientStockDay, UUID> {

  /**
   * Adds {@code qty} to the ingredient's recipe-driven consumption for {@code stockDate} — called
   * by the per-sale depletion in the SAME transaction as the sale, so a rolled-back sale never
   * leaves usage behind. Records the quantity the recipe REQUESTED (not the floored quantity
   * actually available), matching {@code qty_used}'s documented meaning.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO ingredient_stock_day
                 (id, business_id, ingredient_id, stock_date, qty_used, closing_qty,
                  created_at, created_by, updated_at, updated_by, version, company_id)
          SELECT gen_random_uuid(), i.business_id, i.id, :stockDate, :qty, i.stock_qty,
                 now(), :actor, now(), :actor, 0, :companyId
            FROM ingredient i
           WHERE i.id = :ingredientId
          ON CONFLICT (ingredient_id, stock_date)
          DO UPDATE SET qty_used    = ingredient_stock_day.qty_used + EXCLUDED.qty_used,
                        closing_qty = EXCLUDED.closing_qty,
                        updated_at  = now(),
                        updated_by  = EXCLUDED.updated_by,
                        version     = ingredient_stock_day.version + 1
          """,
      nativeQuery = true)
  int addUsage(
      @Param("ingredientId") UUID ingredientId,
      @Param("stockDate") LocalDate stockDate,
      @Param("qty") long qty,
      @Param("actor") String actor,
      @Param("companyId") String companyId);

  /**
   * Records one receive of {@code qty} into the ingredient on {@code stockDate}: adds to {@code
   * received_qty} and increments {@code receipt_count} by exactly one, so the day distinguishes
   * "300 arrived in one delivery" from "300 arrived across six". {@code qty} must be positive — a
   * receive that reduces stock is an adjustment, not a receipt.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO ingredient_stock_day
                 (id, business_id, ingredient_id, stock_date, qty_used, received_qty,
                  receipt_count, closing_qty,
                  created_at, created_by, updated_at, updated_by, version, company_id)
          SELECT gen_random_uuid(), i.business_id, i.id, :stockDate, 0, :qty,
                 1, i.stock_qty,
                 now(), :actor, now(), :actor, 0, :companyId
            FROM ingredient i
           WHERE i.id = :ingredientId
          ON CONFLICT (ingredient_id, stock_date)
          DO UPDATE SET received_qty  = ingredient_stock_day.received_qty + EXCLUDED.received_qty,
                        receipt_count = ingredient_stock_day.receipt_count + 1,
                        closing_qty   = EXCLUDED.closing_qty,
                        updated_at    = now(),
                        updated_by    = EXCLUDED.updated_by,
                        version       = ingredient_stock_day.version + 1
          """,
      nativeQuery = true)
  int addReceipt(
      @Param("ingredientId") UUID ingredientId,
      @Param("stockDate") LocalDate stockDate,
      @Param("qty") long qty,
      @Param("actor") String actor,
      @Param("companyId") String companyId);

  /**
   * Records one manual correction of {@code deltaQty} (SIGNED — negative when the count came up
   * short) on {@code stockDate}: adds to {@code adjustment_qty} and increments {@code
   * adjustment_count} by exactly one. Both a stock-opname line and a manual "set stok" land here,
   * because from the ledger's point of view they are the same act: a human overriding the system's
   * figure. The count is incremented even for a {@code deltaQty} of 0 — a recount that confirmed
   * the figure still happened, and "berapa kali dikoreksi" should say so.
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO ingredient_stock_day
                 (id, business_id, ingredient_id, stock_date, qty_used, adjustment_qty,
                  adjustment_count, closing_qty,
                  created_at, created_by, updated_at, updated_by, version, company_id)
          SELECT gen_random_uuid(), i.business_id, i.id, :stockDate, 0, :deltaQty,
                 1, i.stock_qty,
                 now(), :actor, now(), :actor, 0, :companyId
            FROM ingredient i
           WHERE i.id = :ingredientId
          ON CONFLICT (ingredient_id, stock_date)
          DO UPDATE SET adjustment_qty   = ingredient_stock_day.adjustment_qty
                                             + EXCLUDED.adjustment_qty,
                        adjustment_count = ingredient_stock_day.adjustment_count + 1,
                        closing_qty      = EXCLUDED.closing_qty,
                        updated_at       = now(),
                        updated_by       = EXCLUDED.updated_by,
                        version          = ingredient_stock_day.version + 1
          """,
      nativeQuery = true)
  int addAdjustment(
      @Param("ingredientId") UUID ingredientId,
      @Param("stockDate") LocalDate stockDate,
      @Param("deltaQty") long deltaQty,
      @Param("actor") String actor,
      @Param("companyId") String companyId);

  /** An outlet's per-ingredient usage for one day — the opname sheet + riwayat detail read. */
  @Query(
      value =
          """
          SELECT d.ingredient_id AS ingredient_id,
                 d.qty_used      AS qty_used
            FROM ingredient_stock_day d
           WHERE d.business_id = :businessId
             AND d.stock_date  = :stockDate
          """,
      nativeQuery = true)
  List<IngredientUsageView> findByBusinessIdAndDate(
      @Param("businessId") UUID businessId, @Param("stockDate") LocalDate stockDate);

  /**
   * One ingredient's daily ledger rows across {@code [from, to]} (inclusive both ends — these are
   * calendar days, not instants), oldest first. Days with no movement are simply absent; the reader
   * carries the previous row's {@code closing_qty} forward across the gap.
   */
  @Query(
      value =
          """
          SELECT d.stock_date       AS stock_date,
                 d.qty_used         AS qty_used,
                 d.received_qty     AS received_qty,
                 d.adjustment_qty   AS adjustment_qty,
                 d.waste_qty        AS waste_qty,
                 d.receipt_count    AS receipt_count,
                 d.adjustment_count AS adjustment_count,
                 d.closing_qty      AS closing_qty
            FROM ingredient_stock_day d
           WHERE d.ingredient_id = :ingredientId
             AND d.stock_date   >= :from
             AND d.stock_date   <= :to
           ORDER BY d.stock_date
          """,
      nativeQuery = true)
  List<IngredientStockDayView> findDailyLedger(
      @Param("ingredientId") UUID ingredientId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);

  /**
   * An outlet's per-ingredient movement roll-up over {@code [from, to]} (inclusive): the totals
   * behind "rata-rata pemakaian per hari" and "total koreksi manual", one row per ingredient that
   * moved at all in the window.
   *
   * <p>Deliberately returns COUNTS and TOTALS, never an average: the caller divides by whichever
   * denominator it means (calendar days in the window, or {@code days_with_usage}) and formats the
   * result with locale-aware {@code Intl}. Doing the division here would force a rounding choice on
   * every reader and drag a non-integer type through the persistence layer for no gain.
   *
   * <p>{@code latest_closing_qty} is the closing figure of the LAST day in the window that recorded
   * any movement — {@code null} when every such row predates V47. It is not the ingredient's
   * current stock (later movement may have followed the window); it is where the window left it.
   */
  @Query(
      value =
          """
          SELECT d.ingredient_id                        AS ingredient_id,
                 i.name                                 AS name,
                 i.unit                                 AS unit,
                 SUM(d.qty_used)                        AS total_used_qty,
                 SUM(d.received_qty)                    AS total_received_qty,
                 SUM(d.adjustment_qty)                  AS net_adjustment_qty,
                 SUM(d.waste_qty)                       AS total_waste_qty,
                 SUM(d.receipt_count)                   AS receipt_count,
                 SUM(d.adjustment_count)                AS adjustment_count,
                 COUNT(*)                               AS days_with_movement,
                 COUNT(*) FILTER (WHERE d.qty_used > 0) AS days_with_usage,
                 (ARRAY_AGG(d.closing_qty ORDER BY d.stock_date DESC))[1]
                                                        AS latest_closing_qty
            FROM ingredient_stock_day d
            JOIN ingredient i ON i.id = d.ingredient_id
           WHERE d.business_id = :businessId
             AND d.stock_date >= :from
             AND d.stock_date <= :to
           GROUP BY d.ingredient_id, i.name, i.unit
           ORDER BY i.name
          """,
      nativeQuery = true)
  List<IngredientStockSummaryView> findStockSummary(
      @Param("businessId") UUID businessId,
      @Param("from") LocalDate from,
      @Param("to") LocalDate to);
}
