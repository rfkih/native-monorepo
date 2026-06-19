package id.co.nativeapp.finance.statements.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Income Statement (Laba Rugi) response — period-scoped, derived from the double-entry GL.
 *
 * <p>All monetary amounts are in minor units of the statement's base currency (HR-8 — no float).
 * The statement groups accounts into REVENUE lines and EXPENSE lines; the net is derived: {@code
 * netMinor = totalRevenueMinor - totalExpenseMinor}.
 *
 * <p>{@code usesIllustrativeRules} — when {@code true}, at least one journal entry in the period
 * used illustrative (placeholder) rules (e.g. illustrative payroll seeds). The dashboard should
 * badge this via an i18n key (rule 9); the flag is carried through from the GL trial balance.
 *
 * <p>The precise SAK-EMKM presentation grouping and comparative columns are SME-gated and deferred.
 * This response carries structured line items; the {@code usesIllustrativeRules} flag is the only
 * provisional-data signal.
 *
 * @param period accounting period {@code YYYY-MM}
 * @param currency ISO-4217 base currency code
 * @param revenueLines the REVENUE account lines (credit-normal; net = credit − debit)
 * @param expenseLines the EXPENSE account lines (debit-normal; net = debit − credit)
 * @param totalRevenueMinor sum of all revenue lines' net amounts in minor units
 * @param totalExpenseMinor sum of all expense lines' net amounts in minor units
 * @param netMinor net profit (loss) = totalRevenueMinor − totalExpenseMinor in minor units
 * @param usesIllustrativeRules whether any GL line in the period used illustrative-placeholder
 *     rules
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IncomeStatementResponse(
    String period,
    String currency,
    List<IncomeLineItem> revenueLines,
    List<IncomeLineItem> expenseLines,
    long totalRevenueMinor,
    long totalExpenseMinor,
    long netMinor,
    boolean usesIllustrativeRules) {}
