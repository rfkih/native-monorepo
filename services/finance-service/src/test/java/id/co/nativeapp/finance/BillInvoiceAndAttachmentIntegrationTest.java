package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.ap.domain.BillNotFoundException;
import id.co.nativeapp.finance.ap.domain.DuplicateVendorInvoiceException;
import id.co.nativeapp.finance.ap.domain.InvalidBillAttachmentException;
import id.co.nativeapp.finance.ap.dto.BillAttachmentMetaResponse;
import id.co.nativeapp.finance.ap.dto.BillDetailResponse;
import id.co.nativeapp.finance.ap.dto.BillSummaryResponse;
import id.co.nativeapp.finance.ap.dto.VendorResponse;
import id.co.nativeapp.finance.ap.service.BillAttachmentReader;
import id.co.nativeapp.finance.ap.service.BillAttachmentWriter;
import id.co.nativeapp.finance.ap.service.BillDraftInput;
import id.co.nativeapp.finance.ap.service.BillLineInput;
import id.co.nativeapp.finance.ap.service.BillReader;
import id.co.nativeapp.finance.ap.service.BillWriter;
import id.co.nativeapp.finance.ap.service.VendorWriter;
import id.co.nativeapp.finance.companyexpense.messaging.InventoryPurchaseRecordedSchema;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0084 against real Postgres: the invoice as the vendor wrote it (number, date, terms) rides
 * the draft and decides the posted dates; the header discount taxes the net, splits pro-rata across
 * expense and inventory lines so the GL still balances, and the purchase event carries the net line
 * value; the same vendor's invoice number is refused on a live bill (typed) but free again after a
 * void; attachments store, list, serve and are tenant-isolated.
 */
