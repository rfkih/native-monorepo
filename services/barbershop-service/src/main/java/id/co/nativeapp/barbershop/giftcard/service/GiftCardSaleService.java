package id.co.nativeapp.barbershop.giftcard.service;

import id.co.nativeapp.barbershop.giftcard.dto.SellGiftCardRequest;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Sells gift cards and emits {@code GiftCardSold} (ADR 0027, Phase 4) — the {@code SaleService}
 * concurrency-safe-idempotency contract applied to a liability event.
 *
 * <p>Not itself {@code @Transactional} — the create runs in its own transaction ({@link
 * GiftCardSaleWriter#sell}) so a concurrent-collision conflict on the unique {@code (company_id,
 * idempotency_key)} constraint can be recovered by a SEPARATE-transaction re-read (a PostgreSQL
 * transaction is poisoned once a constraint fires).
 */
@Service
public class GiftCardSaleService {

  private final GiftCardSaleWriter writer;

  public GiftCardSaleService(GiftCardSaleWriter writer) {
    this.writer = writer;
  }

  /**
   * Sells a gift card idempotently and emits exactly one {@code GiftCardSold} on first sale. A
   * retry — sequential or concurrent — with the same {@code idempotencyKey} resolves to the
   * existing sale ({@code created=false}) and emits no second event.
   */
  public GiftCardSaleResult sell(SellGiftCardRequest request) {
    TenantContext.require();
    try {
      return writer.sell(request);
    } catch (DataIntegrityViolationException conflict) {
      return writer
          .findExistingByKey(request.idempotencyKey())
          .map(response -> new GiftCardSaleResult(response, false))
          .orElseThrow(() -> conflict);
    }
  }
}
