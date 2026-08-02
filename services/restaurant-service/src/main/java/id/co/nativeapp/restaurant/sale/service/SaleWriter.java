package id.co.nativeapp.restaurant.sale.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.metric.domain.RestaurantMetricContract;
import id.co.nativeapp.restaurant.metric.messaging.MetricPublishedSchema;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.sale.domain.Sale;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.dto.SaleResponse;
import id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema;
import id.co.nativeapp.restaurant.sale.projection.SaleView;
import id.co.nativeapp.restaurant.sale.repository.SaleRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work behind {@link SaleService}.
 *
 * <p>It is a distinct bean (not private methods on {@code SaleService}) so each transactional
 * method is invoked through the Spring proxy — a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC. {@code
 * SaleService} calls {@link #create} and, only on a concurrent-collision conflict, {@link
 * #findExistingByKey} in a <em>separate</em> transaction (a PostgreSQL transaction is poisoned once
 * a constraint fires).
 */
@Component
public class SaleWriter {

  private static final Logger log = LoggerFactory.getLogger(SaleWriter.class);

  private final SaleRepository repository;
  private final OutboxWriter outboxWriter;
  private final PostOutboxHook postOutboxHook;
  private final OutletAccessGuard outletAccessGuard;

  public SaleWriter(
      SaleRepository repository,
      OutboxWriter outboxWriter,
      PostOutboxHook postOutboxHook,
      OutletAccessGuard outletAccessGuard) {
    this.repository = repository;
    this.outboxWriter = outboxWriter;
    this.postOutboxHook = postOutboxHook;
    this.outletAccessGuard = outletAccessGuard;
  }

