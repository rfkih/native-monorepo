package id.co.nativeapp.loyalty.ingest.service;

import id.co.nativeapp.loyalty.ingest.dto.SaleRecordedFact;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates applying a consumed {@code SaleRecorded} event to the loyalty ledger.
 *
 * <p><strong>The inbound tenant comes from the event, not a request.</strong> There is no JWT on
 * the consumer path, so this service binds the tenant scope from the event's {@code company_id} via
 * {@link TenantContext#callAs} with a fixed {@code "loyalty-sale-ingest"} actor (which lands in the
 * Auditable {@code created_by}), then delegates to the proxied {@link SaleIngestWriter} so the
 * {@code @Transactional} advice and the auto-RLS aspect engage under that tenant (the {@code
 * StaffProjectionService} pattern).
 */
@Service
public class SaleIngestService {

  /** The audit actor for system-driven consumer writes (stamped into {@code created_by}). */
  public static final String CONSUMER_ACTOR = "loyalty-sale-ingest";

  private final SaleIngestWriter writer;

  public SaleIngestService(SaleIngestWriter writer) {
    this.writer = writer;
  }

  /**
   * Applies one decoded {@code SaleRecorded} fact, idempotently, in the event's tenant scope.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if it was a
   *     re-delivery skipped by the idempotent consumer
   */
  public boolean apply(SaleRecordedFact fact) {
    try {
      return TenantContext.callAs(fact.companyId(), CONSUMER_ACTOR, () -> writer.apply(fact));
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      // callAs declares checked Exception; writer.apply throws only unchecked.
      throw new IllegalStateException("Failed to apply SaleRecorded fact", e);
    }
  }
}
