package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.labor.messaging.LaborCostAllocatedEvent;
import id.co.nativeapp.finance.labor.service.LaborCostPostingService;
import id.co.nativeapp.finance.mapping.service.GlAccountResolver;
import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.service.PnlReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Acceptance + idempotency + illustrative + suspense proofs for the {@code LaborCostAllocated}
 * consumer (#23), driven through {@link LaborCostPostingService} against a real PostgreSQL (RLS as
 * the unprivileged {@code app_user}) so the ledger insert, the atomic P&L upsert, and the {@code
 * source_event_id} UNIQUE backstop are genuinely exercised. Posting through the service (not Kafka)
 * keeps the assertions deterministic; the end-to-end Kafka path is in {@link
 * LaborCostAllocatedConsumeAcceptanceTest}.
 */
@SpringBootTest
class LaborCostPostingTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID UNALLOCATED_OUTLET = new UUID(0L, 0L);
  private static final String ACTOR_A = "viewer-a@example.co.id";

  @Autowired private LaborCostPostingService laborService;
  @Autowired private PnlReader pnlReader;

  @Test
  void aLaborBucketPostsOneExpenseAndMovesThePnlExpenseLeg() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    long amount = 20_400_000L;

    boolean posted =
        laborService.handle(
            laborEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                period,
                OUTLET,
                "5100-SALARY",
                amount,
                false,
                false,
                occurredAt));
    assertThat(posted).isTrue();

    // Exactly one EXPENSE posting resolved to the seeded labor salary account (6000), full amount.
    LedgerRow row = singleLedgerRowAsAdmin();
    assertThat(row.postingType).isEqualTo("EXPENSE");
    assertThat(row.glAccountCode).isEqualTo("6000");
    assertThat(row.amountMinor).isEqualTo(amount);
    assertThat(row.postingRole).isEqualTo("PRIMARY");
    assertThat(row.period).isEqualTo(period);

    // The P&L expense leg moved up; net = -expense for a labor-only period.
    ConsolidatedPnl pnl =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.pnlForPeriod(period)).orElseThrow();
    assertThat(pnl.expense()).isEqualTo(Money.ofMinor(amount, "IDR"));
    assertThat(pnl.net()).isEqualTo(Money.ofMinor(-amount, "IDR"));
    assertThat(pnl.usesIllustrativeRules()).isFalse();
  }

  @Test
  void redeliveryOfTheSameBucketIsANoOp() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    long amount = 5_000_000L;
    UUID eventId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    LaborCostAllocatedEvent event =
        laborEvent(
            eventId, runId, 1, period, OUTLET, "5100-SALARY", amount, false, false, occurredAt);

    assertThat(laborService.handle(event)).as("first delivery posts").isTrue();
    assertThat(laborService.handle(event)).as("re-delivery is a no-op").isFalse();

    // One ledger row only; the P&L expense reflects a single application (no double-count).
    assertThat(ledgerCountAsAdmin()).isEqualTo(1L);
    ConsolidatedPnl pnl =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.pnlForPeriod(period)).orElseThrow();
    assertThat(pnl.expense()).isEqualTo(Money.ofMinor(amount, "IDR"));
  }

  @Test
  void theIllustrativeFlagPropagatesToThePostingAndThePnlReadModelStickyOr() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID illustrativeRun = UUID.randomUUID();

    // An illustrative-derived labor bucket lands first.
    assertThat(
            laborService.handle(
                laborEvent(
                    UUID.randomUUID(),
                    illustrativeRun,
                    1,
                    period,
                    OUTLET,
                    "5100-SALARY",
                    10_000_000L,
                    true, // illustrative
                    false,
                    occurredAt)))
        .isTrue();

    // The posting itself is stamped illustrative.
    assertThat(anyPostingIllustrativeAsAdmin()).isTrue();

    // The P&L row is flagged illustrative.
    ConsolidatedPnl afterIllustrative =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.pnlForPeriod(period)).orElseThrow();
    assertThat(afterIllustrative.usesIllustrativeRules()).isTrue();

    // A later NON-illustrative bucket for the SAME period (a separate, clean run_seq=2
    // supersession)
    // CANNOT clear the period flag (sticky-OR). It supersedes run_seq=1, reverses it, and reposts
    // clean — yet the consolidated_pnl row stays flagged because the figure was contaminated.
    UUID cleanRun = UUID.randomUUID();
    assertThat(
            laborService.handle(
                laborEvent(
                    UUID.randomUUID(),
                    cleanRun,
                    2,
                    period,
                    OUTLET,
                    "5100-SALARY",
                    3_000_000L,
                    false, // not illustrative
                    false,
                    occurredAt)))
        .isTrue();
    ConsolidatedPnl afterClean =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.pnlForPeriod(period)).orElseThrow();
    assertThat(afterClean.usesIllustrativeRules()).as("sticky — stays flagged").isTrue();
    // Net per period is exactly the latest clean run (3M): the illustrative run was reversed.
    assertThat(afterClean.expense()).isEqualTo(Money.ofMinor(3_000_000L, "IDR"));
  }

  @Test
  void theUnallocatedSuspenseBucketPostsToTheLaborClearingAccount() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    long amount = 20_400_000L;

    assertThat(
            laborService.handle(
                laborEvent(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    period,
                    UNALLOCATED_OUTLET,
                    "9999-UNALLOCATED-LABOR",
                    amount,
                    false,
                    true, // unallocated
                    occurredAt)))
        .isTrue();

    // It landed on the explicit 6900 labor-clearing account (NOT the expense general-suspense
    // 9999),
    // with the unallocated flag stamped — visible, never dropped.
    LedgerRow row = singleLedgerRowAsAdmin();
    assertThat(row.glAccountCode).isEqualTo(GlAccountResolver.LABOR_CLEARING_ACCOUNT_CODE);
    assertThat(row.glAccountCode).isEqualTo("6900");
    assertThat(row.unallocated).isTrue();
    assertThat(row.amountMinor).isEqualTo(amount);

    // The cost is real and visible in the P&L expense leg (required for reconciliation to balance).
    ConsolidatedPnl pnl =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.pnlForPeriod(period)).orElseThrow();
    assertThat(pnl.expense()).isEqualTo(Money.ofMinor(amount, "IDR"));
  }

  @Test
  void anUnrecognisedLaborHintFailsSafeToSuspenseNeverDropped() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    long amount = 1_000_000L;

    assertThat(
            laborService.handle(
                laborEvent(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    1,
                    period,
                    OUTLET,
                    "7777-MYSTERY-LABOR", // a hint with no mapping_rule
                    amount,
                    false,
                    false,
                    occurredAt)))
        .isTrue();

    // Posted to the expense general-suspense 9999 (money on the books), not dropped.
    LedgerRow row = singleLedgerRowAsAdmin();
    assertThat(row.glAccountCode).isEqualTo(GlAccountResolver.SUSPENSE_ACCOUNT_CODE);
    assertThat(row.amountMinor).isEqualTo(amount);
  }

  @Test
  void aDivergentCurrencyBucketFlagsRatherThanSilentlySplittingThePnl() throws Exception {
    // FIX 5 (#23, no-FX scope): a bucket whose currency differs from the run's established currency
    // must NOT silently create a SECOND consolidated_pnl row (which would only surface later as a
    // read-time "Multiple currencies" IllegalStateException in PnlReader). It is routed to the
    // terminal CURRENCY_MISMATCH state and NOT posted — the period keeps a single-currency P&L.
    Instant occurredAt = Instant.parse("2026-06-30T10:00:00Z");
    String period = "2026-06";
    UUID run = UUID.randomUUID();

    // First bucket establishes the run/period currency as IDR.
    assertThat(
            laborService.handle(
                laborEventCurrency(
                    UUID.randomUUID(),
                    run,
                    1,
                    period,
                    OUTLET,
                    "5100-SALARY",
                    Money.ofMinor(20_000_000L, "IDR"),
                    occurredAt)))
        .isTrue();

    // A USD bucket for the SAME run diverges: it is flagged, not posted, and creates no second row.
    laborService.handle(
        laborEventCurrency(
            UUID.randomUUID(),
            run,
            1,
            period,
            OUTLET,
            "5100-SALARY",
            Money.ofMinor(1_000L, "USD"),
            occurredAt));

    // The run is held back loudly; only the IDR bucket is on the books (the USD one was not
    // posted).
    assertThat(runStateAsAdmin(run)).isEqualTo("CURRENCY_MISMATCH");
    assertThat(ledgerCountAsAdmin()).isEqualTo(1L);

    // The P&L read does NOT throw "Multiple currencies": there is exactly one (IDR) row for the
    // period.
    ConsolidatedPnl pnl =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.pnlForPeriod(period)).orElseThrow();
    assertThat(pnl.expense()).isEqualTo(Money.ofMinor(20_000_000L, "IDR"));
  }

  // ----------------------------------------------------------------------- helpers

  private LaborCostAllocatedEvent laborEventCurrency(
      UUID eventId,
      UUID runId,
      int runSeq,
      String period,
      UUID outlet,
      String glAccount,
      Money amount,
      Instant occurredAt) {
    return new LaborCostAllocatedEvent(
        eventId,
        TENANT_A,
        runId,
        runSeq,
        "REGULAR",
        period,
        outlet,
        glAccount,
        amount,
        false,
        false,
        occurredAt);
  }

  private LaborCostAllocatedEvent laborEvent(
      UUID eventId,
      UUID runId,
      int runSeq,
      String period,
      UUID outlet,
      String glAccount,
      long amountMinor,
      boolean illustrative,
      boolean unallocated,
      Instant occurredAt) {
    return new LaborCostAllocatedEvent(
        eventId,
        TENANT_A,
        runId,
        runSeq,
        "REGULAR",
        period,
        outlet,
        glAccount,
        Money.ofMinor(amountMinor, "IDR"),
        illustrative,
        unallocated,
        occurredAt);
  }

  private record LedgerRow(
      String postingType,
      String glAccountCode,
      long amountMinor,
      String postingRole,
      boolean unallocated,
      String period) {}

  private LedgerRow singleLedgerRowAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT posting_type, gl_account_code, amount_minor, posting_role, unallocated,"
                    + " period FROM ledger_posting")) {
      rs.next();
      return new LedgerRow(
          rs.getString("posting_type"),
          rs.getString("gl_account_code"),
          rs.getLong("amount_minor"),
          rs.getString("posting_role"),
          rs.getBoolean("unallocated"),
          rs.getString("period"));
    }
  }

  private boolean anyPostingIllustrativeAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FROM ledger_posting WHERE uses_illustrative_rules = true")) {
      rs.next();
      return rs.getLong(1) > 0;
    }
  }
}
