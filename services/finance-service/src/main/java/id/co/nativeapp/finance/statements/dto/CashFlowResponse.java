package id.co.nativeapp.finance.statements.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The Cash Flow Statement (Arus Kas) for a period (Phase 5, ADR 0019) — the indirect method, derived
 * entirely from the GL trial balance for the period. Net cash from operating starts at {@code
 * netIncomeMinor} and adjusts for the working-capital movements ({@code operatingLines}); investing
 * and financing are structured for later (fixed assets → investing in Phase 6; no financing accounts
 * seeded yet). All amounts are minor units of the single {@code currency}.
 *
 * <p><strong>Reconciliation.</strong> By double-entry, {@code netChangeInCashMinor} equals {@code
 * cashMovementMinor} (the actual net movement of the cash &amp; cash-equivalent accounts in the
 * period) exactly — {@code reconciled} confirms it; the reader asserts it (a mismatch is an internal
 * classification bug, not a client condition).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CashFlowResponse(
    String period,
    String currency,
    long netIncomeMinor,
    List<CashFlowLineItem> operatingLines,
    long cashFromOperatingMinor,
    List<CashFlowLineItem> investingLines,
    long cashFromInvestingMinor,
    List<CashFlowLineItem> financingLines,
    long cashFromFinancingMinor,
    long netChangeInCashMinor,
    long cashMovementMinor,
    boolean reconciled,
    boolean usesIllustrativeRules) {}
