package id.co.nativeapp.finance.mapping;

/**
 * The outcome of resolving a posting criterion to a {@code chart_of_account} account via the
 * effective-dated {@code mapping_rule}.
 *
 * @param accountCode the resolved {@code chart_of_account.account_code} the posting is stamped with
 * @param mapped {@code true} if a real {@code mapping_rule} matched the criterion; {@code false} if
 *     no rule matched and resolution fell back to the SUSPENSE account (the money is still posted
 *     to the books — never dropped — but quarantined for an operator to reclassify). The expense
 *     consumer uses this to log/observe an unmapped hint without dropping the posting.
 */
public record GlAccountResolution(String accountCode, boolean mapped) {}
