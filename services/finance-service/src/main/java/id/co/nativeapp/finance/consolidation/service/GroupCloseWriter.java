package id.co.nativeapp.finance.consolidation.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.consolidation.domain.ConsolidationEntryType;
import id.co.nativeapp.finance.consolidation.domain.ConsolidationLedgerEntry;
import id.co.nativeapp.finance.consolidation.domain.ConsolidationPostingRole;
import id.co.nativeapp.finance.consolidation.domain.ConsolidationState;
import id.co.nativeapp.finance.consolidation.domain.ConsolidationSummary;
import id.co.nativeapp.finance.consolidation.domain.IntercompanyMatch;
import id.co.nativeapp.finance.consolidation.domain.IntercompanyMatchState;
import id.co.nativeapp.finance.consolidation.domain.StaleCloseRunException;
import id.co.nativeapp.finance.consolidation.dto.GroupCloseResult;
import id.co.nativeapp.finance.consolidation.messaging.ConsolidationClosedSchema;
import id.co.nativeapp.finance.consolidation.messaging.ConsolidationEntryIds;
import id.co.nativeapp.finance.consolidation.repository.ConsolidationLedgerRepository;
import id.co.nativeapp.finance.consolidation.repository.ConsolidationSummaryRepository;
import id.co.nativeapp.finance.consolidation.repository.IntercompanyMatchRepository;
import id.co.nativeapp.finance.fx.dto.Translated;
import id.co.nativeapp.finance.fx.service.TranslationService;
import id.co.nativeapp.finance.group.domain.GroupMember;
import id.co.nativeapp.finance.group.domain.GroupRef;
import id.co.nativeapp.finance.group.repository.GroupMemberRepository;
import id.co.nativeapp.finance.group.repository.GroupRefRepository;
import id.co.nativeapp.finance.grouptb.domain.GroupTrialBalanceLine;
import id.co.nativeapp.finance.grouptb.repository.GroupTrialBalanceLineRepository;
import id.co.nativeapp.finance.mapping.domain.AccountType;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single {@code @Transactional} unit of work that runs a group's consolidation close for a
 * period (P3d SEAM 3a + 3b). Must be invoked inside a {@link TenantContext#callAsGroup} scope bound
 * to the group's LEAD company AND the group id, so the two-GUC conjunction RLS engages on every
 * consolidation-table read/write (rule 5).
 *
 * <p><strong>SEAM 3b — MULTI-CURRENCY, FLAGGED-SIMPLIFIED.</strong> Multi-currency groups are now
 * SUPPORTED (the 3a {@code MULTI_CURRENCY_UNSUPPORTED} hard stop is GONE): every member line is
 * TRANSLATED into the reporting currency by account type (BS at CLOSING, P&amp;L at AVERAGE) via
 * the P3c {@code TranslationService}; the translated set is balanced via a flagged CTA reserve; a
 * cross-currency intercompany pair eliminates its translated legs and posts a flagged
 * FX_TRANSLATION_DIFF residual; the summary is loudly flagged {@code
 * uses_simplified_translation_policy} + {@code uses_stub_fx}. This is a DELIBERATELY SIMPLIFIED,
 * PROVISIONAL policy — full IAS-21 CTA/OCI, historical-rate equity and opening-balance roll-forward
 * are DEFERRED to an accounting SME. See {@link SimplifiedTranslationPolicy}.
 *
 * <p>A distinct bean (not a private method on {@link GroupCloseService}) so the method is invoked
 * through the Spring proxy: a self-invocation would bypass the {@code @Transactional} advice and
 * the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets {@code app.current_tenant} (the
 * lead) AND {@code app.current_group} (the group) — both required for the conjunction WITH CHECK
 * (rule 5).
 *
 * <p>The pipeline, in order:
 *
 * <ol>
 *   <li><strong>Advisory lock</strong> on {@code hashtext(group:period)} (the {@code
 *       LaborCostPostingWriter} primitive) to serialize concurrent/interleaved closes.
 *   <li><strong>Membership effective-dating gate.</strong> Resolve the members DETERMINISTICALLY:
 *       those active at PERIOD-END from {@code group_member}. A WARMING-UP guard ({@link
 *       GroupMembershipReadiness}) HOLDs the close ({@code PENDING_MEMBERS_WARMING_UP}) if the
 *       projection is not caught up — never consolidate a stale member set.
 *   <li><strong>Member-completeness gate.</strong> Every active member must have an ingested {@code
 *       group_trial_balance} for the period; a missing one -> {@code PENDING_MEMBERS}, not
 *       finalized.
 *   <li><strong>Native balance gate (SEAM 3b — the accounting integrity gate).</strong> BEFORE
 *       translation, validate each member's trial balance in its OWN functional currency: a
 *       non-zero signed double-entry residual (its books do not balance) OR a member-published
 *       {@code reconciled = false} HOLDs the close ({@code MEMBER_TRIAL_BALANCE_UNBALANCED}). The
 *       simplified policy ASSUMES each member balances natively, so the only post-translation
 *       residual is the legitimate closing-vs-average FX effect; a raw native imbalance must NEVER
 *       be swept into the CTA and mis-labelled a translation adjustment.
 *   <li><strong>Translation (SEAM 3b).</strong> Translate every member line into the reporting
 *       currency by account type (BS at CLOSING, P&amp;L at AVERAGE). A missing rate fails the
 *       whole close atomically (no summary, no partial ledger); {@code uses_stub_fx} is OR-ed
 *       across every translated line; any translation flags {@code
 *       uses_simplified_translation_policy}.
 *   <li><strong>Intercompany match.</strong> Pair related-party legs on {@code (intercompany_ref,
 *       period)}; same-currency MATCHED -> an ELIMINATION contra; cross-currency MATCHED_CROSS_
 *       CURRENCY -> eliminate the translated legs + a flagged FX_TRANSLATION_DIFF residual;
 *       UNMATCHED / AMOUNT_MISMATCH / MALFORMED -> flagged and the close held back ({@code
 *       INTERCOMPANY_UNRECONCILED} — money is never eliminated against nothing).
 *   <li><strong>Compute + balance-check.</strong> Sum the TRANSLATED trial balances into
 *       revenue/expense/net, apply the eliminations; for a translated close, post the signed
 *       translation residual to the flagged CTA reserve so the set reconciles (the integrity gate),
 *       carry the sticky flags, write {@code consolidation_summary} (state CLOSED).
 *   <li><strong>Supersession (append-only, #23).</strong> A higher {@code close_run_seq} reverses
 *       each prior PRIMARY entry (elimination, CTA, FX_TRANSLATION_DIFF) (negated, role REVERSAL, a
 *       deterministic collision-free synthetic id) and flips the prior summary to TERMINAL {@code
 *       SUPERSEDED}, then reposts.
 * </ol>
 */
@Component
public class GroupCloseWriter {

  private static final Logger log = LoggerFactory.getLogger(GroupCloseWriter.class);

  /** The intercompany-elimination consolidation account a MATCHED revenue trade contras. */
  static final String IC_REVENUE_ELIMINATION_ACCOUNT = "3900-IC-REVENUE-ELIM";

  /** The intercompany-elimination consolidation account a MATCHED expense trade contras. */
  static final String IC_EXPENSE_ELIMINATION_ACCOUNT = "3910-IC-EXPENSE-ELIM";

  private final GroupRefRepository groupRefRepository;
  private final GroupMemberRepository groupMemberRepository;
  private final GroupTrialBalanceLineRepository trialBalanceRepository;
  private final ConsolidationLedgerRepository ledgerRepository;
  private final ConsolidationSummaryRepository summaryRepository;
  private final IntercompanyMatchRepository matchRepository;
  private final GroupMembershipReadiness membershipReadiness;
  private final ProcessedEventStore processedEvents;
  private final TranslationService translationService;
  private final OutboxWriter outboxWriter;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public GroupCloseWriter(
      GroupRefRepository groupRefRepository,
      GroupMemberRepository groupMemberRepository,
      GroupTrialBalanceLineRepository trialBalanceRepository,
      ConsolidationLedgerRepository ledgerRepository,
      ConsolidationSummaryRepository summaryRepository,
      IntercompanyMatchRepository matchRepository,
      GroupMembershipReadiness membershipReadiness,
      ProcessedEventStore processedEvents,
      TranslationService translationService,
      OutboxWriter outboxWriter,
      Clock clock) {
    this.groupRefRepository = groupRefRepository;
    this.groupMemberRepository = groupMemberRepository;
    this.trialBalanceRepository = trialBalanceRepository;
    this.ledgerRepository = ledgerRepository;
    this.summaryRepository = summaryRepository;
    this.matchRepository = matchRepository;
    this.membershipReadiness = membershipReadiness;
    this.processedEvents = processedEvents;
    this.translationService = translationService;
    this.outboxWriter = outboxWriter;
    this.clock = clock;
  }

  /**
   * One member trial-balance line PAIRED with its translation into the group reporting currency
   * (P3d SEAM 3b). {@code translated} is the line's amount in the reporting currency — by {@code
   * account_type}: ASSET/LIABILITY/EQUITY at the CLOSING rate, REVENUE/EXPENSE at the AVERAGE rate
   * (a line already in the reporting currency is the identity). The downstream roll-up,
   * intercompany matching and balance-check all operate on {@code translated} so every figure is in
   * one currency. See {@link SimplifiedTranslationPolicy}.
   */
  private record TranslatedLine(GroupTrialBalanceLine line, Money translated) {

    AccountType accountType() {
      return line.getAccountType();
    }

    UUID memberCompanyId() {
      return line.getMemberCompanyId();
    }

    String intercompanyRef() {
      return line.getIntercompanyRef();
    }
  }

  /**
   * Runs the close for {@code (group, period)} at {@code closeRunSeq}. Idempotent: a re-run at a
   * seq that already has a summary is a clean no-op.
   *
   * @param groupId the consolidation group (must equal the bound group scope)
   * @param period the accounting period {@code YYYY-MM}
   * @param closeRunSeq the close run sequence (a higher seq supersedes lower ones)
   * @return the {@link GroupCloseResult}
   */
  @Transactional
  public GroupCloseResult close(UUID groupId, String period, int closeRunSeq) {
    TenantContext.Tenant tenant = TenantContext.require();
    String lead = tenant.companyId();

    // (a) Serialize concurrent/interleaved closes for THIS (group, period) with a deterministic
    //     transaction-scoped advisory lock taken BEFORE any consolidation-row access (the
    //     LaborCostPostingWriter pattern). The lock is held even before the first summary exists,
    // so
    //     two parallel closes at different seqs cannot both reverse-and-repost without seeing each
    //     other; the later serializes behind the earlier's committed rows.
    ledgerRepository.lockGroupPeriod(groupId + ":" + period);

    // Idempotent re-run: a summary already exists for this exact close_run_seq -> clean no-op.
    var existing =
        summaryRepository.findByGroupIdAndPeriodAndCloseRunSeq(groupId, period, closeRunSeq);
    if (existing.isPresent()) {
      ConsolidationSummary prior = existing.get();
      log.info(
          "Group close groupId={} period={} closeRunSeq={} already ran (state={}); idempotent no-op",
          groupId,
          period,
          closeRunSeq,
          prior.getState());
      return new GroupCloseResult(prior.getState(), prior.getId(), closeRunSeq, false);
    }

    // Refuse to re-close at a seq that is not strictly higher than the current active head: never
    // reverse a NEWER close with a stale/duplicate attempt (the exact-same-seq no-op is handled
    // above; this catches a lower-or-equal seq racing in after a higher close committed).
    if (summaryRepository.existsActiveAtOrAboveSeq(groupId, period, closeRunSeq)) {
      throw new StaleCloseRunException(
          "Refusing group close groupId="
              + groupId
              + " period="
              + period
              + " at closeRunSeq="
              + closeRunSeq
              + ": an active close at an equal-or-higher seq already exists for this period");
    }

    GroupRef ref =
        groupRefRepository
            .findById(groupId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "group_ref not visible for groupId="
                            + groupId
                            + " under bound lead="
                            + lead
                            + " — the group is unknown or the scope is mis-bound"));
    String reportingCurrency = ref.getReportingCurrency();

    // (b) MEMBERSHIP EFFECTIVE-DATING GATE — warming-up guard FIRST. If the group_member projection
    //     is not caught up, HOLD: do not consolidate a stale member set.
    if (!membershipReadiness.isCaughtUp(groupId)) {
      log.warn(
          "Group close groupId={} period={} HELD warming-up: group_member projection not caught up",
          groupId,
          period);
      return holdSummary(
          groupId,
          period,
          reportingCurrency,
          ConsolidationState.PENDING_MEMBERS_WARMING_UP,
          false,
          closeRunSeq);
    }

    // Resolve the member set DETERMINISTICALLY = active at PERIOD-END (effective_from <= period_end
    // AND period_end < effective_to). A member that joined/left mid-period is included/excluded
    // correctly and reproducibly — never the "current" set.
    LocalDate periodEnd = endOf(period);
    List<GroupMember> activeMembers = groupMemberRepository.findActiveAt(groupId, periodEnd);
    Set<UUID> activeMemberIds =
        activeMembers.stream().map(GroupMember::getMemberCompanyId).collect(Collectors.toSet());

    // (b cont.) EMPTY ACTIVE-MEMBER SET — FAIL CLOSED. A zero-member result at period-end means the
    //     membership projection has nothing to consolidate against (a stale/lagging projection, or
    //     a group with no member active at period-end). It must HOLD, NEVER consolidate the full
    //     unfiltered line set: passing everything through here would bypass the
    // active-at-period-end
    //     guarantee in exactly the stale case it exists to protect. The warming-up guard above
    //     handles the KNOWN-pending case; this catches the case where the projection reports caught
    //     up yet yields no active member.
    if (activeMemberIds.isEmpty()) {
      log.warn(
          "Group close groupId={} period={} HELD PENDING_MEMBERS: zero active members at"
              + " period-end {} — refusing to consolidate the full unfiltered line set",
          groupId,
          period,
          periodEnd);
      return holdSummary(
          groupId,
          period,
          reportingCurrency,
          ConsolidationState.PENDING_MEMBERS,
          false,
          closeRunSeq);
    }

    // (c) MEMBER-COMPLETENESS GATE: every active member must have ingested a trial balance.
    Set<UUID> membersWithLines =
        new java.util.HashSet<>(trialBalanceRepository.findMembersWithLines(groupId, period));
    List<UUID> missing =
        activeMemberIds.stream().filter(m -> !membersWithLines.contains(m)).sorted().toList();
    if (!missing.isEmpty()) {
      log.warn(
          "Group close groupId={} period={} PENDING_MEMBERS: {} active member(s) without an"
              + " ingested trial balance: {}",
          groupId,
          period,
          missing.size(),
          missing);
      return holdSummary(
          groupId,
          period,
          reportingCurrency,
          ConsolidationState.PENDING_MEMBERS,
          false,
          closeRunSeq);
    }

    // Load the ingested lines for the active member set (lines from a NON-active member are
    // excluded — a member not in the group at period-end does not consolidate even if it has stray
    // lines). The active set is non-empty here (the fail-closed guard above returned otherwise), so
    // an empty set can never widen the filter to pass everything.
    List<GroupTrialBalanceLine> lines =
        trialBalanceRepository.findByGroupIdAndPeriod(groupId, period).stream()
            .filter(l -> activeMemberIds.contains(l.getMemberCompanyId()))
            .toList();

    boolean usesIllustrative =
        lines.stream().anyMatch(GroupTrialBalanceLine::isUsesIllustrativeRules);

    Currency reportingCcy = Currency.getInstance(reportingCurrency);

    // A close is MULTI-CURRENCY (the translated, CTA-bearing path) when ANY member line is in a
    // currency other than the group's reporting currency. A same-currency (3a) close performs no
    // translation and posts no CTA, so no raw imbalance can be swept into a CTA — the native
    // balance
    // gate below applies ONLY to the multi-currency path (the 3a model is deliberately P&L-only and
    // never carried a balancing full trial balance, so it must NOT be gated on native balance).
    boolean multiCurrency =
        lines.stream().anyMatch(l -> !l.getAmount().currency().equals(reportingCcy));

    // (c2) NATIVE BALANCE GATE (the SEAM-3b accounting Critical fix). The simplified translation
    //     policy ASSUMES each member trial balance already sums to ZERO in its OWN functional
    //     currency, so the ONLY post-translation residual is the legitimate closing-vs-average FX
    //     effect (-> the flagged CTA reserve). Before translating a MULTI-CURRENCY close, VALIDATE
    //     that assumption per member, in the member's functional currency, using the same
    //     double-entry sign convention (ASSET + EXPENSE positive, LIABILITY + EQUITY + REVENUE
    //     negative):
    //       * a member whose native signed residual is NON-ZERO does NOT balance — its raw
    // imbalance
    //         must NEVER be swept into the CTA and mis-labelled a translation adjustment;
    //       * a member that published reconciled = false says its own books do not balance.
    //     EITHER HOLDs the close in MEMBER_TRIAL_BALANCE_UNBALANCED (no consolidation, no CTA
    // sweep,
    //     no CLOSED) — exactly like the member-completeness gate. ONLY when every member native
    // book
    //     balances (and is reconciled) does the close proceed to translate; the post-translation
    //     residual is then PURELY the FX effect.
    List<UUID> unbalancedMembers =
        multiCurrency ? unbalancedNativeMembers(lines, activeMemberIds) : List.of();
    if (!unbalancedMembers.isEmpty()) {
      log.warn(
          "Group close groupId={} period={} HELD MEMBER_TRIAL_BALANCE_UNBALANCED: {} active"
              + " member(s) whose native trial balance does not reconcile (non-zero functional"
              + " residual) or was published reconciled=false: {} — refusing to sweep a raw"
              + " imbalance into the CTA reserve as a translation adjustment",
          groupId,
          period,
          unbalancedMembers.size(),
          unbalancedMembers);
      return holdSummary(
          groupId,
          period,
          reportingCurrency,
          ConsolidationState.MEMBER_TRIAL_BALANCE_UNBALANCED,
          usesIllustrative,
          closeRunSeq);
    }

    // (d) TRANSLATION (P3d SEAM 3b — SIMPLIFIED, FLAGGED). Multi-currency is now SUPPORTED: instead
    //     of the 3a MULTI_CURRENCY_UNSUPPORTED hard stop, EVERY line is translated into the group's
    //     reporting currency by account_type via the P3c TranslationService —
    // ASSET/LIABILITY/EQUITY
    //     at the CLOSING (period-end) rate, REVENUE/EXPENSE at the AVERAGE (period) rate. A line
    //     already in the reporting currency is the identity (no rate, no stub). A MISSING rate
    //     throws MissingFxRateException, which propagates OUT of this @Transactional method and
    // rolls
    //     the WHOLE close back atomically: no summary, no partial ledger (the held/failed state).
    // See
    //     SimplifiedTranslationPolicy for the deferred full-IAS-21 boundary.
    //
    //     uses_stub_fx is OR-ed (Translated.usesStubFx) across every translated line. A close that
    //     translated ANY line is flagged uses_simplified_translation_policy = true (sticky). A
    //     same-currency (3a) close translates everything via the identity, so both flags stay
    // false.
    List<TranslatedLine> translatedLines = new ArrayList<>(lines.size());
    boolean usesStubFx = false;
    boolean usesSimplifiedTranslation = false;
    for (GroupTrialBalanceLine line : lines) {
      Translated t = translate(line, period, reportingCcy);
      translatedLines.add(new TranslatedLine(line, t.amount()));
      usesStubFx = usesStubFx || t.usesStubFx();
      // A non-identity translation (the source currency differs from the reporting currency) is
      // what
      // makes this a SIMPLIFIED multi-currency close.
      if (!line.getAmount().currency().equals(reportingCcy)) {
        usesSimplifiedTranslation = true;
      }
    }

    // (e) INTERCOMPANY MATCH on (intercompany_ref, period): pair related-party legs. Persist the
    //     reconciliation rows; UNMATCHED / AMOUNT_MISMATCH / MALFORMED block the close. A pure
    //     balance-sheet IC ref is OUT_OF_SCOPE_BALANCE_SHEET (non-blocking, not eliminated). A
    //     well-formed CROSS-currency P&L pair is MATCHED_CROSS_CURRENCY (non-blocking; eliminated
    // via
    //     the translated legs + an FX_TRANSLATION_DIFF residual below).
    List<IntercompanyMatch> matches =
        reconcileIntercompany(groupId, period, closeRunSeq, translatedLines);
    matchRepository.saveAll(stamp(matches, lead));
    boolean anyBlocking = matches.stream().anyMatch(IntercompanyMatch::blocksClose);
    if (anyBlocking) {
      log.warn(
          "Group close groupId={} period={} INTERCOMPANY_UNRECONCILED: {} reference(s) blocked"
              + " (UNMATCHED / AMOUNT_MISMATCH / MALFORMED) — money is not eliminated against"
              + " nothing nor against the wrong account class",
          groupId,
          period,
          matches.stream().filter(IntercompanyMatch::blocksClose).count());
      return holdSummary(
          groupId,
          period,
          reportingCurrency,
          ConsolidationState.INTERCOMPANY_UNRECONCILED,
          usesIllustrative,
          closeRunSeq);
    }

    // A ref -> its translated legs, so the elimination posting can use TRANSLATED amounts (the
    // roll-up below is on translated figures, so eliminations must be too).
    Map<String, List<TranslatedLine>> translatedByRef = legsByRef(translatedLines);

    // (f) SUPERSESSION (append-only, #23): a higher close_run_seq reverses each prior PRIMARY entry
    //     (elimination, CTA, FX_TRANSLATION_DIFF) of the superseded run(s) then reposts. Flip the
    //     prior summary to terminal SUPERSEDED.
    //
    //     NOTE on a HELD prior: a prior summary that never finalised (a non-CLOSED HOLD) posted NO
    //     PRIMARY ledger entries, so reversePriorRun finds nothing to reverse — the SUPERSEDED
    //     transition on it is purely cosmetic. Marking it SUPERSEDED rather than overwriting it
    // keeps
    //     the supersession path uniform and append-only. We deliberately do NOT special-case HELD
    //     priors here.
    for (ConsolidationSummary priorSummary :
        summaryRepository.findActivePriorSummaries(groupId, period, closeRunSeq)) {
      reversePriorRun(groupId, period, closeRunSeq, priorSummary.getCloseRunSeq(), lead);
      priorSummary.transitionTo(ConsolidationState.SUPERSEDED);
      summaryRepository.save(priorSummary);
    }

    // NOTE (flagged-simplified scope): the ELIMINATION and FX_TRANSLATION_DIFF postings below are
    // CAPTURED and FLAGGED on the ledger, but they are NOT yet part of a balanced double-entry set
    // (their own contra/OCI legs are absent) — completing them into full double-entry is the
    // deferred
    // #37 accounting-SME gate. See SimplifiedTranslationPolicy.
    Money zeroReporting = Money.ofMinor(0L, reportingCurrency);
    Money eliminatedRevenue = zeroReporting;
    Money eliminatedExpense = zeroReporting;
    for (IntercompanyMatch match : matches) {
      List<TranslatedLine> refLegs = translatedByRef.get(match.getIntercompanyRef());
      if (match.eliminates()) {
        // SAME-CURRENCY MATCHED pair (3a strict invariant): exactly one REVENUE + one EXPENSE leg,
        // both strictly positive, equal NATIVE magnitude, two members. Eliminate the TRANSLATED leg
        // amounts by class. Both legs are in the same source currency, so they translate via the
        // same AVERAGE rate -> equal translated amounts -> group net unchanged, exactly as in 3a.
        Money revenueLeg = translatedLegOfType(refLegs, AccountType.REVENUE);
        Money expenseLeg = translatedLegOfType(refLegs, AccountType.EXPENSE);
        postElimination(
            groupId,
            period,
            closeRunSeq,
            match.getIntercompanyRef(),
            match.revenueLegMember(),
            match.expenseLegMember(),
            revenueLeg,
            expenseLeg,
            lead);
        eliminatedRevenue = eliminatedRevenue.plus(revenueLeg);
        eliminatedExpense = eliminatedExpense.plus(expenseLeg);
      } else if (match.isCrossCurrency()) {
        // CROSS-CURRENCY MATCHED pair (SEAM 3b, SIMPLIFIED): the two legs are in different
        // currencies, so their native magnitudes are not comparable. Each leg is translated into
        // the
        // reporting currency at the AVERAGE rate; we eliminate each TRANSLATED leg from its own
        // account class, then post the residual (translatedRevenueLeg - translatedExpenseLeg)
        // between the two translated legs as a flagged FX_TRANSLATION_DIFF entry — the
        // cross-currency
        // residual is captured and flagged, NEVER silently absorbed. (This is the simplified
        // policy;
        // a full IAS-21 treatment of intra-group FX is part of the deferred SME gate — see
        // SimplifiedTranslationPolicy.)
        Money revenueLeg = translatedLegOfType(refLegs, AccountType.REVENUE);
        Money expenseLeg = translatedLegOfType(refLegs, AccountType.EXPENSE);
        postElimination(
            groupId,
            period,
            closeRunSeq,
            match.getIntercompanyRef(),
            match.revenueLegMember(),
            match.expenseLegMember(),
            revenueLeg,
            expenseLeg,
            lead);
        eliminatedRevenue = eliminatedRevenue.plus(revenueLeg);
        eliminatedExpense = eliminatedExpense.plus(expenseLeg);
        Money fxDiff = revenueLeg.minus(expenseLeg);
        postFxTranslationDiff(
            groupId, period, closeRunSeq, match.getIntercompanyRef(), fxDiff, lead);
        log.info(
            "Group close groupId={} period={} cross-currency IC ref={} eliminated translated"
                + " revenueLeg={} expenseLeg={}, FX_TRANSLATION_DIFF residual={} (flagged)",
            groupId,
            period,
            match.getIntercompanyRef(),
            revenueLeg,
            expenseLeg,
            fxDiff);
      }
      // OUT_OF_SCOPE_BALANCE_SHEET reaches here but neither eliminates() nor isCrossCurrency() — it
      // is recorded only, posting no contra (unchanged from 3a).
    }

    // (g) COMPUTE: sum the TRANSLATED trial balances by account class, then subtract the eliminated
    //     internal trade. Every figure is in the reporting currency (translated above), so
    // Money.plus
    //     never throws on a currency mismatch.
    Money grossRevenue =
        sumTranslatedByAccountType(translatedLines, AccountType.REVENUE, reportingCcy);
    Money grossExpense =
        sumTranslatedByAccountType(translatedLines, AccountType.EXPENSE, reportingCcy);
    Money groupRevenue = grossRevenue.minus(eliminatedRevenue);
    Money groupExpense = grossExpense.minus(eliminatedExpense);

    // (h) BALANCE-CHECK + FLAGGED CTA (the integrity gate, SEAM 3b). Each member native book
    // ALREADY
    //     balanced (the native balance gate above HELD otherwise), so the ONLY reason the
    // TRANSLATED
    //     trial balance does not sum to zero is the closing-vs-average FX effect: balance-sheet
    // items
    //     translate at CLOSING, P&L items at AVERAGE. Compute the signed residual of the TRANSLATED
    //     trial balance and post its negation to the explicitly-flagged CTA / translation-reserve
    //     account (3950-CTA-TRANSLATION-RESERVE), so the translated set BALANCES — the CTA is now
    // the
    //     GENUINE FX effect, NOT a swept-in raw imbalance. GATE the close on it MEANINGFULLY: after
    //     the CTA posts, assert signed(translated TB) + sum(CTA that LANDED in 3950) == 0, with the
    //     residual operand rebuilt from the PER-ACCOUNT-CLASS totals (a fold-by-class, NOT a re-run
    //     of the per-line signedTrialBalanceResidual that produced the CTA) and the CTA RE-READ
    // from
    //     the ledger — two structurally different paths, so a bug in either cannot cancel on both
    //     sides. The residual is NEVER silently dropped, and the gate is no longer the x + (-x)
    //     tautology. (This is the SIMPLIFIED balancing device, NOT the IAS-21 CTA = closing net
    //     assets − opening net assets at the opening rate − average-rate P&L, which needs opening
    //     balances + historical-rate equity — the deferred SME gate. See
    // SimplifiedTranslationPolicy.)
    //
    //     ONLY for a TRANSLATED (multi-currency) close: a same-currency (3a) close performs no
    //     translation, so there is no translation residual and no CTA — its (P&L-only) figures are
    //     consolidated exactly as before (the 3a model never carried full balancing trial balances,
    //     so a signed-residual CTA must not appear for it). The residual the CTA balances is the
    //     CLOSING-vs-AVERAGE translation effect, which is zero without translation.
    Money translatedSignedResidual =
        usesSimplifiedTranslation
            ? signedTrialBalanceResidual(translatedLines, reportingCcy)
            : Money.ofMinor(0L, reportingCcy);
    if (!translatedSignedResidual.isZero()) {
      // Every member native book balanced (the gate above HELD otherwise), so this residual is
      // PURELY the closing-vs-average FX effect — a genuine translation adjustment, NOT a raw
      // imbalance. Post its negation to the flagged CTA reserve so the translated set reconciles.
      Money cta = translatedSignedResidual.negate();
      postCta(groupId, period, closeRunSeq, cta, lead);

      // The integrity GATE — a GENUINE check, not the x + (-x) tautology, and not a re-run of the
      // SAME function over the SAME list either. It asserts: signed(translated TB) + sum(CTA that
      // ACTUALLY LANDED in 3950) == 0, with BOTH operands derived along STRUCTURALLY DIFFERENT
      // paths
      // from the ones that produced the CTA we posted:
      //   * the residual operand is rebuilt from the PER-ACCOUNT-CLASS translated totals (one
      //     sumTranslatedByAccountType per class, then the sign convention applied to the five
      // class
      //     totals) — a fold-by-class, NOT the line-by-line fold of signedTrialBalanceResidual that
      //     computed `cta`. A bug in signedTrialBalanceResidual therefore cannot cancel on both
      //     sides of the check;
      //   * the CTA operand is RE-READ from the ledger (what landed in 3950), not the in-memory
      //     `cta`.
      // So a future refactor that breaks the invariant (a wrong CTA magnitude, or a native
      // imbalance
      // slipping through) fails LOUD rather than silently dropping money.
      Money recomputedResidual = signedResidualFromClassTotals(translatedLines, reportingCcy);
      Money postedCta = postedCtaTotal(groupId, period, closeRunSeq, reportingCcy);
      Money reconciledTotal = recomputedResidual.plus(postedCta);
      if (!reconciledTotal.isZero()) {
        throw new IllegalStateException(
            "Translated consolidation did not balance after the CTA residual: "
                + reconciledTotal
                + " (independently recomputed translated residual="
                + recomputedResidual
                + " + posted CTA="
                + postedCta
                + ", group="
                + groupId
                + " period="
                + period
                + ")");
      }
      log.info(
          "Group close groupId={} period={} SIMPLIFIED-TRANSLATION CTA posted: residual={} ->"
              + " 3950-CTA-TRANSLATION-RESERVE (FLAGGED PROVISIONAL — not IAS-21, accounting SME gate)",
          groupId,
          period,
          cta);
    }

    ConsolidationSummary summary =
        new ConsolidationSummary(
            groupId,
            period,
            reportingCurrency,
            groupRevenue,
            groupExpense,
            ConsolidationState.CLOSED,
            usesIllustrative,
            usesStubFx,
            usesSimplifiedTranslation,
            closeRunSeq);
    summary.setCompanyId(lead);
    summaryRepository.save(summary);

    // (i) EMIT ConsolidationClosed(company_id = lead, group_id = G, period) via the OUTBOX, atomic
    //     with this close tx (rule 3). ONLY reached on a real CLOSED close — every HELD state (
    //     PENDING_MEMBERS, MEMBER_TRIAL_BALANCE_UNBALANCED, INTERCOMPANY_UNRECONCILED,
    //     PENDING_MEMBERS_WARMING_UP) returned early via holdSummary above and emits NOTHING, so
    // the
    //     event fires iff the consolidation is genuinely final ("never present a mid-flight
    //     consolidation as final"). IDEMPOTENT: the emission is claimed in the ProcessedEventStore
    //     under a deterministic (group, period, close_run_seq) key, so a re-delivery at the SAME
    // seq
    //     is a clean no-op (no double-emit), while a re-close at a HIGHER seq derives a fresh key
    // and
    //     legitimately emits a fresh event. The outbox row commits with the summary, so if the
    // close
    //     rolls back the event is never published.
    emitConsolidationClosed(groupId, period, closeRunSeq, lead);

    log.info(
        "Group close groupId={} period={} closeRunSeq={} CLOSED: revenue={} expense={} net={}"
            + " (eliminated internal trade revenue={} expense={}, illustrative={}, stubFx={},"
            + " simplifiedTranslation={})",
        groupId,
        period,
        closeRunSeq,
        groupRevenue,
        groupExpense,
        groupRevenue.minus(groupExpense),
        eliminatedRevenue,
        eliminatedExpense,
        usesIllustrative,
        usesStubFx,
        usesSimplifiedTranslation);

    return new GroupCloseResult(ConsolidationState.CLOSED, summary.getId(), closeRunSeq, true);
  }

  // ----------------------------------------------------------------------- translation (SEAM 3b)

  /**
   * Translates one trial-balance line into the group reporting currency by {@code account_type}
   * (SEAM 3b — SIMPLIFIED): ASSET / LIABILITY / EQUITY at the CLOSING (period-end) rate; REVENUE /
   * EXPENSE at the AVERAGE (period) rate. A line already in the reporting currency short-circuits
   * to the identity inside {@link TranslationService}. A missing rate throws {@link
   * id.co.nativeapp.finance.fx.domain.MissingFxRateException}, which fails the whole close
   * atomically. See {@link SimplifiedTranslationPolicy}.
   */
  private Translated translate(GroupTrialBalanceLine line, String period, Currency reportingCcy) {
    return switch (line.getAccountType()) {
      case ASSET, LIABILITY, EQUITY ->
          translationService.translateBalance(line.getAmount(), period, reportingCcy);
      case REVENUE, EXPENSE ->
          translationService.translateFlow(line.getAmount(), period, reportingCcy);
    };
  }

  // ----------------------------------------------------------------------- intercompany matching

  /**
   * Pairs related-party legs on {@code (intercompany_ref, period)}. Each line that carries an
   * {@code intercompany_ref} is one leg, owned by its {@code member_company_id}; the legs of a ref
   * are a candidate pair. The result, one {@link IntercompanyMatch} per reference, where ONLY a
   * STRICTLY well-formed P&amp;L pair is MATCHED, a pure balance-sheet pair is a non-blocking SKIP,
   * and everything else BLOCKS.
   *
   * <p><strong>3a IC SCOPE — P&amp;L ONLY.</strong> SEAM 3a consolidates only the P&amp;L
   * (revenue/expense -&gt; net). Intercompany ELIMINATION here therefore targets the P&amp;L:
   * exactly one REVENUE leg (the internal sale) + one EXPENSE leg (the internal purchase). A pure
   * balance-sheet IC ref (e.g. an IC receivable=ASSET + IC payable=LIABILITY pair) does NOT affect
   * the P&amp;L net, so it is OUT OF 3a SCOPE: recorded as {@code OUT_OF_SCOPE_BALANCE_SHEET}, NOT
   * eliminated, and NON-BLOCKING (balance-sheet IC elimination is a future / SEAM 3b concern —
   * never make the group un-closable for it).
   *
   * <p><strong>STRICT well-formedness — sign-blindness fabricates net.</strong> The roll-up sums
   * the SIGNED line amount, so a MATCH must be derived from the same signed reality: a leg amount
   * that is ZERO or NEGATIVE ({@code amountMinor <= 0}) is MALFORMED. Without this, a REVENUE(+X) +
   * EXPENSE(-X) pair (equal abs, opposite sign) would pass a sign-blind abs-equality test, but the
   * -X sits in gross expense while the elimination subtracts another +X -> group expense
   * double-counts the negative -> fabricated net. There is no non-negativity guard at
   * ingest/schema, so it is enforced HERE.
   *
   * <p>The classification, in order:
   *
   * <ul>
   *   <li>only one leg (the counterparty did not report) -> UNMATCHED;
   *   <li>MORE THAN TWO legs for the ref -> MALFORMED (the extra legs are summed into gross today;
   *       silently truncating to the first two would leave money un-eliminated and un-flagged);
   *   <li>both legs belong to the SAME member (a self-deal) -> MALFORMED;
   *   <li>BOTH legs are balance-sheet account types (ASSET/LIABILITY/EQUITY) ->
   *       OUT_OF_SCOPE_BALANCE_SHEET (non-blocking, not eliminated — out of 3a P&amp;L scope);
   *   <li>a MIXED ref (one P&amp;L leg + one balance-sheet leg) -> MALFORMED (genuinely malformed);
   *   <li>the two P&amp;L legs are NOT exactly one {@code REVENUE} + one {@code EXPENSE} leg (e.g.
   *       two revenue legs) -> MALFORMED (eliminating would leave internal revenue un-removed AND
   *       fabricate a phantom contra against the wrong class);
   *   <li>EITHER leg amount is ZERO or NEGATIVE ({@code amountMinor <= 0}) -> MALFORMED (a
   *       sign-blind match against a non-positive leg fabricates net);
   *   <li>a one-revenue+one-expense, both-strictly-positive, SAME-currency pair of UNEQUAL
   *       magnitude -> AMOUNT_MISMATCH;
   *   <li>a one-revenue+one-expense, both-strictly-positive, SAME-currency pair of EQUAL magnitude
   *       -> MATCHED (eliminated in the shared currency — the 3a strict invariant);
   *   <li>a one-revenue+one-expense, both-strictly-positive pair in DIFFERENT currencies (SEAM 3b)
   *       -> MATCHED_CROSS_CURRENCY (non-blocking; eliminated via the translated legs + a flagged
   *       FX_TRANSLATION_DIFF residual — the native magnitudes are not comparable, so no
   *       AMOUNT_MISMATCH check applies).
   * </ul>
   *
   * <p>References are processed in a deterministic (sorted) order so the persisted rows and any
   * derived synthetic ids are reproducible across runs. The {@code IntercompanyMatch} row records
   * each leg's NATIVE amount + account class (the source of record); the close eliminates the
   * TRANSLATED amounts.
   */
  private List<IntercompanyMatch> reconcileIntercompany(
      UUID groupId, String period, int closeRunSeq, List<TranslatedLine> lines) {
    Map<String, List<TranslatedLine>> byRef = legsByRef(lines);

    List<IntercompanyMatch> matches = new ArrayList<>();
    for (Map.Entry<String, List<TranslatedLine>> entry : byRef.entrySet()) {
      String ref = entry.getKey();
      List<TranslatedLine> legs = entry.getValue();
      TranslatedLine legA = legs.get(0);

      // One leg only: the counterparty never reported -> UNMATCHED (nothing to eliminate against).
      if (legs.size() == 1) {
        matches.add(unmatched(groupId, period, ref, legA, closeRunSeq));
        continue;
      }

      // MORE THAN TWO legs: a well-formed intercompany ref has EXACTLY two counterparty legs. The
      // extra legs were already summed into gross by the roll-up; truncating to the first two and
      // eliminating would silently leave the surplus un-eliminated AND un-flagged. Block it.
      if (legs.size() > 2) {
        matches.add(malformed(groupId, period, ref, legA, legs.get(1), closeRunSeq));
        continue;
      }

      TranslatedLine legB = legs.get(1);

      // SELF-DEAL: both lines carry the same ref from the SAME member. Not an intercompany trade
      // between two members; eliminating it would invent a contra against a single member. Block.
      if (legA.memberCompanyId().equals(legB.memberCompanyId())) {
        matches.add(malformed(groupId, period, ref, legA, legB, closeRunSeq));
        continue;
      }

      // IC SCOPE — P&L ONLY. Classify each leg as P&L (REVENUE/EXPENSE) or balance-sheet
      // (ASSET/LIABILITY/EQUITY). The close consolidates only the P&L; a pure balance-sheet IC ref
      // does not affect the P&L net.
      boolean aIsPnl = isPnl(legA.accountType());
      boolean bIsPnl = isPnl(legB.accountType());

      // BOTH legs balance-sheet (e.g. IC receivable=ASSET + IC payable=LIABILITY): OUT OF P&L
      // SCOPE.
      // A NON-BLOCKING skip — record it, do NOT eliminate it, do NOT block the close.
      if (!aIsPnl && !bIsPnl) {
        matches.add(
            classified(
                groupId,
                period,
                ref,
                legA,
                legB,
                IntercompanyMatchState.OUT_OF_SCOPE_BALANCE_SHEET,
                closeRunSeq));
        continue;
      }

      // MIXED ref: one P&L leg + one balance-sheet leg. Genuinely malformed — block.
      if (aIsPnl != bIsPnl) {
        matches.add(malformed(groupId, period, ref, legA, legB, closeRunSeq));
        continue;
      }

      // ACCOUNT-CLASS well-formedness (both P&L here): a real intercompany trade is exactly ONE
      // revenue leg (the internal sale) + ONE expense leg (the internal purchase). Two same-class
      // legs (e.g. two REVENUE legs) are NOT eliminable by class — block rather than mis-eliminate.
      boolean oneRevenueOneExpense =
          (legA.accountType() == AccountType.REVENUE && legB.accountType() == AccountType.EXPENSE)
              || (legA.accountType() == AccountType.EXPENSE
                  && legB.accountType() == AccountType.REVENUE);
      if (!oneRevenueOneExpense) {
        matches.add(malformed(groupId, period, ref, legA, legB, closeRunSeq));
        continue;
      }

      // STRICT non-negativity (on the NATIVE amounts): a ZERO or NEGATIVE leg fabricates net under
      // the signed roll-up. Block it MALFORMED.
      if (!legA.line().getAmount().isPositive() || !legB.line().getAmount().isPositive()) {
        matches.add(malformed(groupId, period, ref, legA, legB, closeRunSeq));
        continue;
      }

      // CROSS-CURRENCY (SEAM 3b): the two legs are in different source currencies, so their native
      // magnitudes are not comparable for equality. This is NOT an AMOUNT_MISMATCH — it is a
      // well-formed cross-currency pair, MATCHED_CROSS_CURRENCY: the close translates each leg and
      // posts the FX residual as a flagged FX_TRANSLATION_DIFF (non-blocking).
      boolean sameCurrency =
          legA.line().getAmount().currency().equals(legB.line().getAmount().currency());
      if (!sameCurrency) {
        matches.add(
            classified(
                groupId,
                period,
                ref,
                legA,
                legB,
                IntercompanyMatchState.MATCHED_CROSS_CURRENCY,
                closeRunSeq));
        continue;
      }

      // Both strictly positive, one-revenue+one-expense, SAME-currency pair: equal magnitude ->
      // MATCHED, else AMOUNT_MISMATCH (the 3a strict invariant, unchanged).
      //
      // SELF-DEFENDING MATCHED DECISION (#40): a same-currency MATCH is decided by an EXPLICIT
      // currency-equality conjunction, not just by Money.equals (which happens to compare currency
      // too). Same-currency is already gated upstream (the cross-currency branch above diverted to
      // MATCHED_CROSS_CURRENCY), so this conjunction is an ASSERTED INVARIANT: even if a future
      // gate-ordering accident let a cross-currency pair fall through to here, a same-magnitude
      // pair
      // in DIFFERENT currencies can NEVER be MATCHED (it would AMOUNT_MISMATCH-block, not eliminate
      // against an incomparable magnitude). The matcher defends its own MATCHED precondition.
      Money amountA = legA.line().getAmount();
      Money amountB = legB.line().getAmount();
      boolean sameCurrencyEqualMagnitude =
          amountA.currency().equals(amountB.currency()) && amountA.equals(amountB);
      IntercompanyMatchState state =
          sameCurrencyEqualMagnitude
              ? IntercompanyMatchState.MATCHED
              : IntercompanyMatchState.AMOUNT_MISMATCH;
      matches.add(
          new IntercompanyMatch(
              groupId,
              period,
              ref,
              legA.memberCompanyId(),
              legB.memberCompanyId(),
              amountA,
              amountB,
              legA.accountType(),
              legB.accountType(),
              state,
              closeRunSeq));
    }
    return matches;
  }

  /**
   * Groups the intercompany legs by {@code intercompany_ref}, preserving a deterministic ref order
   * (sorted refs) and a deterministic leg order within a ref (sorted by member id), so the
   * persisted rows and derived synthetic ids are reproducible. Non-intercompany lines (null ref)
   * are excluded.
   */
  private static Map<String, List<TranslatedLine>> legsByRef(List<TranslatedLine> lines) {
    Map<String, List<TranslatedLine>> byRef = new LinkedHashMap<>();
    Set<String> refs = new TreeSet<>();
    for (TranslatedLine line : lines) {
      String ref = line.intercompanyRef();
      if (ref != null) {
        refs.add(ref);
      }
    }
    for (String ref : refs) {
      List<TranslatedLine> legs =
          lines.stream()
              .filter(l -> ref.equals(l.intercompanyRef()))
              .sorted(Comparator.comparing(l -> l.memberCompanyId().toString()))
              .toList();
      byRef.put(ref, legs);
    }
    return byRef;
  }

  /**
   * The TRANSLATED amount of the leg of the given account class in a matched ref's two legs. Only
   * meaningful for an eliminating pair (a one-REVENUE + one-EXPENSE pair), where exactly one leg
   * has the requested class.
   */
  private static Money translatedLegOfType(List<TranslatedLine> refLegs, AccountType type) {
    return refLegs.stream()
        .filter(l -> l.accountType() == type)
        .map(TranslatedLine::translated)
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("Matched IC pair missing its " + type + " leg"));
  }

  private static IntercompanyMatch unmatched(
      UUID groupId, String period, String ref, TranslatedLine legA, int closeRunSeq) {
    return new IntercompanyMatch(
        groupId,
        period,
        ref,
        legA.memberCompanyId(),
        null,
        legA.line().getAmount(),
        null,
        legA.accountType(),
        null,
        IntercompanyMatchState.UNMATCHED,
        closeRunSeq);
  }

  private static IntercompanyMatch malformed(
      UUID groupId,
      String period,
      String ref,
      TranslatedLine legA,
      TranslatedLine legB,
      int closeRunSeq) {
    return classified(
        groupId, period, ref, legA, legB, IntercompanyMatchState.MALFORMED, closeRunSeq);
  }

  /** Builds a two-leg {@link IntercompanyMatch} in the given (non-MATCHED-decision) state. */
  private static IntercompanyMatch classified(
      UUID groupId,
      String period,
      String ref,
      TranslatedLine legA,
      TranslatedLine legB,
      IntercompanyMatchState state,
      int closeRunSeq) {
    return new IntercompanyMatch(
        groupId,
        period,
        ref,
        legA.memberCompanyId(),
        legB.memberCompanyId(),
        legA.line().getAmount(),
        legB.line().getAmount(),
        legA.accountType(),
        legB.accountType(),
        state,
        closeRunSeq);
  }

  /** Whether an account class is a P&L (income-statement) class: REVENUE or EXPENSE. */
  private static boolean isPnl(AccountType type) {
    return type == AccountType.REVENUE || type == AccountType.EXPENSE;
  }

  // ----------------------------------------------------------------------- producer (SEAM 4a)

  /**
   * Emits {@code ConsolidationClosed(company_id = lead, group_id = G, period)} via the outbox for a
   * GROUP close that reached CLOSED (P3d SEAM 4a — the loop closer). Claimed in the {@link
   * ProcessedEventStore} under the deterministic {@code (group, period, close_run_seq)} emission
   * key so a re-delivery at the SAME seq never double-emits, while a re-close at a HIGHER seq emits
   * a fresh event. The outbox INSERT runs on the close's transactional connection, so the event
   * commits atomically with the summary (rule 3) — never published on a rolled-back close.
   */
  private void emitConsolidationClosed(UUID groupId, String period, int closeRunSeq, String lead) {
    UUID emitKey = ConsolidationEntryIds.forConsolidationClosedEmit(groupId, period, closeRunSeq);
    UUID leadCompanyId = UUID.fromString(lead);
    processedEvents.processOnce(
        emitKey,
        () -> {
          GenericRecord event = ConsolidationClosedSchema.toRecord(leadCompanyId, groupId, period);
          outboxWriter.write(
              ConsolidationClosedSchema.AGGREGATE_TYPE,
              groupId.toString(),
              ConsolidationClosedSchema.EVENT_TYPE,
              AvroSerde.serialize(event),
              null,
              leadCompanyId,
              clock.instant());
          log.info(
              "Group close groupId={} period={} closeRunSeq={} emitted ConsolidationClosed"
                  + " (lead={}, group scope) via the outbox",
              groupId,
              period,
              closeRunSeq,
              leadCompanyId);
        });
  }

  // ----------------------------------------------------------------------- posting helpers

  /**
   * Posts the PRIMARY ELIMINATION contras (negative revenue + negative expense) for a matched ref.
   * The revenue contra negates the REVENUE leg amount and the expense contra negates the EXPENSE
   * leg amount SEPARATELY (equal for a well-formed pair, so group net is unchanged). Each contra is
   * stamped with the member that OWNS that leg class ({@code revenueMember} / {@code
   * expenseMember}) for per-leg traceability.
   */
  private void postElimination(
      UUID groupId,
      String period,
      int closeRunSeq,
      String intercompanyRef,
      UUID revenueMember,
      UUID expenseMember,
      Money revenueMagnitude,
      Money expenseMagnitude,
      String lead) {
    // Two contra legs per matched ref: one against intercompany revenue, one against intercompany
    // expense (the internal sale and the internal purchase both leave the group). Each is its own
    // append-only entry with a deterministic, collision-free synthetic source_entry_id, and is
    // stamped with the member that owns its leg class (not memberA for both).
    appendPrimaryElimination(
        groupId,
        period,
        closeRunSeq,
        intercompanyRef + ":REVENUE",
        IC_REVENUE_ELIMINATION_ACCOUNT,
        revenueMagnitude.negate(),
        revenueMember,
        lead);
    appendPrimaryElimination(
        groupId,
        period,
        closeRunSeq,
        intercompanyRef + ":EXPENSE",
        IC_EXPENSE_ELIMINATION_ACCOUNT,
        expenseMagnitude.negate(),
        expenseMember,
        lead);
  }

  private void appendPrimaryElimination(
      UUID groupId,
      String period,
      int closeRunSeq,
      String elimKey,
      String account,
      Money contra,
      UUID sourceMember,
      String lead) {
    UUID sourceEntryId =
        ConsolidationEntryIds.forElimination(groupId, period, closeRunSeq, elimKey);
    appendPrimary(
        groupId,
        period,
        closeRunSeq,
        ConsolidationEntryType.ELIMINATION,
        account,
        contra,
        elimKey,
        sourceMember,
        sourceEntryId,
        lead);
  }

  /**
   * Posts the SEAM-3b SIMPLIFIED CTA / translation-reserve balancing entry (the residual of the
   * translated trial balance) to {@link
   * SimplifiedTranslationPolicy#CTA_TRANSLATION_RESERVE_ACCOUNT}. A PRIMARY entry (reversed
   * append-only on supersession, exactly like an elimination) with a deterministic id, so a
   * re-close at the same seq is idempotent. FLAGGED PROVISIONAL — NOT the IAS-21 CTA (see {@link
   * SimplifiedTranslationPolicy}).
   */
  private void postCta(UUID groupId, String period, int closeRunSeq, Money cta, String lead) {
    UUID sourceEntryId = ConsolidationEntryIds.forCta(groupId, period, closeRunSeq);
    appendPrimary(
        groupId,
        period,
        closeRunSeq,
        ConsolidationEntryType.CTA,
        SimplifiedTranslationPolicy.CTA_TRANSLATION_RESERVE_ACCOUNT,
        cta,
        null,
        null,
        sourceEntryId,
        lead);
  }

  /**
   * Posts the SEAM-3b cross-currency intercompany FX residual (the difference between the two
   * TRANSLATED legs of a {@code MATCHED_CROSS_CURRENCY} pair) to {@link
   * SimplifiedTranslationPolicy#FX_TRANSLATION_DIFF_ACCOUNT} as a flagged {@link
   * ConsolidationEntryType#FX_TRANSLATION_DIFF} entry — the residual is captured and flagged, never
   * silently absorbed. A PRIMARY entry with a deterministic per-ref id (idempotent under re-close).
   */
  private void postFxTranslationDiff(
      UUID groupId,
      String period,
      int closeRunSeq,
      String intercompanyRef,
      Money residual,
      String lead) {
    UUID sourceEntryId =
        ConsolidationEntryIds.forFxTranslationDiff(groupId, period, closeRunSeq, intercompanyRef);
    appendPrimary(
        groupId,
        period,
        closeRunSeq,
        ConsolidationEntryType.FX_TRANSLATION_DIFF,
        SimplifiedTranslationPolicy.FX_TRANSLATION_DIFF_ACCOUNT,
        residual,
        intercompanyRef,
        null,
        sourceEntryId,
        lead);
  }

  /**
   * Appends ONE PRIMARY consolidation ledger entry of the given {@link ConsolidationEntryType},
   * claiming its deterministic synthetic id in the {@link ProcessedEventStore} (idempotent: a
   * re-close at the same seq derives the same id and this is a clean no-op, also backstopped by the
   * UNIQUE {@code source_entry_id} column). The shared posting primitive behind every PRIMARY entry
   * — eliminations, the CTA, and the cross-currency FX_TRANSLATION_DIFF — so they all reverse
   * uniformly on supersession via {@link #reversePriorRun}.
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  private void appendPrimary(
      UUID groupId,
      String period,
      int closeRunSeq,
      ConsolidationEntryType entryType,
      String account,
      Money amount,
      String intercompanyRef,
      UUID sourceMember,
      UUID sourceEntryId,
      String lead) {
    processedEvents.processOnce(
        sourceEntryId,
        () -> {
          ConsolidationLedgerEntry entry =
              new ConsolidationLedgerEntry(
                  groupId,
                  period,
                  entryType,
                  account,
                  amount,
                  intercompanyRef,
                  sourceMember,
                  ConsolidationPostingRole.PRIMARY,
                  closeRunSeq,
                  sourceEntryId);
          entry.setCompanyId(lead);
          ledgerRepository.save(entry);
        });
  }

  /**
   * Reverses every PRIMARY entry of a prior close run append-only (#23): one REVERSAL contra per
   * PRIMARY (amount negated, role REVERSAL, a deterministic collision-free synthetic id).
   * Idempotent — a re-delivered superseding close derives the same reversal ids and claims nothing.
   * Reverses CTA / FX_TRANSLATION_DIFF entries uniformly with eliminations (all PRIMARY rows).
   */
  private void reversePriorRun(
      UUID groupId, String period, int closeRunSeq, int priorSeq, String lead) {
    for (ConsolidationLedgerEntry prior :
        ledgerRepository.findPriorPrimaries(groupId, period, priorSeq)) {
      UUID reversalId =
          ConsolidationEntryIds.forReversal(groupId, period, closeRunSeq, prior.getId());
      processedEvents.processOnce(
          reversalId,
          () -> {
            ConsolidationLedgerEntry reversal =
                new ConsolidationLedgerEntry(
                    groupId,
                    period,
                    ConsolidationEntryType.REVERSAL,
                    prior.getGlAccountCode(),
                    prior.getAmount().negate(),
                    prior.getIntercompanyRef(),
                    prior.getSourceMemberCompanyId(),
                    ConsolidationPostingRole.REVERSAL,
                    closeRunSeq,
                    reversalId);
            reversal.setCompanyId(lead);
            ledgerRepository.save(reversal);
          });
    }
  }

  // ----------------------------------------------------------------------- compute helpers

  /**
   * Sums the TRANSLATED amounts of the lines of one account class (every figure is already in the
   * reporting currency, so {@code Money.plus} never throws on a mismatch).
   */
  private static Money sumTranslatedByAccountType(
      List<TranslatedLine> lines, AccountType type, Currency reportingCcy) {
    Money sum = Money.ofMinor(0L, reportingCcy);
    for (TranslatedLine line : lines) {
      if (line.accountType() == type) {
        sum = sum.plus(line.translated());
      }
    }
    return sum;
  }

  /**
   * The SIGNED residual of the TRANSLATED trial balance under the double-entry sign convention
   * (DEBIT classes positive, CREDIT classes negative): {@code (ASSET + EXPENSE) − (LIABILITY +
   * EQUITY + REVENUE)}, all translated.
   *
   * <p>A trial balance in the member's FUNCTIONAL currency sums these signed amounts to ZERO
   * (debits = credits). After translation — balance-sheet at CLOSING, P&amp;L at AVERAGE — the
   * signed total is no longer zero; that residual is the SIMPLIFIED translation adjustment,
   * balanced to the flagged CTA reserve so the consolidated set reconciles. (This is the simplified
   * balancing device, NOT the IAS-21 CTA — see {@link SimplifiedTranslationPolicy}.)
   */
  private static Money signedTrialBalanceResidual(
      List<TranslatedLine> lines, Currency reportingCcy) {
    Money signed = Money.ofMinor(0L, reportingCcy);
    for (TranslatedLine line : lines) {
      Money amount = line.translated();
      signed = applySignConvention(signed, line.accountType(), amount);
    }
    return signed;
  }

  /**
   * The SAME signed residual as {@link #signedTrialBalanceResidual}, but computed along a
   * STRUCTURALLY DIFFERENT path: it first rolls the translated lines into ONE total PER ACCOUNT
   * CLASS (via {@link #sumTranslatedByAccountType}, the very aggregation that feeds the summary),
   * then applies the double-entry sign convention to those five class totals. The per-line fold and
   * this fold-by-class must agree, so the post-CTA integrity gate (#41) can use this as an
   * INDEPENDENT recompute of the residual operand: a bug in the line-by-line {@code
   * signedTrialBalanceResidual} that produced the posted CTA cannot also corrupt this side of the
   * check, so the two cannot cancel and hide a real imbalance.
   */
  private static Money signedResidualFromClassTotals(
      List<TranslatedLine> lines, Currency reportingCcy) {
    Money signed = Money.ofMinor(0L, reportingCcy);
    for (AccountType type : AccountType.values()) {
      Money classTotal = sumTranslatedByAccountType(lines, type, reportingCcy);
      signed = applySignConvention(signed, type, classTotal);
    }
    return signed;
  }

  /**
   * The DEBIT/CREDIT sign convention applied to one line's magnitude: DEBIT-normal classes (ASSET,
   * EXPENSE) add positively; CREDIT-normal classes (LIABILITY, EQUITY, REVENUE) subtract. The
   * magnitudes are stored positive; the sign is applied here, not at ingest. Shared by the
   * post-translation residual (in the reporting currency) and the per-member NATIVE residual (in
   * the member's functional currency).
   */
  private static Money applySignConvention(Money running, AccountType type, Money magnitude) {
    return switch (type) {
      case ASSET, EXPENSE -> running.plus(magnitude);
      case LIABILITY, EQUITY, REVENUE -> running.minus(magnitude);
    };
  }

  /**
   * The active members (sorted, deterministic) whose NATIVE trial balance does NOT pass the SEAM-3b
   * pre-translation gate: EITHER its signed double-entry residual in its OWN functional currency is
   * non-zero (debits ≠ credits — its books do not balance), OR the member published it {@code
   * reconciled = false}. An empty result means every member book balances natively and is
   * reconciled, so the close may translate and the post-translation residual is PURELY the
   * closing-vs-average FX effect (never a swept-in raw imbalance).
   *
   * <p>The native residual is computed PER MEMBER in that member's functional currency (every line
   * of one member is in one currency — the member's base currency — so {@code Money.plus}/{@code
   * minus} never cross currencies here). A member with no lines cannot reach this method (the
   * member-completeness gate already HELD).
   */
  private static List<UUID> unbalancedNativeMembers(
      List<GroupTrialBalanceLine> lines, Set<UUID> activeMemberIds) {
    // member -> running native signed residual (in the member's functional currency).
    Map<UUID, Money> nativeResidualByMember = new LinkedHashMap<>();
    // member -> whether the member published reconciled (member-level, identical across its lines).
    Map<UUID, Boolean> reconciledByMember = new LinkedHashMap<>();
    for (GroupTrialBalanceLine line : lines) {
      UUID member = line.getMemberCompanyId();
      Money amount = line.getAmount();
      Money running =
          nativeResidualByMember.computeIfAbsent(member, m -> Money.ofMinor(0L, amount.currency()));
      nativeResidualByMember.put(
          member, applySignConvention(running, line.getAccountType(), amount));
      reconciledByMember.merge(member, line.isReconciled(), Boolean::logicalAnd);
    }
    Set<UUID> unbalanced = new TreeSet<>();
    for (UUID member : activeMemberIds) {
      Money residual = nativeResidualByMember.get(member);
      boolean reconciled = Boolean.TRUE.equals(reconciledByMember.get(member));
      // A non-zero native residual (books do not balance) OR a member-reported reconciled=false
      // HOLDs
      // the close. residual is non-null: a member in activeMemberIds always has lines here (the
      // completeness gate already returned otherwise).
      if (residual == null || !residual.isZero() || !reconciled) {
        unbalanced.add(member);
      }
    }
    return List.copyOf(unbalanced);
  }

  /**
   * Sums the PRIMARY {@link ConsolidationEntryType#CTA} amounts this close run ACTUALLY posted,
   * re-read from the ledger (independently of the in-memory CTA the close computed). The SEAM-3b
   * integrity gate adds this to an independently-recomputed translated-TB residual and asserts zero
   * — a real check on a validated-balanced input, not the {@code x + (-x)} tautology.
   */
  private Money postedCtaTotal(
      UUID groupId, String period, int closeRunSeq, Currency reportingCcy) {
    Money sum = Money.ofMinor(0L, reportingCcy);
    for (ConsolidationLedgerEntry cta :
        ledgerRepository.findCtaPrimaries(groupId, period, closeRunSeq)) {
      sum = sum.plus(cta.getAmount());
    }
    return sum;
  }

  /** Writes a gated (non-CLOSED) summary and returns the result — the close is held back loudly. */
  private GroupCloseResult holdSummary(
      UUID groupId,
      String period,
      String reportingCurrency,
      ConsolidationState state,
      boolean usesIllustrative,
      int closeRunSeq) {
    Money zero = Money.ofMinor(0L, reportingCurrency);
    ConsolidationSummary summary =
        new ConsolidationSummary(
            groupId, period, reportingCurrency, zero, zero, state, usesIllustrative, closeRunSeq);
    summary.setCompanyId(TenantContext.require().companyId());
    summaryRepository.save(summary);
    return new GroupCloseResult(state, summary.getId(), closeRunSeq, true);
  }

  private static List<IntercompanyMatch> stamp(List<IntercompanyMatch> matches, String lead) {
    matches.forEach(m -> m.setCompanyId(lead));
    return matches;
  }

  /**
   * The last calendar day of the period {@code YYYY-MM} (the effective-dating "period-end" anchor).
   */
  private static LocalDate endOf(String period) {
    return YearMonth.parse(period).atEndOfMonth();
  }
}
