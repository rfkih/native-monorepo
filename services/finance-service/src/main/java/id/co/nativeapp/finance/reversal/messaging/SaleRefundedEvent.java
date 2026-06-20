package id.co.nativeapp.finance.reversal.messaging;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The decoded form of a {@code SaleRefunded} event consumed by finance-service (ADR 0006, slice 4).
 *
 * <p>Carries the refund event's unique id (the reversal idempotency key), the original {@code
 * sale_id} (the source event being partially reversed), the payment id, the refunded amount as a
 * {@link Money} (integer minor units + ISO-4217; never a float), the cumulative total refunded, and
 * the optional {@code tender_type} string so the GL clearing account is reversed on the correct
 * rail (same routing as {@code SaleRecorded}).
 *
 * @param refundId the refund event's unique id — the {@code ProcessedEventStore} dedup key
 * @param companyId the owning tenant (UUID as string)
 * @param businessId the originating business unit
 * @param saleId the original sale being partially reversed
 * @param paymentId the payment aggregate being refunded
 * @param refundAmount the refunded amount for this event (integer minor units + ISO-4217)
 * @param totalRefundedMinor the cumulative total refunded (including this refund) in minor units
 * @param occurredAt when the refund occurred (UTC)
 * @param tenderType the original tender ({@code "CASH"}, {@code "QRIS"}, {@code "CARD"}, or {@code
 *     null} for legacy)
 */
public record SaleRefundedEvent(
    UUID refundId,
    String companyId,
    UUID businessId,
    UUID saleId,
    UUID paymentId,
    Money refundAmount,
    long totalRefundedMinor,
    Instant occurredAt,
    String tenderType) {}
