package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
import id.co.nativeapp.barbershop.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.barbershop.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import id.co.nativeapp.barbershop.ticket.domain.ItemType;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutRequest;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutResult;
import id.co.nativeapp.barbershop.ticket.dto.PaymentRequest;
import id.co.nativeapp.barbershop.ticket.dto.TicketLineInput;
import id.co.nativeapp.barbershop.ticket.dto.TicketResponse;
import id.co.nativeapp.barbershop.ticket.service.TicketService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Mandatory migration + audit round-trip proof (ENGINEERING-STANDARDS §3.2). The full context boots
 * with {@code ddl-auto=validate}, so Flyway applies the baseline and Hibernate validates every
 * mapping against the migrated schema (catching drift like {@code CHAR(3)} vs {@code VARCHAR} on
 * the money column). Ported from carwash-service's {@code MigrationAndAuditRoundTripTest}, adapted
 * for ADR 0024: barbershop-service has no legacy {@code wash} aggregate, so this proof runs through
 * the ONLY revenue path — a checked-out ticket — instead.
 *
 * <p>A checked-out ticket round-trips with its {@code company_id} and {@code created_by} populated
 * from the {@link TenantContext} scope (rule 4), read over the admin connection (the table is
 * FORCE RLS).
 */
@SpringBootTest
class MigrationAndAuditRoundTripTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @Test
  void aCheckedOutTicketRoundTripsWithCompanyIdAndCreatedByFromTheScope() throws Exception {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT_A, "barbershop", true));

    CatalogItemResponse service =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createService(
                    new CatalogItemCreateRequest(
                        OUTLET, "Haircut", null, 50_000_00L, "IDR", null)));
    StaffProfileResponse barber =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createStaffProfile(
                    new StaffProfileCreateRequest(OUTLET, "Budi", null, true)));

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "audit-key",
            "chair-1",
            barber.id(),
            null,
            List.of(new TicketLineInput(ItemType.SERVICE, service.id(), 1)),
            new PaymentRequest(TenderType.CASH, 100_000_00L));

    CheckoutResult result =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.checkout(request));
    UUID ticketId = result.ticket().ticketId();

    Map<String, Object> row = ticketRowAsAdmin(ticketId);
    assertThat(row.get("company_id")).isEqualTo(TENANT_A);
    assertThat(row.get("created_by")).isEqualTo(ACTOR_A);
    assertThat(row.get("updated_by")).isEqualTo(ACTOR_A);
    // subtotal 50,000.00 IDR + VAT 11% (V1 seed) = 55,500.00 IDR.
    assertThat(row.get("total_minor")).isEqualTo(55_500_00L);
    assertThat(((String) row.get("currency")).strip()).isEqualTo("IDR");

    // The ticket reads back via the native projection inside the scope (RLS-scoped).
    TicketResponse reloaded =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.getById(ticketId));
    assertThat(reloaded.businessId()).isEqualTo(OUTLET);
    assertThat(reloaded.breakdown().grandTotalMinor()).isEqualTo(55_500_00L);
    assertThat(reloaded.staffProfileId()).isEqualTo(barber.id());
  }

  private Map<String, Object> ticketRowAsAdmin(UUID ticketId) throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement(
                "SELECT company_id, created_by, updated_by, total_minor, currency FROM"
                    + " barbershop_ticket WHERE id = ?")) {
      ps.setObject(1, ticketId);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return Map.of(
            "company_id", rs.getString("company_id"),
            "created_by", rs.getString("created_by"),
            "updated_by", rs.getString("updated_by"),
            "total_minor", rs.getLong("total_minor"),
            "currency", rs.getString("currency"));
      }
    }
  }
}
