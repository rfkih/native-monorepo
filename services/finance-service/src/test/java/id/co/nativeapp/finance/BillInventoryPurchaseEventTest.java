package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.ap.dto.VendorResponse;
import id.co.nativeapp.finance.ap.service.BillLineInput;
import id.co.nativeapp.finance.ap.service.BillWriter;
import id.co.nativeapp.finance.ap.service.VendorWriter;
import id.co.nativeapp.finance.companyexpense.messaging.InventoryPurchaseRecordedSchema;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR 0072 P4 — a posted bill with ingredient-linked inventory lines writes ONE {@code
 * InventoryPurchaseRecorded} outbox row in the SAME transaction as the GL posting (rule 3): line_id
 * = bill_line.id, value = the NET line total, source = BILL. A bill without ingredient linkage
 * emits nothing (even with a bare {@code is_inventory} flag); a void emits nothing; and a throw
 * after the post rolls the GL entry AND the event back together (the §3.2 atomicity proof for this
 * producer).
 */
@SpringBootTest
class BillInventoryPurchaseEventTest extends PostgresRlsTestBase {

  private static final String ACTOR = "ap-adr0072@event.test";
  static final String BOOM = "forced failure after the bill post (test harness)";

  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;
  @Autowired private BillPostAtomicityHarness harness;

  @Test
  void aPostedBillWithIngredientLinesEmitsOnePurchaseEvent() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID ingredientA = UUID.randomUUID();
    UUID ingredientB = UUID.randomUUID();

    UUID billId =
        TenantContext.callAs(
            tenant,
            ACTOR,
            () -> {
              VendorResponse vendor = vendorWriter.create("Pasar Induk", "pi@vendor.test", null);
              UUID id =
                  billWriter.createDraft(
                      vendor.id(),
                      "IDR",
                      true,
                      List.of(
                          new BillLineInput("Jasa antar", 1, 50_000L, false),
                          new BillLineInput(
                              "Ayam fillet 10kg",
                              1,
                              900_000L,
                              true,
                              ingredientA,
                              "Ayam fillet",
                              10_000L),
                          new BillLineInput(
                              "Cabai 2kg", 2, 60_000L, true, ingredientB, "Cabai merah", 2_000L)));
              billWriter.post(id, 30);
              return id;
            });

    List<GenericRecord> events = decodeOutboxAsAdmin(tenant);
    assertThat(events).hasSize(1);
    GenericRecord event = events.getFirst();
    assertThat(event.get("purchase_id").toString()).isEqualTo(billId.toString());
    assertThat(event.get("source").toString()).isEqualTo("BILL");
    assertThat(event.get("currency").toString()).isEqualTo("IDR");
    @SuppressWarnings("unchecked")
    List<GenericRecord> lines = (List<GenericRecord>) event.get("lines");
    assertThat(lines).as("only the ingredient-linked lines ride").hasSize(2);
    assertThat(lines.get(0).get("ingredient_id").toString()).isEqualTo(ingredientA.toString());
    assertThat(lines.get(0).get("qty_base")).isEqualTo(10_000L);
    assertThat(lines.get(0).get("value_minor")).as("NET line total").isEqualTo(900_000L);
    assertThat(lines.get(1).get("ingredient_id").toString()).isEqualTo(ingredientB.toString());
    assertThat(lines.get(1).get("qty_base")).isEqualTo(2_000L);
    assertThat(lines.get(1).get("value_minor")).isEqualTo(120_000L);
    assertThat(billLineIdsAsAdmin(billId))
        .as("line_id = bill_line.id (the goods_receipt idempotency anchor)")
        .contains(
            UUID.fromString(lines.get(0).get("line_id").toString()),
            UUID.fromString(lines.get(1).get("line_id").toString()));