  /**
   * Persists a sale and writes its {@code SaleRecorded} outbox row in ONE transaction (rule 3 — the
   * outbox commits atomically with the sale; a rollback drops both).
   *
   * <p>{@code REQUIRES_NEW} guarantees its own transaction even though {@link
   * SaleService#recordSale} is not transactional, and — critically — keeps the conflict re-read
   * ({@link #findExistingByKey}) in a transaction independent of this one, so the aborted create
   * transaction cannot poison the recovery read.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer already
   *     inserted the {@code (company_id, idempotency_key)} row
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RecordSaleResult create(RecordSaleCommand command) {
    String companyId = TenantContext.require().companyId();

    // Idempotency fast path: a prior sale under this tenant + key short-circuits,
    // emitting no second event. RLS-scoped, so it can only match this tenant's rows.
    // Under concurrency two callers may both miss here and race the INSERT below;
    // the (company_id, idempotency_key) unique constraint is the backstop and the
    // loser is recovered by SaleService via findExistingByKey.
    Optional<SaleView> existing = repository.findViewByIdempotencyKey(command.idempotencyKey());
    if (existing.isPresent()) {
      return new RecordSaleResult(toResponse(existing.get()), false);
    }

    // Phase 5 enforcement — CHOKE-POINT guard. Every revenue-recognizing path funnels through a
    // SaleWriter method, so guarding here closes any caller that reaches the SaleRecorded emit
    // WITHOUT an upstream OrderWriter/BillWriter guard — notably the legacy POST /api/v1/sales
    // (cashier-reachable, client-supplied businessId). Placed AFTER the idempotency fast path so
    // an idempotent replay of an already-recorded sale still returns 200 (no NEW revenue minted);
    // only a genuinely new sale at an unassigned outlet is rejected.
    outletAccessGuard.enforce(command.businessId());

    // Validate the amount through libs/money Money (ISO-4217; integer minor units,
    // never a float). Money.ofMinor rejects an unknown currency code with
    // IllegalArgumentException -> mapped to 400 by ApiExceptionHandler.
    //
    // TODO(M1.2): once org-service lands, validate `command.currency()` against the
    // company's immutable base currency (CompanyCreated carries it) and reject a sale
    // whose currency differs from the base. Until then the request's currency is
    // accepted as-is.
    Money amount = Money.ofMinor(command.amountMinor(), command.currency());

    Sale sale =
        new Sale(
            command.businessId(),
            amount,
            command.occurredAt(),
            command.idempotencyKey(),
            command.tenderType(),
            cashCollectedOf(command));
    sale.setCompanyId(companyId);
    Sale saved = repository.saveAndFlush(sale);

    // Build the SaleRecorded GenericRecord from the .avsc and serialize it for the
    // outbox payload (no Avro codegen). Pass the tender_type (ADR 0006, slice 2) and the
    // Phase 2 breakdown (null for legacy / carwash callers — all breakdown fields on the
    // wire are null, finance falls back to subtotal==amount_minor).
    GenericRecord event =
        SaleRecordedSchema.toRecord(
            saved,
            companyId,
            command.tenderType(),
            command.breakdown(),
            command.loyaltyMemberId(),
            command.loyaltyRedeemedPoints(),
            command.loyaltyRedeemedMinor(),
            command.giftCardId(),
            command.giftCardRedeemedMinor());
    byte[] payload = AvroSerde.serialize(event);

    // The outbox INSERT runs on this transaction's connection (rule 3): it commits
    // atomically with the sale above. company_id is a UUID column on the outbox; the
    // tenant id is the JWT `company_id` claim, a UUID for a real company (Auditable
    // stores it as text, but it is validated to be a UUID at the request edge by
    // DevTenantFilter, so UUID.fromString never fails here).
    outboxWriter.write(
        SaleRecordedSchema.AGGREGATE_TYPE,
        saved.getId().toString(),
        SaleRecordedSchema.EVENT_TYPE,
        payload,
        null,
        UUID.fromString(companyId),
        saved.getOccurredAt());

    // Own-sales commission feed: emit a MetricPublished (sales_amount @ employee) in the SAME
    // transaction, attributed to the cashier who rang it (rule 3).
    emitSalesMetric(saved, companyId);

    // Test seam: a no-op in production; a test can install a hook that throws here to
    // prove the sale AND the outbox row roll back together (atomicity, rule 3).
    postOutboxHook.afterOutboxWrite(saved);

    return new RecordSaleResult(SaleResponse.from(saved), true);
  }

  /**
   * Persists a sale and writes its {@code SaleRecorded} outbox row by <em>joining</em> the caller's
   * existing transaction (propagation {@code MANDATORY} — throws if no transaction is active). This
   * is the method {@link id.co.nativeapp.restaurant.order.service.OrderWriter OrderWriter} uses so
   * that the order rows, the sale row, and the {@code SaleRecorded} outbox row all commit — or all
   * roll back — as a single physical transaction (rule 3, C1 fix).
   *
   * <p>Unlike {@link #create} (which suspends any enclosing transaction via {@code REQUIRES_NEW}),
   * this method participates in the caller's unit of work. The {@code PostOutboxHook} seam fires
   * here too, so the checkout-atomicity test can inject a throwing hook and prove the whole
   * transaction rolls back.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public RecordSaleResult recordInCurrentTx(RecordSaleCommand command) {
    String companyId = TenantContext.require().companyId();

    // Phase 5 enforcement — CHOKE-POINT guard (see create()). This is the sole guard for
    // PaymentCaptureWriter.capture (digital-tender revenue recognition), which records a sale in
    // the caller's tx without passing through an OrderWriter/BillWriter guard. Redundant-but-
    // harmless for the checkout/payParked/payBill paths, which already fail-fast-guard upstream.
    outletAccessGuard.enforce(command.businessId());

    Money amount = Money.ofMinor(command.amountMinor(), command.currency());
    Sale sale =
        new Sale(
            command.businessId(),
            amount,
            command.occurredAt(),
            command.idempotencyKey(),
            command.tenderType(),
            cashCollectedOf(command));
    sale.setCompanyId(companyId);
    Sale saved = repository.saveAndFlush(sale);

    GenericRecord event =
        SaleRecordedSchema.toRecord(
            saved,
            companyId,
            command.tenderType(),
            command.breakdown(),
            command.loyaltyMemberId(),
            command.loyaltyRedeemedPoints(),
            command.loyaltyRedeemedMinor(),
            command.giftCardId(),
            command.giftCardRedeemedMinor());
    byte[] payload = AvroSerde.serialize(event);
    outboxWriter.write(
        SaleRecordedSchema.AGGREGATE_TYPE,
        saved.getId().toString(),
        SaleRecordedSchema.EVENT_TYPE,
        payload,
        null,
        UUID.fromString(companyId),
        saved.getOccurredAt());

    emitSalesMetric(saved, companyId);

    postOutboxHook.afterOutboxWrite(saved);

    return new RecordSaleResult(SaleResponse.from(saved), true);
  }

  /**
   * The CASH physically entering the drawer (review C1): for a CASH tender, the grand total minus
   * any gift-card-redeemed portion (a split sale collects only the residual). Null for non-cash
   * tenders — the register close only sums cash.
   */
  private static Long cashCollectedOf(RecordSaleCommand command) {
    if (!"CASH".equals(command.tenderType())) {
      return null;
    }
    long giftCard = command.giftCardRedeemedMinor() != null ? command.giftCardRedeemedMinor() : 0L;
    return Math.subtractExact(command.amountMinor(), giftCard);
  }

