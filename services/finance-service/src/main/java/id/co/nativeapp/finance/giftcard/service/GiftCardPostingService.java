package id.co.nativeapp.finance.giftcard.service;

import id.co.nativeapp.finance.giftcard.messaging.GiftCardSoldEvent;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates posting a consumed {@code GiftCardSold} to the GL — the consumer-side core of ADR
 * 0027 Phase 4 (loyalty + gift cards).
 *
 * <p><strong>The inbound tenant comes from the event, not a request.</strong> finance is purely
 * downstream; there is no JWT on the consumer path. So this service binds the tenant scope from the
 * event's {@code company_id} via {@link TenantContext#callAs} with a fixed {@code
 * "finance-consumer"} actor (which lands in the Auditable {@code created_by}), then delegates to
 * the proxied {@link GiftCardPostingWriter} so the {@code @Transactional} advice and the auto-RLS
 * aspect engage under that tenant. Follows the exact same pattern as {@code RevenuePostingService}
 * / {@code ReversalPostingService}.
 */
@Service
public class GiftCardPostingService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "finance-consumer";

  private final GiftCardPostingWriter writer;

  public GiftCardPostingService(GiftCardPostingWriter writer) {
    this.writer = writer;
  }

  /**
   * Handles one decoded {@code GiftCardSold}, idempotently. Binds the event's tenant for the
   * duration of the posting transaction so RLS applies.
   *
   * @return {@code true} if this delivery posted (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer.
   */
  public boolean handle(GiftCardSoldEvent event) {
    try {
      return TenantContext.callAs(event.companyId(), CONSUMER_ACTOR, () -> writer.post(event));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.post throws only unchecked, so this
      // is unreachable in practice — rewrap defensively.
      throw new IllegalStateException("Failed to handle GiftCardSold", e);
    }
  }
}
