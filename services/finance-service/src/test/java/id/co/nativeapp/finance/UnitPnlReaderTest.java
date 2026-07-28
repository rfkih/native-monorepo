package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.finance.unitpnl.dto.UnitPnlResponse;
import id.co.nativeapp.finance.unitpnl.service.UnitPnlReader;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Reader tests for {@code GET /api/v1/pnl/org-units/{id}} — the per-org-unit P&amp;L rollup.
 *
 * <p>A BUSINESS_UNIT rolls up itself + its child outlets ({@code org_unit_ref.parent_id}); an
 * OUTLET rolls up only its own postings; REVERSAL rows net PRIMARY via the signed SUM; the
 * all-zeros labor-suspense sentinel never joins a ref row and is therefore excluded; a unit unknown
 * to {@code org_unit_ref} (or a TEAM) resolves empty; a known unit with zero postings returns zeros
 * with its known child outlets listed (the console renders zeros — hydration lag is not an error).
 */
@SpringBootTest
class UnitPnlReaderTest extends PostgresRlsTestBase {

  private static final Instant OCCURRED = Instant.parse("2026-07-15T10:00:00Z");
  private static final String PERIOD = "2026-07";
  private static final String ACTOR = "test-reader";
  private static final UUID SENTINEL = new UUID(0L, 0L);

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private UnitPnlReader unitPnlReader;

  @Test
  void businessUnitRollsUpChildOutletsWithBreakdownOrderedByRevenueDesc() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID bu = UUID.randomUUID();
    UUID outletA = UUID.randomUUID();
    UUID outletB = UUID.randomUUID();
    insertOrgUnitRef(bu, tenant, "business_unit", null, "HQ", true);
    insertOrgUnitRef(outletA, tenant, "outlet", bu, "Outlet A", true);
    insertOrgUnitRef(outletB, tenant, "outlet", bu, "Outlet B", true);

    postSale(tenant, outletA, 100_000L);
    postSale(tenant, outletB, 250_000L);

    Optional<UnitPnlResponse> result =
        TenantContext.callAs(tenant, ACTOR, () -> unitPnlReader.unitPnlForPeriod(bu, PERIOD));

