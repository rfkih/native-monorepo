package id.co.nativeapp.restaurant.inventory.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.inventory.domain.GoodsReceipt;
import id.co.nativeapp.restaurant.inventory.domain.GoodsReceiptIdempotencyKeyConflictException;
import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.messaging.StockReceivedSchema;
import id.co.nativeapp.restaurant.inventory.projection.GoodsReceiptReplayView;
import id.co.nativeapp.restaurant.inventory.repository.GoodsReceiptRepository;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;

/**
 * The PRICED-receive core (ADR 0067 Phase B / ADR 0072), extracted from {@link
 * IngredientWriter#addStock} so BOTH entry points share ONE implementation:
 *
 * <ul>
 *   <li>the HTTP path ({@code IngredientWriter.addStock}, which keeps its {@code OutletAccessGuard}
 *       + {@code REQUIRES_NEW} semantics), and
 *   <li>the {@code InventoryPurchaseRecorded} consumer ({@code InventoryPurchaseApplyWriter}),
 *       which CANNOT reuse {@code addStock}: the guard reads roles off the HTTP {@code X-Roles}
 *       header, which is empty on a Kafka thread — authorization for the consumer path already
 *       happened at the finance input (owner/accountant gateway gate).
 * </ul>
 *
 * <p>Every method JOINS the caller's transaction (no {@code @Transactional} here — the caller owns
 * the unit of work and the RLS binding): the moving-average receive, the {@code goods_receipt}
 * idempotency anchor and the {@code StockReceived} outbox event commit together (rule 3).
 */
@Component
public class PricedReceiveWriter {

  /** What the idempotency-key probe found. */
  public enum ReplayOutcome {
    /** The key is unused — apply the receive. */
    NEW,
    /** The key recorded this exact payload before — skip silently (already applied). */
    REPLAY
  }

  private final IngredientRepository ingredientRepository;
  private final GoodsReceiptRepository goodsReceiptRepository;
  private final OutboxWriter outboxWriter;

  public PricedReceiveWriter(
      IngredientRepository ingredientRepository,
      GoodsReceiptRepository goodsReceiptRepository,
      OutboxWriter outboxWriter) {
    this.ingredientRepository = ingredientRepository;
    this.goodsReceiptRepository = goodsReceiptRepository;
    this.outboxWriter = outboxWriter;
  }

  /**
   * Probes the {@code goods_receipt} idempotency anchor for {@code key} within the caller's
   * transaction. {@code NEW} when unused; {@code REPLAY} when the SAME payload was already
   * recorded.
   *
   * @throws GoodsReceiptIdempotencyKeyConflictException when the key recorded a DIFFERENT payload
   */
  public ReplayOutcome checkReplay(
      @Nullable String key, UUID ingredientId, int qty, long valueMinor, String currency) {
    if (key == null) {
      return ReplayOutcome.NEW;
    }
    Optional<GoodsReceiptReplayView> replay =
        goodsReceiptRepository.findReplayByIdempotencyKey(key);
    if (replay.isEmpty()) {
      return ReplayOutcome.NEW;
    }
    GoodsReceiptReplayView existing = replay.get();
    boolean samePayload =
        existing.getIngredientId().equals(ingredientId)
            && existing.getQty() == qty
            && existing.getValueMinor() == valueMinor
            && existing.getCurrency().strip().equals(currency);
    if (!samePayload) {
      throw new GoodsReceiptIdempotencyKeyConflictException(
          "Idempotency-Key was already used to record a different goods receipt");
    }
    return ReplayOutcome.REPLAY;
  }

  /**
   * Applies the priced receive in the caller's transaction: moving-average value-add ({@link
   * Ingredient#receive}), the {@link GoodsReceipt} anchor row, and the {@code StockReceived} outbox
   * event. The caller has already resolved {@link ReplayOutcome#NEW} for {@code key}.
   *
   * <p>{@code receivedAt} is the caller's authoritative receive instant: the HTTP path passes now;
   * the {@code InventoryPurchaseRecorded} consumer passes the event's {@code occurred_at} so the
   * receipt (and its {@code StockReceived}) lands in the SAME period the money posted in — without
   * it, a back-dated purchase straddling a perpetual activation would double-count into both COGS
   * and Inventory (review W6).
   *
   * @return the saved ingredient (post-receive state)
   * @throws IllegalArgumentException from {@link Ingredient#receive} (non-positive qty, negative
   *     value, invalid or mismatched currency)
   */
  public Ingredient apply(
      Ingredient ingredient,
      int qty,
      long valueMinor,
      String currency,
      @Nullable String key,
      String companyId,
      Instant receivedAt) {
    ingredient.receive(qty, valueMinor, currency);
    Ingredient saved = ingredientRepository.saveAndFlush(ingredient);

    GoodsReceipt receipt =
        GoodsReceipt.of(
            saved.getId(), saved.getBusinessId(), qty, valueMinor, currency, receivedAt, key);
    receipt.setCompanyId(companyId);
    GoodsReceipt savedReceipt = goodsReceiptRepository.saveAndFlush(receipt);

    GenericRecord event =
        StockReceivedSchema.toRecord(
            savedReceipt.getId(),
            companyId,
            saved.getBusinessId(),
            saved.getId(),
            qty,
            valueMinor,
            currency,
            receivedAt);
    outboxWriter.write(
        StockReceivedSchema.AGGREGATE_TYPE,
        savedReceipt.getId().toString(),
        StockReceivedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        UUID.fromString(companyId),
        receivedAt);
    return saved;
  }
}
