package id.co.nativeapp.loyalty.earnrule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.LocalDate;

/**
 * {@code POST /api/v1/loyalty/earn-rules} request body. {@code effectiveFrom} defaults to today
 * when omitted; {@code effectiveTo} defaults to the open-ended sentinel ({@code
 * EarnRule.OPEN_ENDED}).
 */
public record EarnRuleCreateRequest(
    @NotNull @PositiveOrZero Long pointsPerMinorBp,
    @PositiveOrZero Long minSaleMinor,
    @NotBlank String provenance,
    @NotBlank String sourceNote,
    LocalDate effectiveFrom,
    LocalDate effectiveTo) {}
