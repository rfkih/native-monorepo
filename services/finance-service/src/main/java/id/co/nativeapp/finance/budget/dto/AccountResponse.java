package id.co.nativeapp.finance.budget.dto;

/**
 * One chart-of-account account (Phase 5) — the budget create-form picker options. Global ref data.
 */
public record AccountResponse(String accountCode, String name, String accountType) {}
