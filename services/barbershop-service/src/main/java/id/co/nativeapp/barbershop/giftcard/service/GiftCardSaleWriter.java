package id.co.nativeapp.barbershop.giftcard.service;

import id.co.nativeapp.barbershop.config.GiftCardProperties;
import id.co.nativeapp.barbershop.giftcard.domain.GiftCardCodeGenerator;
import id.co.nativeapp.barbershop.giftcard.domain.GiftCardMintLimitExceededException;
import id.co.nativeapp.barbershop.giftcard.domain.GiftCardSale;
import id.co.nativeapp.barbershop.giftcard.dto.GiftCardSaleResponse;
import id.co.nativeapp.barbershop.giftcard.dto.SellGiftCardRequest;
import id.co.nativeapp.barbershop.giftcard.messaging.GiftCardSoldSchema;
import id.co.nativeapp.barbershop.giftcard.projection.GiftCardSaleView;
import id.co.nativeapp.barbershop.giftcard.repository.GiftCardSaleRepository;
import id.co.nativeapp.barbershop.outletref.service.OutletAccessGuard;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
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
 * method is invoked through the Spring proxy — a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC.
 *
 * <p><strong>One atomic unit of work.</strong> {@link #sell} runs in {@code REQUIRES_NEW} and: (1)
 * the idempotency fast path returns the existing sale for a re-delivered {@code idempotencyKey}
 * WITHOUT minting a second card or emitting a second event; (2) {@link OutletAccessGuard#enforce} —
 * a till operation, so no owner/manager role is required (unlike a manual discount); (3) rejects a
 * request above the configured {@link GiftCardProperties#maxMintMinor} mint ceiling (security
 * review W-3); (4) mints a fresh card UUID, derives its display code via the KEYED {@link
 * GiftCardCodeGenerator} (security review W-4), persists the {@code gift_card_sale} row; (5) writes
 * {@code GiftCardSold} to the outbox in the SAME transaction (rule 3).
 *
 * <p><strong>Mint controls (security review W-3).</strong> {@code tenderType} is now {@code
 * @NotNull} on {@link SellGiftCardRequest} and {@code amountMinor} is capped by {@link
 * GiftCardProperties#maxMintMinor}. <strong>Deliberately NOT built here:</strong> routing a digital
 * tender (QRIS/CARD) through a pending/capture flow so the card only activates once the payment is
 * confirmed — that is the documented ADR 0027 follow-up, not this wave; this writer still activates
 * the card synchronously on {@code sell()} regardless of tender.
 */
@Component
public class GiftCardSaleWriter {

  private final GiftCardSaleRepository repository;
  private final OutboxWriter outboxWriter;
  private final OutletAccessGuard outletAccessGuard;
  private final GiftCardCodeGenerator giftCardCodeGenerator;
  private final GiftCardProperties giftCardProperties;

  public GiftCardSaleWriter(
      GiftCardSaleRepository repository,
      OutboxWriter outboxWriter,
      OutletAccessGuard outletAccessGuard,
      GiftCardCodeGenerator giftCardCodeGenerator,
      GiftCardProperties giftCardProperties) {
    this.repository = repository;
    this.outboxWriter = outboxWriter;
    this.outletAccessGuard = outletAccessGuard;
    this.giftCardCodeGenerator = giftCardCodeGenerator;
    this.giftCardProperties = giftCardProperties;
  }

  /**
   * Sells (mints) a gift card idempotently.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer already
   *     inserted the {@code (company_id, idempotency_key)} row
   * @throws id.co.nativeapp.barbershop.outletref.domain.OutletNotAssignedException if the cashier
   *     is not assigned to {@code request.businessId()} (403)
   * @throws id.co.nativeapp.barbershop.giftcard.domain.GiftCardMintLimitExceededException if {@code
   *     request.amountMinor()} exceeds {@link GiftCardProperties#maxMintMinor} (422, security
   *     review W-3)
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

    // Security review W-3: a mint ceiling — checked BEFORE any write, so a rejected request leaves
    // no partial gift_card_sale row / GiftCardSold event.
    if (request.amountMinor() > giftCardProperties.maxMintMinor()) {
      throw new GiftCardMintLimitExceededException(
          request.amountMinor(), giftCardProperties.maxMintMinor());
    }

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

    String code = giftCardCodeGenerator.deriveCode(saved.getGiftCardId());
    return new GiftCardSaleResult(toResponse(saved, code), true);
  }

  /**
   * Re-reads a gift-card sale by idempotency key in a FRESH transaction, used to recover the loser
   * of a concurrent insert race after its own {@code sell} transaction aborted.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<GiftCardSaleResponse> findExistingByKey(String idempotencyKey) {
    return repository.findViewByIdempotencyKey(idempotencyKey).map(this::toResponse);
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

  private GiftCardSaleResponse toResponse(GiftCardSaleView view) {
    return new GiftCardSaleResponse(
        view.getId(),
        view.getGiftCardId(),
        giftCardCodeGenerator.deriveCode(view.getGiftCardId()),
        view.getBusinessId(),
        view.getAmountMinor(),
        view.getCurrency().strip(),
        view.getTenderType(),
        view.getOccurredAt());
  }
}
