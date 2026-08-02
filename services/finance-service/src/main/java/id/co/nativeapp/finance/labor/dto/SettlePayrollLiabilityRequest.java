package id.co.nativeapp.finance.labor.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * {@code POST /api/v1/payroll-liabilities/{runLedgerId}/settlements} request body (ADR 0032, Track
 * P phase P5). The {@code Idempotency-Key} header is required separately (the AR/assets idiom);
 * this body carries only WHICH bucket to settle — the amount is NEVER client-supplied, it is always
 * read back from the run's own liability {@code journal_entry} (see {@code
 * PayrollSettlementWriter}).
 *
 * @param kind one of {@code NET_WAGES}/{@code PPH21}/{@code BPJS_KES}/{@code BPJS_TK}/{@code OTHER}
 */
public record SettlePayrollLiabilityRequest(
    @NotBlank @Pattern(
            regexp = "NET_WAGES|PPH21|BPJS_KES|BPJS_TK|OTHER",
            message = "kind must be one of NET_WAGES, PPH21, BPJS_KES, BPJS_TK, OTHER")
        String kind) {}