@SpringBootTest
class BillInvoiceAndAttachmentIntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-000084000001";
  private static final String OTHER_TENANT = "11111111-1111-1111-1111-000084000002";
  private static final String ACTOR = "ap-adr0084@test";
  private static final byte[] PDF = "%PDF-1.4\n%faktur\n".getBytes();

  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;
  @Autowired private BillReader billReader;
  @Autowired private BillAttachmentWriter attachmentWriter;
  @Autowired private BillAttachmentReader attachmentReader;

  @Test
  void invoiceFieldsDiscountAndRateDriveTheDraftThePostAndTheGl() throws Exception {
    UUID ingredientId = UUID.randomUUID();
    UUID billId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              VendorResponse vendor = vendorWriter.create("CV Sumber Pangan", null, "01.234", 14);
              assertThat(vendor.paymentTermDays()).isEqualTo(14);
              // 600,000 expense + 400,000 inventory (ingredient-linked) = 1,000,000 subtotal;
              // 100,000 discount → net 900,000 → PPN 12 % = 108,000 → total 1,008,000.
              return billWriter.createDraft(
                  new BillDraftInput(
                      vendor.id(),
                      "IDR",
                      1_200,
                      100_000L,
                      "INV/2026/09/2214",
                      LocalDate.parse("2026-09-11"),
                      null,
                      "barang diterima kurang 1 dus",
                      List.of(
                          new BillLineInput("Sewa freezer", 1, 600_000L, false),
                          new BillLineInput(
                              "Ayam broiler", 1, 400_000L, true, ingredientId, "Ayam", 24_000L))));
            });

    BillDetailResponse draft = TenantContext.callAs(TENANT, ACTOR, () -> billReader.detail(billId));
    assertThat(draft.subtotalMinor()).isEqualTo(1_000_000L);
    assertThat(draft.discountMinor()).isEqualTo(100_000L);
    assertThat(draft.taxBp()).isEqualTo(1_200);
    assertThat(draft.taxMinor()).as("12 % of the NET").isEqualTo(108_000L);
    assertThat(draft.totalMinor()).isEqualTo(1_008_000L);
    assertThat(draft.vendorInvoiceNumber()).isEqualTo("INV/2026/09/2214");
    assertThat(draft.billDate())
        .as("the invoice date is on the draft")
        .isEqualTo(LocalDate.parse("2026-09-11"));
    assertThat(draft.termDays()).isNull();
    assertThat(draft.note()).isEqualTo("barang diterima kurang 1 dus");

    // Post with the vendor's 14-day term: due = invoice date + 14, bill date kept.
    TenantContext.callAs(TENANT, ACTOR, () -> billWriter.post(billId, 14));
    BillDetailResponse posted =
        TenantContext.callAs(TENANT, ACTOR, () -> billReader.detail(billId));
    assertThat(posted.billDate()).isEqualTo(LocalDate.parse("2026-09-11"));
    assertThat(posted.dueDate()).isEqualTo(LocalDate.parse("2026-09-25"));

    List<BillSummaryResponse> list =
        TenantContext.callAs(TENANT, ACTOR, () -> billReader.list(null, null, null));
    assertThat(list)
        .singleElement()
        .satisfies(s -> assertThat(s.vendorInvoiceNumber()).isEqualTo("INV/2026/09/2214"));

    // GL: the discount spreads 60/40 → expense 540,000 / inventory (COGS, periodic) 360,000,
    // tax 108,000, AP 1,008,000 — balanced, no suspense.
    Map<String, Long> debit = accountAmountsAsAdmin(billId, "debit_minor");
    Map<String, Long> credit = accountAmountsAsAdmin(billId, "credit_minor");
    assertThat(debit)
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("5000", 540_000L, "5100", 360_000L, "1300", 108_000L));
    assertThat(credit).containsExactlyInAnyOrderEntriesOf(Map.of("2000", 1_008_000L));

    // The purchase event carries the ingredient line NET of its discount share.
    List<GenericRecord> events = decodeOutboxAsAdmin(TENANT);
    assertThat(events).hasSize(1);
    @SuppressWarnings("unchecked")
    List<GenericRecord> lines = (List<GenericRecord>) events.get(0).get("lines");
    assertThat(lines).hasSize(1);
    assertThat(lines.get(0).get("value_minor")).isEqualTo(360_000L);
  }

  @Test
  void theSameVendorsInvoiceNumberIsRefusedWhileLiveAndFreeAgainAfterAVoid() throws Exception {
    UUID vendorId =
        TenantContext.callAs(TENANT, ACTOR, () -> vendorWriter.create("PT Aneka", null, null).id());
    UUID first =
        TenantContext.callAs(
            TENANT, ACTOR, () -> billWriter.createDraft(draft(vendorId, "abn-9921", 50_000L)));

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () -> billWriter.createDraft(draft(vendorId, "ABN-9921", 70_000L))))
        .as("case-insensitive, on a DRAFT too")
        .isInstanceOf(DuplicateVendorInvoiceException.class);

    // Another vendor may carry the same number.
    UUID otherVendor =
        TenantContext.callAs(TENANT, ACTOR, () -> vendorWriter.create("UD Tirta", null, null).id());
    TenantContext.callAs(
        TENANT, ACTOR, () -> billWriter.createDraft(draft(otherVendor, "ABN-9921", 1L)));

    // Void the first: the number is free again for that vendor.
    TenantContext.callAs(TENANT, ACTOR, () -> billWriter.voidBill(first));
    UUID again =
        TenantContext.callAs(
            TENANT, ACTOR, () -> billWriter.createDraft(draft(vendorId, "ABN-9921", 90_000L)));
    assertThat(again).isNotEqualTo(first);
  }

  @Test
  void attachmentsStoreListServeDedupeAndStayInsideTheTenant() throws Exception {
    UUID billId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              UUID v = vendorWriter.create("PT Gas", null, null).id();
              return billWriter.createDraft(draft(v, "GAS-1", 10_000L));
            });

    BillAttachmentMetaResponse meta =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> attachmentWriter.upload(billId, "application/pdf", PDF, "../faktur gas.pdf"));
    assertThat(meta.contentType()).isEqualTo("application/pdf");
    assertThat(meta.byteSize()).isEqualTo(PDF.length);
    assertThat(meta.originalFilename()).as("path stripped").isEqualTo("faktur gas.pdf");

    // Byte-identical re-upload → the same row, no duplicate.
    BillAttachmentMetaResponse again =
        TenantContext.callAs(
            TENANT, ACTOR, () -> attachmentWriter.upload(billId, null, PDF, "faktur.pdf"));
    assertThat(again.id()).isEqualTo(meta.id());
    // A browser that does not know the file's type declares octet-stream — undeclared, not a lie.
    BillAttachmentMetaResponse octet =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> attachmentWriter.upload(billId, "application/octet-stream", PDF, "faktur.pdf"));
    assertThat(octet.id()).isEqualTo(meta.id());
    assertThat(TenantContext.callAs(TENANT, ACTOR, () -> attachmentReader.list(billId))).hasSize(1);

    // Served bytes are the bytes.
    var content =
        TenantContext.callAs(TENANT, ACTOR, () -> attachmentReader.contentMeta(billId, meta.id()));
    assertThat(attachmentReader.payload(content.objectKey())).isEqualTo(PDF);
    assertThat(content.objectKey()).startsWith("finance/" + TENANT + "/bill/");

    // Garbage is refused before the DB is touched.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () ->
                        attachmentWriter.upload(billId, "text/plain", "hello".getBytes(), "x.txt")))
        .isInstanceOf(InvalidBillAttachmentException.class);

    // Another tenant sees nothing of it — the list, the serve and the delete alike.
    assertThat(TenantContext.callAs(OTHER_TENANT, ACTOR, () -> attachmentReader.list(billId)))
        .isEmpty();
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    OTHER_TENANT, ACTOR, () -> attachmentReader.contentMeta(billId, meta.id())))
        .isInstanceOf(BillNotFoundException.class);
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    OTHER_TENANT,
                    ACTOR,
                    () -> {
                      attachmentWriter.delete(billId, meta.id());
                      return null;
                    }))
        .isInstanceOf(BillNotFoundException.class);
    // Nor can a sibling bill's URL serve it: /ap/bills/{other}/attachments/{id} is a 404.
    UUID siblingBill =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              UUID v = vendorWriter.create("PT Lain", null, null).id();
              return billWriter.createDraft(draft(v, "LAIN-1", 5_000L));
            });
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT, ACTOR, () -> attachmentReader.contentMeta(siblingBill, meta.id())))
        .isInstanceOf(BillNotFoundException.class);
    assertThat(TenantContext.callAs(TENANT, ACTOR, () -> attachmentReader.list(billId))).hasSize(1);

    // Delete removes the row only.
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> {
          attachmentWriter.delete(billId, meta.id());
          return null;
        });
    assertThat(TenantContext.callAs(TENANT, ACTOR, () -> attachmentReader.list(billId))).isEmpty();
  }

  // ---------------------------------------------------------------- helpers

  private static BillDraftInput draft(UUID vendorId, String number, long priceMinor) {
    return new BillDraftInput(
        vendorId,
        "IDR",
        0,
        0L,
        number,
        null,
        null,
        null,
        List.of(new BillLineInput("Item", 1, priceMinor, false)));
  }

  private Map<String, Long> accountAmountsAsAdmin(UUID billId, String amountColumn)
      throws Exception {
    Map<String, Long> byAccount = new LinkedHashMap<>();
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT jl.account_code, jl."
                    + amountColumn
                    + " FROM journal_line jl JOIN journal_entry je ON je.id = jl.entry_id"
                    + " WHERE je.source_event_id = ? AND jl."
                    + amountColumn
                    + " > 0")) {
      ps.setObject(1, billId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          byAccount.put(rs.getString(1), rs.getLong(2));
        }
      }
    }
    return byAccount;
  }

  private List<GenericRecord> decodeOutboxAsAdmin(String tenant) throws Exception {
    List<GenericRecord> events = new ArrayList<>();
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

  private Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
