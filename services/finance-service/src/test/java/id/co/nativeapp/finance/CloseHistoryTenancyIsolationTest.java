package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.withinclose.dto.CloseHistoryResponse;
import id.co.nativeapp.finance.withinclose.service.CloseHistoryReader;
import id.co.nativeapp.finance.withinclose.service.WithinCompanyCloseService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * RLS tenant-isolation proof for {@code GET /api/v1/closes} — the {@link
 * id.co.nativeapp.finance.withinclose.repository.WithinCompanyCloseRepository#findAllViews} query
 * and its service-layer projection-to-DTO mapping in {@link CloseHistoryReader}.
 *
 * <p>Company A runs a within-company close; the {@code findAllViews} query carries <em>no</em>
 * {@code WHERE company_id} — the result set is constrained solely by the auto-applied RLS policy
 * (rule 5). This test proves that:
 *
 * <ol>
 *   <li>A sees its own close row (the query + native-projection alias mapping + the {@link
 *       id.co.nativeapp.finance.withinclose.projection.WithinCompanyCloseView#getId} {@code String}
 *       coercion over a real UUID column all work end-to-end against a real DB).
 *   <li>B, bound as a different tenant, sees an empty result (RLS blocks A's rows from B's session
 *       GUC even though the query has no {@code WHERE company_id}).
 * </ol>
 *
 * <p>Seeding goes through {@link WithinCompanyCloseService} (the real production write path) rather
 * than a JDBC backdoor, so the {@code company_id} column is set by the {@code TenantAuditorAware}
 * from the bound tenant — exactly as a real close request would stamp it.
 *
 * <p>Runs as the unprivileged {@code app_user} role (see {@link PostgresRlsTestBase}); {@code FORCE
 * ROW LEVEL SECURITY} on {@code within_company_close} binds even that owning role.
 */
@SpringBootTest
class CloseHistoryTenancyIsolationTest extends PostgresRlsTestBase {

  // Unique UUIDs — no overlap with GlTenancyIsolationTest (eeee/ffff) or
  // StatementsTenancyIsolationTest (7777/7772), RevenueTenancyIsolationTest (1111/9999).
  private static final String TENANT_A = "cccccccc-cccc-cccc-cccc-cccccccccccc";
  private static final String TENANT_B = "dddddddd-dddd-dddd-dddd-dddddddddddd";
  private static final UUID BUSINESS_A = UUID.fromString("aaaabbbb-aaaa-bbbb-aaaa-bbbbaaaabbbb");
  private static final String PERIOD = "2026-05";
  // 2026-05-10 — inside PERIOD so the ledger posting period resolves to 2026-05.
  private static final Instant IN_PERIOD = Instant.parse("2026-05-10T08:00:00Z");

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private WithinCompanyCloseService closeService;
  @Autowired private CloseHistoryReader closeHistoryReader;

  /**
   * Primary assertion: A's close rows are invisible to B under RLS.
   *
   * <p>This also exercises the full real-DB path of {@link
   * CloseHistoryReader#findAllForCurrentTenant}: the native query → {@code WithinCompanyCloseView}
   * projection interface → getId() String coercion over a real UUID column → DTO mapping. If the
   * alias mapping or the String getId() coercion were broken, the seed step itself would surface
   * the defect before the isolation assertion runs.
   */
  @Test
  void closeHistoryPostedUnderTenantAIsInvisibleToTenantB() throws Exception {
    // Seed a ledger posting for A so the close has something to gather.
    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT_A, BUSINESS_A, Money.ofMinor(3_000_000L, "IDR"), IN_PERIOD));

    // Run a within-company close as A (binds A as the session tenant; company_id stamped from GUC).
    TenantContext.runAs(TENANT_A, "closer-a", () -> closeService.close(PERIOD, "IDR"));

    // A sees its own close row — exercises the real-DB native query + String getId() coercion.
    List<CloseHistoryResponse> aView =
        TenantContext.callAs(
            TENANT_A, "reader-a", () -> closeHistoryReader.findAllForCurrentTenant());
    assertThat(aView).as("Tenant A must see its own close history").hasSize(1);

    CloseHistoryResponse closeRow = aView.getFirst();
    // getId() on WithinCompanyCloseView returns String over a UUID column.
    // Deliberate coercion: the projection returns the UUID as String so
    // CloseHistoryResponse.closeId
    // is a String, consistent with the JSON API surface. The real-DB test confirms this coercion
    // works (Postgres will cast uuid→text transparently for the String-typed projection getter).
    assertThat(closeRow.closeId())
        .as("closeId must be a valid UUID string")
        .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    assertThat(closeRow.period()).isEqualTo(PERIOD);
    assertThat(closeRow.baseCurrency()).isEqualTo("IDR");
    assertThat(closeRow.firstClose()).isTrue();
    assertThat(closeRow.reconciled()).isTrue();

    // B sees EMPTY — RLS scopes within_company_close to the bound session GUC (B's company_id),
    // which does not match A's rows (no WHERE company_id in the query — only RLS enforces this).
    List<CloseHistoryResponse> bView =
        TenantContext.callAs(
            TENANT_B, "reader-b", () -> closeHistoryReader.findAllForCurrentTenant());
    assertThat(bView)
        .as(
            "Tenant B must see EMPTY close history for a close run by A"
                + " (RLS blocks A's within_company_close rows from B's session GUC)")
        .isEmpty();
  }

  @Override
  void resetTables() {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement()) {
      st.execute(
          "TRUNCATE TABLE ledger_posting, consolidated_revenue, consolidated_pnl,"
              + " outlet_revenue, org_unit_ref,"
              + " payroll_run_ledger, group_ref, group_member, group_lead,"
              + " group_trial_balance, consolidation_ledger, consolidation_summary,"
              + " intercompany_match, group_membership_pending, processed_event,"
              + " outbox, within_company_close, member_group_index,"
              + " journal_line, journal_entry CASCADE");
    } catch (Exception ignored) {
      // Tables not yet created — nothing to reset.
    }
  }
}
