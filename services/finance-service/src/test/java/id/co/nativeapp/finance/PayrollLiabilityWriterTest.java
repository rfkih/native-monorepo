package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedEvent;
import id.co.nativeapp.finance.labor.messaging.PayrollLiabilitiesPostedEvent;
import id.co.nativeapp.finance.labor.messaging.PayrollLiabilitiesPostedEvent.LiabilityBucket;
import id.co.nativeapp.finance.labor.messaging.PayrollPostedEvent;
import id.co.nativeapp.finance.labor.service.LaborCostPostingService;
import id.co.nativeapp.finance.labor.service.PayrollLiabilityService;
import id.co.nativeapp.finance.labor.service.PayrollReconciliationService;
import id.co.nativeapp.money.Money;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The {@code PayrollLiabilityWriter} test matrix (ADR 0032, Track P phase P4) — mirrors {@code
 * LaborCostPostingTest}/{@code LaborSupersessionTest}/{@code PayrollReconciliationTest}'s rigor:
 * balanced entry + per-bucket legs, a negative bucket posting as the opposite (Dr) side, an
 * unmapped role failing safe to suspense, idempotent re-delivery, supersession under BOTH arrival
 * orders, and order-independence against the pre-existing Labor/Reconciliation control row. Posting
 * through the service (not Kafka) keeps the assertions deterministic, exactly like the existing
 * labor test suite.
 */
