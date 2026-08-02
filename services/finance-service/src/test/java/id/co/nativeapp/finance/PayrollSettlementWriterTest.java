package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.labor.domain.NegativeLiabilityBucketException;
import id.co.nativeapp.finance.labor.domain.PayrollLiabilityBucketEmptyException;
import id.co.nativeapp.finance.labor.domain.PayrollLiabilityNotSettleableException;
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

  // ----------------------------------------------------------------------- helpers

  private String currentPeriod() {
    return LedgerPosting.periodOf(clock.instant());
  }

  private UUID postLiability(
      UUID runId, int runSeq, long employerCostMinor, List<LiabilityBucket> buckets)
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
                    currentPeriod(),
                    "IDR",
                    Money.ofMinor(employerCostMinor, "IDR"),
                    buckets,
                    false,
                    clock.instant())));
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
}
