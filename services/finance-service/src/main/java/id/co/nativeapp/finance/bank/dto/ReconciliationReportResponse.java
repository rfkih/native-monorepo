package id.co.nativeapp.finance.bank.dto;

import java.util.List;
import java.util.UUID;

/**
 * The reconciliation report for one bank account: the sub-ledger's bank balance (Σ {@code
 * amount_minor} of its RECONCILED lines), the tenant's {@code CASH_CLEARING} (1900) GL balance
 * (cash-in-transit awaiting sweep — company-wide, not per-account), and the count/list of
 * UNRECONCILED lines still awaiting action.
 */
public record ReconciliationReportResponse(
    UUID bankAccountId,
    String currency,
    long bankBalanceMinor,
    long cashClearingBalanceMinor,
    long unreconciledCount,
    List<StatementLineResponse> unreconciledLines) {}
