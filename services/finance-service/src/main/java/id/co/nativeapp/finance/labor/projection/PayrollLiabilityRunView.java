package id.co.nativeapp.finance.labor.projection;

import java.util.UUID;

/**
 * Read projection for one ACTIVE ({@code liability_state = 'POSTED'}) run's five payroll-liability
 * bucket totals (ADR 0032, Track P phase P5) — backs {@link
 * id.co.nativeapp.finance.labor.repository.PayrollRunLedgerRepository#findLiabilityRunsForPeriod}.
 *
 * <p><strong>Bucket-amount read design (documented per the task).</strong> A bucket amount is NOT
 * stored per-role on {@code payroll_run_ledger} — only the run's SINGLE liability {@code
 * journal_entry_id} is. The five totals here are reconstructed by joining that entry's {@code
 * journal_line} rows back to the {@link id.co.nativeapp.finance.gl.domain.AccountRole} each line's
 * {@code account_code} resolved to AT POSTING TIME: a {@code LATERAL} join against {@code
 * role_account_map} using the SAME resolution rule {@code RoleAccountResolver} used when the entry
 * was built ({@code account_role WHERE occurred_at BETWEEN effective_from AND effective_to ORDER BY
 * version DESC, effective_from DESC LIMIT 1}), keyed on the journal entry's OWN {@code occurred_at}
 * (not "now") — so a later {@code role_account_map} change can never misattribute a historical
 * line. The signed bucket total is {@code credit_minor - debit_minor} summed per role: a positive
 * bucket was posted as a credit leg, a negative bucket (the December Art-17 refund case) as a debit
 * leg of its absolute value (ADR 0032 §3) — the subtraction reconstructs the ORIGINAL signed amount
 * exactly. The {@code LABOR_CLEARING} (6900) line never matches one of the five bucket roles, so it
 * contributes nothing to any bucket total.
 */
public interface PayrollLiabilityRunView {

  UUID getRunLedgerId();

  UUID getPayrollRunId();

  String getPeriod();

  int getRunSeq();

  String getRunType();

  String getLiabilityState();

  String getCurrency();

  long getNetWagesMinor();

  long getPph21Minor();

  long getBpjsKesMinor();

  long getBpjsTkMinor();

  long getOtherMinor();
}
