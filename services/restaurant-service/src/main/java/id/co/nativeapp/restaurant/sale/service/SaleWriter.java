package id.co.nativeapp.restaurant.sale.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.metric.domain.RestaurantMetricContract;
import id.co.nativeapp.restaurant.metric.messaging.MetricPublishedSchema;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import id.co.nativeapp.restaurant.recipe.messaging.SaleCogsRecordedSchema;
import id.co.nativeapp.restaurant.register.service.CashWindowLock;
import id.co.nativeapp.restaurant.sale.domain.OperatorMismatchException;
import id.co.nativeapp.restaurant.sale.domain.Sale;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.dto.SaleHistoryResponse;
import id.co.nativeapp.restaurant.sale.dto.SaleResponse;
import id.co.nativeapp.restaurant.sale.messaging.SaleRecordedSchema;
import id.co.nativeapp.restaurant.sale.projection.SaleHistoryView;
import id.co.nativeapp.restaurant.sale.projection.SaleView;
import id.co.nativeapp.restaurant.sale.repository.SaleRepository;
import id.co.nativeapp.security.OperatorContextProvider;
import id.co.nativeapp.security.OperatorPrincipal;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
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
 *
 * <p><strong>CashWindowLock (verified HIGH race fix).</strong> {@link #create} acquires the
 * per-business {@link CashWindowLock} SHARED ({@link CashWindowLock#acquireForCommit}) as the FIRST
 * lock-acquiring statement, strictly BEFORE the "happening right now" default for {@code
 * occurredAt} is resolved — mirroring {@code OrderWriter.checkout}. {@link #recordInCurrentTx} does
 * NOT take the lock itself: it runs {@code MANDATORY} inside a caller's transaction
 * (checkout/payParked/payBill/capture) that has ALREADY acquired the SHARED lock earlier in that
 * same transaction — re-acquiring is unnecessary (a PostgreSQL advisory xact lock is re-entrant
 * within one transaction). See {@code RegisterSessionWriter} class javadoc for the full contract.
 */
@Component
public class SaleWriter {

  private static final Logger log = LoggerFactory.getLogger(SaleWriter.class);

  private final SaleRepository repository;
  private final OutboxWriter outboxWriter;
  private final PostOutboxHook postOutboxHook;
  private final OutletAccessGuard outletAccessGuard;
  private final CashWindowLock cashWindowLock;
  private final OperatorContextProvider operatorContextProvider;
  private final OperatorRequiredGuard operatorRequiredGuard;

  public SaleWriter(
      SaleRepository repository,
      OutboxWriter outboxWriter,
      PostOutboxHook postOutboxHook,
      OutletAccessGuard outletAccessGuard,
      CashWindowLock cashWindowLock,
      OperatorContextProvider operatorContextProvider,
      OperatorRequiredGuard operatorRequiredGuard) {
    this.repository = repository;
    this.outboxWriter = outboxWriter;
    this.postOutboxHook = postOutboxHook;
    this.outletAccessGuard = outletAccessGuard;
    this.cashWindowLock = cashWindowLock;
    this.operatorContextProvider = operatorContextProvider;
    this.operatorRequiredGuard = operatorRequiredGuard;
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
    // ADR 0049 P2: read once, up front, so the SAME operator context stamps the seller AND
    // attributes the commission metric below — read via a verified X-Operator-Session (absent for
    // every sale today, since no PIN operators exist yet in the field; inert by default).
    Optional<OperatorPrincipal> operator = operatorContextProvider.current();

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

    // ADR 0049 P4 — CHOKE-POINT guard: a device (outlet-terminal) sale with no verified operator
    // session is rejected outright (409 operator-required). Inert for actor_type=user (owner/
    // manager/cashier ringing directly) — see ActorTypeProvider/OperatorRequiredGuard javadoc.
    operatorRequiredGuard.enforce(command.businessId(), operator);

    // CashWindowLock (verified HIGH race fix) — SHARED, FIRST lock-acquiring statement, strictly
    // BEFORE occurredAt is resolved below (see RegisterSessionWriter class javadoc for the
    // contract).
    // command.occurredAt() may be a caller-supplied backdated instant (legacy POST /api/v1/sales
    // allows an explicit occurredAt) — that is deliberate historical data, independent of lock
    // timing, and is used as-is. Only the "happening right now" default (null) is captured HERE,
    // inside the lock, instead of by the controller before the transaction even started.
    cashWindowLock.acquireForCommit(command.businessId());
    Instant occurredAt = command.occurredAt() != null ? command.occurredAt() : Instant.now();

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
            occurredAt,
            command.idempotencyKey(),
            command.tenderType(),
            cashCollectedOf(command),
            command.channel(),
            giftCardRedeemedOf(command));
    sale.setCompanyId(companyId);
    // ADR 0049 P2/P4: stamp the resolved seller (if any) BEFORE the first save — the
    // sold_by_user_id column is updatable=false, so this must happen at creation. A mismatched
    // operator (wrong tenant/outlet) rejects the whole write (OperatorMismatchException, 409) —
    // never silently falls back to the device actor.
    String resolvedSellerId = resolveSeller(operator, command);
    stampSeller(sale, resolvedSellerId, operator, companyId, command.businessId());
    // V39: snapshot the Phase 2 price breakdown onto the sale row (BEFORE the first save — the
    // columns are updatable=false) so the POS daily summary aggregates exact per-sale figures
    // instead of re-deriving pricing per order. No-op when the caller carries no breakdown.
    stampBreakdownIfPresent(sale, command);
    // ADR 0067 Phase C: snapshot the caller's COGS fold (BEFORE the first save — updatable=false).
    // No-op when the caller's depletion carried no costed ingredients.
    sale.stampCogs(command.cogsMinor(), command.cogsCurrency());
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
            command.giftCardRedeemedMinor(),
            command.channel());
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

    // ADR 0067 Phase C: SaleCogsRecorded, same transaction, ONLY when the fold was positive
    // (sale.cogs_minor stays NULL and nothing is emitted for a sale with no costed depletion).
    emitSaleCogsRecordedIfPresent(saved, companyId);

    // Own-sales commission feed: emit a MetricPublished (sales_amount @ employee) in the SAME
    // transaction, attributed to the resolved seller (operator who rang it, ADR 0049 P2; else the
    // ring-time operator carried async via command.soldByUserId(), ADR 0049 P4), else the cashier
    // who rang it (rule 3, today's exact pre-ADR-0049 behaviour).
    emitSalesMetric(saved, companyId, resolvedSellerId);

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
    // ADR 0049 P2: same operator-context read as create() — see its comment.
    Optional<OperatorPrincipal> operator = operatorContextProvider.current();

    // Phase 5 enforcement — CHOKE-POINT guard (see create()). This is the sole guard for
    // PaymentCaptureWriter.capture (digital-tender revenue recognition), which records a sale in
    // the caller's tx without passing through an OrderWriter/BillWriter guard. Redundant-but-
    // harmless for the checkout/payParked/payBill paths, which already fail-fast-guard upstream.
    outletAccessGuard.enforce(command.businessId());

    // ADR 0049 P4 — CHOKE-POINT guard (see create()). On the async PaymentCaptureWriter.capture
    // path (a Kafka consumer thread, no HTTP request) ActorTypeProvider always resolves "user", so
    // this never re-fires there — the device was already required (or the operator already
    // captured) at the synchronous checkout that minted the PENDING payment.
    operatorRequiredGuard.enforce(command.businessId(), operator);

    Money amount = Money.ofMinor(command.amountMinor(), command.currency());
    Sale sale =
        new Sale(
            command.businessId(),
            amount,
            command.occurredAt(),
            command.idempotencyKey(),
            command.tenderType(),
            cashCollectedOf(command),
            command.channel(),
            giftCardRedeemedOf(command));
    sale.setCompanyId(companyId);
    String resolvedSellerId = resolveSeller(operator, command);
    stampSeller(sale, resolvedSellerId, operator, companyId, command.businessId());
    // V39: snapshot the Phase 2 price breakdown onto the sale row (BEFORE the first save — the
    // columns are updatable=false) so the POS daily summary aggregates exact per-sale figures
    // instead of re-deriving pricing per order. No-op when the caller carries no breakdown.
    stampBreakdownIfPresent(sale, command);
    // ADR 0067 Phase C: snapshot the caller's COGS fold (BEFORE the first save — updatable=false).
    // No-op when the caller's depletion carried no costed ingredients.
    sale.stampCogs(command.cogsMinor(), command.cogsCurrency());
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
            command.giftCardRedeemedMinor(),
            command.channel());
    byte[] payload = AvroSerde.serialize(event);
    outboxWriter.write(
        SaleRecordedSchema.AGGREGATE_TYPE,
        saved.getId().toString(),
        SaleRecordedSchema.EVENT_TYPE,
        payload,
        null,
        UUID.fromString(companyId),
        saved.getOccurredAt());

    // ADR 0067 Phase C: SaleCogsRecorded, same transaction, ONLY when the fold was positive.
    emitSaleCogsRecordedIfPresent(saved, companyId);

    emitSalesMetric(saved, companyId, resolvedSellerId);

    postOutboxHook.afterOutboxWrite(saved);

    return new RecordSaleResult(SaleResponse.from(saved), true);
  }

  /**
   * ADR 0067 Phase C: writes the {@code SaleCogsRecorded} outbox row in the caller's transaction —
   * ONLY when {@code saved.getCogsMinor()} is present (a sale with no costed recipe depletion emits
   * nothing, mirroring {@code sale.cogs_minor} staying NULL). {@code occurredAt} drives the
   * accounting period, the SAME period as this sale's revenue.
   */
  private void emitSaleCogsRecordedIfPresent(Sale saved, String companyId) {
    Long cogsMinor = saved.getCogsMinor();
    if (cogsMinor == null) {
      return;
    }
    GenericRecord cogsEvent =
        SaleCogsRecordedSchema.toRecord(
            saved.getId(),
            companyId,
            saved.getBusinessId(),
            saved.getOccurredAt(),
            cogsMinor,
            saved.getCogsCurrency());
    outboxWriter.write(
        SaleCogsRecordedSchema.AGGREGATE_TYPE,
        saved.getId().toString(),
        SaleCogsRecordedSchema.EVENT_TYPE,
        AvroSerde.serialize(cogsEvent),
        null,
        UUID.fromString(companyId),
        saved.getOccurredAt());
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
    return Math.subtractExact(command.amountMinor(), giftCardRedeemedOf(command));
  }

  /**
   * The gift-card-redeemed portion of a sale, minor units (0 when none) — V27, ADR 0038 phase 2.
   * The NET amount that hits a NON-cash tender's clearing account is {@code amount − this}; the
   * register close reconciles each non-cash tender against that net.
   */
  private static long giftCardRedeemedOf(RecordSaleCommand command) {
    return command.giftCardRedeemedMinor() != null ? command.giftCardRedeemedMinor() : 0L;
  }

  /**
   * Stamps the Phase 2 price-breakdown reporting snapshot onto a freshly-built sale (V39) when the
   * caller carries one — the SAME figures emitted on the {@code SaleRecorded} event, so the sale
   * row, the event, and the receipt all agree. {@code discount_minor} is decomposed to PROMO-ONLY
   * exactly as {@link SaleRecordedSchema#toRecord} does (subtract the loyalty redemption, which is
   * a separate contra-revenue term). No-op for legacy / carwash callers that pass a {@code null}
   * breakdown — the columns stay NULL and the daily-summary reader falls back to {@code subtotal ==
   * amount_minor}. Must run BEFORE the first save (the columns are {@code updatable=false}).
   */
  private static void stampBreakdownIfPresent(Sale sale, RecordSaleCommand command) {
    PriceBreakdown breakdown = command.breakdown();
    if (breakdown == null) {
      return;
    }
    long loyaltyMinor =
        command.loyaltyRedeemedMinor() != null ? command.loyaltyRedeemedMinor() : 0L;
    sale.stampBreakdown(
        breakdown.subtotal().amountMinor(),
        // PROMO-ONLY discount: strip the loyalty term (carried separately below), exactly as the
        // SaleRecorded wire decomposes it.
        breakdown.discount().amountMinor() - loyaltyMinor,
        breakdown.serviceCharge().amountMinor(),
        breakdown.tax().amountMinor(),
        loyaltyMinor,
        breakdown.usesIllustrativeRules());
  }

  /**
   * Resolves the seller id for a sale (ADR 0049 P2/P4), in priority order:
   *
   * <ol>
   *   <li>{@code command.soldByUserId()} — the RING-TIME operator carried async (ADR 0049 P4) by
   *       {@code PaymentCaptureWriter#capture} from the payment row {@code
   *       payment.service.PaymentWriter#recordPendingDigitalInCurrentTx} stamped (and
   *       tenant/outlet-validated) at checkout. When present it is AUTHORITATIVE and wins: the
   *       commission credit follows whoever RANG the sale, never whoever holds a live session at
   *       CAPTURE time — so a shift-change cashier who merely clicks "mark as paid" can never take
   *       the ringer's credit (code-review follow-up). {@code null} for every non-capture caller
   *       (the legacy {@code POST /api/v1/sales}, {@code OrderWriter}/{@code BillWriter}'s CASH
   *       paths never set it).
   *   <li>Else a LIVE verified operator session ({@code operator}, read at the top of {@link
   *       #create}/{@link #recordInCurrentTx} via {@code OperatorContextProvider}) — the
   *       PIN-identified cashier ringing THIS request directly (the direct/checkout paths, where
   *       there is no async-carried ring-time seller).
   *   <li>Else {@code null} — today's exact pre-ADR-0049 behaviour: {@link #emitSalesMetric} falls
   *       back to the bound actor and {@link #stampSeller} leaves {@code sold_by_user_id} unset.
   * </ol>
   */
  private static String resolveSeller(
      Optional<OperatorPrincipal> operator, RecordSaleCommand command) {
    if (command.soldByUserId() != null) {
      return command.soldByUserId();
    }
    return operator.map(OperatorPrincipal::operatorUserId).orElse(null);
  }

  /**
   * Emits one {@code MetricPublished} ({@code sales_amount} @ employee grain) for a sale, in the
   * caller's transaction (rule 3).
   *
   * <p><strong>ADR 0049 P2/P4 seller attribution.</strong> When {@code resolvedSellerId} is
   * non-null (a PIN-identified cashier's live operator session, ADR 0049 P2; or the ring-time
   * operator carried async through a digital-tender capture, ADR 0049 P4), the metric {@code
   * subject} is THAT id — the load-bearing commission-correctness change, since it may differ from
   * the device actor once outlet credentials exist (P3). When {@code resolvedSellerId} is {@code
   * null} (today's norm off a device — no PIN operators exist yet in the field, or an ordinary
   * {@code actor_type=user} login rang directly), behaviour is BYTE-IDENTICAL to pre-ADR-0049: the
   * bound actor (the JWT sub, also on {@code sale.created_by}), skipped when the actor is NOT a
   * UUID — {@code metric_input.subject_id} is a UUID column, so a non-UUID actor (the header-trust
   * dev recipe's fixed actor) cannot key a metric row — emitting one would fail the consumer
   * decode. This is a documented dev-mode caveat; real logins always carry a UUID sub. The
   * resolved-seller path carries no such fallback for a malformed id — a non-UUID id is skipped
   * (never emitted as garbage), symmetrically with the actor path.
   */
  private void emitSalesMetric(Sale saved, String companyId, String resolvedSellerId) {
    String subjectId;
    if (resolvedSellerId != null) {
      // A resolved seller id is always expected to be the employee's linked Keycloak sub (a UUID)
      // — but guard it symmetrically with the actor path (code review S1): metric_input.subject_id
      // is a UUID column, so a non-UUID would be a poison MetricPublished the payroll consumer
      // cannot decode. Skip rather than emit garbage (a corrupt id is a data fault, not a per-sale
      // error).
      try {
        subjectId = UUID.fromString(resolvedSellerId).toString();
      } catch (IllegalArgumentException e) {
        log.warn(
            "Skipping sales_amount metric — resolved seller '{}' is not a UUID", resolvedSellerId);
        return;
      }
    } else {
      String actor = TenantContext.require().actor();
      UUID subject;
      try {
        subject = UUID.fromString(actor);
      } catch (IllegalArgumentException e) {
        log.debug(
            "Skipping sales_amount metric — actor '{}' is not a UUID sub (dev recipe)", actor);
        return;
      }
      subjectId = subject.toString();
    }
    String period = saved.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
    GenericRecord metric =
        MetricPublishedSchema.toRecord(
            RestaurantMetricContract.SALES_AMOUNT,
            period,
            RestaurantMetricContract.EMPLOYEE_GRAIN,
            subjectId,
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
   * Stamps {@code sale.sold_by_user_id} from the resolved seller id, if any (ADR 0049 P2/P4). No-op
   * when {@code resolvedSellerId} is {@code null}.
   *
   * <p>Validation is applied to whichever principal actually SUPPLIED {@code resolvedSellerId}:
   *
   * <ul>
   *   <li>When the resolved seller is the LIVE operator session (the direct/checkout ring path —
   *       {@code resolvedSellerId} equals {@code operator.operatorUserId()}), its token's {@code
   *       companyId}/{@code businessId} must match the bound tenant / this sale's own outlet, else
   *       the whole write is rejected ({@link OperatorMismatchException}, 409) — a
   *       stolen/misdirected token must never attribute a sale, and must never silently fall back
   *       to the device actor.
   *   <li>When the resolved seller instead came from {@code command.soldByUserId()} (ADR 0049 P4's
   *       async-carried ring-time operator — the Kafka consumer thread has no live token), it is
   *       trusted as-is: it was ALREADY validated against the tenant/outlet at the moment {@code
   *       PaymentWriter#recordPendingDigitalInCurrentTx} stamped it onto the PENDING payment during
   *       the synchronous checkout (via the same {@link OperatorMismatchException#requireMatch}). A
   *       live operator present on a manual on-request capture is merely the capturer, not the
   *       seller, so it neither wins ({@link #resolveSeller}) nor gates the write here.
   * </ul>
   *
   * @throws OperatorMismatchException if the LIVE operator that supplied the seller id names a
   *     {@code companyId}/{@code businessId} that does not match the bound tenant / this outlet
   */
  private static void stampSeller(
      Sale sale,
      String resolvedSellerId,
      Optional<OperatorPrincipal> operator,
      String companyId,
      UUID businessId) {
    if (resolvedSellerId == null) {
      return;
    }
    if (operator.isPresent() && resolvedSellerId.equals(operator.get().operatorUserId())) {
      OperatorPrincipal principal = operator.get();
      OperatorMismatchException.requireMatch(
          principal.companyId(), principal.businessId(), companyId, businessId);
    }
    sale.stampSeller(resolvedSellerId);
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

  /**
   * The cashier's "today's transactions" list ({@code GET /api/v1/sales}) — a business unit's sales
   * with {@code occurredAt} in {@code [from, to)}, newest first, hard-capped at 200 rows.
   * RLS-scoped automatically; no manual {@code company_id} predicate. Read path: a native-query
   * projection (only the response columns), never {@code SELECT *} of the entity.
   *
   * <p>Outlet-gated (review W1): a cashier may only read the sales of an outlet they are assigned
   * to — the same {@link OutletAccessGuard} policy the sale-recording writers and the register
   * reads use (owner/manager bypass, grandfathered tenants allow). Without it a cashier could read
   * a sibling outlet's transactions cross-outlet (RLS only scopes by company); this also backs the
   * owner/manager past-day drill-down over a closed session's window.
   */
  @Transactional(readOnly = true)
  public List<SaleHistoryResponse> findHistory(UUID businessId, Instant from, Instant to) {
    outletAccessGuard.enforce(businessId);
    return repository.findHistory(businessId, from, to).stream()
        .map(SaleWriter::toHistoryResponse)
        .toList();
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

  /** Maps a read projection to the response shape (currency CHAR(3) is right-padded — strip it). */
  private static SaleHistoryResponse toHistoryResponse(SaleHistoryView view) {
    return new SaleHistoryResponse(
        view.getId(),
        view.getOrderId(),
        view.getOccurredAt(),
        view.getAmountMinor(),
        view.getCurrency().strip(),
        view.getTenderType(),
        view.getChannelCode());
  }
}
