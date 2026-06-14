package id.co.nativeapp.finance.revenue;

/**
 * The kind of a {@link LedgerPosting}. {@link #REVENUE} is posted from {@code SaleRecorded}; {@link
 * #EXPENSE} from {@code ExpenseRecorded} (#21). The enum doubles as the {@code mapping_rule}
 * resolution criterion's posting-type dimension (the resolver keys on it), and the ledger
 * generalises to payroll postings (later milestones) without a schema change. Persisted as its name
 * via {@code EnumType.STRING}.
 */
public enum PostingType {
  /** Revenue recognised from a recorded sale ({@code SaleRecorded}). */
  REVENUE,
  /** An expense recorded by a vertical ({@code ExpenseRecorded}). */
  EXPENSE
}
