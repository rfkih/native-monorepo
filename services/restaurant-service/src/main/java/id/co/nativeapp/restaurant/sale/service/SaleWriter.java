package id.co.nativeapp.restaurant.sale.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.sale.domain.Sale;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.dto.SaleResponse;
import id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema;
import id.co.nativeapp.restaurant.sale.projection.SaleView;
import id.co.nativeapp.restaurant.sale.repository.SaleRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
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

  private final SaleRepository repository;
  private final OutboxWriter outboxWriter;
  private final PostOutboxHook postOutboxHook;

  public SaleWriter(
      SaleRepository repository, OutboxWriter outboxWriter, PostOutboxHook postOutboxHook) {
    this.repository = repository;
    this.outboxWriter = outboxWriter;
    this.postOutboxHook = postOutboxHook;
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
        new Sale(command.businessId(), amount, command.occurredAt(), command.idempotencyKey());
    sale.setCompanyId(companyId);
    Sale saved = repository.saveAndFlush(sale);

    // Build the SaleRecorded GenericRecord from the .avsc and serialize it for the
    // outbox payload (no Avro codegen). Pass the tender_type from the command so
    // finance can route the GL clearing account by tender (ADR 0006, slice 2);
    // null for legacy/no-payment sales (carwash always leaves it null).
    GenericRecord event = SaleRecordedSchema.toRecord(saved, companyId, command.tenderType());
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

    Money amount = Money.ofMinor(command.amountMinor(), command.currency());
    Sale sale =
        new Sale(command.businessId(), amount, command.occurredAt(), command.idempotencyKey());
    sale.setCompanyId(companyId);
    Sale saved = repository.saveAndFlush(sale);

    GenericRecord event = SaleRecordedSchema.toRecord(saved, companyId, command.tenderType());
    byte[] payload = AvroSerde.serialize(event);
    outboxWriter.write(
        SaleRecordedSchema.AGGREGATE_TYPE,
        saved.getId().toString(),
        SaleRecordedSchema.EVENT_TYPE,
        payload,
        null,
        UUID.fromString(companyId),
        saved.getOccurredAt());

    postOutboxHook.afterOutboxWrite(saved);

    return new RecordSaleResult(SaleResponse.from(saved), true);
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
