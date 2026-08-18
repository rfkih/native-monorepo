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
 * ADR 0067 Phase B, §3 — {@code BillWriter}'s inventory-flagged net split, against a REAL
 * Testcontainers Postgres. The load-bearing dormancy proof: a NON-activated tenant IGNORES the
 * {@code inventory} flag entirely and stays BYTE-IDENTICAL to today ({@link
 * BillPostingUnaffectedByV53Test}'s exact V28 shape) — proving the flag alone, without a company
 * activation, can NEVER move a rupiah into {@code 2050 GRNI}. A TEST-ACTIVATED tenant (an {@code
 * inventory_method_config} row inserted directly by this test — no production activation path
 * exists yet) posts the split, and its void posts the exact contra.
 */
@SpringBootTest
class BillInventoryRoutingTest extends PostgresRlsTestBase {

  private static final String ACTIVE_TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1";
  private static final String INACTIVE_TENANT = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2";
  private static final String ACTOR = "ap-adr0067@isolation.co.id";

  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;

  @Test
  void anActivatedTenantSplitsExpenseAndGrniForAnInventoryFlaggedLine() throws Exception {
    activatePerpetualInventory(ACTIVE_TENANT);

    UUID billId =
        TenantContext.callAs(
            ACTIVE_TENANT,
            ACTOR,
            () -> {
              VendorResponse vendor = vendorWriter.create("Acme Active", "ap@active.test", null);
              UUID id =
                  billWriter.createDraft(
                      vendor.id(),
                      "IDR",
                      true,
                      List.of(
                          new BillLineInput("Office supplies", 2, 300_000L, false),
                          new BillLineInput("Raw ingredients", 1, 400_000L, true)));
              billWriter.post(id, 30);
              return id;
            });

    // subtotal = 1,000,000 (expenseNet 600,000 + inventoryNet 400,000); tax = 11% = 110,000;
    // gross = 1,110,000.
    Map<String, Long> debit = accountAmountsAsAdmin(billId, "debit_minor");
    Map<String, Long> credit = accountAmountsAsAdmin(billId, "credit_minor");

    assertThat(debit)
        .as(
            "Dr EXPENSE (5000, non-inventory net) / Dr GRNI_CLEARING (2050, inventory net) / Dr"
                + " VAT_INPUT (1300, tax)")
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("5000", 600_000L, "2050", 400_000L, "1300", 110_000L));
    assertThat(credit)
        .as("Cr AP (2000, gross)")
        .containsExactlyInAnyOrderEntriesOf(Map.of("2000", 1_110_000L));
    assertThat(debit.keySet()).as("no suspense fallback").doesNotContain("9999");
  }

  @Test
  void voidingAnActivatedBillPostsTheExactContra() throws Exception {
    activatePerpetualInventory(ACTIVE_TENANT);

    UUID billId =
        TenantContext.callAs(
            ACTIVE_TENANT,
            ACTOR,
            () -> {
              VendorResponse vendor = vendorWriter.create("Acme Void", "ap@void.test", null);
              UUID id =
                  billWriter.createDraft(
                      vendor.id(),
                      "IDR",
                      true,
                      List.of(
                          new BillLineInput("Office supplies", 2, 300_000L, false),
                          new BillLineInput("Raw ingredients", 1, 400_000L, true)));
              billWriter.post(id, 30);
              billWriter.voidBill(id);
              return id;
            });

    UUID voidEntryId = voidEntryIdAsAdmin(ACTIVE_TENANT);
    Map<String, Long> debit = accountAmountsForEntryAsAdmin(voidEntryId, "debit_minor");
    Map<String, Long> credit = accountAmountsForEntryAsAdmin(voidEntryId, "credit_minor");

    assertThat(debit)
        .as("Dr AP (2000, gross) — the contra opens")
        .containsExactlyInAnyOrderEntriesOf(Map.of("2000", 1_110_000L));
    assertThat(credit)
        .as(
            "Cr EXPENSE (5000) / Cr GRNI_CLEARING (2050) / Cr VAT_INPUT (1300) — the mirrored"
                + " contra legs")
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("5000", 600_000L, "2050", 400_000L, "1300", 110_000L));
    assertThat(billId).isNotNull();
  }

  @Test
  void aNonActivatedTenantIgnoresTheInventoryFlagAndStaysByteIdentical() throws Exception {
    // NO inventory_method_config row for INACTIVE_TENANT — the Phase B DORMANT state.
    UUID billId =
        TenantContext.callAs(
            INACTIVE_TENANT,
            ACTOR,
            () -> {
              VendorResponse vendor =
                  vendorWriter.create("Acme Inactive", "ap@inactive.test", null);
              UUID id =
                  billWriter.createDraft(
                      vendor.id(),
                      "IDR",
                      true,
                      // The SAME inventory-flagged line as the activated test above — but this
                      // tenant is not perpetual-active, so the flag must be completely ignored.
                      List.of(
                          new BillLineInput("Office supplies", 2, 300_000L, false),
                          new BillLineInput("Raw ingredients", 1, 400_000L, true)));
              billWriter.post(id, 30);
              return id;
            });

    Map<String, Long> debit = accountAmountsAsAdmin(billId, "debit_minor");
    Map<String, Long> credit = accountAmountsAsAdmin(billId, "credit_minor");

    assertThat(debit)
        .as("EVERYTHING routes to EXPENSE (5000) — the flag is ignored, today's V28 shape")
        .containsExactlyInAnyOrderEntriesOf(Map.of("5000", 1_000_000L, "1300", 110_000L));
    assertThat(credit).containsExactlyInAnyOrderEntriesOf(Map.of("2000", 1_110_000L));
    assertThat(debit.keySet())
        .as("2050 GRNI Clearing must NEVER appear for a non-activated tenant")
        .doesNotContain("2050");
    assertThat(debit.keySet()).doesNotContain("9999");
  }

  /**
   * Activates perpetual inventory with a NULL {@code cutover_period} deliberately — {@code
   * BillWriter} keys its period off the REAL system clock ({@code Clock.systemUTC()}, {@code
   * EventsConfig}), not a test-controlled instant, so a literal cutover date would be flaky against
   * the host's wall-clock. NULL means "no gate" ({@link
   * id.co.nativeapp.finance.inventory.service.PerpetualInventoryReader}), matching ADR 0067 §5's
   * "new companies: perpetual-active from creation" — deterministic regardless of when this test
   * runs.
   */
  private void activatePerpetualInventory(String companyId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO inventory_method_config"
                    + " (id, method, perpetual_active, cutover_period, activated_at, created_at,"
                    + " created_by, updated_at, updated_by, version, company_id, currency,"
                    + " idempotency_key, opening_inventory_value_minor)"
                    + " VALUES (?, 'PERPETUAL', true, NULL, now(), now(), 'test', now(),"
                    + " 'test', 0, ?, 'IDR', ?, 0)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, companyId);
      ps.setString(3, "test-activate-" + companyId);
      ps.executeUpdate();
    }
  }

  private UUID voidEntryIdAsAdmin(String companyId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT id FROM journal_entry WHERE company_id = ? AND description = ?")) {
      ps.setString(1, companyId);
      ps.setString(2, "AP bill voided");
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("the void contra entry must exist").isTrue();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private Map<String, Long> accountAmountsAsAdmin(UUID billId, String amountColumn)
      throws Exception {
    Map<String, Long> byAccount = new LinkedHashMap<>();
    try (Connection admin = admin();
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

  private Map<String, Long> accountAmountsForEntryAsAdmin(UUID entryId, String amountColumn)
      throws Exception {
    Map<String, Long> byAccount = new LinkedHashMap<>();
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT account_code, "
                    + amountColumn
                    + " FROM journal_line"
                    + " WHERE entry_id = ? AND "
                    + amountColumn
                    + " > 0")) {
      ps.setObject(1, entryId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          byAccount.put(rs.getString(1), rs.getLong(2));
        }
      }
    }
    return byAccount;
  }

  private Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
