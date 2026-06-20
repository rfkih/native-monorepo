package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.restaurant.order.domain.Order;
import id.co.nativeapp.restaurant.order.repository.OrderRepository;
import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.service.SaleWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that captures a PENDING digital payment,
 * records the Sale, emits {@code SaleRecorded}, and links the order — atomically (ADR 0006).
 *
 * <p>A distinct bean from {@link PaymentCaptureService} so the method is invoked through the Spring
 * proxy: the {@code @Transactional} advice and the {@code RlsAutoApplyAspect} both engage (same
 * pattern as {@code SaleWriter}/{@code OrderWriter}).
 *
 * <p><strong>Atomicity.</strong> All of the following commit (or all roll back) in ONE transaction:
 * the {@link Payment#capture(UUID, Instant)} state transition, the {@link
 * SaleWriter#recordInCurrentTx} call that writes the {@code sale} row + {@code SaleRecorded} outbox
 * row, and the {@link Order#linkSale(UUID)} update that moves the order to {@code COMPLETED}.
 *
 * <p><strong>Idempotency.</strong> The capture is idempotent: if the payment is already {@link
 * Payment.Status#CAPTURED} (re-delivery), the existing {@code sale_id} is used to look up the sale
 * without writing a second one — enforced by the {@code UNIQUE (company_id, idempotency_key)} on
 * the {@code sale} table (via {@link SaleWriter#recordInCurrentTx}'s fast path) and by the {@link
 * Payment.Status#PENDING} guard on {@link Payment#capture(UUID, Instant)} which would throw if
 * capture is attempted again on a CAPTURED payment.
 */
@Component
public class PaymentCaptureWriter {

  private final PaymentRepository paymentRepository;
  private final OrderRepository orderRepository;
  private final SaleWriter saleWriter;

  public PaymentCaptureWriter(
      PaymentRepository paymentRepository, OrderRepository orderRepository, SaleWriter saleWriter) {
    this.paymentRepository = paymentRepository;
    this.orderRepository = orderRepository;
    this.saleWriter = saleWriter;
  }

  /**
   * Captures a {@link Payment.Status#PENDING} digital payment: records the Sale, emits {@code
   * SaleRecorded}, transitions the payment to {@link Payment.Status#CAPTURED}, and links the order.
   * Runs in {@code REQUIRES_NEW} so it has its own transaction boundary (same pattern as {@link
   * SaleWriter#create}).
   *
   * @param paymentId the payment to capture (must be PENDING and belong to the current tenant)
   * @param capturedAt the moment of capture (server-side clock)
   * @return the captured payment response
   * @throws IllegalArgumentException if the payment is not found, not PENDING, or is not a digital
   *     tender
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentResponse capture(UUID paymentId, Instant capturedAt) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();

    // Load the full aggregate (write path).
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

    if (!payment.getTenderType().isDigital()) {
      throw new IllegalArgumentException(
          "Only digital payments can be captured via this endpoint; tender="
              + payment.getTenderType());
    }

    if (payment.getStatus() == Payment.Status.CAPTURED) {
      // Idempotent re-delivery: return the existing state without side effects.
      return PaymentResponse.from(payment);
    }

    // Payment.capture() enforces the PENDING guard — throws if already VOIDED/REFUNDED/etc.
    // The sale idempotency key is the original checkout idempotency_key + ":sale" so a
    // re-delivered capture finds the same sale without writing a second SaleRecorded.
    String saleIdempotencyKey = payment.getIdempotencyKey().replace(":pay", "") + ":capture-sale";

    // Record the sale and emit SaleRecorded in THIS transaction (MANDATORY joins us).
    RecordSaleCommand saleCommand =
        new RecordSaleCommand(
            payment.getBusinessId(),
            payment.getAmount().amountMinor(),
            payment.getAmount().currency().getCurrencyCode(),
            capturedAt,
            saleIdempotencyKey,
            payment.getTenderType().name()); // tender_type for GL routing (ADR 0006 slice 2)
    RecordSaleResult saleResult = saleWriter.recordInCurrentTx(saleCommand);

    // Capture the payment aggregate: PENDING → CAPTURED, sets sale_id + captured_at.
    payment.capture(saleResult.sale().id(), capturedAt);
    paymentRepository.saveAndFlush(payment);

    // Link the sale to the order and move it to COMPLETED.
    Order order =
        orderRepository
            .findById(payment.getOrderId())
            .orElseThrow(
                () -> new IllegalArgumentException("Order not found for payment: " + paymentId));
    order.linkSale(saleResult.sale().id());
    orderRepository.saveAndFlush(order);

    return PaymentResponse.from(payment);
  }
}
