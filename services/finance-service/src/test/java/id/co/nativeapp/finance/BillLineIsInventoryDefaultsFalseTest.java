package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.finance.ap.dto.VendorResponse;
import id.co.nativeapp.finance.ap.service.BillLineInput;
import id.co.nativeapp.finance.ap.service.BillWriter;
import id.co.nativeapp.finance.ap.service.VendorWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * V54 (ADR 0067 Phase B §3) acceptance proof: the new {@code bill_line.is_inventory} column is a
 * pure additive expand — {@code NOT NULL DEFAULT FALSE} — that (a) applies cleanly on top of every
 * prior migration (a {@code @SpringBootTest} only starts if Flyway, including V54, migrates the
 * real Testcontainers schema without error) and (b) leaves an existing vendor-bill posting
 * byte-identical to before V54: {@link BillWriter} is untouched by this migration (no producer/
 * consumer/BillWriter Java lands until the next task), so a taxable bill still writes its {@code
 * bill_line} rows with {@code is_inventory = FALSE} (the column default — the writer never mentions
 * the column) and still posts EXACTLY {@code Dr 5000 (net) / Dr 1300 (tax) / Cr 2000 (gross)} — the
 * same shape {@link BillPostingUnaffectedByV53Test} already pins for V53, re-proven here to confirm
 * V54 introduces no regression of its own.
 */
@SpringBootTest
class BillLineIsInventoryDefaultsFalseTest extends PostgresRlsTestBase {

  private static final String TENANT = "cccccccc-cccc-cccc-cccc-ccccccccccc2";
  private static final String ACTOR = "ap-v54@isolation.co.id";

  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;

  @Test
  void everyBillLineRowDefaultsIsInventoryFalseAndPostingIsUnaffected() throws Exception {
    UUID billId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              VendorResponse vendor = vendorWriter.create("Acme V54", "ap@acme-v54.test", null);
              UUID id =
                  billWriter.createDraft(
                      vendor.id(),
                      "IDR",
                      true,
                      List.of(
                          new BillLineInput("Supplies", 2, 500_000L, false),
                          new BillLineInput("Delivery fee", 1, 200_000L, false)));
              billWriter.post(id, 30);
              return id;
            });

    // 1. Every bill_line row this (unmodified) BillWriter inserted defaults is_inventory = FALSE —
    //    the writer never sets the column, so this is purely the DEFAULT FALSE clause from V54.
    List<Boolean> isInventoryFlags = billLineIsInventoryFlagsAsAdmin(billId);
    assertThat(isInventoryFlags)
        .as("two bill_line rows, both defaulting is_inventory = FALSE")
        .hasSize(2)
        .allMatch(flag -> !flag, "is_inventory = FALSE");

    // 2. The GL posting is unaffected: same V28 shape as pre-V54 (net = 1,200,000; tax = 11% =
    //    132,000; gross = 1,332,000), never touching 2050 GRNI, never falling back to 9999
    // suspense.
    Map<String, Long> byAccountDebit = accountAmountsAsAdmin(billId, "debit_minor");
    Map<String, Long> byAccountCredit = accountAmountsAsAdmin(billId, "credit_minor");

    assertThat(byAccountDebit)
        .as("Dr EXPENSE (5000, net) / Dr VAT_INPUT (1300, tax) — unaffected by V54")
        .containsExactlyInAnyOrderEntriesOf(Map.of("5000", 1_200_000L, "1300", 132_000L));
    assertThat(byAccountCredit)
        .as("Cr AP (2000, gross) — unaffected by V54")
        .containsExactlyInAnyOrderEntriesOf(Map.of("2000", 1_332_000L));
    assertThat(byAccountDebit).as("2050 GRNI Clearing must never appear").doesNotContainKey("2050");
    assertThat(byAccountDebit.keySet()).as("no suspense fallback").doesNotContain("9999");
    assertThat(byAccountCredit.keySet()).doesNotContain("9999");
  }

  private List<Boolean> billLineIsInventoryFlagsAsAdmin(UUID billId) throws Exception {
    List<Boolean> flags = new java.util.ArrayList<>();
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT is_inventory FROM bill_line WHERE bill_id = ? ORDER BY line_no")) {
      ps.setObject(1, billId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          flags.add(rs.getBoolean(1));
        }
      }
    }
    return flags;
  }

  private Map<String, Long> accountAmountsAsAdmin(UUID billId, String amountColumn)
      throws Exception {
    Map<String, Long> byAccount = new LinkedHashMap<>();
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT jl.account_code, jl."
                    + amountColumn
                    + " FROM journal_line jl"
                    + " JOIN journal_entry je ON je.id = jl.entry_id"
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
}
