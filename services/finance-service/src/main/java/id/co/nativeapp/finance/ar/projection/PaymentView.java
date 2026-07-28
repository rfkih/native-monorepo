package id.co.nativeapp.finance.ar.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for one {@code invoice_payment} row of an invoice detail. Reached only from the
 * service + repository layers.
 */
public interface PaymentView {

  UUID getId();

  long getAmountMinor();

  String getCurrency();

  Instant getPaidAt();

  String getMethod();
}
