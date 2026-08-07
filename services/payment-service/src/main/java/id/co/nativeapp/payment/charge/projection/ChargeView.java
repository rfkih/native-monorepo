package id.co.nativeapp.payment.charge.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model for the till's charge poll (CODE-STRUCTURE §3.3): exactly what the console needs to
 * render/refresh the QR panel. Snake_case aliases map to these camelCase getters.
 */
public interface ChargeView {

  UUID getId();

  String getStatus();

  String getVertical();

  UUID getPaymentId();

  String getQrString();

  String getQrUrl();

  Instant getExpiresAt();

  Long getAmountMinor();

  String getCurrency();
}
