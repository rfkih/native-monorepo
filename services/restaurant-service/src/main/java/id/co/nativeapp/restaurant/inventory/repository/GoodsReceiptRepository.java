package id.co.nativeapp.restaurant.inventory.repository;

import id.co.nativeapp.restaurant.inventory.domain.GoodsReceipt;
import id.co.nativeapp.restaurant.inventory.projection.GoodsReceiptReplayView;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Write-path repository for {@link GoodsReceipt} rows (ADR 0067 Phase B, §1). A thin data port: no
 * business logic, no manual {@code WHERE company_id} (RLS scopes every query, rule 5). Injected
 * only by {@code IngredientWriter}.
 */
public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {

  /**
   * Idempotency-replay probe (ADR 0067 Phase D, D1): the receipt previously recorded under this
   * caller-supplied key, if any — {@code (company_id, idempotency_key)} is partial-unique (V43), so
   * RLS + this key together identify at most one row. A read path returns a narrow projection, not
   * the full entity (CODE-STRUCTURE §3.3); {@code IngredientWriter#addStock} uses it to decide
   * between an idempotent replay (same payload — add nothing again) and a genuine conflict (same
   * key, different payload — 409).
   */
  @Query(
      value =
          "SELECT ingredient_id AS ingredient_id, qty AS qty, value_minor AS value_minor,"
              + " currency AS currency"
              + " FROM goods_receipt WHERE idempotency_key = :key",
      nativeQuery = true)
  Optional<GoodsReceiptReplayView> findReplayByIdempotencyKey(@Param("key") String key);
}
