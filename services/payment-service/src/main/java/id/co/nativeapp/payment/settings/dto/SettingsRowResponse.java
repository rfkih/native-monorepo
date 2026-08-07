package id.co.nativeapp.payment.settings.dto;

import java.util.UUID;

/**
 * One settings scope in the owner list (ADR 0045). Credential exposure is exactly {@code
 * serverKeyLast4} + the {@code connected} flag — the key itself has no field here by construction
 * (rule 6).
 */
public record SettingsRowResponse(
    UUID id,
    UUID outletId,
    String mode,
    boolean hasStaticImage,
    Integer staticQrByteSize,
    String staticQrSha256,
    GatewayInfoResponse gateway) {

  /** The company-default row's gateway configuration, or {@code null} when none is set. */
  public record GatewayInfoResponse(
      String provider, String environment, String serverKeyLast4, boolean connected) {}
}
