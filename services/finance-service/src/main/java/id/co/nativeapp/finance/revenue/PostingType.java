package id.co.nativeapp.finance.revenue;

/**
 * The kind of a {@link LedgerPosting}. M1.5 posts only {@link #REVENUE} (from {@code
 * SaleRecorded}); the enum exists so the ledger generalises to expenses and payroll postings (later
 * milestones) without a schema change. Persisted as its name via {@code EnumType.STRING}.
 */
public enum PostingType {
  /** Revenue recognised from a recorded sale. */
  REVENUE
}
