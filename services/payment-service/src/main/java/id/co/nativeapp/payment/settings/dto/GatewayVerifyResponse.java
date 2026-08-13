package id.co.nativeapp.payment.settings.dto;

/**
 * The outcome of a {@link GatewayVerifyRequest} (ADR 0045 amendment). {@code result} is one of
 * {@code VALID} (the key authenticated), {@code INVALID} (the provider rejected it — wrong key or a
 * key for the other environment), or {@code UNREACHABLE} (the provider could not be reached — try
 * again). Availability only — never any key material.
 */
public record GatewayVerifyResponse(String result) {}
