package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.carwash.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.carwash.catalog.service.CatalogService;
import id.co.nativeapp.carwash.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.carwash.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.carwash.payment.domain.TenderType;
import id.co.nativeapp.carwash.ticket.domain.ItemType;
import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import id.co.nativeapp.carwash.ticket.dto.CheckoutResult;
import id.co.nativeapp.carwash.ticket.dto.PaymentRequest;
import id.co.nativeapp.carwash.ticket.dto.TicketLineInput;
import id.co.nativeapp.carwash.ticket.dto.TicketResponse;
import id.co.nativeapp.carwash.ticket.messaging.TicketSaleRecordedSchema;
import id.co.nativeapp.carwash.ticket.service.TicketService;
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
 * Acceptance tests for the carwash POS ticket checkout (ADR 0023) — the money path.
 *
 * <ol>
 *   <li>CASH checkout: breakdown math reconciles against the V5 seed (11% VAT on subtotal −
 *       discount, zero service charge), the ticket + lines + payment persist, {@code sale_id} is
 *       set, exactly ONE {@code SaleRecorded} with the FULL breakdown + tender CASH + {@code
 *       uses_illustrative_rules=true}, and {@code wash_count} + {@code upsell_amount} + (linked
 *       washer) {@code sales_amount} metrics are emitted — all atomically.
 *   <li>An unlinked washer profile produces NO {@code sales_amount} metric.
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
  private static final String CARWASH = "carwash";

  @Autowired private TicketService ticketService;
  @Autowired private CatalogService catalogService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void cashCheckoutRecognisesRevenueAtomicallyWithMetricsAndIsIdempotentOnRetry() throws Exception {
    grantCarwash(TENANT_A);

    CatalogItemResponse pkg = createPackage("Basic Wash", 50_000_00L);
    CatalogItemResponse addon = createAddon("Wax", 20_000_00L);
    StaffProfileResponse washer = createStaffProfile("Budi", UUID.randomUUID());

    String idempotencyKey = "ticket-req-1";
    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            idempotencyKey,
            "bay-1",
            "B 1234 XYZ",
            washer.id(),
            null,
            List.of(
                new TicketLineInput(ItemType.PACKAGE, pkg.id(), 1),
                new TicketLineInput(ItemType.ADDON, addon.id(), 1)),
            new PaymentRequest(TenderType.CASH, 100_000_00L));

    CheckoutResult first =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.checkout(request));
    assertThat(first.created()).isTrue();
    TicketResponse ticket = first.ticket();

    // subtotal = 50_000_00 + 20_000_00 = 70_000_00; VAT 11% (V5 seed) -> 7_700_00; total =
    // 77_700_00.
    assertThat(ticket.breakdown().subtotalMinor()).isEqualTo(70_000_00L);
    assertThat(ticket.breakdown().discountMinor()).isEqualTo(0L);
    assertThat(ticket.breakdown().serviceChargeMinor()).isEqualTo(0L);
    assertThat(ticket.breakdown().taxMinor()).isEqualTo(7_700_00L);
    assertThat(ticket.breakdown().grandTotalMinor()).isEqualTo(77_700_00L);
    assertThat(ticket.breakdown().usesIllustrativeRules()).isTrue();
    assertThat(ticket.saleId()).isEqualTo(ticket.ticketId());
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

    // wash_count(1)@outlet + upsell_amount(addon total)@outlet + sales_amount(grand
    // total)@employee.
    List<Map<String, Object>> metricRows = outboxRows("MetricPublished");
    assertThat(metricRows).hasSize(3);
    Map<String, Long> byKey = decodeMetricValuesByKey(metricRows);
    assertThat(byKey)
        .containsEntry("wash_count", 1L)
        .containsEntry("upsell_amount", 20_000_00L)
        .containsEntry("sales_amount", 77_700_00L);

    // Idempotent retry: same ticket, no second write/event.
    CheckoutResult retry =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.checkout(request));
    assertThat(retry.created()).isFalse();
    assertThat(retry.ticket().ticketId()).isEqualTo(ticket.ticketId());
    assertThat(outboxRows("SaleRecorded")).hasSize(1);
    assertThat(outboxRows("MetricPublished")).hasSize(3);
    assertThat(ticketRowCountAsAdmin()).isEqualTo(1L);
  }

  @Test
  void anUnlinkedWasherProducesNoSalesAmountMetric() throws Exception {
    grantCarwash(TENANT_A);
    CatalogItemResponse pkg = createPackage("Basic Wash", 30_000_00L);
    // A profile with NO employee link.
    StaffProfileResponse unlinked = createStaffProfile("Bay 2 crew", null);

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "ticket-unlinked-1",
            "bay-2",
            null,
            unlinked.id(),
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, pkg.id(), 1)),
            new PaymentRequest(TenderType.CASH, 100_000_00L));

    TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.checkout(request));

    List<Map<String, Object>> metricRows = outboxRows("MetricPublished");
    Map<String, Long> byKey = decodeMetricValuesByKey(metricRows);
    assertThat(byKey).containsKeys("wash_count", "upsell_amount").doesNotContainKey("sales_amount");
  }

  @Test
  void digitalCheckoutPendsThenCaptureRecordsRevenueAndIsIdempotent() throws Exception {
    grantCarwash(TENANT_A);
    CatalogItemResponse pkg = createPackage("Basic Wash", 40_000_00L);

    CheckoutRequest request =
        new CheckoutRequest(
            OUTLET,
            "ticket-digital-1",
            "bay-3",
            null,
            null,
            null,
            List.of(new TicketLineInput(ItemType.PACKAGE, pkg.id(), 1)),
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
    assertThat(metricRows).hasSize(2); // wash_count + upsell_amount (no addon, no washer link)

    // Capture retry -> 200, no side effects, no second event.
    TicketResponse capturedRetry =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> ticketService.capture(ticket.ticketId()));
    assertThat(capturedRetry.payment().status()).isEqualTo("CAPTURED");
    assertThat(outboxRows("SaleRecorded")).hasSize(1);
    assertThat(outboxRows("MetricPublished")).hasSize(2);
  }

  // -------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------

  private void grantCarwash(String companyId) {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), companyId, CARWASH, true));
  }

  private CatalogItemResponse createPackage(String name, long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            catalogService.createPackage(
                new CatalogItemCreateRequest(OUTLET, name, null, priceMinor, "IDR")));
  }

  private CatalogItemResponse createAddon(String name, long priceMinor) throws Exception {
    return TenantContext.callAs(
        TENANT_A,
        ACTOR_A,
        () ->
            catalogService.createAddon(
                new CatalogItemCreateRequest(OUTLET, name, null, priceMinor, "IDR")));
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
                    id.co.nativeapp.carwash.metric.messaging.MetricPublishedSchema.schema()))
        .collect(Collectors.toMap(r -> r.get("metric_key").toString(), r -> (Long) r.get("value")));
  }

  private long ticketRowCountAsAdmin() throws Exception {
    try (java.sql.Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        java.sql.Statement st = admin.createStatement();
        java.sql.ResultSet rs = st.executeQuery("SELECT count(*) FROM carwash_ticket")) {
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
