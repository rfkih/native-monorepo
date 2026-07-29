package id.co.nativeapp.finance.ap.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for one {@code bill_payment} row of a bill detail. Reached only from the service
 * + repository layers.
 */
public interface PaymentView {

  UUID getId();

  long getAmountMinor();

  String getCurrency();

  Instant getPaidAt();

  String getMethod();
}
