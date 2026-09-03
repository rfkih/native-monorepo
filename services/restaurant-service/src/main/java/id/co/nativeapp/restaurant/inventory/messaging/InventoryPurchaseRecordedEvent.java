package id.co.nativeapp.restaurant.inventory.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A decoded {@code InventoryPurchaseRecorded} event (ADR 0072): finance recorded the money for a
 * purchase carrying ingredient lines; this service applies each line as a priced goods receipt.
 *
 * @param eventId the durable event UUID (the {@code id} header) — the consumer idempotency key
 * @param purchaseId the finance aggregate (company_expense or bill) id
 * @param source {@code EXPENSE} | {@code BILL}; unknown future values are skip-and-log
 * @param companyId the owning tenant, bound via {@code TenantContext.callAs}
 * @param currency ISO-4217 code of every line's {@code valueMinor}
 * @param occurredAt the money-posting instant
 * @param lines the ingredient lines, applied independently and idempotently
 */
public record InventoryPurchaseRecordedEvent(
    UUID eventId,
    UUID purchaseId,
    String source,
    String companyId,
    String currency,
    Instant occurredAt,
    List<Line> lines) {

  /**
   * One ingredient line.
   *
   * @param lineId the purchase line id — stored as {@code goods_receipt.idempotency_key}
   * @param ingredientId the ingredient to receive into
   * @param qtyBase quantity in the ingredient's BASE unit
   * @param valueMinor the exact amount paid for this line (net of VAT), minor units
   */
  public record Line(UUID lineId, UUID ingredientId, long qtyBase, long valueMinor) {}
}
