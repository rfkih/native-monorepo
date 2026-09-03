package id.co.nativeapp.restaurant.inventory.service;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.restaurant.inventory.domain.GoodsReceiptIdempotencyKeyConflictException;
import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.messaging.InventoryPurchaseRecordedEvent;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The idempotent {@code @Transactional} unit of work for a consumed {@code
 * InventoryPurchaseRecorded} (ADR 0072): one {@link ProcessedEventStore#processOnce} claim, then
 * each line applied INDEPENDENTLY as a priced goods receipt through {@link PricedReceiveWriter}
 * with {@code goods_receipt.idempotency_key = line_id} — so neither a redelivered event nor a
 * duplicated line can double-add stock or double-count the moving-average value.
 *
 * <p><strong>Park, don't drop — and don't fail the good lines.</strong> A business anomaly on one
 * line (unknown/deactivated ingredient, receipt currency ≠ the ingredient's cost currency, qty
 * beyond int range, a line_id already recorded with a DIFFERENT payload) is recorded to the {@code
 * error_log} inbox IN THIS SAME TRANSACTION and the loop continues with the remaining lines — the
 * money is already safely posted in finance; stock for the parked line is corrected operationally
 * (the {@code PaymentChargeExpiredWriter} idiom). This method never throws for business anomalies;
 * redelivery cannot fix them, so throwing would only spin the partition.
 *
 * <p><strong>No {@code OutletAccessGuard} here, deliberately.</strong> The guard authorizes HTTP
 * actors from the {@code X-Roles} header, which does not exist on a Kafka thread; authorization for
 * this path already happened at the finance input (the owner/accountant gateway gate on the
 * expense/bill submit). Scoped strictly to this consumer (ADR 0072 §risks).
 */
@Component
public class InventoryPurchaseApplyWriter {

  private static final Logger log = LoggerFactory.getLogger(InventoryPurchaseApplyWriter.class);

  /** Error-inbox sources (one per anomaly class, greppable in ops). */
  static final String UNKNOWN_INGREDIENT_SOURCE =
      "restaurant.inventory-purchase.unknown-ingredient";

  static final String APPLY_FAILED_SOURCE = "restaurant.inventory-purchase.apply-failed";
  static final String KEY_CONFLICT_SOURCE = "restaurant.inventory-purchase.key-conflict";

  private final IngredientRepository ingredientRepository;
  private final PricedReceiveWriter pricedReceiveWriter;
  private final ProcessedEventStore processedEvents;
  private final ErrorInboxWriter errorInboxWriter;

  public InventoryPurchaseApplyWriter(
      IngredientRepository ingredientRepository,
      PricedReceiveWriter pricedReceiveWriter,
      ProcessedEventStore processedEvents,
      ErrorInboxWriter errorInboxWriter) {
    this.ingredientRepository = ingredientRepository;
    this.pricedReceiveWriter = pricedReceiveWriter;
    this.processedEvents = processedEvents;
    this.errorInboxWriter = errorInboxWriter;
  }

  /**
   * Applies the event exactly once (event-id claim + per-line receipts + parks, one commit).
   *
   * @return {@code true} on first delivery, {@code false} on a skipped re-delivery
   */
  @Transactional
  public boolean apply(InventoryPurchaseRecordedEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> applyLines(event));
  }

  private void applyLines(InventoryPurchaseRecordedEvent event) {
    String companyId = TenantContext.require().companyId();
    for (InventoryPurchaseRecordedEvent.Line line : event.lines()) {
      applyLine(event, line, companyId);
    }
  }

  /** Applies ONE line; parks anomalies and returns normally so the remaining lines still apply. */
  private void applyLine(
      InventoryPurchaseRecordedEvent event,
      InventoryPurchaseRecordedEvent.Line line,
      String companyId) {
    String lineKey = line.lineId().toString();

    int qty;
    try {
      qty = Math.toIntExact(line.qtyBase());
    } catch (ArithmeticException overflow) {
      park(
          APPLY_FAILED_SOURCE,
          "line "
              + lineKey
              + " of purchase "
              + event.purchaseId()
              + ": qty_base "
              + line.qtyBase()
              + " exceeds the int stock range");
      return;
    }

    // RLS-scoped load: an unknown id and another tenant's ingredient are indistinguishable — both
    // park (the finance input cannot validate ingredient ids across the service boundary, rule 1).
    Optional<Ingredient> ingredient = ingredientRepository.findById(line.ingredientId());
    if (ingredient.isEmpty()) {
      park(
          UNKNOWN_INGREDIENT_SOURCE,
          "line "
              + lineKey
              + " of purchase "
              + event.purchaseId()
              + ": ingredient "
              + line.ingredientId()
              + " not found in this tenant — receive it manually once resolved");
      return;
    }

    try {
      if (pricedReceiveWriter.checkReplay(
              lineKey, line.ingredientId(), qty, line.valueMinor(), event.currency())
          == PricedReceiveWriter.ReplayOutcome.REPLAY) {
        log.debug(
            "InventoryPurchaseRecorded line {} already received (purchase {}) — replay no-op",
            lineKey,
            event.purchaseId());
        return;
      }
      pricedReceiveWriter.apply(
          ingredient.get(), qty, line.valueMinor(), event.currency(), lineKey, companyId);
    } catch (GoodsReceiptIdempotencyKeyConflictException conflict) {
      park(
          KEY_CONFLICT_SOURCE,
          "line "
              + lineKey
              + " of purchase "
              + event.purchaseId()
              + ": line_id already recorded a DIFFERENT goods receipt — investigate before"
              + " adjusting stock");
    } catch (RuntimeException failure) {
      // e.g. currency ≠ the ingredient's cost currency, or another receive invariant.
      park(
          APPLY_FAILED_SOURCE,
          "line " + lineKey + " of purchase " + event.purchaseId() + ": " + failure.getMessage());
    }
  }

  /**
   * Records {@code message} to the error inbox IN THE CALLER'S transaction so the park and the
   * {@code processed_event} claim commit atomically. Never throws — money is already posted in
   * finance; the stock side is corrected operationally.
   */
  private void park(String source, String message) {
    String companyId = TenantContext.require().companyId();
    String traceId = MDC.get("traceId");
    log.warn("{}: {}", source, message);
    errorInboxWriter.recordInCurrentTx(
        new IllegalStateException(message), source, companyId, traceId);
  }
}
