package id.co.nativeapp.finance.consolidation;

import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.finance.group.GroupMember;
import id.co.nativeapp.finance.group.GroupMemberRepository;
import id.co.nativeapp.finance.group.GroupRef;
import id.co.nativeapp.finance.group.GroupRefRepository;
import id.co.nativeapp.finance.grouptb.GroupTrialBalanceLine;
import id.co.nativeapp.finance.grouptb.GroupTrialBalanceLineRepository;
import id.co.nativeapp.finance.mapping.AccountType;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single {@code @Transactional} unit of work that runs a group's SINGLE-CURRENCY consolidation
 * close for a period (P3d SEAM 3a). Must be invoked inside a {@link TenantContext#callAsGroup}
 * scope bound to the group's LEAD company AND the group id, so the two-GUC conjunction RLS engages
 * on every consolidation-table read/write (rule 5).
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
 *   <li><strong>Same-currency scope.</strong> Assert every member's base currency == the group's
 *       reporting currency; ANY divergence is a MULTI-CURRENCY close, REJECTED cleanly ({@code
 *       MULTI_CURRENCY_UNSUPPORTED} — FX is SEAM 3b), never silently summing mixed currencies.
 *   <li><strong>Intercompany match.</strong> Pair related-party legs on {@code (intercompany_ref,
 *       period)}; MATCHED -> an ELIMINATION contra; UNMATCHED / AMOUNT_MISMATCH -> flagged and the
 *       close held back ({@code INTERCOMPANY_UNRECONCILED} — money is never eliminated against
 *       nothing).
 *   <li><strong>Compute.</strong> Sum the trial balances into revenue/expense/net, apply the
 *       eliminations, carry {@code uses_illustrative_rules} sticky-OR, write {@code
 *       consolidation_summary} (state CLOSED).
 *   <li><strong>Supersession (append-only, #23).</strong> A higher {@code close_run_seq} reverses
 *       each prior PRIMARY elimination (negated, role REVERSAL, a deterministic collision-free
 *       synthetic id) and flips the prior summary to TERMINAL {@code SUPERSEDED}, then reposts.
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

  @SuppressWarnings("checkstyle:ParameterNumber")
  public GroupCloseWriter(
      GroupRefRepository groupRefRepository,
      GroupMemberRepository groupMemberRepository,
      GroupTrialBalanceLineRepository trialBalanceRepository,
      ConsolidationLedgerRepository ledgerRepository,
      ConsolidationSummaryRepository summaryRepository,
      IntercompanyMatchRepository matchRepository,
      GroupMembershipReadiness membershipReadiness,
      ProcessedEventStore processedEvents) {
    this.groupRefRepository = groupRefRepository;
    this.groupMemberRepository = groupMemberRepository;
    this.trialBalanceRepository = trialBalanceRepository;
    this.ledgerRepository = ledgerRepository;
    this.summaryRepository = summaryRepository;
    this.matchRepository = matchRepository;
    this.membershipReadiness = membershipReadiness;
    this.processedEvents = processedEvents;
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

    // (d) SAME-CURRENCY SCOPE: every line must be in the reporting currency. ANY divergence is a
    //     multi-currency close -> SEAM 3b not built -> REJECT cleanly, never silently sum mixed
    //     currencies (Money.plus would throw on the mismatch; we turn that into a clear gated
    // state).
    for (GroupTrialBalanceLine line : lines) {
      String lineCurrency = line.getAmount().currency().getCurrencyCode();
      if (!lineCurrency.equals(reportingCurrency)) {
        log.warn(
            "Group close groupId={} period={} MULTI_CURRENCY_UNSUPPORTED: member={} line currency {}"
                + " != reporting currency {} (FX translation is SEAM 3b)",
            groupId,
            period,
            line.getMemberCompanyId(),
            lineCurrency,
            reportingCurrency);
        return holdSummary(
            groupId,
            period,
            reportingCurrency,
            ConsolidationState.MULTI_CURRENCY_UNSUPPORTED,
            usesIllustrative,
            closeRunSeq);
      }
    }

    // (e) INTERCOMPANY MATCH on (intercompany_ref, period): pair related-party legs. Persist the
    //     reconciliation rows; UNMATCHED / AMOUNT_MISMATCH / MALFORMED block the close. A pure
    //     balance-sheet IC ref is OUT_OF_SCOPE_BALANCE_SHEET — recorded, non-blocking, not
    //     eliminated (3a consolidates only the P&L; balance-sheet IC elimination is SEAM 3b).
    List<IntercompanyMatch> matches = reconcileIntercompany(groupId, period, closeRunSeq, lines);
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

    // (f) SUPERSESSION (append-only, #23): a higher close_run_seq reverses each prior PRIMARY
    //     elimination of the superseded run(s) then reposts. Flip the prior summary to terminal
    //     SUPERSEDED.
    //
    //     NOTE on a HELD prior: a prior summary that never finalised (a non-CLOSED HOLD —
    //     PENDING_*/MULTI_CURRENCY_UNSUPPORTED/INTERCOMPANY_UNRECONCILED) posted NO PRIMARY ledger
    //     entries, so reversePriorRun finds nothing to reverse — the SUPERSEDED transition on it is
    //     purely cosmetic (it leaves no orphaned eliminations). Marking it SUPERSEDED rather than
    //     overwriting it keeps the supersession path uniform and append-only (one summary row per
    //     close_run_seq, the prior history preserved), and the active-head query reads the latest
    //     non-SUPERSEDED row either way. We deliberately do NOT special-case HELD priors here.
    for (ConsolidationSummary priorSummary :
        summaryRepository.findActivePriorSummaries(groupId, period, closeRunSeq)) {
      reversePriorRun(groupId, period, closeRunSeq, priorSummary.getCloseRunSeq(), lead);
      priorSummary.transitionTo(ConsolidationState.SUPERSEDED);
      summaryRepository.save(priorSummary);
    }

    // (e cont.) Post the ELIMINATION contras for the MATCHED references (this run's PRIMARY
    // entries).
    Money zeroReporting = Money.ofMinor(0L, reportingCurrency);
    Money eliminatedRevenue = zeroReporting;
    Money eliminatedExpense = zeroReporting;
    for (IntercompanyMatch match : matches) {
      // Only a strictly well-formed MATCHED P&L pair drives an elimination. A non-blocking
      // OUT_OF_SCOPE_BALANCE_SHEET ref reaches here (it did NOT block above) but must NOT be
      // eliminated — it is out of 3a's P&L scope. Skip it; post no contra.
      if (!match.eliminates()) {
        continue;
      }
      // Guaranteed strictly well-formed: exactly one REVENUE leg + one EXPENSE leg, BOTH strictly
      // positive, equal magnitude, between two members. Eliminate BY ACCOUNT CLASS using each leg's
      // OWN validated-positive amount (no abs() — the strict invariant already rejected any
      // non-positive leg), so each contra EXACTLY offsets what the SIGNED roll-up summed for that
      // class. For a well-formed pair the two are equal, so group net is unchanged: the internal
      // sale and the internal purchase both leave the group.
      Money revenueLeg = match.revenueLegAmount();
      Money expenseLeg = match.expenseLegAmount();
      postElimination(
          groupId,
          period,
          closeRunSeq,
          match.getIntercompanyRef(),
          // Per-leg traceability: each contra is stamped with the member that owns THAT leg class.
          match.revenueLegMember(),
          match.expenseLegMember(),
          revenueLeg,
          expenseLeg,
          lead);
      eliminatedRevenue = eliminatedRevenue.plus(revenueLeg);
      eliminatedExpense = eliminatedExpense.plus(expenseLeg);
    }

    // (f) COMPUTE: sum the trial balances by account class, then subtract the eliminated internal
    //     trade. All same-currency by the gate above, so Money.plus never throws here.
    Money grossRevenue = sumByAccountType(lines, AccountType.REVENUE, reportingCurrency);
    Money grossExpense = sumByAccountType(lines, AccountType.EXPENSE, reportingCurrency);
    Money groupRevenue = grossRevenue.minus(eliminatedRevenue);
    Money groupExpense = grossExpense.minus(eliminatedExpense);

    ConsolidationSummary summary =
        new ConsolidationSummary(
            groupId,
            period,
            reportingCurrency,
            groupRevenue,
            groupExpense,
            ConsolidationState.CLOSED,
            usesIllustrative,
            closeRunSeq);
    summary.setCompanyId(lead);
    summaryRepository.save(summary);

    log.info(
        "Group close groupId={} period={} closeRunSeq={} CLOSED: revenue={} expense={} net={}"
            + " (eliminated internal trade revenue={} expense={}, illustrative={})",
        groupId,
        period,
        closeRunSeq,
        groupRevenue,
        groupExpense,
        groupRevenue.minus(groupExpense),
        eliminatedRevenue,
        eliminatedExpense,
        usesIllustrative);

    return new GroupCloseResult(ConsolidationState.CLOSED, summary.getId(), closeRunSeq, true);
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
   *   <li>a one-revenue+one-expense, both-strictly-positive pair of UNEQUAL magnitude ->
   *       AMOUNT_MISMATCH;
   *   <li>a one-revenue+one-expense, both-strictly-positive pair of EQUAL magnitude (same currency)
   *       -> MATCHED.
   * </ul>
   *
   * <p>References are processed in a deterministic (sorted) order so the persisted rows and any
   * derived synthetic ids are reproducible across runs.
   */
  private List<IntercompanyMatch> reconcileIntercompany(
      UUID groupId, String period, int closeRunSeq, List<GroupTrialBalanceLine> lines) {
    // Group the intercompany legs by ref, preserving a deterministic ref order.
    Map<String, List<GroupTrialBalanceLine>> byRef = new LinkedHashMap<>();
    Set<String> refs = new TreeSet<>();
    for (GroupTrialBalanceLine line : lines) {
      String ref = line.getIntercompanyRef();
      if (ref != null) {
        refs.add(ref);
      }
    }
    for (String ref : refs) {
      List<GroupTrialBalanceLine> legs =
          lines.stream()
              .filter(l -> ref.equals(l.getIntercompanyRef()))
              .sorted(Comparator.comparing(l -> l.getMemberCompanyId().toString()))
              .toList();
      byRef.put(ref, legs);
    }

    List<IntercompanyMatch> matches = new ArrayList<>();
    for (Map.Entry<String, List<GroupTrialBalanceLine>> entry : byRef.entrySet()) {
      String ref = entry.getKey();
      List<GroupTrialBalanceLine> legs = entry.getValue();
      GroupTrialBalanceLine legA = legs.get(0);

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

      GroupTrialBalanceLine legB = legs.get(1);

      // SELF-DEAL: both lines carry the same ref from the SAME member. Not an intercompany trade
      // between two members; eliminating it would invent a contra against a single member. Block.
      if (legA.getMemberCompanyId().equals(legB.getMemberCompanyId())) {
        matches.add(malformed(groupId, period, ref, legA, legB, closeRunSeq));
        continue;
      }

      // 3a IC SCOPE — P&L ONLY. Classify each leg as P&L (REVENUE/EXPENSE) or balance-sheet
      // (ASSET/LIABILITY/EQUITY). 3a consolidates only the P&L; a pure balance-sheet IC ref does
      // not
      // affect the P&L net.
      boolean aIsPnl = isPnl(legA.getAccountType());
      boolean bIsPnl = isPnl(legB.getAccountType());

      // BOTH legs balance-sheet (e.g. IC receivable=ASSET + IC payable=LIABILITY): OUT OF 3a SCOPE.
      // A NON-BLOCKING skip — record it, do NOT eliminate it, do NOT block the close. Balance-sheet
      // IC elimination is a future / SEAM 3b concern; flagging it MALFORMED here would wrongly make
      // a group with a legitimate IC receivable/payable pair un-closable on its P&L.
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

      // MIXED ref: one P&L leg + one balance-sheet leg. Genuinely malformed (an IC trade is not
      // part
      // P&L, part balance-sheet on the same ref) — block rather than half-eliminate.
      if (aIsPnl != bIsPnl) {
        matches.add(malformed(groupId, period, ref, legA, legB, closeRunSeq));
        continue;
      }

      // ACCOUNT-CLASS well-formedness (both P&L here): a real intercompany trade is exactly ONE
      // revenue leg (the internal sale) + ONE expense leg (the internal purchase). Two same-class
      // legs (e.g. two REVENUE legs) are NOT eliminable by class — block rather than mis-eliminate.
      boolean oneRevenueOneExpense =
          (legA.getAccountType() == AccountType.REVENUE
                  && legB.getAccountType() == AccountType.EXPENSE)
              || (legA.getAccountType() == AccountType.EXPENSE
                  && legB.getAccountType() == AccountType.REVENUE);
      if (!oneRevenueOneExpense) {
        matches.add(malformed(groupId, period, ref, legA, legB, closeRunSeq));
        continue;
      }

      // STRICT non-negativity: the roll-up sums the SIGNED amount, so a ZERO or NEGATIVE leg
      // (amountMinor <= 0) cannot be eliminated against its abs without fabricating net (a -X
      // expense leg sits in gross expense while the elimination subtracts a +X). Block it
      // MALFORMED.
      // (Equality is on the raw amounts below — both validated strictly positive here, so a
      // positive
      // == positive comparison is sign-faithful, no abs() needed.)
      if (!legA.getAmount().isPositive() || !legB.getAmount().isPositive()) {
        matches.add(malformed(groupId, period, ref, legA, legB, closeRunSeq));
        continue;
      }

      // Both strictly positive, one-revenue+one-expense pair: equal magnitude (same currency) ->
      // MATCHED, else AMOUNT_MISMATCH.
      IntercompanyMatchState state =
          legA.getAmount().equals(legB.getAmount())
              ? IntercompanyMatchState.MATCHED
              : IntercompanyMatchState.AMOUNT_MISMATCH;
      matches.add(
          new IntercompanyMatch(
              groupId,
              period,
              ref,
              legA.getMemberCompanyId(),
              legB.getMemberCompanyId(),
              legA.getAmount(),
              legB.getAmount(),
              legA.getAccountType(),
              legB.getAccountType(),
              state,
              closeRunSeq));
    }
    return matches;
  }

  private static IntercompanyMatch unmatched(
      UUID groupId, String period, String ref, GroupTrialBalanceLine legA, int closeRunSeq) {
    return new IntercompanyMatch(
        groupId,
        period,
        ref,
        legA.getMemberCompanyId(),
        null,
        legA.getAmount(),
        null,
        legA.getAccountType(),
        null,
        IntercompanyMatchState.UNMATCHED,
        closeRunSeq);
  }

  private static IntercompanyMatch malformed(
      UUID groupId,
      String period,
      String ref,
      GroupTrialBalanceLine legA,
      GroupTrialBalanceLine legB,
      int closeRunSeq) {
    return classified(
        groupId, period, ref, legA, legB, IntercompanyMatchState.MALFORMED, closeRunSeq);
  }

  /** Builds a two-leg {@link IntercompanyMatch} in the given (non-MATCHED-decision) state. */
  private static IntercompanyMatch classified(
      UUID groupId,
      String period,
      String ref,
      GroupTrialBalanceLine legA,
      GroupTrialBalanceLine legB,
      IntercompanyMatchState state,
      int closeRunSeq) {
    return new IntercompanyMatch(
        groupId,
        period,
        ref,
        legA.getMemberCompanyId(),
        legB.getMemberCompanyId(),
        legA.getAmount(),
        legB.getAmount(),
        legA.getAccountType(),
        legB.getAccountType(),
        state,
        closeRunSeq);
  }

  /** Whether an account class is a P&L (income-statement) class: REVENUE or EXPENSE. */
  private static boolean isPnl(AccountType type) {
    return type == AccountType.REVENUE || type == AccountType.EXPENSE;
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
    // Idempotency claim inside the surrounding transaction: a re-close at the same seq derives the
    // same id and this is a clean no-op (also backstopped by the UNIQUE source_entry_id column).
    processedEvents.processOnce(
        sourceEntryId,
        () -> {
          ConsolidationLedgerEntry entry =
              new ConsolidationLedgerEntry(
                  groupId,
                  period,
                  ConsolidationEntryType.ELIMINATION,
                  account,
                  contra,
                  elimKey,
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

  private Money sumByAccountType(
      List<GroupTrialBalanceLine> lines, AccountType type, String reportingCurrency) {
    Money sum = Money.ofMinor(0L, reportingCurrency);
    for (GroupTrialBalanceLine line : lines) {
      if (line.getAccountType() == type) {
        // Same-currency by the gate above; Money.plus is exact and never throws here.
        sum = sum.plus(line.getAmount());
      }
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
