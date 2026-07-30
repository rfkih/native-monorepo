package id.co.nativeapp.carwash.giftcard.repository;

import id.co.nativeapp.carwash.giftcard.domain.GiftCardSale;
import id.co.nativeapp.carwash.giftcard.projection.GiftCardSaleView;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link GiftCardSale}. Carries no manual {@code WHERE company_id}:
 * every method runs inside a {@code @Transactional}, so {@link RlsAutoApplyAspect} sets {@code
 * app.current_tenant} automatically (rule 5). The read path is a native query + projection (never
 * {@code SELECT *}); the write path uses the inherited {@code save}/{@code saveAndFlush}.
 */
public interface GiftCardSaleRepository extends JpaRepository<GiftCardSale, UUID> {

  /**
   * Finds an existing gift-card sale by its client idempotency key within the bound tenant
   * (RLS-scoped). Used for the idempotency fast-path short-circuit and the conflict-recovery
   * re-read (mirrors {@code SaleRepository#findViewByIdempotencyKey}).
   */
  @Query(
      value =
          """
          SELECT s.id                  AS id,
                 s.gift_card_id        AS gift_card_id,
                 s.business_id         AS business_id,
                 s.amount_minor        AS amount_minor,
                 s.currency            AS currency,
                 s.tender_type         AS tender_type,
                 s.occurred_at         AS occurred_at,
                 s.idempotency_key     AS idempotency_key
            FROM gift_card_sale s
           WHERE s.idempotency_key = :idempotencyKey
          """,
      nativeQuery = true)
  Optional<GiftCardSaleView> findViewByIdempotencyKey(
      @Param("idempotencyKey") String idempotencyKey);
}
