package id.co.nativeapp.loyalty.ingest.service;

import id.co.nativeapp.loyalty.ingest.dto.GiftCardSoldFact;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates applying a consumed {@code GiftCardSold} event. Binds the tenant from the event's
 * {@code company_id} (no JWT on the consumer path) and delegates to the proxied {@link
 * GiftCardSoldWriter} — the {@code SaleIngestService} pattern.
 */
@Service
public class GiftCardSoldService {

  public static final String CONSUMER_ACTOR = "loyalty-giftcard-ingest";

  private final GiftCardSoldWriter writer;

  public GiftCardSoldService(GiftCardSoldWriter writer) {
    this.writer = writer;
  }

  /**
   * Applies one decoded {@code GiftCardSold} fact, idempotently, in the event's tenant scope.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if a re-delivery
   */
  public boolean apply(GiftCardSoldFact fact) {
    try {
      return TenantContext.callAs(fact.companyId(), CONSUMER_ACTOR, () -> writer.apply(fact));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to apply GiftCardSold fact", e);
    }
  }
}
