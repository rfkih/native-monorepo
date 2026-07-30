package id.co.nativeapp.carwash.loyaltyref.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Decoded view of a {@code LoyaltyBalanceChanged} event (ADR 0027) — the projection the consumer
 * set-if-newer upserts into {@code member_balance_ref}.
 *
 * @param eventId the durable event UUID taken from the {@code id} Kafka header; the idempotency key
 * @param memberId the loyalty member aggregate id (also the Kafka partition key)
 * @param companyId the owning tenant; used to bind {@link id.co.nativeapp.tenant.TenantContext}
 * @param pointsBalance the member's resulting points balance — an ABSOLUTE set-to-value, not a
 *     delta. Not money (a ledger unit)
 * @param balanceSeq monotonically increasing per-member sequence; set-if-newer guard
 * @param occurredAt when the balance change occurred
 */
public record LoyaltyBalanceChangedEvent(
    UUID eventId, UUID memberId, String companyId, long pointsBalance, long balanceSeq, Instant occurredAt) {}
