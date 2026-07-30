package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import id.co.nativeapp.barbershop.ticket.dto.PaymentRequest;
import id.co.nativeapp.barbershop.ticket.dto.TicketLineInput;
import id.co.nativeapp.barbershop.ticket.service.TicketPostOutboxHook;
import id.co.nativeapp.barbershop.ticket.service.TicketService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Acceptance — atomicity (rule 3), mirroring carwash-service's {@code TicketCheckoutAtomicityTest}.
 * A failure AFTER the outbox writes but still inside the ticket-checkout transaction rolls back the
 * ticket, its lines, its payment, AND the outbox rows ({@code SaleRecorded} + {@code
 * MetricPublished}) together.
 */
@SpringBootTest
class TicketCheckoutAtomicityTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  /**
   * Installs a {@link TicketPostOutboxHook} that throws. A DISTINCT bean name + {@link Primary}
   * makes it win injection into {@link id.co.nativeapp.barbershop.ticket.service.TicketWriter
   * TicketWriter} without clashing with the production {@code @ConditionalOnMissingBean} default —
   * the exact carwash-service pattern.
   */
  @TestConfiguration
  static class ThrowingHookConfig {
    @Bean
    @Primary
    TicketPostOutboxHook throwingTicketPostOutboxHook() {
      return ticket -> {
        throw new IllegalStateException("forced failure after outbox write (atomicity test)");
      };
    }
  }

  @Test
  void aFailureAfterTheOutboxWriteRollsBackTheTicketLinesPaymentAndOutbox() throws Exception {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT_A, "barbershop", true));

    CatalogItemResponse service =
        TenantContext.callAs(
            TENANT_A,
            ACTOR_A,
            () ->
                catalogService.createService(
                    new CatalogItemCreateRequest(
                        OUTLET, "Haircut", null, 30_000_00L, "IDR", null)));
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
            "ticket-atomic-1",
            "chair-1",
            barber.id(),
            null,
            List.of(new TicketLineInput(ItemType.SERVICE, service.id(), 1)),
            new PaymentRequest(TenderType.CASH, 50_000_00L));

    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.checkout(request)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("forced failure");

    // The ticket, its line, its payment, and EVERY outbox row rolled back together.
    assertThat(countAsAdmin("barbershop_ticket")).isZero();
    assertThat(countAsAdmin("barbershop_ticket_line")).isZero();
    assertThat(countAsAdmin("barbershop_payment")).isZero();
    assertThat(countAsAdmin("outbox", "SaleRecorded")).isZero();
    assertThat(countAsAdmin("outbox", "MetricPublished")).isZero();
  }

  private long countAsAdmin(String table) throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private long countAsAdmin(String table, String eventType) throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM " + table + " WHERE event_type = ?")) {
      ps.setString(1, eventType);
      try (java.sql.ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }
}
