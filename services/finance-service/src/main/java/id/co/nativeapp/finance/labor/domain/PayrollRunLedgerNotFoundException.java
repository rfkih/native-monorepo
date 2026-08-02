package id.co.nativeapp.finance.labor.domain;

import java.util.UUID;

/**
 * A settlement (or liabilities read) referenced a {@code payroll_run_ledger} row not visible in the
 * bound tenant (an unknown id, or — invisible under RLS — another tenant's). Mapped to 404 with a
 * generic detail (no existence disclosure), mirroring {@code AssetNotFoundException} / {@code
 * TaxFilingNotFoundException}.
 */
public class PayrollRunLedgerNotFoundException extends RuntimeException {

  public PayrollRunLedgerNotFoundException(UUID runLedgerId) {
    super("no such payroll run ledger is accessible: " + runLedgerId);
  }
}
