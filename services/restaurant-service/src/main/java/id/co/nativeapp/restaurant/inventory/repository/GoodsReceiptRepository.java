package id.co.nativeapp.restaurant.inventory.repository;

import id.co.nativeapp.restaurant.inventory.domain.GoodsReceipt;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Write-path repository for {@link GoodsReceipt} rows (ADR 0067 Phase B, §1) — one row per PRICED
 * ingredient receive. A thin data port: no business logic, no manual {@code WHERE company_id} (RLS
 * scopes every query, rule 5). Injected only by {@code IngredientWriter}.
 */
public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, UUID> {}
