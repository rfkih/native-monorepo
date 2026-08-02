package id.co.nativeapp.finance.labor.domain;

/**
 * A settlement was attempted against a liability bucket whose recognised amount is NEGATIVE — the
 * December Art-17 true-up refund month (ADR 0031) can drive {@code PPH21_PAYABLE} negative: a
 * negative payable is in substance a RECEIVABLE from the tax office, netted against the NEXT
 * period's remittance, not paid out standalone. v1 (ADR 0032, Track P phase P5) REJECTS settling a
 * negative bucket rather than inventing an unmodeled receivable flow; mapped to 422 (the request is
 * well-formed but the bucket cannot be processed as asked). A documented residual — see the ADR.
 */
public class NegativeLiabilityBucketException extends RuntimeException {

  public NegativeLiabilityBucketException(String kind) {
    super(
        "the "
            + kind
            + " bucket is negative for this run — net it against the next period's remittance;"
            + " it is not settleable standalone");
  }
}
