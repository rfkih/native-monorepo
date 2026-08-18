package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.labor.domain.NegativeLiabilityBucketException;
import id.co.nativeapp.finance.labor.domain.PayrollLiabilityBucketEmptyException;
import id.co.nativeapp.finance.labor.domain.PayrollLiabilityNotSettleableException;
import id.co.nativeapp.finance.labor.domain.PayrollLiabilitySuspenseBucketException;
import id.co.nativeapp.finance.labor.domain.PayrollSettlementAlreadySettledException;
import id.co.nativeapp.finance.labor.domain.PayrollSettlementIdempotencyKeyConflictException;
import id.co.nativeapp.finance.labor.domain.SettlementKind;
import id.co.nativeapp.finance.labor.dto.PayrollLiabilityBucketResponse;
import id.co.nativeapp.finance.labor.dto.PayrollLiabilityRunResponse;
import id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedEvent;
import id.co.nativeapp.finance.labor.messaging.PayrollLiabilitiesPostedEvent;
import id.co.nativeapp.finance.labor.messaging.PayrollLiabilitiesPostedEvent.LiabilityBucket;
import id.co.nativeapp.finance.labor.service.LaborCostPostingService;
import id.co.nativeapp.finance.labor.service.PayrollLiabilityReader;
import id.co.nativeapp.finance.labor.service.PayrollLiabilityService;
import id.co.nativeapp.finance.labor.service.PayrollSettlementResult;
import id.co.nativeapp.finance.labor.service.PayrollSettlementWriter;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The {@code PayrollSettlementWriter} test matrix (ADR 0032, Track P phase P5): one-shot settle,
 * same-key replay, idempotency-key conflict, already-settled-under-a-different-key, a superseded
 * run, a never-recognised run, an unrecognised bucket, a negative bucket (422 residual), the
 * balanced Dr/Cr journal, the amount ALWAYS read from the liability entry (never client-supplied —
 * the writer signature carries no amount parameter at all), and the currency guard. Posting through
 * the services directly (not Kafka) keeps the assertions deterministic, mirroring {@code
 * PayrollLiabilityWriterTest}.
 *
 * <p>Review C1 (critical) adds the {@code role_account_map} REMAP matrix: a remap effective between
 * accrual and settlement must still debit the ORIGINAL (accrual-time) account, and a mapping that
 * has EXPIRED by settlement time must not 500 — both prove {@link
 * PayrollSettlementWriter#buildSettlementEntry} never re-resolves the bucket role at "now". Review
 * W3 adds the SUSPENSE-bucket guard (settling a bucket parked in suspense at accrual time is
 * rejected). The W2 concurrency race lives in the sibling {@code PayrollSettlementConcurrencyTest}.
 */
@SpringBootTest
class PayrollSettlementWriterTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR = "settlement@integration.co.id";

  @Autowired private PayrollLiabilityService liabilityService;
  @Autowired private LaborCostPostingService laborService;
  @Autowired private PayrollSettlementWriter settlementWriter;
  @Autowired private PayrollLiabilityReader liabilityReader;
  @Autowired private RevenuePostingService revenueService;
  @Autowired private Clock clock;

  @Test
  void settlesABucketPostsABalancedDrBucketCrCashClearingEntryAndMarksItSettled() throws Exception {
    UUID runId = UUID.randomUUID();
    UUID runLedgerId =
        postLiability(
            runId,
            1,
            20_400_000L,
            List.of(
                new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(17_798_333L, "IDR")),
                new LiabilityBucket("PPH21_PAYABLE", Money.ofMinor(2_101_667L, "IDR")),
                new LiabilityBucket("BPJS_KES_PAYABLE", Money.ofMinor(500_000L, "IDR"))));

    PayrollSettlementResult result = settle(runLedgerId, SettlementKind.NET_WAGES, "settle-net-1");
    assertThat(result.created()).as("first settlement posts").isTrue();

    PayrollLiabilityRunResponse detail = forRunLedger(runLedgerId);
    PayrollLiabilityBucketResponse netWages = bucket(detail, "NET_WAGES");
    assertThat(netWages.settled()).isTrue();
    assertThat(netWages.journalEntryId()).isNotNull();
    // The settled AMOUNT is exactly the bucket total the liability entry carried — read back from
    // the GL, never accepted as a request parameter (the writer signature has no amount arg at
    // all).
    assertThat(netWages.amountMinor()).isEqualTo(17_798_333L);

    List<LineRow> lines = linesForEntryAsAdmin(netWages.journalEntryId());
    assertThat(lines).hasSize(2);
    LineRow bucketLine =
        lines.stream().filter(l -> l.accountCode.equals("2640")).findFirst().orElseThrow();
    assertThat(bucketLine.debitMinor).isEqualTo(17_798_333L);
    assertThat(bucketLine.creditMinor).isEqualTo(0L);
    LineRow clearingLine =
        lines.stream().filter(l -> l.accountCode.equals("1900")).findFirst().orElseThrow();
    assertThat(clearingLine.creditMinor).isEqualTo(17_798_333L);
    assertThat(clearingLine.debitMinor).isEqualTo(0L);
    long totalDebit = lines.stream().mapToLong(l -> l.debitMinor).sum();
    long totalCredit = lines.stream().mapToLong(l -> l.creditMinor).sum();
    assertThat(totalDebit).isEqualTo(totalCredit).isEqualTo(17_798_333L);

    // The OTHER two buckets are untouched (still unsettled).
    assertThat(bucket(detail, "PPH21").settled()).isFalse();
    assertThat(bucket(detail, "BPJS_KES").settled()).isFalse();

    // Provenance-derived (was hardcoded true): a non-illustrative liability + OFFICIAL
    // CASH_CLEARING.
    assertThat(usesIllustrativeRulesAsAdmin(netWages.journalEntryId())).isFalse();
  }

  @Test
  void settlingABucketWhoseLiabilityWasIllustrativeKeepsTheSettlementFlaggedProvisional()
      throws Exception {
    // The liability entry's OWN uses_illustrative_rules (event.usesIllustrativeRules(), the
    // posting-level provenance PayrollLiabilityWriter stamps — see its class javadoc) travels onto
    // the settlement even though CASH_CLEARING itself resolves OFFICIAL, because
    // PayrollSettlementWriter#buildSettlementEntry ORs the bucket's inherited flag with whatever
    // CASH_CLEARING resolves to now.
    UUID runId = UUID.randomUUID();
    Instant occurredAt = clock.instant();
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            liabilityService.handle(
                new PayrollLiabilitiesPostedEvent(
                    UUID.randomUUID(),
                    TENANT_A,
                    runId,
                    1,
                    "REGULAR",
                    LedgerPosting.periodOf(occurredAt),
                    "IDR",
                    Money.ofMinor(10_000_000L, "IDR"),
                    List.of(
                        new LiabilityBucket(
                            "NET_WAGES_PAYABLE", Money.ofMinor(10_000_000L, "IDR"))),
                    true, // the run's liability itself is illustrative-badged
                    occurredAt)));
    UUID runLedgerId = runLedgerIdAsAdmin(runId, 1);

    PayrollSettlementResult result =
        settle(runLedgerId, SettlementKind.NET_WAGES, "illustrative-liability-key");
    assertThat(result.created()).isTrue();

    UUID settlementEntryId = settlementJournalEntryIdAsAdmin(runLedgerId, "NET_WAGES");
    assertThat(usesIllustrativeRulesAsAdmin(settlementEntryId))
        .as("the settlement inherits the liability run's illustrative provenance")
        .isTrue();
  }

  @Test
  void aSameKeyReplayReturnsTheOriginalSettlementWithoutPostingAgain() throws Exception {
    UUID runId = UUID.randomUUID();
    UUID runLedgerId =
        postLiability(
            runId,
            1,
            10_000_000L,
            List.of(new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(10_000_000L, "IDR"))));

    PayrollSettlementResult first = settle(runLedgerId, SettlementKind.NET_WAGES, "replay-key-1");
    assertThat(first.created()).isTrue();

    PayrollSettlementResult replay = settle(runLedgerId, SettlementKind.NET_WAGES, "replay-key-1");
    assertThat(replay.created()).as("same-key replay posts nothing new").isFalse();
    assertThat(replay.settlementId()).isEqualTo(first.settlementId());

    assertThat(settlementCountAsAdmin(runLedgerId, "NET_WAGES")).isEqualTo(1L);
  }

  @Test
  void theSameKeyAgainstADifferentBucketIsAConflict() throws Exception {
    UUID runId = UUID.randomUUID();
    UUID runLedgerId =
        postLiability(
            runId,
            1,
            10_500_000L,
            List.of(
                new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(10_000_000L, "IDR")),
                new LiabilityBucket("PPH21_PAYABLE", Money.ofMinor(500_000L, "IDR"))));

    settle(runLedgerId, SettlementKind.NET_WAGES, "shared-key");

    assertThatThrownBy(() -> settle(runLedgerId, SettlementKind.PPH21, "shared-key"))
        .isInstanceOf(PayrollSettlementIdempotencyKeyConflictException.class);
  }

  @Test
  void aDifferentKeyAgainstAnAlreadySettledBucketIsRejected() throws Exception {
    UUID runId = UUID.randomUUID();
    UUID runLedgerId =
        postLiability(
            runId,
            1,
            10_000_000L,
            List.of(new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(10_000_000L, "IDR"))));

    settle(runLedgerId, SettlementKind.NET_WAGES, "key-a");

    assertThatThrownBy(() -> settle(runLedgerId, SettlementKind.NET_WAGES, "key-b"))
        .isInstanceOf(PayrollSettlementAlreadySettledException.class);
  }

  @Test
  void settlingABucketTheRunNeverRecognisedIsRejected() throws Exception {
    UUID runId = UUID.randomUUID();
    UUID runLedgerId =
        postLiability(
            runId,
            1,
            10_000_000L,
            List.of(new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(10_000_000L, "IDR"))));

    assertThatThrownBy(() -> settle(runLedgerId, SettlementKind.PPH21, "empty-bucket-key"))
        .isInstanceOf(PayrollLiabilityBucketEmptyException.class);
  }

  @Test
  void settlingASupersededRunsLiabilityIsForbidden() throws Exception {
    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();

    UUID runLedgerId1 =
        postLiability(
            run1,
            1,
            10_000_000L,
            List.of(new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(10_000_000L, "IDR"))));
    // A higher run_seq of the SAME (period, run_type) supersedes run1's liability entry (ADR 0032
    // P4 supersession).
    postLiability(
        run2,
        2,
        11_000_000L,
        List.of(new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(11_000_000L, "IDR"))));

    assertThatThrownBy(() -> settle(runLedgerId1, SettlementKind.NET_WAGES, "superseded-key"))
        .isInstanceOf(PayrollLiabilityNotSettleableException.class)
        .hasMessageContaining("superseded");
  }

  @Test
  void settlingARunWithNoLiabilityRecognisedYetIsRejected() throws Exception {
    // A LaborCostAllocated bucket opens the SHARED payroll_run_ledger control row WITHOUT ever
    // touching its liability_state (ADR 0032's two-independent-lifecycles design) — the run row
    // exists and is visible, but liability_state is still NULL.
    UUID runId = UUID.randomUUID();
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            laborService.handle(
                new LaborCostAllocatedEvent(
                    UUID.randomUUID(),
                    TENANT_A,
                    runId,
                    1,
                    "REGULAR",
                    currentPeriod(),
                    OUTLET,
                    "5100-SALARY",
                    Money.ofMinor(10_000_000L, "IDR"),
                    false,
                    false,
                    clock.instant())));
    UUID runLedgerId = runLedgerIdAsAdmin(runId, 1);

    assertThatThrownBy(() -> settle(runLedgerId, SettlementKind.NET_WAGES, "never-recognised-key"))
        .isInstanceOf(PayrollLiabilityNotSettleableException.class)
        .hasMessageContaining("no payroll liability has been recognised");
  }

  @Test
  void aNegativeBucketIsRejectedButOtherPositiveBucketsStillSettle() throws Exception {
    UUID runId = UUID.randomUUID();
    UUID runLedgerId =
        postLiability(
            runId,
            1,
            5_527_000L,
            List.of(
                new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(67_465_700L, "IDR")),
                new LiabilityBucket("PPH21_PAYABLE", Money.ofMinor(-62_665_700L, "IDR")),
                new LiabilityBucket("BPJS_KES_PAYABLE", Money.ofMinor(250_000L, "IDR")),
                new LiabilityBucket("BPJS_TK_PAYABLE", Money.ofMinor(477_000L, "IDR"))));

    assertThatThrownBy(() -> settle(runLedgerId, SettlementKind.PPH21, "negative-key"))
        .isInstanceOf(NegativeLiabilityBucketException.class);

    // The other (positive) buckets are unaffected — settling one is independent per kind.
    PayrollSettlementResult netWages =
        settle(runLedgerId, SettlementKind.NET_WAGES, "positive-key");
    assertThat(netWages.created()).isTrue();
  }

  @Test
  void aDivergentPeriodCurrencyIsRejected() throws Exception {
    UUID company = UUID.randomUUID();
    // Establish the period's currency as USD via an unrelated sale.
    TenantContext.callAs(
        company.toString(),
        ACTOR,
        () ->
            revenueService.handle(
                new SaleRecordedEvent(
                    UUID.randomUUID(),
                    company.toString(),
                    OUTLET,
                    Money.ofMinor(1_000_000L, "USD"),
                    clock.instant())));

    UUID runId = UUID.randomUUID();
    TenantContext.callAs(
        company.toString(),
        ACTOR,
        () ->
            liabilityService.handle(
                new PayrollLiabilitiesPostedEvent(
                    UUID.randomUUID(),
                    company.toString(),
                    runId,
                    1,
                    "REGULAR",
                    currentPeriod(),
                    "IDR",
                    Money.ofMinor(10_000_000L, "IDR"),
                    List.of(
                        new LiabilityBucket(
                            "NET_WAGES_PAYABLE", Money.ofMinor(10_000_000L, "IDR"))),
                    false,
                    clock.instant())));
    UUID runLedgerId = runLedgerIdAsAdmin(runId, 1);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    company.toString(),
                    ACTOR,
                    () ->
                        settlementWriter.settle(
                            runLedgerId, SettlementKind.NET_WAGES, "currency-guard-key")))
        .isInstanceOf(MismatchedPostingCurrencyException.class);
  }

  @Test
  void aRoleAccountMapRemapBetweenAccrualAndSettlementStillDebitsTheOriginalAccrualAccount()
      throws Exception {
    // P5 review C1 (CRITICAL): the settlement Dr leg must debit the account NET_WAGES_PAYABLE
    // mapped to AT ACCRUAL TIME — never a fresh resolution at settlement "now". Accrue on a FIXED
    // PAST date, then insert a HIGHER-VERSION remap effective from a LATER date (covering "now",
    // NOT the accrual date) before settling.
    UUID runId = UUID.randomUUID();
    Instant accrualOccurredAt = Instant.parse("2026-01-15T10:00:00Z");
    UUID runLedgerId =
        postLiabilityAt(
            runId,
            1,
            10_000_000L,
            List.of(new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(10_000_000L, "IDR"))),
            accrualOccurredAt);

    insertChartOfAccountAsAdmin("2699", "Remap Test Account (C1)", "LIABILITY");
    insertRoleAccountMapAsAdmin(
        "NET_WAGES_PAYABLE",
        "2699",
        3, // V51 (ADR 0042) now occupies version 2 with the OFFICIAL supersede; remap sits above it
        LocalDate.parse("2026-02-01"),
        LocalDate.parse("9999-12-31"));
    try {
      PayrollSettlementResult result = settle(runLedgerId, SettlementKind.NET_WAGES, "remap-key-1");
      assertThat(result.created()).isTrue();

      UUID settlementEntryId = settlementJournalEntryIdAsAdmin(runLedgerId, "NET_WAGES");
      List<LineRow> lines = linesForEntryAsAdmin(settlementEntryId);
      assertThat(lines)
          .as("never debits the NEW (remapped) account")
          .noneMatch(l -> l.accountCode.equals("2699"));
      LineRow bucketLine =
          lines.stream().filter(l -> l.accountCode.equals("2640")).findFirst().orElseThrow();
      assertThat(bucketLine.debitMinor).isEqualTo(10_000_000L);
    } finally {
      deleteRoleAccountMapAsAdmin("NET_WAGES_PAYABLE", 3);
      deleteChartOfAccountAsAdmin("2699");
    }
  }

  @Test
  void settlingSucceedsEvenWhenTheBucketRolesMappingHasExpiredByNow() throws Exception {
    // P5 review C1 secondary path: the PRE-FIX code re-resolved the bucket role AT "now" inside
    // buildSettlementEntry, which threw IllegalStateException (-> an unhandled 500) once the
    // mapping had since expired. The fix never asks what the role maps to "now" — it reuses the
    // accrual-time resolution unconditionally — so settling still succeeds.
    UUID runId = UUID.randomUUID();
    Instant accrualOccurredAt = Instant.parse("2026-01-15T10:00:00Z");
    UUID runLedgerId =
        postLiabilityAt(
            runId,
            1,
            10_000_000L,
            List.of(new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(10_000_000L, "IDR"))),
            accrualOccurredAt);

    // Shrink the pre-seeded (V40) NET_WAGES_PAYABLE mapping so it no longer covers "now" — but it
    // still covers the accrual date (2026-01-15).
    updateRoleAccountMapEffectiveToAsAdmin("NET_WAGES_PAYABLE", 1, LocalDate.parse("2026-01-31"));
    try {
      PayrollSettlementResult result =
          settle(runLedgerId, SettlementKind.NET_WAGES, "expired-mapping-key");
      assertThat(result.created()).as("no 500 — settling never re-resolves at \"now\"").isTrue();

      UUID settlementEntryId = settlementJournalEntryIdAsAdmin(runLedgerId, "NET_WAGES");
      LineRow bucketLine =
          linesForEntryAsAdmin(settlementEntryId).stream()
              .filter(l -> l.accountCode.equals("2640"))
              .findFirst()
              .orElseThrow();
      assertThat(bucketLine.debitMinor).isEqualTo(10_000_000L);
    } finally {
      // Restore the global (V40) mapping to the open-ended sentinel so no other test observes the
      // shrunk window.
      updateRoleAccountMapEffectiveToAsAdmin("NET_WAGES_PAYABLE", 1, LocalDate.parse("9999-12-31"));
    }
  }

  @Test
  void buildSettlementEntryNeverResolvesTheBucketRoleItselfOnlyCashClearingResolvesAtNow() {
    // A focused, DB-independent proof of the C1 mechanism: buildSettlementEntry takes the bucket's
    // account code as a plain parameter and debits EXACTLY that code — it makes no
    // RoleAccountResolver call for kind.liabilityRole() at all (only CASH_CLEARING resolves, at
    // "now"). An arbitrary, never-mapped code proves this: if the method tried to re-resolve
    // NET_WAGES_PAYABLE itself, an unmapped/bogus code would never appear on the Dr leg.
    JournalEntry entry =
        settlementWriter.buildSettlementEntry(
            SettlementKind.NET_WAGES,
            "9999", // deliberately the suspense code — a plain pass-through, not a real resolution
            false,
            Money.ofMinor(5_000_000L, "IDR"),
            "2026-08",
            clock.instant(),
            UUID.randomUUID());
    JournalLine drLeg =
        entry.getLines().stream().filter(l -> l.getDebitMinor() > 0).findFirst().orElseThrow();
    assertThat(drLeg.getAccountCode()).isEqualTo("9999");
    assertThat(drLeg.getDebitMinor()).isEqualTo(5_000_000L);
    // Provenance-derived (was hardcoded true): a non-illustrative bucket + OFFICIAL CASH_CLEARING.
    assertThat(entry.isUsesIllustrativeRules()).isFalse();
  }

  @Test
  void anIllustrativeBucketProvenanceBadgesTheSettlementEntryProvisional() {
    // The bucket's OWN historical provenance (read off the liability entry that produced
    // bucketAccountCode) alone flags the settlement, even when CASH_CLEARING itself resolves
    // OFFICIAL — the OR in buildSettlementEntry.
    JournalEntry entry =
        settlementWriter.buildSettlementEntry(
            SettlementKind.NET_WAGES,
            "2640",
            true, // the liability entry that resolved 2640 was itself illustrative
            Money.ofMinor(5_000_000L, "IDR"),
            "2026-08",
            clock.instant(),
            UUID.randomUUID());

    assertThat(entry.isUsesIllustrativeRules())
        .as("an illustrative bucket provenance flags the settlement entry provisional")
        .isTrue();
  }

  @Test
  void settlingABucketPostedToSuspenseAtAccrualTimeIsRejected() throws Exception {
    // P5 review W3: OTHER_DEDUCTIONS_PAYABLE is temporarily UNMAPPED (the V40 row removed) so the
    // accrual routes it to SUSPENSE (9999) exactly like
    // anUnrecognisedLiabilityRoleFailsSafeToSuspenseNeverDropped in PayrollLiabilityWriterTest.
    // Settling a suspense-parked bucket must be rejected — it would neither clear the intended
    // payable nor be distinguishable from another suspense-parked bucket by account code alone.
    // Unmap the role ENTIRELY: remove the V40 version-1 row AND the V51 (ADR 0042) version-2
    // OFFICIAL supersede — otherwise the accrual still resolves 2690 and never routes to SUSPENSE.
    deleteRoleAccountMapAsAdmin("OTHER_DEDUCTIONS_PAYABLE", 1);
    deleteRoleAccountMapAsAdmin("OTHER_DEDUCTIONS_PAYABLE", 2);
    try {
      UUID runId = UUID.randomUUID();
      UUID runLedgerId =
          postLiability(
              runId,
              1,
              1_000_000L,
              List.of(
                  new LiabilityBucket(
                      "OTHER_DEDUCTIONS_PAYABLE", Money.ofMinor(1_000_000L, "IDR"))));

      assertThatThrownBy(() -> settle(runLedgerId, SettlementKind.OTHER, "suspense-key"))
          .isInstanceOf(PayrollLiabilitySuspenseBucketException.class);
    } finally {
      // Restore both migrated rows (the resolver reads gl_account_code only, so the flag value on
      // the restored rows is immaterial to any assertion).
      insertRoleAccountMapAsAdmin(
          "OTHER_DEDUCTIONS_PAYABLE",
          "2690",
          1,
          LocalDate.parse("2000-01-01"),
          LocalDate.parse("9999-12-31"));
      insertRoleAccountMapAsAdmin(
          "OTHER_DEDUCTIONS_PAYABLE",
          "2690",
          2,
          LocalDate.parse("2000-01-01"),
          LocalDate.parse("9999-12-31"));
    }
  }

  // ----------------------------------------------------------------------- helpers

  private String currentPeriod() {
    return LedgerPosting.periodOf(clock.instant());
  }

  private UUID postLiability(
      UUID runId, int runSeq, long employerCostMinor, List<LiabilityBucket> buckets)
      throws Exception {
    return postLiabilityAt(runId, runSeq, employerCostMinor, buckets, clock.instant());
  }

  /** Like {@link #postLiability} but with an explicit {@code occurred_at} (the C1 remap tests). */
  private UUID postLiabilityAt(
      UUID runId,
      int runSeq,
      long employerCostMinor,
      List<LiabilityBucket> buckets,
      Instant occurredAt)
      throws Exception {
    TenantContext.callAs(
        TENANT_A,
        ACTOR,
        () ->
            liabilityService.handle(
                new PayrollLiabilitiesPostedEvent(
                    UUID.randomUUID(),
                    TENANT_A,
                    runId,
                    runSeq,
                    "REGULAR",
                    LedgerPosting.periodOf(occurredAt),
                    "IDR",
                    Money.ofMinor(employerCostMinor, "IDR"),
                    buckets,
                    false,
                    occurredAt)));
    return runLedgerIdAsAdmin(runId, runSeq);
  }

  /**
   * The {@code payroll_run_ledger.id} for a run, read over the ADMIN (BYPASSRLS) connection —
   * mirrors {@code PayrollLiabilityWriterTest}'s admin-read helpers; a plain repository call from
   * the test (outside any service's own {@code @Transactional} unit of work) is deliberately
   * avoided here.
   */
  private UUID runLedgerIdAsAdmin(UUID payrollRunId, int runSeq) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT id FROM payroll_run_ledger WHERE payroll_run_id = ? AND run_seq = ?")) {
      ps.setObject(1, payrollRunId);
      ps.setInt(2, runSeq);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return (UUID) rs.getObject(1);
      }
    }
  }

  private PayrollSettlementResult settle(
      UUID runLedgerId, SettlementKind kind, String idempotencyKey) throws Exception {
    return TenantContext.callAs(
        TENANT_A, ACTOR, () -> settlementWriter.settle(runLedgerId, kind, idempotencyKey));
  }

  private PayrollLiabilityRunResponse forRunLedger(UUID runLedgerId) throws Exception {
    return TenantContext.callAs(
        TENANT_A, ACTOR, () -> liabilityReader.forRunLedger(runLedgerId).orElseThrow());
  }

  private static PayrollLiabilityBucketResponse bucket(
      PayrollLiabilityRunResponse run, String kind) {
    return run.buckets().stream().filter(b -> b.kind().equals(kind)).findFirst().orElseThrow();
  }

  private record LineRow(String accountCode, long debitMinor, long creditMinor) {}

  private List<LineRow> linesForEntryAsAdmin(UUID entryId) throws Exception {
    List<LineRow> rows = new java.util.ArrayList<>();
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT account_code, debit_minor, credit_minor FROM journal_line WHERE entry_id ="
                    + " ? ORDER BY line_no")) {
      ps.setObject(1, entryId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(new LineRow(rs.getString(1), rs.getLong(2), rs.getLong(3)));
        }
      }
    }
    return rows;
  }

  private long settlementCountAsAdmin(UUID runLedgerId, String kind) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM payroll_settlement WHERE payroll_run_ledger_id = ? AND kind"
                    + " = ?")) {
      ps.setObject(1, runLedgerId);
      ps.setString(2, kind);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static Connection adminConnection() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** The {@code journal_entry.uses_illustrative_rules} flag for {@code entryId}, read as admin. */
  private boolean usesIllustrativeRulesAsAdmin(UUID entryId) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT uses_illustrative_rules FROM journal_entry WHERE id = ?")) {
      ps.setObject(1, entryId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getBoolean(1);
      }
    }
  }

  /**
   * The {@code journal_entry_id} of a {@code payroll_settlement} row, read over the admin
   * connection.
   */
  private UUID settlementJournalEntryIdAsAdmin(UUID runLedgerId, String kind) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT journal_entry_id FROM payroll_settlement WHERE payroll_run_ledger_id = ?"
                    + " AND kind = ?")) {
      ps.setObject(1, runLedgerId);
      ps.setString(2, kind);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return (UUID) rs.getObject(1);
      }
    }
  }

  // --------------------------------------------------------------- role_account_map admin helpers
  // role_account_map/chart_of_account are GLOBAL reference tables (not tenant-scoped, not reset by
  // PostgresRlsTestBase#resetTables — see its own javadoc: "the chart_of_account and mapping_rule
  // reference data seeded by Flyway ... is left intact"). The C1/W3 tests below therefore ALWAYS
  // restore whatever they insert/delete/shrink in a try/finally, so no other test in the shared
  // Testcontainers Postgres instance ever observes the temporary change.

  private void insertChartOfAccountAsAdmin(String code, String name, String type) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO chart_of_account (account_code, name, account_type) VALUES (?, ?, ?)"
                    + " ON CONFLICT (account_code) DO NOTHING")) {
      ps.setString(1, code);
      ps.setString(2, name);
      ps.setString(3, type);
      ps.executeUpdate();
    }
  }

  private void deleteChartOfAccountAsAdmin(String code) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("DELETE FROM chart_of_account WHERE account_code = ?")) {
      ps.setString(1, code);
      ps.executeUpdate();
    }
  }

  private void insertRoleAccountMapAsAdmin(
      String role, String code, int version, LocalDate effectiveFrom, LocalDate effectiveTo)
      throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO role_account_map (id, account_role, gl_account_code, version,"
                    + " uses_illustrative, effective_from, effective_to) VALUES (gen_random_uuid(),"
                    + " ?, ?, ?, TRUE, ?, ?)")) {
      ps.setString(1, role);
      ps.setString(2, code);
      ps.setInt(3, version);
      ps.setObject(4, effectiveFrom);
      ps.setObject(5, effectiveTo);
      ps.executeUpdate();
    }
  }

  private void deleteRoleAccountMapAsAdmin(String role, int version) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "DELETE FROM role_account_map WHERE account_role = ? AND version = ?")) {
      ps.setString(1, role);
      ps.setInt(2, version);
      ps.executeUpdate();
    }
  }

  private void updateRoleAccountMapEffectiveToAsAdmin(
      String role, int version, LocalDate effectiveTo) throws Exception {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement(
                "UPDATE role_account_map SET effective_to = ? WHERE account_role = ? AND version ="
                    + " ?")) {
      ps.setObject(1, effectiveTo);
      ps.setString(2, role);
      ps.setInt(3, version);
      ps.executeUpdate();
    }
  }
}
