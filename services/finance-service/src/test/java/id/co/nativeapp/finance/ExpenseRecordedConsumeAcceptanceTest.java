package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.service.PnlReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test (b) — end-to-end CONSUME of {@code ExpenseRecorded} alongside {@code SaleRecorded}: the
 * EXPENSE gl_account is resolved via {@code mapping_rule}, an EXPENSE {@code ledger_posting} is
 * written, and the consolidated P&L read model reflects revenue, expense, AND net (revenue −
 * expense) — and re-delivering the expense does NOT double-count (idempotency, HR-3).
 *
 * <p>Publish one {@code SaleRecorded} (revenue) and one {@code ExpenseRecorded} (expense, gl_hint
 * {@code supplies} → account 5200) for the same tenant/period/currency; await both ledger rows;
 * assert the P&L. Then re-deliver the same expense event id and prove the P&L is unchanged (the
 * ledger stays at two rows). Awaitility awaits the async consumption (no {@code Thread.sleep}).
 */
@SpringBootTest
class ExpenseRecordedConsumeAcceptanceTest extends KafkaPostgresTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID BUSINESS_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR_A = "viewer-a@example.co.id";

  @Autowired private PnlReader pnlReader;

  @Test
  void consumingAnExpensePostsAnExpenseLedgerAndMovesThePnlAndIsIdempotent() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");
    String period = "2026-06";
    long revenueMinor = 2_000_000L;
    long expenseMinor = 750_000L;

    // Revenue leg: a SaleRecorded.
    UUID saleId = UUID.randomUUID();
    GenericRecord sale =
        SaleRecordedFixtures.record(saleId, TENANT_A, BUSINESS_A, revenueMinor, "IDR", occurredAt);
    SaleRecordedFixtures.publish(
        KAFKA.getBootstrapServers(), saleId.toString(), UUID.randomUUID(), sale);

    // Expense leg: an ExpenseRecorded with gl_hint=supplies (-> account 5200).
    UUID expenseId = UUID.randomUUID();
    UUID expenseEventId = UUID.randomUUID();
    GenericRecord expense =
        ExpenseRecordedFixtures.record(
            expenseId, TENANT_A, BUSINESS_A, expenseMinor, "IDR", "supplies", occurredAt);
    ExpenseRecordedFixtures.publish(
        KAFKA.getBootstrapServers(), expenseId.toString(), expenseEventId, expense);

    // Await both ledger rows (revenue + expense).
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(ledgerCountAsAdmin()).isEqualTo(2L));

    // The EXPENSE posting resolved to the seeded supplies expense account (5200).
    Map<String, Object> expensePosting = expensePostingAsAdmin();
    assertThat(expensePosting.get("posting_type")).isEqualTo("EXPENSE");
    assertThat(expensePosting.get("gl_account_code")).isEqualTo("5200");
    assertThat(expensePosting.get("amount_minor")).isEqualTo(expenseMinor);

    // The P&L read model reflects revenue, expense, and net.
    ConsolidatedPnl pnl =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.accumulatedPnlForPeriod(period))
            .orElseThrow();
    assertThat(pnl.revenue()).isEqualTo(Money.ofMinor(revenueMinor, "IDR"));
    assertThat(pnl.expense()).isEqualTo(Money.ofMinor(expenseMinor, "IDR"));
    assertThat(pnl.net()).isEqualTo(Money.ofMinor(revenueMinor - expenseMinor, "IDR"));

    // IDEMPOTENCY: re-deliver the SAME expense event id, then a distinct marker expense after it
    // on the same partition; once the marker posts (3 ledger rows), the duplicate is necessarily
    // drained — so the no-double-count assertion is race-free.
    ExpenseRecordedFixtures.publish(
        KAFKA.getBootstrapServers(), expenseId.toString(), expenseEventId, expense);

    UUID markerId = UUID.randomUUID();
    GenericRecord marker =
        ExpenseRecordedFixtures.record(
            markerId, TENANT_A, BUSINESS_A, 1L, "IDR", "supplies", occurredAt);
    ExpenseRecordedFixtures.publish(
        KAFKA.getBootstrapServers(), expenseId.toString(), UUID.randomUUID(), marker);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(ledgerCountAsAdmin()).isEqualTo(3L));

    // The duplicate expense was NOT re-applied: expense is the original + the 1-minor marker only.
    ConsolidatedPnl afterDup =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.accumulatedPnlForPeriod(period))
            .orElseThrow();
    assertThat(afterDup.expense()).isEqualTo(Money.ofMinor(expenseMinor + 1L, "IDR"));
    assertThat(afterDup.revenue()).isEqualTo(Money.ofMinor(revenueMinor, "IDR"));
  }

  private Map<String, Object> expensePostingAsAdmin() throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs =
            st.executeQuery(
                "SELECT posting_type, gl_account_code, amount_minor FROM ledger_posting"
                    + " WHERE posting_type = 'EXPENSE' AND amount_minor > 1")) {
      rs.next();
      return Map.of(
          "posting_type", rs.getString("posting_type"),
          "gl_account_code", rs.getString("gl_account_code"),
          "amount_minor", rs.getLong("amount_minor"));
    }
  }
}
