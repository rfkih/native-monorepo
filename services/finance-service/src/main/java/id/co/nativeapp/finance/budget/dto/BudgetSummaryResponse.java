package id.co.nativeapp.finance.budget.dto;

import java.util.UUID;

/**
 * A budget list row (Phase 5): the header plus a line count and total planned amount (minor units).
 */
public record BudgetSummaryResponse(
    UUID id, String name, String period, String currency, long lineCount, long totalPlannedMinor) {}
