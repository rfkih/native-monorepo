package id.co.nativeapp.barbershop.payment.projection;

import java.util.UUID;

/**
 * Read-path projection for the {@code barbershop_payment} aggregate (native-query, CODE-STRUCTURE
 * §3.3). Selected by column alias from {@link
 * id.co.nativeapp.barbershop.payment.repository.BarbershopPaymentRepository
 * BarbershopPaymentRepository}; only the columns needed for the ticket response / capture status
 * check are fetched — never {@code SELECT *} of the full {@link
 * id.co.nativeapp.barbershop.payment.domain.BarbershopPayment BarbershopPayment} entity.
 *
 * <p>Money is returned as {@code amountMinor} (long, integer minor units) + {@code currency}
 * (ISO-4217, {@code CHAR(3)} — strip trailing spaces at the call site). Never a float (rule 8).
 */
public interface BarbershopPaymentView {

  UUID getId();

  UUID getTicketId();

  UUID getBusinessId();

  String getTenderType();

  String getStatus();

  long getAmountMinor();

  String getCurrency();

  Long getTenderedMinor();

  Long getChangeMinor();

  String getProviderRef();

  boolean isProviderPending();
}
