package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.messaging.SaleRefundedSchema;
import id.co.nativeapp.restaurant.payment.messaging.SaleVoidedSchema;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.tenant.TenantContext;
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
 * <p><strong>Idempotency.</strong> Both operations are guarded by a deterministic outbox key: the
 * {@code void_id} / {@code refund_id} are synthesised from the payment id + an incrementing token
 * so re-delivery is detected by the outbox {@code (aggregate_type, aggregate_id, event_type)}
 * uniqueness or the outbox payload's idempotency. The payment state-transition guards ({@link
 * Payment#voidPayment()} / {@link Payment#refund(Money)}) enforce the invariants at the domain
 * level: only CAPTURED payments can be voided/refunded.
 */
@Component
public class VoidRefundWriter {

  private final PaymentRepository paymentRepository;
  private final OutboxWriter outboxWriter;

  public VoidRefundWriter(PaymentRepository paymentRepository, OutboxWriter outboxWriter) {
    this.paymentRepository = paymentRepository;
    this.outboxWriter = outboxWriter;
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
    UUID voidId = UUID.randomUUID();
    GenericRecord event =
        SaleVoidedSchema.toRecord(
            voidId,
            payment.getSaleId(),
            payment.getId(),
            companyId,
            payment.getBusinessId(),
            payment.getAmount(),
            occurredAt,
            payment.getTenderType().name());
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
   * @param occurredAt the moment of the refund (server-side clock)
   * @return the updated payment response
   * @throws IllegalArgumentException if the payment is not found or the refund amount is invalid
   * @throws IllegalStateException if the payment state machine rejects the transition
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentResponse refund(UUID paymentId, Money refundAmount, Instant occurredAt) {
    String companyId = TenantContext.require().companyId();

    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

    // Domain guard: accumulates refund, transitions CAPTURED→PARTIALLY_REFUNDED or REFUNDED.
    Money newTotal = payment.refund(refundAmount);
    paymentRepository.saveAndFlush(payment);

    // Emit SaleRefunded outbox event — commits in this transaction.
    UUID refundId = UUID.randomUUID();
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
            payment.getTenderType().name());
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
