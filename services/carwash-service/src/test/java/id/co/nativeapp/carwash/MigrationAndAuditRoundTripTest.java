package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.wash.dto.RecordWashCommand;
import id.co.nativeapp.carwash.wash.dto.WashResponse;
import id.co.nativeapp.carwash.wash.service.WashService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Mandatory migration + audit round-trip proof (ENGINEERING-STANDARDS §3.2). The full context boots
 * with {@code ddl-auto=validate}, so Flyway applies the baseline and Hibernate validates every
 * mapping against the migrated schema (catching drift like {@code CHAR(3)} vs {@code VARCHAR} on
 * the money column). A recorded wash round-trips with its {@code company_id} and {@code created_by}
 * populated from the {@link TenantContext} scope (rule 4), read over the admin connection (the
 * table is FORCE RLS).
 */
@SpringBootTest
class MigrationAndAuditRoundTripTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private WashService washService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void aRecordedWashRoundTripsWithCompanyIdAndCreatedByFromTheScope() throws Exception {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT_A, "carwash", true));

    UUID washId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                washService
                    .recordWash(
                        new RecordWashCommand(
                            OUTLET,
                            "bay-1",
                            5_000_00L,
                            "IDR",
                            null,
                            null,
                            Instant.parse("2026-06-14T08:30:00Z"),
                            "audit-key"))
                    .wash()
                    .id());

    Map<String, Object> row = washRowAsAdmin(washId);
    assertThat(row.get("company_id")).isEqualTo(TENANT_A);
    assertThat(row.get("created_by")).isEqualTo(ACTOR_A);
    assertThat(row.get("updated_by")).isEqualTo(ACTOR_A);
    assertThat(row.get("amount_minor")).isEqualTo(5_000_00L);
    assertThat(((String) row.get("currency")).strip()).isEqualTo("IDR");

    // The wash reads back via the native projection inside the scope (RLS-scoped). The read path
    // returns a WashResponse (native-query projection), so entity fields are accessed via the
    // record accessors.
    WashResponse reloaded =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                washService.findAllForCurrentTenant().stream()
                    .filter(w -> w.id().equals(washId))
                    .findFirst()
                    .orElseThrow());
    assertThat(reloaded.businessId()).isEqualTo(OUTLET);
    assertThat(reloaded.amountMinor()).isEqualTo(5_000_00L);
  }

  private Map<String, Object> washRowAsAdmin(UUID washId) throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT company_id, created_by, updated_by, amount_minor, currency FROM wash WHERE"
                    + " id = ?")) {
      ps.setObject(1, washId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return Map.of(
            "company_id", rs.getString("company_id"),
            "created_by", rs.getString("created_by"),
            "updated_by", rs.getString("updated_by"),
            "amount_minor", rs.getLong("amount_minor"),
            "currency", rs.getString("currency"));
      }
    }
  }
}
