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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * RLS isolation for the per-org-unit P&amp;L rollup: tenant A's unit — ref rows AND postings — is
 * completely invisible inside tenant B's scope. The reader carries no {@code WHERE company_id};
 * only auto-applied RLS scopes it (rule 5), so a foreign unit id behaves exactly like an unknown
 * one (anti-enumeration: B cannot even learn that A's unit exists).
 */
@SpringBootTest
class UnitPnlTenancyIsolationTest extends PostgresRlsTestBase {

  private static final Instant OCCURRED = Instant.parse("2026-07-15T10:00:00Z");
  private static final String PERIOD = "2026-07";

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private UnitPnlReader unitPnlReader;

  @Test
  void aUnitAndItsPostingsAreInvisibleToAnotherTenant() throws Exception {
    String tenantA = UUID.randomUUID().toString();
    String tenantB = UUID.randomUUID().toString();
    UUID bu = UUID.randomUUID();
    UUID outlet = UUID.randomUUID();
    insertOrgUnitRef(bu, tenantA, "business_unit", null, "A HQ", true);
    insertOrgUnitRef(outlet, tenantA, "outlet", bu, "A Outlet", true);
    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), tenantA, outlet, Money.ofMinor(500_000L, "IDR"), OCCURRED));

    // Inside B's scope, A's unit id resolves EMPTY — indistinguishable from unknown.
    Optional<UnitPnlResponse> visibleToB =
        TenantContext.callAs(tenantB, "b", () -> unitPnlReader.unitPnlForPeriod(bu, PERIOD));
    assertThat(visibleToB).isEmpty();

    // Sanity: inside A's own scope the rollup is present and carries the posting.
    Optional<UnitPnlResponse> visibleToA =
        TenantContext.callAs(tenantA, "a", () -> unitPnlReader.unitPnlForPeriod(bu, PERIOD));
    assertThat(visibleToA).isPresent();
    assertThat(visibleToA.get().revenueMinor()).isEqualTo(500_000L);
  }

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
}
