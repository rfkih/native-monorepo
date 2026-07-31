package id.co.nativeapp.restaurant.order.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Summary response for one entry in the parked-orders tray.
 *
 * <p>Mapping from the read-path projection lives in the service layer ({@code OrderService}) so
 * that the ArchUnit {@code Projection accessed only by Service + Repository} rule is respected —
 * the {@code dto} layer must not depend on {@code projection}.
 *
 * @param orderId the parked order id
 * @param businessId the owning business unit
 * @param totalMinor the grand total in minor currency units
 * @param currency ISO-4217 currency code
 * @param tableLabel the label of the associated table, or {@code null} for TAKEAWAY/DELIVERY
 * @param lineCount the number of order lines in the cart
 * @param occurredAt when the order was parked
 * @param orderType DINE_IN / TAKEAWAY / DELIVERY
 * @param source Phase 6 (ADR 0029): POS (default) or SELF_ORDER — badges an anonymous QR-scanned
 *     park so the cashier's ParkedTray can flag it for confirmation
 */
public record ParkedOrderSummary(
    UUID orderId,
    UUID businessId,
    long totalMinor,
    String currency,
    String tableLabel,
    int lineCount,
    Instant occurredAt,
    String orderType,
    String source) {}
