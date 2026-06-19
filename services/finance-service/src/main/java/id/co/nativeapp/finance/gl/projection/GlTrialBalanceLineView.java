package id.co.nativeapp.finance.gl.projection;

/**
 * Read projection over one aggregated trial-balance line from the GL {@code journal_line} roll-up —
 * only the columns the GL trial-balance reader needs, never {@code SELECT *} of the full entity.
 *
 * <p>Backs the native read query on {@code JournalEntryRepository#glTrialBalance}. Snake_case
 * native-query aliases map to these accessors via Spring Data's projection-interface convention
 * (CLAUDE.md "native-query aliases snake_case; map via projection interfaces"). Extracted into the
 * feature's dedicated {@code gl.projection} package so the ArchUnit {@code Projection} layer rule
 * enforces it is accessed only from service and repository layers.
 */
public interface GlTrialBalanceLineView {

  String getAccountCode();

  String getAccountType();

  long getTotalDebitMinor();

  long getTotalCreditMinor();

  String getCurrency();

  boolean getUsesIllustrativeRules();
}
