package id.co.nativeapp.finance.reversal.messaging;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The decoded form of a {@code SaleVoided} event consumed by finance-service (ADR 0006, slice 4).
 *
 * <p>Carries the void event's unique id (the reversal idempotency key), the original {@code
 * sale_id} (the source event being reversed), the payment id, the voided amount as a {@link Money}
 * (integer minor units + ISO-4217; never a float), and the optional {@code tender_type} string so
 * the GL clearing account is reversed on the correct rail (same routing as {@code SaleRecorded}).
 *
 * @param voidId the void event's unique id — the {@code ProcessedEventStore} dedup key
 * @param companyId the owning tenant (UUID as string)
 * @param businessId the originating business unit
 * @param saleId the original sale being reversed
 * @param paymentId the payment aggregate being voided
 * @param amount the voided amount (integer minor units + ISO-4217; never a float)
 * @param occurredAt when the void occurred (UTC)
 * @param tenderType the original tender ({@code "CASH"}, {@code "QRIS"}, {@code "CARD"}, or {@code
 *     null} for legacy — finance routes the GL clearing account by this value)
 */
public record SaleVoidedEvent(
    UUID voidId,
    String companyId,
    UUID businessId,
    UUID saleId,
    UUID paymentId,
    Money amount,
    Instant occurredAt,
    String tenderType) {}
