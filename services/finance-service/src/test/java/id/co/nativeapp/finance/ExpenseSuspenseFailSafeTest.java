package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.expense.messaging.ExpenseRecordedEvent;
import id.co.nativeapp.finance.expense.service.ExpensePostingService;
import id.co.nativeapp.finance.mapping.service.GlAccountResolver;
import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.service.PnlReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Test (d) — an UNRESOLVABLE gl_hint FAILS SAFE: the expense is posted to the SUSPENSE account, not
 * silently dropped (HR-3 — never lose money).
 *
 * <p>An {@code ExpenseRecorded} carrying a specific {@code gl_hint} finance does not recognise
 * ({@code mystery-category}) is posted through {@link ExpensePostingService}. We PROVE money is not
 * lost: a single EXPENSE {@code ledger_posting} exists, stamped with the suspense account ({@code
 * 9999}), carrying the full amount; and the consolidated P&L's expense leg reflects it. So the
 * money is on the books and visible in the P&L, quarantined on suspense for reclassification — the
 * opposite of a silent drop.
 *
 * <p>Posting straight through the service (rather than over Kafka) keeps the assertion
 * deterministic; the DLT path for an UNDECODABLE payload (a different failure) is covered by the
 * Kafka DLT tests.
 */
@SpringBootTest
class ExpenseSuspenseFailSafeTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID BUSINESS_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR_A = "viewer-a@example.co.id";

  @Autowired private ExpensePostingService expensePostingService;
  @Autowired private PnlReader pnlReader;

  @Test
  void anUnmappableGlHintIsPostedToSuspenseAndNeverDropped() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");
    String period = "2026-06";
    long amountMinor = 1_250_000L;

    boolean posted =
        expensePostingService.handle(
            new ExpenseRecordedEvent(
                UUID.randomUUID(),
                TENANT_A,
                BUSINESS_A,
                Money.ofMinor(amountMinor, "IDR"),
                "mystery-category", // a specific hint with no mapping_rule
                occurredAt));
    assertThat(posted).as("the unmappable expense was still posted (not dropped)").isTrue();

    // The single EXPENSE posting landed on the suspense account, carrying the full amount.
    SuspensePosting row = suspensePostingAsAdmin();
    assertThat(row.glAccountCode).isEqualTo(GlAccountResolver.SUSPENSE_ACCOUNT_CODE);
    assertThat(row.postingType).isEqualTo("EXPENSE");
    assertThat(row.amountMinor).isEqualTo(amountMinor);

    // The P&L expense leg reflects the suspense-posted amount — the money is visible, not lost.
    ConsolidatedPnl pnl =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> pnlReader.pnlForPeriod(period)).orElseThrow();
    assertThat(pnl.expense()).isEqualTo(Money.ofMinor(amountMinor, "IDR"));
    assertThat(pnl.net()).isEqualTo(Money.ofMinor(-amountMinor, "IDR"));
  }

  private SuspensePosting suspensePostingAsAdmin() throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT posting_type, gl_account_code, amount_minor FROM ledger_posting");
        ResultSet rs = ps.executeQuery()) {
      rs.next();
      return new SuspensePosting(
          rs.getString("posting_type"),
          rs.getString("gl_account_code"),
          rs.getLong("amount_minor"));
    }
  }

  private record SuspensePosting(String postingType, String glAccountCode, long amountMinor) {}
}
