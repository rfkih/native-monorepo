package id.co.nativeapp.finance.withinclose.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The {@code POST /api/v1/closes} request body (P3d SEAM 4a) — the period to close, plus an
 * OPTIONAL base-currency cross-check.
 *
 * <p><strong>{@code baseCurrency} is NOT authoritative.</strong> Per CLAUDE.md the company's base
 * (functional) currency is set at company creation and is immutable once transactions exist; the
 * close DERIVES it from the company's ledger (the currency of its postings for the period). A
 * body-supplied {@code baseCurrency} is only an OPTIONAL cross-check the close compares against the
 * ledger-derived currency — a mismatch FAILS LOUD ({@code 422}), it never overrides the books. It
 * is therefore nullable; when present it must still be a well-formed ISO-4217 code. The {@code
 * company_id} is NEVER in the body — it is the bound tenant (rule 5).
 *
 * @param period the accounting period {@code YYYY-MM} (an impossible month is rejected 400)
 * @param baseCurrency an OPTIONAL ISO-4217 cross-check currency (nullable); the ledger is the
 *     source of truth
 */
public record WithinCompanyCloseRequest(
    @NotBlank @Pattern(
            regexp = "\\d{4}-(0[1-9]|1[0-2])",
            message = "period must be a valid YYYY-MM month")
        String period,
    @Pattern(regexp = "[A-Z]{3}", message = "baseCurrency must be a 3-letter ISO-4217 code") String baseCurrency) {}
