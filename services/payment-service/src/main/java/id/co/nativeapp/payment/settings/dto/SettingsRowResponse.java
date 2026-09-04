package id.co.nativeapp.payment.settings.dto;

import java.util.UUID;

/**
 * One settings scope in the owner list (ADR 0045). {@code unitId} is {@code null} for the company
 * default row, or an outlet/division org-unit id (ADR 0045 amendment, V4) for an override row — the
 * console distinguishes divisions from outlets itself, from the org tree it already holds.
 * Credential exposure is exactly each environment's {@code serverKeyLast4} + {@code connected} flag
 * — the key itself has no field here by construction (rule 6).
 */
public record SettingsRowResponse(
    UUID id,
    UUID unitId,
    String mode,
    boolean hasStaticImage,
    Integer staticQrByteSize,
    String staticQrSha256,
    GatewayInfoResponse gateway) {

  /**
   * The company-default row's gateway configuration, or {@code null} when no provider is set. Both
   * environment slots are exposed independently (V6): {@code activeEnvironment} is the one the till
   * uses, while {@code sandbox}/{@code production} report each slot's own connection state so the
   * owner can hold Sandbox and Production keys at the same time and switch between them freely.
   *
   * <p>{@code environment}/{@code serverKeyLast4}/{@code connected} are the pre-V6 single-slot
   * fields, RETAINED (never removed/renamed — ENGINEERING-STANDARDS §1.3) as a view of the ACTIVE
   * slot so a stale console bundle mid-rolling-deploy still reads a coherent connection state. New
   * clients read {@code activeEnvironment} + the per-environment slots.
   */
  public record GatewayInfoResponse(
      String provider,
      String activeEnvironment,
      EnvCredentialResponse sandbox,
      EnvCredentialResponse production,
      String environment,
      String serverKeyLast4,
      boolean connected) {

    /** One environment slot's readable trace + whether it holds a server key. */
    public record EnvCredentialResponse(String serverKeyLast4, boolean connected) {}
  }
}
