package id.co.nativeapp.finance.gl.projection;

/**
 * Read projection over one aggregated cumulative trial-balance line from the GL {@code
 * journal_line} roll-up — CUMULATIVE (all journal activity up to and including a given period).
 *
 * <p>Backs the native read query on {@code JournalEntryRepository#glTrialBalanceAsOf}. Snake_case
 * native-query aliases map to these accessors via Spring Data's projection-interface convention.
 * Extracted into the feature's dedicated {@code gl.projection} package so the ArchUnit {@code
 * Projection} layer rule enforces it is accessed only from service and repository layers.
 *
 * <p>The cumulative variant sums all lines where {@code journal_entry.period <= :period} — this is
 * the data the Balance Sheet is built from: every account's running balance from inception up to
 * the as-of period.
 */
public interface GlCumulativeTrialBalanceLineView {

  String getAccountCode();

  String getAccountType();

  long getTotalDebitMinor();

  long getTotalCreditMinor();

  String getCurrency();

  boolean getUsesIllustrativeRules();
}
