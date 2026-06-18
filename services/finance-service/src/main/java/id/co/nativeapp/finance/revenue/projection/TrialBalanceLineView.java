package id.co.nativeapp.finance.revenue.projection;

/**
 * Read projection over one aggregated trial-balance line from the {@code ledger_posting} roll-up —
 * only the six columns the within-company close needs, never {@code SELECT *} of the full entity.
 *
 * <p>Backs the native read query on {@code LedgerPostingRepository#trialBalanceForPeriod}.
 * Snake_case native-query aliases map to these accessors via Spring Data's projection-interface
 * convention (CLAUDE.md "native-query aliases snake_case; map via projection interfaces").
 * Extracted from the former nested {@code LedgerPostingRepository.TrialBalanceLineProjection} into
 * its own {@code projection} package so the ArchUnit {@code Projection} layer rule can enforce that
 * projections are accessed only from service and repository layers.
 */
public interface TrialBalanceLineView {

  String getGlAccountCode();

  String getAccountType();

  String getPostingType();

  long getAmountMinor();

  String getCurrency();

  boolean getUsesIllustrativeRules();
}
