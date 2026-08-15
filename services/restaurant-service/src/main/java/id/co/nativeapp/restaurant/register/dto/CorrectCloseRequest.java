package id.co.nativeapp.restaurant.register.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * A manager/owner CASH-count correction of an already-CLOSED register session (ADR 0064): the
 * corrected physical whole-drawer count and a REQUIRED reason for the audit trail. Money is integer
 * minor units of the session currency (rule 8). No idempotency key — correcting to the value
 * already recorded is a server-side no-op.
 *
 * @param countedCashMinor the corrected physical whole-drawer count (≥ 0, includes the float)
 * @param reason the manager/owner's reason for the correction (recorded for audit)
 */
public record CorrectCloseRequest(
    @NotNull @PositiveOrZero Long countedCashMinor, @NotBlank @Size(max = 500) String reason) {}
