package id.co.nativeapp.finance.consolidation;

/**
 * The group-consolidation read DTO (P3d SEAM 4b) — the CONSOLIDATED / ELIMINATED group total for a
 * (group, period), in the group's reporting currency, plus the provisional flags and the close
 * state, so a dashboard can render and BADGE the consolidation.
 *
 * <p><strong>Group total ONLY — never a member's books.</strong> Every field is a GROUP-level
 * figure assembled from {@code consolidation_summary} (the post-elimination roll-up) and {@code
 * intercompany_match} aggregate counts under the two-GUC group scope (tenant = lead, group = G).
 * There is NO per-member standalone trial balance, NO member ledger, NO member company id here —
 * the surface exposes the eliminated total + the group's own eliminations status, nothing
 * member-scoped (the consolidation summary already nets internal trade out).
 *
 * <p>The money fields are rule-8 Money (integer minor units + the single {@code
 * reportingCurrency}), never a float. The flags are the SEAM-3b provenance badges: {@code
 * usesStubFx} (any applied FX rate was a stub), {@code usesIllustrativeRules} (a member figure
 * derives from illustrative statutory rules), {@code usesSimplifiedTranslationPolicy} (the close
 * translated under the simplified, flagged multi-currency policy — the consolidation is
 * PROVISIONAL, "needs accounting sign-off"). {@code closeRunSeq} identifies the close run the
 * figures came from.
 *
 * @param groupId the consolidation group
 * @param period the accounting period {@code YYYY-MM}
 * @param reportingCurrency the group's ISO-4217 reporting currency (every amount is in it)
 * @param groupRevenueMinor the post-elimination consolidated revenue (minor units)
 * @param groupExpenseMinor the post-elimination consolidated expense (minor units)
 * @param groupNetMinor the consolidated net ({@code revenue - expense}, minor units)
 * @param state the close lifecycle state (CLOSED / a gated hold-back state)
 * @param usesStubFx sticky-OR — any applied FX rate was a stub
 * @param usesIllustrativeRules sticky-OR — a member figure derives from illustrative statutory
 *     rules
 * @param usesSimplifiedTranslationPolicy the close translated under the simplified, flagged policy
 * @param closeRunSeq the close run the figures came from
 * @param eliminations the group eliminations / intercompany-match status (counts only)
 */
public record GroupConsolidationResponse(
    java.util.UUID groupId,
    String period,
    String reportingCurrency,
    long groupRevenueMinor,
    long groupExpenseMinor,
    long groupNetMinor,
    ConsolidationState state,
    boolean usesStubFx,
    boolean usesIllustrativeRules,
    boolean usesSimplifiedTranslationPolicy,
    int closeRunSeq,
    GroupEliminationStatusResponse eliminations) {

  /**
   * Projects a {@link ConsolidationSummary} (the group total + flags) plus the {@link
   * GroupEliminationStatusResponse} into the read DTO. Reads the summary's getters / Money
   * accessors only — NEVER a member-scoped row. The entity is read inside the bound group scope;
   * the DTO is what crosses the controller boundary.
   */
  public static GroupConsolidationResponse from(
      ConsolidationSummary summary, GroupEliminationStatusResponse eliminations) {
    return new GroupConsolidationResponse(
        summary.getGroupId(),
        summary.getPeriod(),
        summary.getReportingCurrency(),
        summary.groupRevenue().amountMinor(),
        summary.groupExpense().amountMinor(),
        summary.groupNet().amountMinor(),
        summary.getState(),
        summary.isUsesStubFx(),
        summary.isUsesIllustrativeRules(),
        summary.isUsesSimplifiedTranslationPolicy(),
        summary.getCloseRunSeq(),
        eliminations);
  }
}
