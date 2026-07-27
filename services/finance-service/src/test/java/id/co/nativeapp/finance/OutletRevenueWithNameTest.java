package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.outlet.dto.OutletRevenueResponse;
import id.co.nativeapp.finance.outlet.service.OutletRevenueReader;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.service.RevenuePostingService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Reader test for the {@code outletName} Phase 2 addition to {@code GET /api/v1/pnl/outlets}.
 *
 * <p>Proves two cases:
 *
 * <ol>
 *   <li><strong>Named row after hydration:</strong> when an {@code org_unit_ref} row exists for the
 *       {@code business_id}, the {@code outletName} field carries the org-unit's display name.
 *   <li><strong>Null name before hydration:</strong> when NO {@code org_unit_ref} row exists (the
 *       consumer has not yet processed the {@code OrgUnitCreated} event), the revenue row is still
 *       returned with {@code outletName = null} — the read never blocks on hydration (rule 2).
 * </ol>
 */
@SpringBootTest
class OutletRevenueWithNameTest extends PostgresRlsTestBase {

  private static final String TENANT = "dddddddd-dddd-dddd-dddd-dddddddddddd";
  private static final UUID OUTLET_WITH_NAME =
      UUID.fromString("e1e1e1e1-e1e1-e1e1-e1e1-e1e1e1e1e1e1");
  private static final UUID OUTLET_NO_REF = UUID.fromString("f2f2f2f2-f2f2-f2f2-f2f2-f2f2f2f2f2f2");
  private static final Instant OCCURRED = Instant.parse("2026-07-15T10:00:00Z");
  private static final String PERIOD = "2026-07";
  private static final String ACTOR = "test-reader";

  @Autowired private RevenuePostingService revenuePostingService;
  @Autowired private OutletRevenueReader outletRevenueReader;

  @Test
  void returnsOutletNameWhenRefRowExists() throws Exception {
    // Post a sale for OUTLET_WITH_NAME.
    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT, OUTLET_WITH_NAME, Money.ofMinor(120_000L, "IDR"), OCCURRED));

    // Insert an org_unit_ref row for OUTLET_WITH_NAME (simulating the consumer having processed
    // OrgUnitCreated for this outlet; in production this comes via the Kafka listener).
    insertOrgUnitRef(OUTLET_WITH_NAME, TENANT, "outlet", "Grand Ballroom Outlet", true);

    Optional<OutletRevenueResponse> response =
        TenantContext.callAs(TENANT, ACTOR, () -> outletRevenueReader.outletsForPeriod(PERIOD));

    assertThat(response).isPresent();
    List<OutletRevenueResponse.OutletRow> rows = response.get().outlets();
    OutletRevenueResponse.OutletRow namedRow =
        rows.stream()
            .filter(r -> r.businessId().equals(OUTLET_WITH_NAME))
            .findFirst()
            .orElseThrow(() -> new AssertionError("OUTLET_WITH_NAME not found in response"));
    assertThat(namedRow.outletName())
        .as("outletName must be populated from org_unit_ref when a ref row exists")
        .isEqualTo("Grand Ballroom Outlet");
  }

  @Test
  void returnsNullOutletNameWhenNoRefRowExists() throws Exception {
    // Post a sale for OUTLET_NO_REF — deliberately do NOT insert an org_unit_ref row.
    revenuePostingService.handle(
        new SaleRecordedEvent(
            UUID.randomUUID(), TENANT, OUTLET_NO_REF, Money.ofMinor(80_000L, "IDR"), OCCURRED));

    Optional<OutletRevenueResponse> response =
        TenantContext.callAs(TENANT, ACTOR, () -> outletRevenueReader.outletsForPeriod(PERIOD));

    assertThat(response).isPresent();
    OutletRevenueResponse.OutletRow unnamed =
        response.get().outlets().stream()
            .filter(r -> r.businessId().equals(OUTLET_NO_REF))
            .findFirst()
            .orElseThrow(() -> new AssertionError("OUTLET_NO_REF not found in response"));
    assertThat(unnamed.outletName())
        .as(
            "outletName must be null when org_unit_ref row does not exist yet (not yet hydrated;"
                + " the read never blocks on hydration — rule 2)")
        .isNull();
  }

  // ------------------------------------------------------------------ helper

  /**
   * Directly inserts an {@code org_unit_ref} row via the admin (BYPASSRLS) connection, simulating
   * what the org-unit event consumer would have written. Uses the admin connection so it bypasses
   * the session GUC requirement and can write to any tenant's rows.
   */
  private void insertOrgUnitRef(
      UUID orgUnitId, String companyId, String type, String name, boolean active) throws Exception {
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO org_unit_ref"
                    + " (org_unit_id, type, parent_id, name, active,"
                    + "  created_at, created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, NULL, ?, ?, NOW(), 'test', NOW(), 'test', 0, ?)"
                    + " ON CONFLICT (org_unit_id) DO NOTHING")) {
      ps.setObject(1, orgUnitId);
      ps.setString(2, type);
      ps.setString(3, name);
      ps.setBoolean(4, active);
      ps.setString(5, companyId);
      ps.executeUpdate();
    }
  }
}
