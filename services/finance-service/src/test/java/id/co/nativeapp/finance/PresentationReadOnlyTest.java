package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.expense.ExpensePostingService;
import id.co.nativeapp.finance.expense.ExpenseRecordedEvent;
import id.co.nativeapp.finance.fx.PnlPresentation;
import id.co.nativeapp.finance.fx.PresentationConverter;
import id.co.nativeapp.finance.fx.RevenuePresentation;
import id.co.nativeapp.finance.pnl.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.PnlReader;
import id.co.nativeapp.finance.revenue.RevenuePostingService;
import id.co.nativeapp.finance.revenue.RevenueReader;
import id.co.nativeapp.finance.revenue.SaleRecordedEvent;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * THE view-only proof: a presentation-currency conversion query does NOT mutate any stored figure.
 *
 * <p>Posts revenue + expense for a tenant (so {@code ledger_posting}, {@code consolidated_revenue},
 * {@code consolidated_pnl} hold real rows), snapshots every column of those tables over an admin
 * connection, runs a presentation conversion through {@link PresentationConverter} (the read path
 * the controllers use), then re-snapshots and asserts the rows are BYTE-FOR-BYTE identical —
 * including the audit {@code version} (an UPDATE would bump it). The transaction-currency books and
 * the read models are untouched; presentation is a pure read transform.
 */
@SpringBootTest
class PresentationReadOnlyTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final UUID BUSINESS = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR = "viewer@example.co.id";
  private static final String PERIOD = "2026-06";
  private static final Currency USD = Currency.getInstance("USD");

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private ExpensePostingService expensePostingService;
  @Autowired private RevenueReader revenueReader;
  @Autowired private PnlReader pnlReader;
  @Autowired private PresentationConverter presentationConverter;

  @Test
  void aPresentationConversionDoesNotChangeAnyStoredFigure() throws Exception {
    Instant occurredAt = Instant.parse("2026-06-14T08:30:00Z");

    // Post IDR revenue and an IDR expense so all three tables hold rows.
    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT, BUSINESS, Money.ofMinor(16_000_000L, "IDR"), occurredAt));
    expensePostingService.handle(
        new ExpenseRecordedEvent(
            UUID.randomUUID(),
            TENANT,
            BUSINESS,
            Money.ofMinor(6_000_000L, "IDR"),
            "supplies",
            occurredAt));

    // Snapshot every stored row BEFORE the conversion.
    String ledgerBefore = snapshot("SELECT * FROM ledger_posting ORDER BY id");
    String revenueBefore = snapshot("SELECT * FROM consolidated_revenue ORDER BY id");
    String pnlBefore = snapshot("SELECT * FROM consolidated_pnl ORDER BY id");

    // Run the presentation conversions (the read path) under the tenant scope, into USD.
    RevenuePresentation revView =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              Optional<Money> total = revenueReader.revenueForPeriod(PERIOD);
              return presentationConverter.convertRevenue(total.orElseThrow(), PERIOD, USD);
            });
    PnlPresentation pnlView =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              ConsolidatedPnl pnl = pnlReader.pnlForPeriod(PERIOD).orElseThrow();
              return presentationConverter.convertPnl(pnl, PERIOD, USD);
            });

    // The conversions produced USD figures (sanity — the read actually ran), flagged stub.
    assertThat(revView.presentationTotal().currency()).isEqualTo(USD);
    assertThat(revView.usesStubFx()).isTrue();
    assertThat(pnlView.presentationNet().currency()).isEqualTo(USD);
    assertThat(pnlView.usesStubFx()).isTrue();

    // Snapshot AFTER — must be byte-for-byte identical (no UPDATE, no version bump, no new row).
    assertThat(snapshot("SELECT * FROM ledger_posting ORDER BY id")).isEqualTo(ledgerBefore);
    assertThat(snapshot("SELECT * FROM consolidated_revenue ORDER BY id")).isEqualTo(revenueBefore);
    assertThat(snapshot("SELECT * FROM consolidated_pnl ORDER BY id")).isEqualTo(pnlBefore);
  }

  /**
   * Serializes a full result set (all columns, ordered) to a stable string over the admin
   * (BYPASSRLS) connection, so the snapshot sees every tenant's rows regardless of any session GUC.
   */
  private String snapshot(String sql) throws Exception {
    StringBuilder out = new StringBuilder();
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      int columns = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        TreeMap<String, String> row = new TreeMap<>();
        for (int i = 1; i <= columns; i++) {
          row.put(rs.getMetaData().getColumnName(i), String.valueOf(rs.getObject(i)));
        }
        out.append(row).append('\n');
      }
    }
    return out.toString();
  }
}
