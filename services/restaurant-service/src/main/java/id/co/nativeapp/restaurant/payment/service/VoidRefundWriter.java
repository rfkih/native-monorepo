package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.domain.PaymentRefund;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.messaging.SaleRefundedSchema;
import id.co.nativeapp.restaurant.payment.messaging.SaleVoidedSchema;
import id.co.nativeapp.restaurant.payment.repository.PaymentRefundRepository;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.restaurant.register.service.CashWindowLock;
import id.co.nativeapp.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for void and refund operations (ADR 0006, slice 4).
 *
 * <p>A distinct bean from {@link VoidRefundService} so each method is invoked through the Spring
 * proxy: the {@code @Transactional} advice and the {@code RlsAutoApplyAspect} both engage (same
 * pattern as {@code SaleWriter}/{@code PaymentCaptureWriter}).
 *
 * <p><strong>Atomicity.</strong> Each operation commits the payment state-transition, the outbox
 * event row, and any aggregate updates in ONE transaction. A rollback clears all side effects.
 *
 * <p><strong>Idempotency.</strong> Both operations emit a DETERMINISTIC event id so a retry of the
 * same logical void or refund derives the same UUID — making the operation idempotent at the outbox
 * UNIQUE key backstop AND at the finance-side {@code processOnce} dedupe (ADR 0006). The {@code
 * void_id} is derived as a type-3 UUID over {@code "<paymentId>:VOID"}. The {@code refund_id} is
 * derived over {@code "<paymentId>:REFUND:<cumulativeTotalMinor>"} so each distinct partial refund
 * produces a distinct id while a retry of the same logical refund (same amounts) collapses to the
 * same id. The payment state-transition guards ({@link Payment#voidPayment()} / {@link
 * Payment#refund(Money)}) also enforce domain-level invariants: only CAPTURED payments can be
 * voided/refunded.
 *
 * <p><strong>CashWindowLock (verified HIGH race fix).</strong> {@link #refund} acquires the
 * per-business {@link CashWindowLock} SHARED ({@link CashWindowLock#acquireForCommit}) as the FIRST
 * lock-acquiring statement — strictly BEFORE it captures {@code occurredAt}, which becomes the
 * append-only {@code payment_refund} row's timestamp (the exact column the register close sums).
 * {@code occurredAt} is captured HERE (inside the lock), no longer passed in from {@link
 * VoidRefundService}, precisely so a refund forced to wait behind a concurrent register close
 * (EXCLUSIVE mode) gets a FRESH post-commit timestamp instead of a stale pre-lock one. {@link
 * #voidPayment} does NOT take this lock — it writes no {@code sale}/{@code payment_refund} row the
 * register close reads. See {@code RegisterSessionWriter} class javadoc for the full contract.
 */
@Component
public class VoidRefundWriter {

  private final PaymentRepository paymentRepository;
  private final PaymentRefundRepository paymentRefundRepository;
  private final OutboxWriter outboxWriter;
  private final CashWindowLock cashWindowLock;

  public VoidRefundWriter(
      PaymentRepository paymentRepository,
      PaymentRefundRepository paymentRefundRepository,
      OutboxWriter outboxWriter,
      CashWindowLock cashWindowLock) {
    this.paymentRepository = paymentRepository;
    this.paymentRefundRepository = paymentRefundRepository;
    this.outboxWriter = outboxWriter;
    this.cashWindowLock = cashWindowLock;
  }

  /**
   * Voids a captured payment — transitions it to {@link Payment.Status#VOIDED} and emits a {@code
   * SaleVoided} outbox event so finance can post the reversal contra entry. Runs in its own {@code
   * REQUIRES_NEW} transaction.
   *
   * @param paymentId the payment to void (must be CAPTURED and visible to the current tenant)
   * @param occurredAt the moment of the void (server-side clock)
   * @return the voided payment response
   * @throws IllegalArgumentException if the payment is not found or not in CAPTURED state
   * @throws IllegalStateException if the payment state machine rejects the transition
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentResponse voidPayment(UUID paymentId, Instant occurredAt) {
    String companyId = TenantContext.require().companyId();

    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

    // Domain guard: throws IllegalStateException if not CAPTURED.
    payment.voidPayment();
    paymentRepository.saveAndFlush(payment);

    // Emit SaleVoided outbox event — commits in this transaction.
    // DETERMINISTIC void id: a name-based UUID (type 3, MD5) over "<paymentId>:VOID".
    // A retry of the same logical void derives the same voidId, so the outbox UNIQUE key and
    // the finance-side processOnce dedupe are idempotent across retries (ADR 0006 / H2).
    UUID voidId =
        UUID.nameUUIDFromBytes((payment.getId() + ":VOID").getBytes(StandardCharsets.UTF_8));
    GenericRecord event =
        SaleVoidedSchema.toRecord(
            voidId,
            payment.getSaleId(),
            payment.getId(),
            companyId,
            payment.getBusinessId(),
            payment.getAmount(),
            occurredAt,
            payment.getTenderType().name(),
            payment.getChannelCode());
    byte[] payload = AvroSerde.serialize(event);
    outboxWriter.write(
        SaleVoidedSchema.AGGREGATE_TYPE,
        payment.getSaleId().toString(),
        SaleVoidedSchema.EVENT_TYPE,
        payload,
        null,
        UUID.fromString(companyId),
        occurredAt);

    return PaymentResponse.from(payment);
  }

  /**
   * Refunds part or all of a captured payment — accumulates the refund on the payment aggregate
   * (via {@link Payment#refund(Money)}), transitions to {@link Payment.Status#REFUNDED} or {@link
   * Payment.Status#PARTIALLY_REFUNDED}, and emits a {@code SaleRefunded} outbox event. Runs in its
   * own {@code REQUIRES_NEW} transaction.
   *
   * @param paymentId the payment to refund (must be CAPTURED or PARTIALLY_REFUNDED)
   * @param refundAmount the amount to refund (must be positive and must not exceed the remaining
   *     refundable amount)
   * @return the updated payment response
   * @throws IllegalArgumentException if the payment is not found or the refund amount is invalid
   * @throws IllegalStateException if the payment state machine rejects the transition
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentResponse refund(UUID paymentId, Money refundAmount) {
    String companyId = TenantContext.require().companyId();

    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

    // ADR 0036 review W4: an ONLINE payment refunds ALL-OR-NOTHING. Finance rejects partial
    // refunds (PartialRefundNotSupportedException → DLT), so a partial ONLINE refund would leave
    // the per-channel platform receivable permanently overstated — restaurant and finance would
    // silently diverge. Reject at the edge instead; the platform's own ledger settles per order.
    if (payment.getTenderType() == TenderType.ONLINE
        && refundAmount.amountMinor() != payment.getAmount().amountMinor()) {
      throw new IllegalArgumentException(
          "an ONLINE payment can only be refunded in full ("
              + payment.getAmount().amountMinor()
              + " minor units) — partial platform refunds are not supported");
    }

    // CashWindowLock (verified HIGH race fix) — SHARED, FIRST lock-acquiring statement, strictly
    // BEFORE occurredAt is captured below (which becomes the payment_refund row's timestamp, the
    // exact column the register close sums). See RegisterSessionWriter class javadoc for the
    // contract.
    cashWindowLock.acquireForCommit(payment.getBusinessId());
    Instant occurredAt = Instant.now();

    // Domain guard: accumulates refund, transitions CAPTURED→PARTIALLY_REFUNDED or REFUNDED.
    Money newTotal = payment.refund(refundAmount);
    paymentRepository.saveAndFlush(payment);

    // Append-only refund-event ledger (V22, ADR 0036 review C3): the per-refund DELTA at its own
    // timestamp — the register close attributes cash refunds to session windows exactly.
    PaymentRefund refundEvent = new PaymentRefund(payment, refundAmount, occurredAt);
    refundEvent.setCompanyId(companyId);
    paymentRefundRepository.save(refundEvent);

    // Emit SaleRefunded outbox event — commits in this transaction.
    // DETERMINISTIC refund id: a name-based UUID (type 3, MD5) over
    // "<paymentId>:REFUND:<newTotal>". The cumulative total (newTotal.amountMinor()) is the
    // authoritative token that distinguishes each distinct partial refund (a second partial
    // refund to a different total produces a different id), while a retry of the SAME logical
    // refund (same amounts → same total) derives the same refundId — idempotent via processOnce
    // and the finance-side source_event_id UNIQUE backstop (ADR 0006 / H2).
    UUID refundId =
        UUID.nameUUIDFromBytes(
            (payment.getId() + ":REFUND:" + newTotal.amountMinor())
                .getBytes(StandardCharsets.UTF_8));
    GenericRecord event =
        SaleRefundedSchema.toRecord(
            refundId,
            payment.getSaleId(),
            payment.getId(),
            companyId,
            payment.getBusinessId(),
            refundAmount,
            newTotal.amountMinor(),
            occurredAt,
            payment.getTenderType().name(),
            payment.getChannelCode());
    byte[] payload = AvroSerde.serialize(event);
    outboxWriter.write(
        SaleRefundedSchema.AGGREGATE_TYPE,
        payment.getSaleId().toString(),
        SaleRefundedSchema.EVENT_TYPE,
        payload,
        null,
        UUID.fromString(companyId),
        occurredAt);

    return PaymentResponse.from(payment);
  }
}
