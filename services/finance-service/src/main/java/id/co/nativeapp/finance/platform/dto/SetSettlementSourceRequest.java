package id.co.nativeapp.finance.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Names who pays out a tender family (ADR 0076). {@code MARKETPLACE} is not accepted: a marketplace
 * balance is paid out by its own channel, so configuring it would let the two drift apart.
 */
public record SetSettlementSourceRequest(
    @NotBlank @Pattern(regexp = "QRIS|CARD", message = "sourceKind must be QRIS or CARD") String sourceKind,
    @NotBlank @Size(max = 32) String sourceCode) {}
