package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates payment void and refund operations (ADR 0006, slice 4).
 *
 * <p>Not itself {@code @Transactional} — transactional units live in {@link VoidRefundWriter}.
 * Follows the {@code SaleService} / {@code OrderService} pattern: the service validates the tenant
 * scope and delegates to the writer bean (via the Spring proxy) so the {@code @Transactional}
 * advice and the RLS aspect engage.
 */
@Service
public class VoidRefundService {

  private final VoidRefundWriter writer;

  public VoidRefundService(VoidRefundWriter writer) {
    this.writer = writer;
  }

  /**
   * Voids a captured payment and emits {@code SaleVoided}. The void transitions the payment from
   * {@link id.co.nativeapp.restaurant.payment.domain.Payment.Status#CAPTURED} to {@link
   * id.co.nativeapp.restaurant.payment.domain.Payment.Status#VOIDED}.
   *
   * @param paymentId the payment to void
   * @return the voided payment response
   * @throws IllegalArgumentException if the payment is not found
   * @throws IllegalStateException if the payment is not CAPTURED (domain guard)
   */
  public PaymentResponse voidPayment(UUID paymentId) {
    TenantContext.require();
    return writer.voidPayment(paymentId, Instant.now());
  }

  /**
   * Refunds part or all of a captured payment and emits {@code SaleRefunded}. The refund amount
   * must be positive and must not exceed the remaining refundable balance.
   *
   * @param paymentId the payment to refund
   * @param refundAmount the refund amount (integer minor units + ISO-4217; never a float)
   * @return the updated payment response (PARTIALLY_REFUNDED or REFUNDED)
   * @throws IllegalArgumentException if the payment is not found or the amount is invalid
   */
  public PaymentResponse refund(UUID paymentId, Money refundAmount) {
    TenantContext.require();
    // occurredAt is captured INSIDE the writer, after the CashWindowLock (verified HIGH race fix)
    // — see VoidRefundWriter class javadoc.
    return writer.refund(paymentId, refundAmount);
  }
}
