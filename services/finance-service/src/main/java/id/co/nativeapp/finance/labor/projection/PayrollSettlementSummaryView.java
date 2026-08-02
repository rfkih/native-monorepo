package id.co.nativeapp.finance.labor.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for a {@code payroll_settlement} row — backs the settlement-status join the
 * liabilities read combines with {@link PayrollLiabilityRunView} (ADR 0032, Track P phase P5).
 */
public interface PayrollSettlementSummaryView {

  UUID getPayrollRunLedgerId();

  String getKind();

  long getAmountMinor();

  String getAmountCurrency();

  UUID getJournalEntryId();

  Instant getSettledAt();
}
