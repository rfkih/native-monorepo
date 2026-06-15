package id.co.nativeapp.finance.consolidation;

/**
 * THE SIMPLIFIED, FLAGGED MULTI-CURRENCY CONSOLIDATION-TRANSLATION POLICY (P3d SEAM 3b).
 *
 * <p><strong>⚠ THIS IS A DELIBERATELY SIMPLIFIED, LOUDLY-FLAGGED POLICY — NOT AUDITED, NOT
 * FINAL.</strong> A multi-currency group consolidation produced under it MUST be treated as
 * PROVISIONAL and MUST carry the {@code uses_simplified_translation_policy} flag. NEVER present
 * such a consolidation as audited / IFRS-final.
 *
 * <h2>What this seam DOES (the simplified machinery)</h2>
 *
 * <ol>
 *   <li><strong>Translation by account type.</strong> Every {@code group_trial_balance} line is
 *       translated into the group's reporting currency using the P3c {@code TranslationService}:
 *       ASSET / LIABILITY / EQUITY at the <strong>CLOSING</strong> (period-end) rate ({@code
 *       translateBalance}); REVENUE / EXPENSE at the <strong>AVERAGE</strong> (period) rate ({@code
 *       translateFlow}). A line already in the reporting currency is the identity (no rate, no
 *       stub). A MISSING rate ({@code MissingFxRateException}) FAILS THE WHOLE CLOSE atomically —
 *       no summary, no partial ledger.
 *   <li><strong>Balance-check + CTA (the integrity gate).</strong> Because balance-sheet items
 *       translate at CLOSING and P&amp;L items at AVERAGE, the translated trial balance does NOT
 *       sum to zero. The residual is the SIMPLIFIED translation adjustment. It is posted to the
 *       EXPLICITLY-FLAGGED CTA / translation-reserve account ({@link
 *       #CTA_TRANSLATION_RESERVE_ACCOUNT}) as a {@link ConsolidationEntryType#CTA} entry, so the
 *       consolidated set BALANCES. The close is GATED on this: after the CTA posts, the translated
 *       set must reconcile to zero — the residual is never silently dropped.
 *   <li><strong>Cross-currency intercompany.</strong> A same-currency IC pair keeps the 3a strict
 *       invariant (eliminate in the shared currency). A CROSS-currency IC pair translates each leg
 *       to the reporting currency (AVERAGE), eliminates the translated amounts, and posts the
 *       residual between the two translated legs as a {@link
 *       ConsolidationEntryType#FX_TRANSLATION_DIFF} entry (flagged) — never silently absorbed.
 *   <li><strong>Loud flags.</strong> Any translated or stub-rate close is flagged {@code
 *       uses_simplified_translation_policy = true} (sticky/monotonic) and {@code uses_stub_fx}
 *       carries the seeded-rate provenance (sticky-OR across every translated line). Same-currency
 *       closes leave both false.
 * </ol>
 *
 * <h2>What this seam DELIBERATELY DOES NOT DO (DEFERRED to an accounting SME — a HARD GATE)</h2>
 *
 * <p>The following are STRUCTURALLY REQUIRED for an audited IAS-21 consolidation and are absent by
 * design — the same class of decision as DJP/BPJS statutory figures: do NOT invent them as
 * production values.
 *
 * <ul>
 *   <li><strong>The real IAS-21 CTA.</strong> The IAS-21 cumulative translation adjustment is
 *       {@code (closing net assets at the closing rate) − (opening net assets at the opening rate)
 *       − (average-rate P&L)}, recognised in OCI. That needs OPENING balances and an OCI mechanism.
 *       The residual this seam posts is a per-period BALANCING device under the simplified policy,
 *       NOT that figure.
 *   <li><strong>Historical-rate equity.</strong> Equity / share capital should translate at the
 *       HISTORICAL rate (the rate when the equity arose), not the closing rate. This seam
 *       translates EQUITY at the closing rate (the simplification), which is part of why the
 *       residual is non-zero.
 *   <li><strong>Opening-balance roll-forward (cumulative consolidation).</strong> A real
 *       consolidation rolls the prior period's translated balances forward; this seam translates
 *       each period independently.
 * </ul>
 *
 * <p>This is a documentation-only holder (a single source of truth referenced from {@link
 * GroupCloseWriter} and the V8 migration). It has no behaviour; the policy is enforced in {@link
 * GroupCloseWriter}.
 */
final class SimplifiedTranslationPolicy {

  /**
   * The explicitly-flagged CTA / translation-reserve consolidation account the simplified
   * balance-check residual posts to (an EQUITY-class reserve in a real chart). The {@code CTA-}
   * token in the code makes it self-describing in the ledger that this is the simplified
   * translation reserve, not an IAS-21 OCI line.
   */
  static final String CTA_TRANSLATION_RESERVE_ACCOUNT = "3950-CTA-TRANSLATION-RESERVE";

  /**
   * The consolidation account a cross-currency intercompany residual ({@link
   * ConsolidationEntryType#FX_TRANSLATION_DIFF}) posts to.
   */
  static final String FX_TRANSLATION_DIFF_ACCOUNT = "3960-FX-TRANSLATION-DIFF";

  private SimplifiedTranslationPolicy() {}
}
