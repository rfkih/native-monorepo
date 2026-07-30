package id.co.nativeapp.loyalty.ingest.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The decoded shape of a consumed {@code SaleVoided} OR {@code SaleRefunded} event, unified: both
 * trigger the IDENTICAL full-reversal-of-the-sale's-loyalty-facts behaviour (see {@code
 * ingest.service.SaleReversalWriter} class javadoc — loyalty stored only the SALE-level facts, not
 * a per-line/per-refund breakdown, so even a PARTIAL refund reverses the FULL loyalty impact of the
 * original sale, mirroring finance's posture per the task).
 */
public record SaleReversalFact(
    UUID eventId, Kind kind, UUID saleId, String companyId, UUID businessId, Instant occurredAt) {

  /** Which event caused this reversal — carried for traceability/logging only. */
  public enum Kind {
    VOIDED,
    REFUNDED
  }
}
