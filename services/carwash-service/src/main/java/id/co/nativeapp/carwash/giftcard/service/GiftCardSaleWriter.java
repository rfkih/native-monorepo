package id.co.nativeapp.carwash.giftcard.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.carwash.giftcard.domain.GiftCardCodeGenerator;
import id.co.nativeapp.carwash.giftcard.domain.GiftCardSale;
import id.co.nativeapp.carwash.giftcard.dto.GiftCardSaleResponse;
import id.co.nativeapp.carwash.giftcard.dto.SellGiftCardRequest;
import id.co.nativeapp.carwash.giftcard.messaging.GiftCardSoldSchema;
import id.co.nativeapp.carwash.giftcard.projection.GiftCardSaleView;
import id.co.nativeapp.carwash.giftcard.repository.GiftCardSaleRepository;
import id.co.nativeapp.carwash.outletref.service.OutletAccessGuard;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write unit of work for selling (minting) a gift card at the till
 * (ADR 0027, Phase 4) — the {@code SaleWriter} idempotency/orchestration contract applied to a
 * liability event instead of a revenue one.
 *
 * <p>A distinct bean (not private methods on {@link GiftCardSaleService}) so each transactional
 * method is invoked through the Spring proxy — a self-invocation would bypass the {@code
 * @Transactional} advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC.
 *
 * <p><strong>One atomic unit of work.</strong> {@link #sell} runs in {@code REQUIRES_NEW} and: (1)
 * the idempotency fast path returns the existing sale for a re-delivered {@code idempotencyKey}
 * WITHOUT minting a second card or emitting a second event; (2) {@link OutletAccessGuard#enforce}
 * — a till operation, so no owner/manager role is required (unlike a manual discount); (3) mints a
 * fresh card UUID, derives its display code, persists the {@code gift_card_sale} row; (4) writes
 * {@code GiftCardSold} to the outbox in the SAME transaction (rule 3).
 */
@Component
public class GiftCardSaleWriter {

  private final GiftCardSaleRepository repository;
  private final OutboxWriter outboxWriter;
  private final OutletAccessGuard outletAccessGuard;

  public GiftCardSaleWriter(
      GiftCardSaleRepository repository,
      OutboxWriter outboxWriter,
      OutletAccessGuard outletAccessGuard) {
    this.repository = repository;
    this.outboxWriter = outboxWriter;
    this.outletAccessGuard = outletAccessGuard;
  }

  /**
   * Sells (mints) a gift card idempotently.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer already
   *     inserted the {@code (company_id, idempotency_key)} row
   * @throws id.co.nativeapp.carwash.outletref.domain.OutletNotAssignedException if the cashier
   *     is not assigned to {@code request.businessId()} (403)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public GiftCardSaleResult sell(SellGiftCardRequest request) {
    String companyId = TenantContext.require().companyId();

    Optional<GiftCardSaleView> existing =
        repository.findViewByIdempotencyKey(request.idempotencyKey());
    if (existing.isPresent()) {
      return new GiftCardSaleResult(toResponse(existing.get()), false);
    }

    // Till operation: outlet-assignment enforced, but NO owner/manager gate (unlike a manual
    // discount) — selling a gift card is routine, not discretionary.
    outletAccessGuard.enforce(request.businessId());

    Money amount = Money.ofMinor(request.amountMinor(), request.currency());
    UUID giftCardId = UUID.randomUUID();
    Instant now = Instant.now();

    GiftCardSale sale =
        new GiftCardSale(
            giftCardId,
            request.businessId(),
            amount,
            request.tenderType(),
            now,
            request.idempotencyKey());
    sale.setCompanyId(companyId);
    GiftCardSale saved = repository.saveAndFlush(sale);

    GenericRecord event = GiftCardSoldSchema.toRecord(saved, companyId);
    outboxWriter.write(
        GiftCardSoldSchema.AGGREGATE_TYPE,
        saved.getGiftCardId().toString(),
        GiftCardSoldSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        UUID.fromString(companyId),
        saved.getOccurredAt());

    String code = GiftCardCodeGenerator.deriveCode(saved.getGiftCardId());
    return new GiftCardSaleResult(toResponse(saved, code), true);
  }

  /**
   * Re-reads a gift-card sale by idempotency key in a FRESH transaction, used to recover the loser
   * of a concurrent insert race after its own {@code sell} transaction aborted.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<GiftCardSaleResponse> findExistingByKey(String idempotencyKey) {
    return repository.findViewByIdempotencyKey(idempotencyKey).map(GiftCardSaleWriter::toResponse);
  }

  private static GiftCardSaleResponse toResponse(GiftCardSale sale, String code) {
    return new GiftCardSaleResponse(
        sale.getId(),
        sale.getGiftCardId(),
        code,
        sale.getBusinessId(),
        sale.getAmount().amountMinor(),
        sale.getAmount().currency().getCurrencyCode(),
        sale.getTenderType(),
        sale.getOccurredAt());
  }

  private static GiftCardSaleResponse toResponse(GiftCardSaleView view) {
    return new GiftCardSaleResponse(
        view.getId(),
        view.getGiftCardId(),
        GiftCardCodeGenerator.deriveCode(view.getGiftCardId()),
        view.getBusinessId(),
        view.getAmountMinor(),
        view.getCurrency().strip(),
        view.getTenderType(),
        view.getOccurredAt());
  }
}
