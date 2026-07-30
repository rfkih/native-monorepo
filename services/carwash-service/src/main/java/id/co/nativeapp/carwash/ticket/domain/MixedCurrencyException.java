package id.co.nativeapp.carwash.ticket.domain;

import java.util.Set;

/**
 * Thrown when a checkout/quote request's lines resolve to catalog items spanning more than one
 * ISO-4217 currency — a single ticket carries exactly one transaction currency (rule 8). Maps to
 * {@code 422 Unprocessable Entity} via {@code config.TicketAdvice}.
 */
public class MixedCurrencyException extends RuntimeException {

  public MixedCurrencyException(Set<String> currencies) {
    super("All lines on a ticket must share the same currency; found: " + currencies);
  }
}
