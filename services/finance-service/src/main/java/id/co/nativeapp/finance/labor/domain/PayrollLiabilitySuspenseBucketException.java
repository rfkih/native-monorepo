package id.co.nativeapp.finance.labor.domain;

import java.util.UUID;

/**
 * A settlement was attempted against a bucket whose liability entry line posted to the SUSPENSE
 * account (9999) — the role was unrecognised or unmapped AT ACCRUAL TIME, so {@code
 * PayrollLiabilityWriter} routed it to suspense instead of the intended control account (ADR 0032,
 * Track P phase P5 review W3). Settling it would debit suspense, which does not clear the intended
 * payable and (if a DIFFERENT bucket role was ALSO unmapped at accrual time) could double-pay: two
 * distinct bucket roles sharing the same suspense account code are indistinguishable by account
 * code alone. The bucket must be reclassified (an accountant maps the missing {@code
 * role_account_map} row and the run is corrected/re-posted) before it can be settled. Mapped to 409
 * (a state conflict — the run and the kind both exist, but this bucket cannot be settled as
 * currently classified).
 */
public class PayrollLiabilitySuspenseBucketException extends RuntimeException {

  public PayrollLiabilitySuspenseBucketException(UUID runLedgerId, String kind) {
    super(
        "the "
            + kind
            + " bucket of payroll run ledger "
            + runLedgerId
            + " posted to the SUSPENSE account (unmapped at accrual time) — reclassify it before"
            + " settling");
  }
}
