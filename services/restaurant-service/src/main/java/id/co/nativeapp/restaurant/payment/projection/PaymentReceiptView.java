package id.co.nativeapp.restaurant.payment.projection;

import java.util.UUID;

/**
 * Read-path projection for the order read path's receipt-rebuild need ({@code GET
 * /api/v1/orders/{id}}) — narrower than {@link PaymentView}: only the columns {@code
 * PaymentResponse} needs, not the full bookkeeping-only columns ({@code businessId}, {@code
 * providerRef}, {@code capturedAt}, {@code occurredAt}, {@code idempotencyKey}). {@code
 * refundedMinor} IS included — a reprinted receipt needs to show a partial-refund amount.
 *
 * <p>Selected by column alias from the native query in {@link
 * id.co.nativeapp.restaurant.payment.repository.PaymentRepository PaymentRepository}; no {@code
 * SELECT *} of the full {@link id.co.nativeapp.restaurant.payment.domain.Payment Payment} entity.
 *
 * <p>Money is returned as {@code amountMinor} (long, integer minor units) + {@code currency}
 * (ISO-4217, CHAR(3) — strip trailing spaces in the service layer). Never a float (rule 8).
 */
public interface PaymentReceiptView {

  UUID getId();

  UUID getOrderId();

  String getTenderType();

  String getStatus();

  long getAmountMinor();

  String getCurrency();

  Long getTenderedMinor();

  Long getChangeMinor();

  boolean isProviderPending();

  UUID getSaleId();

  long getRefundedMinor();
}
