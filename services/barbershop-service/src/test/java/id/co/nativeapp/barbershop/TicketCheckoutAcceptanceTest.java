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
import id.co.nativeapp.barbershop.ticket.messaging.TicketSaleRecordedSchema;
import id.co.nativeapp.barbershop.ticket.service.TicketService;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Acceptance tests for the barbershop POS ticket checkout (ADR 0024) — the money path. Ported from
 * carwash-service's {@code TicketCheckoutAcceptanceTest} (ADR 0023).
 *
 * <ol>
 *   <li>CASH checkout: breakdown math reconciles against the V1 seed (11% VAT on subtotal −
 *       discount, zero service charge), the ticket + lines + payment persist, {@code sale_id} is
 *       set, exactly ONE {@code SaleRecorded} with the FULL breakdown + tender CASH + {@code
 *       uses_illustrative_rules=true}, and {@code service_count} + (linked barber) {@code
 *       sales_amount} metrics are emitted — all atomically. barberEmployeeId is MANDATORY at
 *       checkout (ADR 0024) — every ticket here selects a staff profile.
 *   <li>An unlinked barber profile produces NO {@code sales_amount} metric (the LINK stays optional
 *       even though the profile SELECTION is mandatory).
 *   <li>Digital (QRIS) checkout persists a PENDING payment with {@code sale_id} NULL and ZERO
 *       outbox rows; {@code capture} then records the sale + metrics; a capture retry is idempotent
 *       (200, no second event).
 *   <li>A checkout retry with the same {@code idempotencyKey} is idempotent ({@code created=false},
 *       no second event, no second row).
 * </ol>
 */
