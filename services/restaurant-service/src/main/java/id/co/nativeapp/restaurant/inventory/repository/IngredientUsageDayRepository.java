package id.co.nativeapp.restaurant.inventory.repository;

import id.co.nativeapp.restaurant.inventory.domain.IngredientUsageDay;
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
 * Spring Data repository for {@link IngredientUsageDay} (V42 — "stock terpakai" per day).
 *
 * <p>No manual {@code WHERE company_id} — {@link RlsAutoApplyAspect} sets the tenant GUC and the
 * V42 RLS policy applies the tenant scope (rule 5); the UPSERT carries an explicit {@code
 * company_id} because the FORCE-RLS {@code WITH CHECK} rejects an insert whose company does not
 * match the session tenant (the {@code IngredientWriter#create} note).
 */
public interface IngredientUsageDayRepository extends JpaRepository<IngredientUsageDay, UUID> {

  /**
   * Atomically adds {@code qty} to the ingredient's usage for {@code usageDate} — called by the
   * per-sale depletion in the SAME transaction as the sale, so a rolled-back sale never leaves
   * usage behind. {@code INSERT … SELECT FROM ingredient} sources {@code business_id} from the
   * ingredient row itself (no caller plumbing; 0 rows when the ingredient was concurrently
   * hard-deleted — nothing to record, matching the depletion's own no-op). {@code ON CONFLICT} on
   * the (ingredient, date) unique makes concurrent sales additive, never lost — the same guarantee
   * {@code depleteStockFloorZero}'s single-row UPDATE gives the stock figure.
   */
  @Modifying
  @Query(
      value =
          """
          INSERT INTO ingredient_usage_day
                 (id, business_id, ingredient_id, usage_date, qty_used,
                  created_at, created_by, updated_at, updated_by, version, company_id)
          SELECT gen_random_uuid(), i.business_id, i.id, :usageDate, :qty,
                 now(), :actor, now(), :actor, 0, :companyId
            FROM ingredient i
           WHERE i.id = :ingredientId
          ON CONFLICT (ingredient_id, usage_date)
          DO UPDATE SET qty_used   = ingredient_usage_day.qty_used + EXCLUDED.qty_used,
                        updated_at = now(),
                        updated_by = EXCLUDED.updated_by,
                        version    = ingredient_usage_day.version + 1
          """,
      nativeQuery = true)
  int addUsage(
      @Param("ingredientId") UUID ingredientId,
      @Param("usageDate") LocalDate usageDate,
      @Param("qty") long qty,
      @Param("actor") String actor,
      @Param("companyId") String companyId);

  /** An outlet's per-ingredient usage for one day — the opname sheet + riwayat detail read. */
  @Query(
      value =
          """
          SELECT u.ingredient_id AS ingredient_id,
                 u.qty_used      AS qty_used
            FROM ingredient_usage_day u
           WHERE u.business_id = :businessId
             AND u.usage_date  = :usageDate
          """,
      nativeQuery = true)
  List<IngredientUsageView> findByBusinessIdAndDate(
      @Param("businessId") UUID businessId, @Param("usageDate") LocalDate usageDate);
}
