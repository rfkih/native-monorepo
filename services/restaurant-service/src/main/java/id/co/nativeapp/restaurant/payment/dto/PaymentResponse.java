package id.co.nativeapp.restaurant.payment.dto;

import id.co.nativeapp.restaurant.payment.domain.Payment;
import java.util.UUID;

/**
 * Response shape for a payment tender. {@code amountMinor}/{@code currency} is the tender amount as
 * Money (rule 8); {@code tenderedMinor}/{@code changeMinor} are cash-only (null for digital);
 * {@code providerPending} flags a digital tender whose real settlement is not yet wired (ADR 0006).
 *
 * <p>Exactly one of {@code orderId}/{@code billId} is set (V38) — {@code orderId} for an
 * order-originated payment, {@code billId} for a bill-originated gateway payment (dynamic QRIS/CARD
 * on a full-bill check).
 */
public record PaymentResponse(
    UUID paymentId,
    UUID orderId,
    UUID billId,
    String tenderType,
    String status,
    long amountMinor,
    String currency,
    Long tenderedMinor,
    Long changeMinor,
    boolean providerPending,
    UUID saleId) {

  /** Maps the write-path aggregate to the response shape. */
  public static PaymentResponse from(Payment payment) {
    return new PaymentResponse(
        payment.getId(),
        payment.getOrderId(),
        payment.getBillId(),
        payment.getTenderType().name(),
        payment.getStatus().name(),
        payment.getAmount().amountMinor(),
        payment.getAmount().currency().getCurrencyCode(),
        payment.getTenderedMinor(),
        payment.getChangeMinor(),
        payment.isProviderPending(),
        payment.getSaleId());
  }
}