    assertThat(result).isPresent();
    UnitPnlResponse pnl = result.get();
    assertThat(pnl.orgUnitId()).isEqualTo(bu);
    assertThat(pnl.revenueMinor()).isEqualTo(350_000L);
    assertThat(pnl.expenseMinor()).isZero();
    assertThat(pnl.netMinor()).isEqualTo(350_000L);
    assertThat(pnl.currency()).isEqualTo("IDR");
    // Breakdown: child outlets only, revenue-desc.
    assertThat(pnl.outlets()).hasSize(2);
    assertThat(pnl.outlets().get(0).orgUnitId()).isEqualTo(outletB);
    assertThat(pnl.outlets().get(0).revenueMinor()).isEqualTo(250_000L);
    assertThat(pnl.outlets().get(1).orgUnitId()).isEqualTo(outletA);
    assertThat(pnl.outlets().get(1).name()).isEqualTo("Outlet A");
  }

  @Test
  void reversalRowsNetAgainstPrimaryInTheRollup() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID bu = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();
    insertOrgUnitRef(bu, tenant, "business_unit", null, "HQ", true);
    insertOrgUnitRef(outlet, tenant, "outlet", bu, "Outlet", true);

    postSale(tenant, outlet, 200_000L);
    // A REVERSAL contra row exactly as ReversalPostingWriter writes it (negative amount,
    // posting_role = REVERSAL, same business/period/type). The reversal writer's own behavior
    // is proven by OutletRevenueReversalTest; here we prove the rollup's signed SUM nets it.
    insertLedgerPosting(tenant, outlet, "REVENUE", -80_000L, "REVERSAL", false);

    Optional<UnitPnlResponse> result =
        TenantContext.callAs(tenant, ACTOR, () -> unitPnlReader.unitPnlForPeriod(bu, PERIOD));

    assertThat(result).isPresent();
    assertThat(result.get().revenueMinor()).isEqualTo(120_000L);
    assertThat(result.get().netMinor()).isEqualTo(120_000L);
    assertThat(result.get().outlets().get(0).revenueMinor()).isEqualTo(120_000L);
  }

  @Test
  void outletRollsUpOnlyItsOwnPostingsWithEmptyBreakdown() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID bu = UUID.randomUUID();
    UUID outletA = UUID.randomUUID();
    UUID outletB = UUID.randomUUID();
    insertOrgUnitRef(bu, tenant, "business_unit", null, "HQ", true);
    insertOrgUnitRef(outletA, tenant, "outlet", bu, "Outlet A", true);
    insertOrgUnitRef(outletB, tenant, "outlet", bu, "Outlet B", true);

    postSale(tenant, outletA, 100_000L);
    postSale(tenant, outletB, 999_000L);

    Optional<UnitPnlResponse> result =
        TenantContext.callAs(tenant, ACTOR, () -> unitPnlReader.unitPnlForPeriod(outletA, PERIOD));

    assertThat(result).isPresent();
    assertThat(result.get().revenueMinor()).isEqualTo(100_000L);
    assertThat(result.get().outlets()).isEmpty();
  }

  @Test
  void unallocatedLaborSentinelIsExcludedFromTheRollup() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID bu = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();
    insertOrgUnitRef(bu, tenant, "business_unit", null, "HQ", true);
    insertOrgUnitRef(outlet, tenant, "outlet", bu, "Outlet", true);

    postSale(tenant, outlet, 100_000L);
    // Labor-suspense EXPENSE row on the all-zeros sentinel (no outlet) — company-level
    // suspense, never attributable to a unit; must not join any org_unit_ref row.
    insertLedgerPosting(tenant, SENTINEL, "EXPENSE", 50_000L, "PRIMARY", true);

    Optional<UnitPnlResponse> result =
        TenantContext.callAs(tenant, ACTOR, () -> unitPnlReader.unitPnlForPeriod(bu, PERIOD));

    assertThat(result).isPresent();
    assertThat(result.get().expenseMinor()).isZero();
    assertThat(result.get().netMinor()).isEqualTo(100_000L);
  }

  @Test
  void unknownUnitResolvesEmpty() throws Exception {
    String tenant = UUID.randomUUID().toString();
    Optional<UnitPnlResponse> result =
        TenantContext.callAs(
            tenant, ACTOR, () -> unitPnlReader.unitPnlForPeriod(UUID.randomUUID(), PERIOD));
    assertThat(result).isEmpty();
  }

  @Test
  void teamUnitResolvesEmpty() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID team = UUID.randomUUID();
    insertOrgUnitRef(team, tenant, "team", null, "Kitchen Team", true);

    Optional<UnitPnlResponse> result =
        TenantContext.callAs(tenant, ACTOR, () -> unitPnlReader.unitPnlForPeriod(team, PERIOD));

    assertThat(result).isEmpty();
  }

  @Test
  void knownUnitWithNoPostingsReturnsZerosWithKnownOutletRows() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID bu = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();
    insertOrgUnitRef(bu, tenant, "business_unit", null, "HQ", true);
    insertOrgUnitRef(outlet, tenant, "outlet", bu, "Fresh Outlet", true);

    Optional<UnitPnlResponse> result =
        TenantContext.callAs(tenant, ACTOR, () -> unitPnlReader.unitPnlForPeriod(bu, PERIOD));

    assertThat(result).isPresent();
    assertThat(result.get().revenueMinor()).isZero();
    assertThat(result.get().expenseMinor()).isZero();
    assertThat(result.get().currency()).isNull(); // no postings → no stored currency
    assertThat(result.get().outlets()).hasSize(1);
    assertThat(result.get().outlets().get(0).name()).isEqualTo("Fresh Outlet");
    assertThat(result.get().outlets().get(0).revenueMinor()).isZero();
  }

  // ------------------------------------------------------------------ helpers

  private void postSale(String tenant, UUID businessId, long amountMinor) {
    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), tenant, businessId, Money.ofMinor(amountMinor, "IDR"), OCCURRED));
  }

  /** Inserts an {@code org_unit_ref} row via the admin (BYPASSRLS) connection. */
  private void insertOrgUnitRef(
      UUID orgUnitId, String companyId, String type, UUID parentId, String name, boolean active)
      throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO org_unit_ref"
                    + " (org_unit_id, type, parent_id, name, active,"
                    + "  created_at, created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, ?, ?, ?, NOW(), 'test', NOW(), 'test', 0, ?)")) {
      ps.setObject(1, orgUnitId);
      ps.setString(2, type);
      ps.setObject(3, parentId);
      ps.setString(4, name);
      ps.setBoolean(5, active);
      ps.setString(6, companyId);
      ps.executeUpdate();
    }
  }

  /**
   * Inserts a {@code ledger_posting} row directly via the admin connection, reusing a GL account
   * code already present for the tenant (posted by the real service path) so the {@code
   * chart_of_account} FK holds. Used for the REVERSAL contra and the labor-suspense sentinel rows,
   * whose writers are proven elsewhere — here only the rollup arithmetic is under test.
   */
  private void insertLedgerPosting(
      String companyId,
      UUID businessId,
      String postingType,
      long amountMinor,
      String postingRole,
      boolean unallocated)
      throws Exception {
    try (Connection admin =
        java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      String glCode = anyGlCodeForTenant(admin, companyId);
      try (PreparedStatement ps =
          admin.prepareStatement(
              "INSERT INTO ledger_posting"
                  + " (id, business_id, period, posting_type, amount_minor, currency,"
                  + "  source_event_id, gl_account_code, posting_role, uses_illustrative_rules,"
                  + "  unallocated, created_at, created_by, updated_at, updated_by, version,"
                  + "  company_id)"
                  + " VALUES (?, ?, ?, ?, ?, 'IDR', ?, ?, ?, false, ?, NOW(), 'test', NOW(),"
                  + " 'test', 0, ?)")) {
        ps.setObject(1, UUID.randomUUID());
        ps.setObject(2, businessId);
        ps.setString(3, PERIOD);
        ps.setString(4, postingType);
        ps.setLong(5, amountMinor);
        ps.setObject(6, UUID.randomUUID());
        ps.setString(7, glCode);
        ps.setString(8, postingRole);
        ps.setBoolean(9, unallocated);
        ps.setString(10, companyId);
        ps.executeUpdate();
      }
    }
  }

  /** A GL account code that exists for the tenant (written by the real posting path). */
  private String anyGlCodeForTenant(Connection admin, String companyId) throws Exception {
    try (Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT gl_account_code FROM ledger_posting WHERE company_id = '"
                    + companyId
                    + "' LIMIT 1")) {
      if (rs.next()) {
        return rs.getString(1);
      }
    }
    // No posting yet for this tenant — fall back to any chart_of_account code for it.
    try (Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT account_code FROM chart_of_account WHERE company_id = '"
                    + companyId
                    + "' LIMIT 1")) {
      if (rs.next()) {
        return rs.getString(1);
      }
    }
    throw new IllegalStateException("no GL account code available for tenant " + companyId);
  }
}