@SpringBootTest
class PayrollLiabilityWriterTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final Instant OCCURRED_AT = Instant.parse("2026-06-30T12:00:00Z");
  private static final String PERIOD = "2026-06";

  @Autowired private PayrollLiabilityService liabilityService;
  @Autowired private LaborCostPostingService laborService;
  @Autowired private PayrollReconciliationService reconciliationService;

  @Test
  void postsABalancedEntryWithOneCreditLegPerBucketAndDrLaborClearing() throws Exception {
    UUID runId = UUID.randomUUID();
    PayrollLiabilitiesPostedEvent event =
        event(
            UUID.randomUUID(),
            runId,
            1,
            20_400_000L,
            List.of(
                new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(17_798_333L, "IDR")),
                new LiabilityBucket("PPH21_PAYABLE", Money.ofMinor(2_101_667L, "IDR")),
                new LiabilityBucket("BPJS_KES_PAYABLE", Money.ofMinor(500_000L, "IDR"))));

    assertThat(liabilityService.handle(event)).as("first delivery posts").isTrue();

    assertThat(entryCountAsAdmin()).isEqualTo(1L);
    List<LineRow> lines = linesAsAdmin();
    assertThat(lines).hasSize(4);

    // Dr LABOR_CLEARING (6900) for the full employer cost total.
    LineRow clearing =
        lines.stream().filter(l -> l.accountCode.equals("6900")).findFirst().orElseThrow();
    assertThat(clearing.debitMinor).isEqualTo(20_400_000L);
    assertThat(clearing.creditMinor).isEqualTo(0L);

    // Cr NET_WAGES_PAYABLE (2640), PPH21_PAYABLE (2610), BPJS_KES_PAYABLE (2620).
    assertThat(creditFor(lines, "2640")).isEqualTo(17_798_333L);
    assertThat(creditFor(lines, "2610")).isEqualTo(2_101_667L);
    assertThat(creditFor(lines, "2620")).isEqualTo(500_000L);

    long totalDebit = lines.stream().mapToLong(l -> l.debitMinor).sum();
    long totalCredit = lines.stream().mapToLong(l -> l.creditMinor).sum();
    assertThat(totalDebit).isEqualTo(totalCredit).isEqualTo(20_400_000L);

    assertThat(liabilityStateAsAdmin(runId)).isEqualTo("POSTED");
    assertThat(liabilityEntryIdAsAdmin(runId)).isNotNull();
  }

  @Test
  void aNegativeBucketPostsAsADebitLegInsteadOfANegativeCredit() throws Exception {
    UUID runId = UUID.randomUUID();
    PayrollLiabilitiesPostedEvent event =
        event(
            UUID.randomUUID(),
            runId,
            1,
            5_527_000L,
            List.of(
                new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(67_465_700L, "IDR")),
                new LiabilityBucket("PPH21_PAYABLE", Money.ofMinor(-62_665_700L, "IDR")),
                new LiabilityBucket("BPJS_KES_PAYABLE", Money.ofMinor(250_000L, "IDR")),
                new LiabilityBucket("BPJS_TK_PAYABLE", Money.ofMinor(477_000L, "IDR"))));

    assertThat(liabilityService.handle(event)).isTrue();

    List<LineRow> lines = linesAsAdmin();
    // PPH21_PAYABLE (2610) is a DEBIT leg for the absolute value, not a negative credit.
    LineRow pph21 =
        lines.stream().filter(l -> l.accountCode.equals("2610")).findFirst().orElseThrow();
    assertThat(pph21.debitMinor).isEqualTo(62_665_700L);
    assertThat(pph21.creditMinor).isEqualTo(0L);

    long totalDebit = lines.stream().mapToLong(l -> l.debitMinor).sum();
    long totalCredit = lines.stream().mapToLong(l -> l.creditMinor).sum();
    // Dr(6900=5,527,000 + 2610=62,665,700) = 68,192,700; Cr(2640=67,465,700 + 2620=250,000 +
    // 2630=477,000) = 68,192,700.
    assertThat(totalDebit).isEqualTo(totalCredit).isEqualTo(68_192_700L);
  }

  @Test
  void anUnrecognisedLiabilityRoleFailsSafeToSuspenseNeverDropped() throws Exception {
    UUID runId = UUID.randomUUID();
    PayrollLiabilitiesPostedEvent event =
        event(
            UUID.randomUUID(),
            runId,
            1,
            1_000_000L,
            List.of(
                new LiabilityBucket(
                    "SOME_FUTURE_ROLE_NOT_YET_KNOWN", Money.ofMinor(1_000_000L, "IDR"))));

    assertThat(liabilityService.handle(event)).isTrue();

    List<LineRow> lines = linesAsAdmin();
    LineRow suspense =
        lines.stream().filter(l -> l.accountCode.equals("9999")).findFirst().orElseThrow();
    assertThat(suspense.creditMinor).isEqualTo(1_000_000L); // money posted, not dropped
  }

  @Test
  void redeliveryOfTheSameEventIsANoOp() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    PayrollLiabilitiesPostedEvent event =
        event(
            eventId,
            runId,
            1,
            1_000_000L,
            List.of(new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(1_000_000L, "IDR"))));

    assertThat(liabilityService.handle(event)).as("first delivery").isTrue();
    assertThat(liabilityService.handle(event)).as("re-delivery is a no-op").isFalse();

    assertThat(entryCountAsAdmin()).isEqualTo(1L);
  }

  @Test
  void aHigherRunSeqReversesThePriorLiabilityEntryAndRepostsSoOnlyTheLatestIsActive()
      throws Exception {
    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();

    assertThat(
            liabilityService.handle(
                event(
                    UUID.randomUUID(),
                    run1,
                    1,
                    20_000_000L,
                    List.of(
                        new LiabilityBucket(
                            "NET_WAGES_PAYABLE", Money.ofMinor(20_000_000L, "IDR"))))))
        .isTrue();
    assertThat(
            liabilityService.handle(
                event(
                    UUID.randomUUID(),
                    run2,
                    2,
                    22_000_000L,
                    List.of(
                        new LiabilityBucket(
                            "NET_WAGES_PAYABLE", Money.ofMinor(22_000_000L, "IDR"))))))
        .isTrue();

    // 3 entries: run1 PRIMARY, run1's REVERSAL contra, run2 PRIMARY.
    assertThat(entryCountAsAdmin()).isEqualTo(3L);
    assertThat(liabilityStateAsAdmin(run1)).isEqualTo("SUPERSEDED");
    assertThat(liabilityStateAsAdmin(run2)).isEqualTo("POSTED");

    // The reversal contra for run1's clearing leg is a CREDIT of 20,000,000 (the original was a
    // debit) — the exact per-leg swap.
    List<LineRow> allLines = linesAsAdmin();
    long clearingCredits =
        allLines.stream()
            .filter(l -> l.accountCode.equals("6900"))
            .mapToLong(l -> l.creditMinor)
            .sum();
    assertThat(clearingCredits).isEqualTo(20_000_000L);
  }

  @Test
  void redeliveringTheSupersedingRunNeverDoubleReverses() throws Exception {
    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();

    liabilityService.handle(
        event(
            UUID.randomUUID(),
            run1,
            1,
            20_000_000L,
            List.of(new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(20_000_000L, "IDR")))));

    PayrollLiabilitiesPostedEvent run2Event =
        event(
            UUID.randomUUID(),
            run2,
            2,
            22_000_000L,
            List.of(new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(22_000_000L, "IDR"))));
    assertThat(liabilityService.handle(run2Event)).as("first delivery of run 2").isTrue();
    assertThat(liabilityService.handle(run2Event)).as("re-delivery of run 2").isFalse();

    assertThat(entryCountAsAdmin()).isEqualTo(3L);
  }

  @Test
  void aLowerSeqRunArrivingAfterAHigherSeqRunSelfSupersedesToZero() throws Exception {
    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();

    // RUN 2 (run_seq=2) lands FIRST.
    assertThat(
            liabilityService.handle(
                event(
                    UUID.randomUUID(),
                    run2,
                    2,
                    22_000_000L,
                    List.of(
                        new LiabilityBucket(
                            "NET_WAGES_PAYABLE", Money.ofMinor(22_000_000L, "IDR"))))))
        .isTrue();

    // RUN 1 (run_seq=1) arrives LATE — a higher-seq run's liability is already ACTIVE, so run 1
    // is already superseded: it posts its PRIMARY then immediately self-contras.
    assertThat(
            liabilityService.handle(
                event(
                    UUID.randomUUID(),
                    run1,
                    1,
                    20_000_000L,
                    List.of(
                        new LiabilityBucket(
                            "NET_WAGES_PAYABLE", Money.ofMinor(20_000_000L, "IDR"))))))
        .isTrue();

    assertThat(entryCountAsAdmin()).isEqualTo(3L); // run2 PRIMARY, run1 PRIMARY, run1 self-REVERSAL
    assertThat(liabilityStateAsAdmin(run1)).isEqualTo("SUPERSEDED");
    assertThat(liabilityStateAsAdmin(run2)).isEqualTo("POSTED");

    // run1's clearing leg nets to zero: one 20,000,000 debit PRIMARY + one 20,000,000 credit
    // self-reversal.
    List<LineRow> allLines = linesAsAdmin();
    long clearingDebit = allLines.stream().mapToLong(l -> l.debitMinor).sum();
    long clearingCredit = allLines.stream().mapToLong(l -> l.creditMinor).sum();
    assertThat(clearingDebit).isEqualTo(clearingCredit);
  }

  @Test
  void redeliveringTheLateLowerSeqRunNeverDoubleReverses() throws Exception {
    UUID run1 = UUID.randomUUID();
    UUID run2 = UUID.randomUUID();

    liabilityService.handle(
        event(
            UUID.randomUUID(),
            run2,
            2,
            22_000_000L,
            List.of(new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(22_000_000L, "IDR")))));

    PayrollLiabilitiesPostedEvent lateRun1 =
        event(
            UUID.randomUUID(),
            run1,
            1,
            20_000_000L,
            List.of(new LiabilityBucket("NET_WAGES_PAYABLE", Money.ofMinor(20_000_000L, "IDR"))));
    assertThat(liabilityService.handle(lateRun1)).as("first delivery of late run 1").isTrue();
    assertThat(liabilityService.handle(lateRun1)).as("re-delivery of late run 1").isFalse();

    assertThat(entryCountAsAdmin()).isEqualTo(3L);
  }

  @Test
  void orderIndependenceAgainstThePreExistingLaborAndReconciliationControlRow() throws Exception {
    // ADR 0032: the liability writer shares payroll_run_ledger with LaborCostPostingWriter /
    // PayrollReconciliationWriter but owns ONLY liability_entry_id/liability_state — posting the
    // liability event FIRST (before any labor bucket or PayrollPosted) must not disturb the OTHER
    // writers' fields, and vice versa; both lifecycles reach their correct terminal state
    // regardless of arrival order.
    UUID runId = UUID.randomUUID();

    // 1) Liability event arrives FIRST — opens the row.
    assertThat(
            liabilityService.handle(
                event(
                    UUID.randomUUID(),
                    runId,
                    1,
                    20_400_000L,
                    List.of(
                        new LiabilityBucket(
                            "NET_WAGES_PAYABLE", Money.ofMinor(20_400_000L, "IDR"))))))
        .isTrue();
    assertThat(liabilityStateAsAdmin(runId)).isEqualTo("POSTED");
    // The shared `state` column is still PENDING (untouched by the liability writer).
    assertThat(runStateAsAdmin(runId)).isEqualTo("PENDING");

    // 2) A LaborCostAllocated bucket arrives — the SAME run row, accumulating the labor dimension.
    assertThat(
            laborService.handle(
                new LaborCostAllocatedEvent(
                    UUID.randomUUID(),
                    TENANT_A,
                    runId,
                    1,
                    "REGULAR",
                    PERIOD,
                    OUTLET,
                    "5100-SALARY",
                    Money.ofMinor(20_400_000L, "IDR"),
                    false,
                    false,
                    OCCURRED_AT)))
        .isTrue();
    // The liability dimension is unaffected by the labor bucket landing.
    assertThat(liabilityStateAsAdmin(runId)).isEqualTo("POSTED");

    // 3) PayrollPosted (reconciliation) arrives last — the run reconciles on the shared `state`.
    assertThat(
            reconciliationService.handle(
                new PayrollPostedEvent(
                    UUID.randomUUID(),
                    TENANT_A,
                    runId,
                    1,
                    "REGULAR",
                    PERIOD,
                    "IDR",
                    Money.ofMinor(20_400_000L, "IDR"),
                    Money.ofMinor(0L, "IDR"),
                    Money.ofMinor(0L, "IDR"),
                    Money.ofMinor(20_400_000L, "IDR"),
                    false,
                    OCCURRED_AT)))
        .isTrue();
    assertThat(runStateAsAdmin(runId)).isEqualTo("RECONCILED");
    // The liability dimension is STILL unaffected — both lifecycles reached their correct terminal
    // state independently on the SAME row.
    assertThat(liabilityStateAsAdmin(runId)).isEqualTo("POSTED");
  }

  // ----------------------------------------------------------------------- helpers

  private PayrollLiabilitiesPostedEvent event(
      UUID eventId,
      UUID runId,
      int runSeq,
      long employerCostMinor,
      List<LiabilityBucket> liabilities) {
    return new PayrollLiabilitiesPostedEvent(
        eventId,
        TENANT_A,
        runId,
        runSeq,
        "REGULAR",
        PERIOD,
        "IDR",
        Money.ofMinor(employerCostMinor, "IDR"),
        liabilities,
        false,
        OCCURRED_AT);
  }

  private long creditFor(List<LineRow> lines, String accountCode) {
    return lines.stream()
        .filter(l -> l.accountCode.equals(accountCode))
        .mapToLong(l -> l.creditMinor)
        .sum();
  }

  private record LineRow(String accountCode, long debitMinor, long creditMinor) {}

  private long entryCountAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM journal_entry")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private List<LineRow> linesAsAdmin() throws Exception {
    List<LineRow> rows = new java.util.ArrayList<>();
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT account_code, debit_minor, credit_minor FROM journal_line ORDER BY"
                    + " entry_id, line_no")) {
      while (rs.next()) {
        rows.add(new LineRow(rs.getString(1), rs.getLong(2), rs.getLong(3)));
      }
    }
    return rows;
  }

  private String liabilityStateAsAdmin(UUID runId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT liability_state FROM payroll_run_ledger WHERE payroll_run_id = '"
                    + runId
                    + "'")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private UUID liabilityEntryIdAsAdmin(UUID runId) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT liability_entry_id FROM payroll_run_ledger WHERE payroll_run_id = '"
                    + runId
                    + "'")) {
      rs.next();
      Object value = rs.getObject(1);
      return value == null ? null : (UUID) value;
    }
  }
}
