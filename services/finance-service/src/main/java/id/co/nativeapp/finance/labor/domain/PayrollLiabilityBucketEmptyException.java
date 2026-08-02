package id.co.nativeapp.finance.labor.domain;

import java.util.UUID;

/**
 * A settlement was attempted against a bucket the run's liability entry never recognised (no
 * matching {@code journal_line} for that role — the bucket total reads as zero, ADR 0032, Track P
 * phase P5). There is nothing to pay: a zero-amount journal line cannot be posted (the {@code
 * JournalLine} factories reject a non-positive amount by construction), so this is rejected before
 * ever attempting one. Mapped to 409 (a state conflict — the run and the kind both exist, but this
 * combination has no money to settle).
 */
public class PayrollLiabilityBucketEmptyException extends RuntimeException {

  public PayrollLiabilityBucketEmptyException(UUID runLedgerId, String kind) {
    super(
        "payroll run ledger "
            + runLedgerId
            + " has no "
            + kind
            + " liability recognised — nothing to settle");
  }
}
