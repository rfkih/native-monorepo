package id.co.nativeapp.finance.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Request to record one platform payout (ADR 0036 Phase C): the channel settled {@code grossMinor}
 * of its receivable and paid out {@code netMinor}; the fee is DERIVED ({@code gross − net}), never
 * client-supplied. Money rule 8: integer minor units + ISO-4217 — never a float. Bean validation
 * rejects a missing amount at the edge (a missing {@code Long} must never deserialize into a silent
 * 0 — the register-close W5 lesson).
 */
public record SettlePlatformRequest(
    @NotBlank @Size(max = 32) String channelCode,
    @NotNull @Positive Long grossMinor,
    @NotNull @PositiveOrZero Long netMinor,
    @NotBlank @Size(min = 3, max = 3) String currency) {}