    // A void posts the money contra but never a stock event.
    TenantContext.callAs(tenant, ACTOR, () -> billWriter.voidBill(billId));
    assertThat(decodeOutboxAsAdmin(tenant)).hasSize(1);
  }

  @Test
  void aBillWithoutIngredientLinkageEmitsNothing() throws Exception {
    String tenant = UUID.randomUUID().toString();
    TenantContext.callAs(
        tenant,
        ACTOR,
        () -> {
          VendorResponse vendor = vendorWriter.create("Toko ATK", "atk@vendor.test", null);
          UUID id =
              billWriter.createDraft(
                  vendor.id(),
                  "IDR",
                  false,
                  List.of(
                      new BillLineInput("Kertas", 10, 40_000L, false),
                      // Flagged inventory but NOT ingredient-linked: routes 5100 money-side, but
                      // there is nothing to receive — no event.
                      new BillLineInput("Bahan tanpa link", 1, 100_000L, true)));
          billWriter.post(id, 14);
          return id;
        });

    assertThat(decodeOutboxAsAdmin(tenant)).isEmpty();
  }

  @Test
  void aFailureAfterThePostRollsBackTheEntryAndTheEventTogether() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID draftId =
        TenantContext.callAs(
            tenant,
            ACTOR,
            () -> {
              VendorResponse vendor = vendorWriter.create("Atomik", "atomik@vendor.test", null);
              return billWriter.createDraft(
                  vendor.id(),
                  "IDR",
                  false,
                  List.of(
                      new BillLineInput(
                          "Beras 25kg", 1, 300_000L, true, UUID.randomUUID(), "Beras", 25_000L)));
            });

    assertThatThrownBy(
            () -> TenantContext.callAs(tenant, ACTOR, () -> harness.postThenBoom(draftId)))
        .hasMessageContaining(BOOM);

    assertThat(decodeOutboxAsAdmin(tenant)).isEmpty();
    assertThat(journalCountAsAdmin(tenant)).isZero();
    assertThat(billStatusAsAdmin(draftId)).as("the bill stays DRAFT").isEqualTo("DRAFT");
  }

  // ---------------------------------------------------------------- helpers

  private List<GenericRecord> decodeOutboxAsAdmin(String tenant) throws Exception {
    List<GenericRecord> events = new java.util.ArrayList<>();
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT payload FROM outbox WHERE event_type = 'InventoryPurchaseRecorded'"
                    + " AND company_id = ?::uuid ORDER BY occurred_at, id")) {
      ps.setString(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          events.add(
              AvroSerde.deserialize(
                  rs.getBytes("payload"), InventoryPurchaseRecordedSchema.schema()));
        }
      }
    }
    return events;
  }

  private List<UUID> billLineIdsAsAdmin(UUID billId) throws Exception {
    List<UUID> ids = new java.util.ArrayList<>();
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT id FROM bill_line WHERE bill_id = ?")) {
      ps.setObject(1, billId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getObject(1, UUID.class));
        }
      }
    }
    return ids;
  }

  private long journalCountAsAdmin(String tenant) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM journal_entry WHERE company_id = ?")) {
      ps.setString(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String billStatusAsAdmin(UUID billId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps = admin.prepareStatement("SELECT status FROM bill WHERE id = ?")) {
      ps.setObject(1, billId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getString(1);
      }
    }
  }

  private Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /** Joins {@code post}'s transaction, lets everything (GL + outbox) write, then throws. */
  static class BillPostAtomicityHarness {
    private final BillWriter billWriter;

    BillPostAtomicityHarness(BillWriter billWriter) {
      this.billWriter = billWriter;
    }

    @Transactional
    public Void postThenBoom(UUID billId) {
      billWriter.post(billId, 30);
      throw new IllegalStateException(BOOM);
    }
  }

  /** Distinct context configuration — the harness bean never leaks into other test classes. */
  @TestConfiguration
  static class HarnessConfig {
    @Bean
    BillPostAtomicityHarness billPostAtomicityHarness(BillWriter billWriter) {
      return new BillPostAtomicityHarness(billWriter);
    }
  }
}