  /**
   * Emits one {@code MetricPublished} ({@code sales_amount} @ employee grain) for a sale,
   * attributed to the cashier who rang it (the bound actor = the JWT sub, also on {@code
   * sale.created_by}), in the caller's transaction (rule 3). Skipped when the actor is NOT a UUID:
   * {@code metric_input.subject_id} is a UUID column, so a non-UUID actor (the header-trust dev
   * recipe's fixed actor) cannot key a metric row — emitting one would fail the consumer decode.
   * This is a documented dev-mode caveat; real logins always carry a UUID sub.
   */
  private void emitSalesMetric(Sale saved, String companyId) {
    String actor = TenantContext.require().actor();
    UUID subject;
    try {
      subject = UUID.fromString(actor);
    } catch (IllegalArgumentException e) {
      log.debug("Skipping sales_amount metric — actor '{}' is not a UUID sub (dev recipe)", actor);
      return;
    }
    String period = saved.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
    GenericRecord metric =
        MetricPublishedSchema.toRecord(
            RestaurantMetricContract.SALES_AMOUNT,
            period,
            RestaurantMetricContract.EMPLOYEE_GRAIN,
            subject.toString(),
            saved.getAmount().amountMinor(),
            saved.getBusinessId().toString());
    outboxWriter.write(
        MetricPublishedSchema.AGGREGATE_TYPE,
        saved.getId().toString(),
        MetricPublishedSchema.EVENT_TYPE,
        AvroSerde.serialize(metric),
        null,
        UUID.fromString(companyId),
        saved.getOccurredAt());
  }

  /**
   * Re-reads a sale by idempotency key in a FRESH transaction, used to recover the loser of a
   * concurrent insert race after its own create transaction aborted. RLS-scoped to the bound
   * tenant, matching the unique constraint exactly.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<SaleResponse> findExistingByKey(String idempotencyKey) {
    return repository.findViewByIdempotencyKey(idempotencyKey).map(SaleWriter::toResponse);
  }

  /**
   * All sales visible to the bound tenant — no {@code WHERE company_id}; the result set is
   * constrained solely by the auto-applied RLS policy. Read path: a native-query projection (only
   * the response columns), never {@code SELECT *} of the {@code Auditable} entity.
   */
  @Transactional(readOnly = true)
  public List<SaleResponse> findAllForCurrentTenant() {
    return repository.findAllViews().stream().map(SaleWriter::toResponse).toList();
  }

  /** Maps a read projection to the response shape (currency CHAR(3) is right-padded — strip it). */
  private static SaleResponse toResponse(SaleView view) {
    return new SaleResponse(
        view.getId(),
        view.getBusinessId(),
        view.getAmountMinor(),
        view.getCurrency().strip(),
        view.getOccurredAt(),
        view.getIdempotencyKey());
  }
}