@SpringBootTest
class TicketCheckoutAcceptanceTest extends KafkaPostgresRedisTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "attendant-a@example.co.id";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String BARBERSHOP = "barbershop";

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void cashCheckoutRecognisesRevenueAtomicallyWithMetricsAndIsIdempotentOnRetry() throws Exception {
    grantBarbershop(TENANT_A);

    CatalogItemResponse service = createService("Haircut", 50_000_00L);
    CatalogItemResponse addon = createAddon("Hot Towel", 20_000_00L);
    StaffProfileResponse barber = createStaffProfile("Budi", UUID.randomUUID());

    String idempotencyKey = "ticket-req-1";
    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            idempotencyKey,
            "chair-1",
            barber.id(),
            null,
            List.of(
                new TicketLineInput(ItemType.SERVICE, service.id(), 1),
                new TicketLineInput(ItemType.ADDON, addon.id(), 1)),
            new PaymentRequest(TenderType.CASH, 100_000_00L));

    CheckoutResult first =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.checkout(request));
    assertThat(first.created()).isTrue();
    TicketResponse ticket = first.ticket();

    // subtotal = 50_000_00 + 20_000_00 = 70_000_00; VAT 11% (V1 seed) -> 7_700_00; total =
    // 77_700_00.
    assertThat(ticket.breakdown().subtotalMinor()).isEqualTo(70_000_00L);
    assertThat(ticket.breakdown().discountMinor()).isEqualTo(0L);
    assertThat(ticket.breakdown().serviceChargeMinor()).isEqualTo(0L);
    assertThat(ticket.breakdown().taxMinor()).isEqualTo(7_700_00L);
    assertThat(ticket.breakdown().grandTotalMinor()).isEqualTo(77_700_00L);
    assertThat(ticket.breakdown().usesIllustrativeRules()).isTrue();
    assertThat(ticket.saleId()).isEqualTo(ticket.ticketId());
    assertThat(ticket.staffProfileId()).isEqualTo(barber.id());
    assertThat(ticket.staffLabel()).isEqualTo("Budi");
    assertThat(ticket.lines()).hasSize(2);
    assertThat(ticket.payment()).isNotNull();
    assertThat(ticket.payment().status()).isEqualTo("CAPTURED");
    assertThat(ticket.payment().tenderType()).isEqualTo("CASH");
    assertThat(ticket.payment().changeMinor()).isEqualTo(100_000_00L - 77_700_00L);
    assertThat(ticket.payment().saleId()).isEqualTo(ticket.ticketId());

    // Exactly ONE SaleRecorded, full breakdown, tender CASH.
    List<Map<String, Object>> saleRows = outboxRows("SaleRecorded");
    assertThat(saleRows).hasSize(1);
    assertThat(saleRows.getFirst().get("aggregate_id")).isEqualTo(ticket.ticketId().toString());
    GenericRecord sale =
        AvroSerde.deserialize(
            (byte[]) saleRows.getFirst().get("payload"), TicketSaleRecordedSchema.schema());
    assertThat(sale.get("sale_id").toString()).isEqualTo(ticket.ticketId().toString());
    assertThat(sale.get("business_id").toString()).isEqualTo(OUTLET.toString());
    assertThat(sale.get("amount_minor")).isEqualTo(77_700_00L);
    assertThat(sale.get("currency").toString()).isEqualTo("IDR");
    assertThat(sale.get("tender_type").toString()).isEqualTo("CASH");
    assertThat(sale.get("subtotal_minor")).isEqualTo(70_000_00L);
    assertThat(sale.get("tax_minor")).isEqualTo(7_700_00L);
    assertThat(sale.get("uses_illustrative_rules")).isEqualTo(true);

    // service_count(1)@outlet + sales_amount(grand total)@employee — NO upsell_amount analog
    // (deliberate difference from carwash, ADR 0024).
    List<Map<String, Object>> metricRows = outboxRows("MetricPublished");
    assertThat(metricRows).hasSize(2);
    Map<String, Long> byKey = decodeMetricValuesByKey(metricRows);
    assertThat(byKey).containsEntry("service_count", 1L).containsEntry("sales_amount", 77_700_00L);

    // Idempotent retry: same ticket, no second write/event.
    CheckoutResult retry =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.checkout(request));
    assertThat(retry.created()).isFalse();
    assertThat(retry.ticket().ticketId()).isEqualTo(ticket.ticketId());
    assertThat(outboxRows("SaleRecorded")).hasSize(1);
    assertThat(outboxRows("MetricPublished")).hasSize(2);
    assertThat(ticketRowCountAsAdmin()).isEqualTo(1L);
  }

  @Test
  void anUnlinkedBarberProducesNoSalesAmountMetric() throws Exception {
    grantBarbershop(TENANT_A);
    CatalogItemResponse service = createService("Haircut", 30_000_00L);
    // A profile with NO employee link — but still MANDATORY at checkout (ADR 0024).
    StaffProfileResponse unlinked = createStaffProfile("Chair 2 crew", null);

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "ticket-unlinked-1",
            "chair-2",
            unlinked.id(),
            null,
            List.of(new TicketLineInput(ItemType.SERVICE, service.id(), 1)),
            new PaymentRequest(TenderType.CASH, 100_000_00L));

    TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.checkout(request));

    List<Map<String, Object>> metricRows = outboxRows("MetricPublished");
    Map<String, Long> byKey = decodeMetricValuesByKey(metricRows);
    assertThat(byKey).containsKey("service_count").doesNotContainKey("sales_amount");
  }

  @Test
  void digitalCheckoutPendsThenCaptureRecordsRevenueAndIsIdempotent() throws Exception {
    grantBarbershop(TENANT_A);
    CatalogItemResponse service = createService("Haircut", 40_000_00L);
    StaffProfileResponse barber = createStaffProfile("Chair 3 crew", null);

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "ticket-digital-1",
            "chair-3",
            barber.id(),
            null,
            List.of(new TicketLineInput(ItemType.SERVICE, service.id(), 1)),
            new PaymentRequest(TenderType.QRIS, null));

    CheckoutResult result =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.checkout(request));
    assertThat(result.created()).isTrue();
    TicketResponse ticket = result.ticket();
    assertThat(ticket.saleId()).isNull();
    assertThat(ticket.payment().status()).isEqualTo("PENDING");
    assertThat(ticket.payment().providerPending()).isTrue();

    // Zero outbox rows — an authorized-but-uncaptured digital tender produces no revenue.
    assertThat(outboxRows("SaleRecorded")).isEmpty();
    assertThat(outboxRows("MetricPublished")).isEmpty();

    // Capture -> sale recorded + metrics.
    TicketResponse captured =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.capture(ticket.ticketId()));
    assertThat(captured.saleId()).isEqualTo(ticket.ticketId());
    assertThat(captured.payment().status()).isEqualTo("CAPTURED");
    assertThat(outboxRows("SaleRecorded")).hasSize(1);
    List<Map<String, Object>> metricRows = outboxRows("MetricPublished");
    assertThat(metricRows).hasSize(1); // service_count only (unlinked barber, no sales_amount)

    // Capture retry -> 200, no side effects, no second event.
    TicketResponse capturedRetry =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.capture(ticket.ticketId()));
    assertThat(capturedRetry.payment().status()).isEqualTo("CAPTURED");
    assertThat(outboxRows("SaleRecorded")).hasSize(1);
    assertThat(outboxRows("MetricPublished")).hasSize(1);
  }

  @Test
  void aZeroGrandTotalCheckoutIsRejectedAndRecordsNothing() throws Exception {
    // Mirrors carwash review S1: a fully-discounted / zero-priced cart must never emit a
    // zero-amount SaleRecorded (the finance journal needs a positive total). 400 via
    // IllegalArgumentException.
    grantBarbershop(TENANT_A);
    CatalogItemResponse freeService = createService("Promo Freebie", 0L);
    StaffProfileResponse barber = createStaffProfile("Chair 4 crew", null);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT_A,
                    ACTOR_A,
                    () ->
                        ticketService.checkout(
                            new CheckoutRequest(
                                OUTLET,
                                "zero-total-1",
                                "chair-1",
                                barber.id(),
                                null,
                                List.of(new TicketLineInput(ItemType.SERVICE, freeService.id(), 1)),
                                new PaymentRequest(TenderType.CASH, 0L)))))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(ticketRowCountAsAdmin()).isZero();
    assertThat(outboxRows("SaleRecorded")).isEmpty();
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  private void grantBarbershop(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, BARBERSHOP, true));
  }

  private CatalogItemResponse createService(String name, long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            catalogService.createService(
                new CatalogItemCreateRequest(OUTLET, name, null, priceMinor, "IDR", null)));
  }

  private CatalogItemResponse createAddon(String name, long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            catalogService.createAddon(
                new CatalogItemCreateRequest(OUTLET, name, null, priceMinor, "IDR", null)));
  }

  private StaffProfileResponse createStaffProfile(String label, UUID employeeId) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            catalogService.createStaffProfile(
                new StaffProfileCreateRequest(OUTLET, label, employeeId, true)));
  }

  private Map<String, Long> decodeMetricValuesByKey(List<Map<String, Object>> rows) {
    return rows.stream()
        .map(
            row ->
                AvroSerde.deserialize(
                    (byte[]) row.get("payload"),
                    id.co.nativeapp.barbershop.metric.messaging.MetricPublishedSchema.schema()))
        .collect(Collectors.toMap(r -> r.get("metric_key").toString(), r -> (Long) r.get("value")));
  }

  private long ticketRowCountAsAdmin() throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM barbershop_ticket")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private List<Map<String, Object>> outboxRows(String eventType) {
    return jdbcTemplate.queryForList(
        "SELECT event_type, aggregate_type, aggregate_id, company_id, payload "
            + "FROM outbox WHERE event_type = ? ORDER BY occurred_at",
        eventType);
  }
}
