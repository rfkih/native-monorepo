package id.co.nativeapp.loyalty.ingest.service;

import id.co.nativeapp.loyalty.ingest.dto.SaleReversalFact;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates applying a consumed {@code SaleVoided}/{@code SaleRefunded} reversal. Binds the
 * tenant from the event's {@code company_id} (no JWT on the consumer path) and delegates to the
 * proxied {@link SaleReversalWriter} — the {@code SaleIngestService} pattern.
 */
@Service
public class SaleReversalService {

  public static final String CONSUMER_ACTOR = "loyalty-reversal-ingest";

  private final SaleReversalWriter writer;

  public SaleReversalService(SaleReversalWriter writer) {
    this.writer = writer;
  }

  /**
   * Applies one decoded reversal fact, idempotently, in the event's tenant scope.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if a re-delivery
   */
  public boolean apply(SaleReversalFact fact) {
    try {
      return TenantContext.callAs(fact.companyId(), CONSUMER_ACTOR, () -> writer.apply(fact));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Failed to apply sale-reversal fact", e);
    }
  }
}
