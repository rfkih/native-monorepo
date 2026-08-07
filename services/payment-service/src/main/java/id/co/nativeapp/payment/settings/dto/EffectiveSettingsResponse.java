package id.co.nativeapp.payment.settings.dto;

/**
 * What the till needs at tender time (ADR 0045, DIVISION-scope amendment), resolved outlet override
 * → division override → company default → implicit MANUAL. {@code staticQrAvailable} falls back
 * per-facet: an outlet (or division) override without its own image still reports availability
 * {@code true} when ANY row in the chain carries one. {@code gateway} reflects the COMPANY default
 * row (credentials are company-level); {@code null} when no provider is configured. The console
 * fails open to MANUAL whenever a mode's prerequisite here is false.
 */
public record EffectiveSettingsResponse(
    String mode, boolean staticQrAvailable, EffectiveGatewayResponse gateway) {

  /** Gateway availability as the till sees it — never any key material. */
  public record EffectiveGatewayResponse(String provider, String environment, boolean connected) {}
}
