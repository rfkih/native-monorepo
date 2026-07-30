package id.co.nativeapp.finance.statements.dto;

/**
 * One line of the Cash Flow Statement (Phase 5) — a non-cash account's contribution to the change
 * in cash for the period, in <em>cash-impact</em> terms: {@code amountMinor} is {@code credit −
 * debit} for the account (positive = the movement <em>added</em> cash, e.g. AP rose or AR fell;
 * negative = the movement <em>used</em> cash, e.g. AR rose). Minor units — never a float.
 */
public record CashFlowLineItem(String accountCode, String accountType, long amountMinor) {}
