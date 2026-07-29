package id.co.nativeapp.finance.tax.domain;

import java.util.UUID;

/**
 * Thrown when a {@link TaxFiling} id is not visible in the bound tenant (RLS-scoped). Mapped to
 * {@code 404} with a generic detail — a foreign-tenant id is indistinguishable from a missing one,
 * so there is no existence disclosure (mirrors {@code BillNotFoundException}).
 */
public class TaxFilingNotFoundException extends RuntimeException {

  public TaxFilingNotFoundException(UUID filingId) {
    super("tax filing not found: " + filingId);
  }
}
