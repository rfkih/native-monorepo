package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.expense.messaging.ExpenseRecordedEvent;
import id.co.nativeapp.finance.expense.service.ExpensePostingService;
import id.co.nativeapp.finance.pnl.domain.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.finance.pnl.service.PnlReader;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Hardening test for the single-base-currency invariant on the revenue/expense posting path (#26 —
 * finance-expansion robustness). A company has ONE immutable base currency (CLAUDE.md); a posting
 * whose currency diverges is a producer contract violation. Before this guard such a posting would
 * silently create a SECOND {@code consolidated_pnl} row keyed on {@code (company, period,
 * currency)}, which then detonated at READ time as a generic {@code 500} ({@code PnlReader} rejects
 * a multi-currency period). The write-time guard now FAILS CLOSED: a typed {@link
 * MismatchedPostingCurrencyException} (classified non-retryable → DLT) rolls the consume back so no
 * divergent row is created and the period's P&amp;L read stays clean.
 *
 * <p>Drives the posting services directly (they bind the event tenant via {@link
 * TenantContext#callAs}) against the real Flyway schema + RLS as the unprivileged {@code app_user}
 * — no Kafka needed for the guard logic.
 */
@SpringBootTest
class PostingCurrencyGuardTest extends PostgresRlsTestBase {

  private static final String PERIOD = "2026-06";
  private static final Instant IN_PERIOD = Instant.parse("2026-06-15T00:00:00Z");
  private static final String ACTOR = "viewer@example.co.id";

  @Autowired private RevenuePostingService revenueService;
  @Autowired private ExpensePostingService expenseService;
  @Autowired private PnlReader pnlReader;

  @Test
  void aDivergentCurrencyExpenseIsRejectedAndLeavesThePeriodSingleCurrency() throws Exception {
    UUID company = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();

    // The period's currency is established as IDR by a sale.
    revenueService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(),
            company.toString(),
            outlet,
            Money.ofMinor(10_000_000L, "IDR"),
            IN_PERIOD));

    // A USD expense for the same company+period violates the immutable base currency → fail closed.
    ExpenseRecordedEvent divergent =
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            company.toString(),
            outlet,
            Money.ofMinor(1_000_000L, "USD"),
            "supplies",
            IN_PERIOD);
    assertThatThrownBy(() -> expenseService.handle(divergent))
        .isInstanceOf(MismatchedPostingCurrencyException.class);

    // No divergent second-currency row was created: exactly ONE consolidated_pnl row, in IDR.
    assertThat(pnlRowCountAsAdmin(company)).isEqualTo(1L);
    assertThat(pnlCurrencyAsAdmin(company)).isEqualTo("IDR");

    // And the P&L read stays clean (does NOT throw the multi-currency 500): revenue intact, the
    // rejected expense never landed.
    ConsolidatedPnl pnl =
        TenantContext.callAs(
                company.toString(), ACTOR, () -> pnlReader.accumulatedPnlForPeriod(PERIOD))
            .orElseThrow();
    assertThat(pnl.revenue()).isEqualTo(Money.ofMinor(10_000_000L, "IDR"));
    assertThat(pnl.expense()).isEqualTo(Money.ofMinor(0L, "IDR"));
  }

  @Test
  void aDivergentCurrencySaleIsRejectedAfterAnExpenseEstablishesTheCurrency() throws Exception {
    UUID company = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();

    // The period's currency is established as IDR by an expense this time.
    expenseService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            company.toString(),
            outlet,
            Money.ofMinor(4_000_000L, "IDR"),
            "supplies",
            IN_PERIOD));

    // A USD sale for the same company+period is rejected.
    SaleRecordedEvent divergent =
        new SaleRecordedEvent(
            UUID.randomUUID(),
            company.toString(),
            outlet,
            Money.ofMinor(5_000_000L, "USD"),
            IN_PERIOD);
    assertThatThrownBy(() -> revenueService.handle(divergent))
        .isInstanceOf(MismatchedPostingCurrencyException.class);

    assertThat(pnlRowCountAsAdmin(company)).isEqualTo(1L);
    assertThat(pnlCurrencyAsAdmin(company)).isEqualTo("IDR");
    // The rejected sale wrote nothing — no IDR (or USD) consolidated_revenue row leaked through.
    assertThat(revenueRowCountAsAdmin(company)).isZero();
  }

  @Test
  void sameCurrencyPostingsAccumulateNormally() throws Exception {
    UUID company = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();

    revenueService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(),
            company.toString(),
            outlet,
            Money.ofMinor(10_000_000L, "IDR"),
            IN_PERIOD));
    expenseService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            company.toString(),
            outlet,
            Money.ofMinor(4_000_000L, "IDR"),
            "supplies",
            IN_PERIOD));

    assertThat(pnlRowCountAsAdmin(company)).isEqualTo(1L);
    ConsolidatedPnl pnl =
        TenantContext.callAs(
                company.toString(), ACTOR, () -> pnlReader.accumulatedPnlForPeriod(PERIOD))
            .orElseThrow();
    assertThat(pnl.revenue()).isEqualTo(Money.ofMinor(10_000_000L, "IDR"));
    assertThat(pnl.expense()).isEqualTo(Money.ofMinor(4_000_000L, "IDR"));
    assertThat(pnl.net()).isEqualTo(Money.ofMinor(6_000_000L, "IDR"));
  }

  // ----------------------------------------------------------------------- admin (BYPASSRLS) reads

  private long pnlRowCountAsAdmin(UUID company) throws SQLException {
    return countAsAdmin("SELECT count(*) FROM consolidated_pnl WHERE company_id = ?", company);
  }

  private long revenueRowCountAsAdmin(UUID company) throws SQLException {
    return countAsAdmin("SELECT count(*) FROM consolidated_revenue WHERE company_id = ?", company);
  }

  private long countAsAdmin(String sql, UUID company) throws SQLException {
    try (Connection admin = adminConnection();
        PreparedStatement ps = admin.prepareStatement(sql)) {
      ps.setString(1, company.toString());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String pnlCurrencyAsAdmin(UUID company) throws SQLException {
    try (Connection admin = adminConnection();
        PreparedStatement ps =
            admin.prepareStatement("SELECT currency FROM consolidated_pnl WHERE company_id = ?")) {
      ps.setString(1, company.toString());
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getString(1);
      }
    }
  }

  private static Connection adminConnection() throws SQLException {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
