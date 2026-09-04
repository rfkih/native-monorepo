package id.co.nativeapp.finance.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to activate perpetual inventory accounting for the bound company (ADR 0067 §5, Phase D4)
 * — OWNER-ONLY (gateway-enforced). {@code cutoverPeriod} is the {@code YYYY-MM} the company's
 * perpetual accounting starts from; {@code openingInventoryValueMinor} is the counted on-hand
 * inventory value AS OF that cutover, in minor units (never a float — rule 8; zero is valid — a
 * brand-new company with nothing on hand yet still activates, just without an opening journal);
 * {@code currency} is the ISO-4217 code the opening value is expressed in.
 */
public record ActivatePerpetualInventoryRequest(
    @NotBlank @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "must be YYYY-MM")
        String cutoverPeriod,
    @Min(0) long openingInventoryValueMinor,
    @NotBlank @Size(min = 3, max = 3) String currency) {}
