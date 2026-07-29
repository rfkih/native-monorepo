package id.co.nativeapp.finance.budget.service;

/**
 * A budget line to create (Phase 5): an account and its planned amount in minor units. The
 * service-layer input the controller maps request DTOs onto (mirrors {@code BillLineInput}).
 */
public record BudgetLineInput(String accountCode, long amountMinor) {}
