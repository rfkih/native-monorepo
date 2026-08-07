package id.co.nativeapp.payment.settings.domain;

/**
 * How a company takes QRIS at the till (ADR 0045). Resolved per outlet: override row → company
 * default row → implicit {@link #MANUAL}.
 *
 * <ul>
 *   <li>{@link #MANUAL} — today's behavior and the implicit default: the cashier shows their own QR
 *       out-of-band and confirms with "Mark as paid".
 *   <li>{@link #STATIC} — the merchant's uploaded static QRIS image is shown on the till (and
 *       customer display); capture stays manual.
 *   <li>{@link #GATEWAY} — dynamic QRIS per transaction via the merchant's own Midtrans account;
 *       capture arrives from the signed webhook.
 * </ul>
 */
public enum QrisMode {
  MANUAL,
  STATIC,
  GATEWAY;

  /**
   * Parses a request-supplied mode string.
   *
   * @throws SettingsValidationException if the value is not a known mode (→ 422)
   */
  public static QrisMode parse(String value) {
    if (value == null || value.isBlank()) {
      throw new SettingsValidationException("mode is required (MANUAL, STATIC or GATEWAY)");
    }
    try {
      return QrisMode.valueOf(value.strip());
    } catch (IllegalArgumentException e) {
      throw new SettingsValidationException("Unknown mode: must be MANUAL, STATIC or GATEWAY");
    }
  }
}
