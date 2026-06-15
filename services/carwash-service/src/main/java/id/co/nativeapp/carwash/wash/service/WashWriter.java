package id.co.nativeapp.carwash.wash.service;

import id.co.nativeapp.carwash.metric.domain.CarwashMetricContract;
import id.co.nativeapp.carwash.metric.domain.CarwashMetricContract.MetricDeclaration;
import id.co.nativeapp.carwash.metric.messaging.MetricPublishedSchema;
import id.co.nativeapp.carwash.wash.domain.Wash;
import id.co.nativeapp.carwash.wash.dto.RecordWashCommand;
import id.co.nativeapp.carwash.wash.dto.RecordWashResult;
import id.co.nativeapp.carwash.wash.messaging.SaleRecordedSchema;
import id.co.nativeapp.carwash.wash.repository.WashRepository;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work behind {@link WashService}.
 *
 * <p>It is a distinct bean (not private methods on {@code WashService}) so each transactional
 * method is invoked through the Spring proxy — a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC. {@code
 * WashService} calls {@link #create} and, only on a concurrent-collision conflict, {@link
 * #findExistingByKey} in a <em>separate</em> transaction (a PostgreSQL transaction is poisoned once
 * a constraint fires).
 *
 * <p><strong>One wash, two event kinds, one transaction (rule 3).</strong> {@link #create} writes
 * the wash row, ONE {@code SaleRecorded} outbox row (so finance consolidates carwash revenue), and
 * ONE {@code MetricPublished} outbox row per entry in the carwash {@link CarwashMetricContract}
 * (e.g. {@code wash_count} + {@code upsell_amount} at the outlet grain) — all in the SAME
 * transaction. A rollback drops every row together.
 */
@Component
public class WashWriter {

  private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyy-MM-dd");

  private final WashRepository repository;
  private final OutboxWriter outboxWriter;
  private final PostOutboxHook postOutboxHook;

  public WashWriter(
      WashRepository repository, OutboxWriter outboxWriter, PostOutboxHook postOutboxHook) {
    this.repository = repository;
    this.outboxWriter = outboxWriter;
    this.postOutboxHook = postOutboxHook;
  }

  /**
   * Persists a wash and writes its {@code SaleRecorded} + {@code MetricPublished} outbox rows in
   * ONE transaction (rule 3 — the outbox commits atomically with the wash; a rollback drops them
   * all).
   *
   * <p>{@code REQUIRES_NEW} guarantees its own transaction even though {@link
   * WashService#recordWash} is not transactional, and — critically — keeps the conflict re-read
   * ({@link #findExistingByKey}) in a transaction independent of this one, so the aborted create
   * transaction cannot poison the recovery read.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer already
   *     inserted the {@code (company_id, idempotency_key)} row
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public RecordWashResult create(RecordWashCommand command) {
    String companyId = TenantContext.require().companyId();

    // Idempotency fast path: a prior wash under this tenant + key short-circuits, emitting no
    // second
    // events. RLS-scoped, so it can only match this tenant's rows. Under concurrency two callers
    // may
    // both miss here and race the INSERT below; the (company_id, idempotency_key) unique constraint
    // is the backstop and the loser is recovered by WashService via findExistingByKey.
    Optional<Wash> existing = repository.findByIdempotencyKey(command.idempotencyKey());
    if (existing.isPresent()) {
      return new RecordWashResult(existing.get(), false);
    }

    // Validate the amount through libs/money Money (ISO-4217; integer minor units, never a float).
    // Money.ofMinor rejects an unknown currency code with IllegalArgumentException -> mapped to
    // 400.
    Money amount = Money.ofMinor(command.amountMinor(), command.currency());
    Optional<Wash.Upsell> upsell = toUpsell(command, amount);

    Wash wash =
        new Wash(
            command.businessId(),
            command.bay(),
            upsell,
            amount,
            command.occurredAt(),
            command.idempotencyKey());
    wash.setCompanyId(companyId);
    Wash saved = repository.saveAndFlush(wash);

    writeSaleRecorded(saved, companyId);
    writeMetrics(saved, companyId);

    // Test seam: a no-op in production; a test can install a hook that throws here to prove the
    // wash
    // AND the outbox rows roll back together (atomicity, rule 3).
    postOutboxHook.afterOutboxWrite(saved);

    return new RecordWashResult(saved, true);
  }

  /**
   * Re-reads a wash by idempotency key in a FRESH transaction, used to recover the loser of a
   * concurrent insert race after its own create transaction aborted. RLS-scoped to the bound
   * tenant, matching the unique constraint exactly.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<Wash> findExistingByKey(String idempotencyKey) {
    return repository.findByIdempotencyKey(idempotencyKey);
  }

  /**
   * All washes visible to the bound tenant — no {@code WHERE company_id}; the result set is
   * constrained solely by the auto-applied RLS policy.
   */
  @Transactional(readOnly = true)
  public List<Wash> findAllForCurrentTenant() {
    return repository.findAll();
  }

  private static Optional<Wash.Upsell> toUpsell(RecordWashCommand command, Money washAmount) {
    if (command.upsellName() == null || command.upsellName().isBlank()) {
      return Optional.empty();
    }
    if (command.upsellAmountMinor() == null) {
      throw new IllegalArgumentException(
          "upsellAmountMinor is required when upsellName is present");
    }
    // The upsell shares the wash currency (one transaction currency per wash).
    Money upsellAmount =
        Money.ofMinor(command.upsellAmountMinor(), washAmount.currency().getCurrencyCode());
    return Optional.of(new Wash.Upsell(command.upsellName(), upsellAmount));
  }

  private void writeSaleRecorded(Wash saved, String companyId) {
    GenericRecord event = SaleRecordedSchema.toRecord(saved, companyId);
    outboxWriter.write(
        SaleRecordedSchema.AGGREGATE_TYPE,
        saved.getId().toString(),
        SaleRecordedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        UUID.fromString(companyId),
        saved.getOccurredAt());
  }

  /**
   * Emits one {@code MetricPublished} per entry in the carwash metric contract (rule 7 / the
   * declared contract). At the {@code outlet} grain the subject is the carwash outlet ({@code
   * business_id}); {@code wash_count} contributes {@code 1}, {@code upsell_amount} contributes the
   * wash's upsell amount in minor units. The period is the wash date (UTC, {@code YYYY-MM-DD}).
   */
  private void writeMetrics(Wash saved, String companyId) {
    String period = LocalDate.ofInstant(saved.getOccurredAt(), ZoneOffset.UTC).format(PERIOD);
    String outletId = saved.getBusinessId().toString();
    for (MetricDeclaration declaration : CarwashMetricContract.DECLARATIONS) {
      long value = metricValue(declaration.metricKey(), saved);
      GenericRecord event =
          MetricPublishedSchema.toRecord(
              declaration.metricKey(),
              period,
              declaration.grain().wireValue(),
              outletId, // subject_id at the outlet grain is the outlet
              value,
              outletId);
      outboxWriter.write(
          MetricPublishedSchema.AGGREGATE_TYPE,
          outletId,
          MetricPublishedSchema.EVENT_TYPE,
          AvroSerde.serialize(event),
          null,
          UUID.fromString(companyId),
          saved.getOccurredAt());
    }
  }

  private static long metricValue(String metricKey, Wash wash) {
    return switch (metricKey) {
      case CarwashMetricContract.WASH_COUNT -> 1L;
      case CarwashMetricContract.UPSELL_AMOUNT -> wash.getUpsellAmountMinor();
      default ->
          throw new IllegalStateException("No value mapping for declared metric: " + metricKey);
    };
  }
}
