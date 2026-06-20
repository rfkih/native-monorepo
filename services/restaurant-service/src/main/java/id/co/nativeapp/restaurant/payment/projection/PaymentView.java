package id.co.nativeapp.restaurant.payment.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-path projection for the {@code payment} aggregate (native-query, CODE-STRUCTURE §3.3).
 *
 * <p>Selected by column alias from the native query in {@link
 * id.co.nativeapp.restaurant.payment.repository.PaymentRepository PaymentRepository}; only the
 * columns needed for the receipt / capture response are fetched — no {@code SELECT *} of the full
 * {@link id.co.nativeapp.restaurant.payment.domain.Payment Payment} entity.
 *
 * <p>Money is returned as {@code amountMinor} (long, integer minor units) + {@code currency}
 * (ISO-4217, CHAR(3) — strip trailing spaces in the service layer). Never a float (rule 8).
 */
public interface PaymentView {

  UUID getId();

  UUID getOrderId();

  UUID getBusinessId();

  String getTenderType();

  String getStatus();

  long getAmountMinor();

  String getCurrency();

  Long getTenderedMinor();

  Long getChangeMinor();

  long getRefundedMinor();

  String getProviderRef();

  boolean isProviderPending();

  UUID getSaleId();

  Instant getCapturedAt();

  Instant getOccurredAt();

  String getIdempotencyKey();
}
