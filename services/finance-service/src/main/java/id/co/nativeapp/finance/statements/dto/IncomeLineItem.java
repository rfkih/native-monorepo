package id.co.nativeapp.finance.statements.dto;

/**
 * One line in the Income Statement — the net of an account's credits minus debits (for REVENUE, a
 * credit-normal class) or debits minus credits (for EXPENSE, a debit-normal class), expressed in
 * minor units of the statement's base currency. Never a float (HR-8).
 *
 * @param accountCode the GL account code (e.g. {@code "4000-SALES"})
 * @param accountType the account classification: {@code "REVENUE"} or {@code "EXPENSE"}
 * @param netMinor net contribution in minor units: positive when the account is on its normal side
 *     (revenue credit > debit; expense debit > credit); negative otherwise
 * @param currency ISO-4217 currency code
 */
public record IncomeLineItem(
    String accountCode, String accountType, long netMinor, String currency) {}
