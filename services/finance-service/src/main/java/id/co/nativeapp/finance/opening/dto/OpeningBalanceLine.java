package id.co.nativeapp.finance.opening.dto;

import id.co.nativeapp.finance.opening.domain.OpeningBalanceSide;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * One line of an opening balance sheet (ADR 0037): a carrying-value amount posted to {@code
 * accountCode} on {@code side}. Money rule 8 — integer minor units, never a float; the currency is
 * the request-level one (a single ISO-4217 code per opening entry). {@code @NotNull @Positive} on
 * the amount rejects a missing/zero amount at the edge (a missing {@code Long} must never
 * deserialize into a silent 0 — the register-close W5 lesson).
 */
public record OpeningBalanceLine(
    @NotBlank @Size(max = 32) String accountCode,
    @NotNull @Positive Long amountMinor,
    @NotNull OpeningBalanceSide side) {}
