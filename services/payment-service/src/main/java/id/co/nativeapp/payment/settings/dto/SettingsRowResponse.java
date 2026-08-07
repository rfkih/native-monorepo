package id.co.nativeapp.payment.settings.dto;

import java.util.UUID;

/**
 * One settings scope in the owner list (ADR 0045). {@code unitId} is {@code null} for the company
 * default row, or an outlet/division org-unit id (ADR 0045 amendment, V4) for an override row — the
 * console distinguishes divisions from outlets itself, from the org tree it already holds.
 * Credential exposure is exactly {@code serverKeyLast4} + the {@code connected} flag — the key
 * itself has no field here by construction (rule 6).
 */
public record SettingsRowResponse(
    UUID id,
    UUID unitId,
    String mode,
    boolean hasStaticImage,
    Integer staticQrByteSize,
    String staticQrSha256,
    GatewayInfoResponse gateway) {

  /** The company-default row's gateway configuration, or {@code null} when none is set. */
  public record GatewayInfoResponse(
      String provider, String environment, String serverKeyLast4, boolean connected) {}
}
