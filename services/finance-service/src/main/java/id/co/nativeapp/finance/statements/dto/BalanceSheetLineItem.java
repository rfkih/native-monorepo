package id.co.nativeapp.finance.statements.dto;

/**
 * One line on the Balance Sheet — the running balance of an account as of the as-of period,
 * expressed in minor units of the statement's base currency. Never a float (HR-8).
 *
 * <p>The balance is the account's net on its normal side: for ASSET/EXPENSE (debit-normal) it is
 * {@code totalDebit − totalCredit}; for LIABILITY/EQUITY/REVENUE (credit-normal) it is {@code
 * totalCredit − totalDebit}. A negative balance means the account is on the abnormal side.
 *
 * @param accountCode the GL account code
 * @param accountType one of {@code "ASSET"}, {@code "LIABILITY"}, {@code "EQUITY"}
 * @param balanceMinor running balance in minor units (positive = normal side)
 * @param currency ISO-4217 currency code
 */
public record BalanceSheetLineItem(
    String accountCode, String accountType, long balanceMinor, String currency) {}
