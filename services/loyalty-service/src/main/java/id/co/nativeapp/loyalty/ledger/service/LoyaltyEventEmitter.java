package id.co.nativeapp.loyalty.ledger.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.loyalty.ledger.messaging.GiftCardStateChangedSchema;
import id.co.nativeapp.loyalty.ledger.messaging.LoyaltyBalanceChangedSchema;
import id.co.nativeapp.loyalty.ledger.messaging.LoyaltyRedemptionFlaggedSchema;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;

/**
 * Writes the {@code LoyaltyBalanceChanged} / {@code GiftCardStateChanged} / {@code
 * LoyaltyRedemptionFlagged} outbox rows (ADR 0027) — shared by every feature that mutates a
 * member's points balance or a gift card's state ({@code member.service.MemberEnrollWriter} for
 * {@code ENROLLED}, and every {@code ingest.service.*Writer} for {@code EARNED}/{@code
 * REDEEMED}/{@code REVERSED} and the overdraft flag) so the emission logic cannot diverge across
 * call sites — mirrors barbershop-service's {@code TicketEventEmitter}.
 *
 * <p>Not itself {@code @Transactional} — always invoked from inside the caller's own {@code
 * @Transactional} unit of work (which already has the tenant GUC set by the RLS auto-apply aspect
 * on the enclosing transactional bean), so the balance/state-changed row commits atomically with
 * the ledger mutation (rule 3).
 */
@Component
public class LoyaltyEventEmitter {

  private final OutboxWriter outboxWriter;

  public LoyaltyEventEmitter(OutboxWriter outboxWriter) {
    this.outboxWriter = outboxWriter;
  }

  /** Emits {@code LoyaltyBalanceChanged} with the member's resulting ABSOLUTE balance + sequence. */
  public void emitBalanceChanged(
      UUID memberId,
      String companyId,
      long pointsBalance,
      long balanceSeq,
      String reason,
      Instant occurredAt) {
    GenericRecord event =
        LoyaltyBalanceChangedSchema.toRecord(
            memberId, companyId, pointsBalance, balanceSeq, reason, occurredAt);
    outboxWriter.write(
        LoyaltyBalanceChangedSchema.AGGREGATE_TYPE,
        memberId.toString(),
        LoyaltyBalanceChangedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        UUID.fromString(companyId),
        occurredAt);
  }

  /** Emits {@code GiftCardStateChanged} with the card's resulting ABSOLUTE state/balance + sequence. */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public void emitGiftCardStateChanged(
      UUID giftCardId,
      String companyId,
      String state,
      long balanceMinor,
      String currency,
      long balanceSeq,
      Instant occurredAt) {
    GenericRecord event =
        GiftCardStateChangedSchema.toRecord(
            giftCardId, companyId, state, balanceMinor, currency, balanceSeq, occurredAt);
    outboxWriter.write(
        GiftCardStateChangedSchema.AGGREGATE_TYPE,
        giftCardId.toString(),
        GiftCardStateChangedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        UUID.fromString(companyId),
        occurredAt);
  }

  /** Emits a points-overdraft {@code LoyaltyRedemptionFlagged}. */
  public void emitPointsOverdraftFlagged(
      String companyId, UUID businessId, UUID saleId, UUID memberId, long shortfallPoints, Instant occurredAt) {
    UUID flagId = UUID.randomUUID();
    GenericRecord event =
        LoyaltyRedemptionFlaggedSchema.forPoints(
            flagId, companyId, businessId, saleId, memberId, shortfallPoints, occurredAt);
    outboxWriter.write(
        LoyaltyRedemptionFlaggedSchema.AGGREGATE_TYPE,
        flagId.toString(),
        LoyaltyRedemptionFlaggedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        UUID.fromString(companyId),
        occurredAt);
  }

  /** Emits a gift-card-overdraft {@code LoyaltyRedemptionFlagged}. */
  public void emitGiftCardOverdraftFlagged(
      String companyId,
      UUID businessId,
      UUID saleId,
      UUID giftCardId,
      long shortfallMinor,
      Instant occurredAt) {
    UUID flagId = UUID.randomUUID();
    GenericRecord event =
        LoyaltyRedemptionFlaggedSchema.forGiftCard(
            flagId, companyId, businessId, saleId, giftCardId, shortfallMinor, occurredAt);
    outboxWriter.write(
        LoyaltyRedemptionFlaggedSchema.AGGREGATE_TYPE,
        flagId.toString(),
        LoyaltyRedemptionFlaggedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        UUID.fromString(companyId),
        occurredAt);
  }
}
