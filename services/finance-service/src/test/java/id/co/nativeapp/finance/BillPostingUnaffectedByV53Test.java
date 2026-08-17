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
 * V53 (ADR 0067 Phase 0) acceptance proof: an existing vendor-bill posting is BYTE-IDENTICAL to
 * before V53 — {@link BillWriter} is untouched by V53, and the guardrail documented in the V53
 * migration header (the new INVENTORY_NET-carrying template seeded future-dated at DB version 3, so
 * it can never resolve today) must hold end-to-end through the REAL writer + REAL resolvers, not
 * just at the resolver-unit level ({@link PerpetualInventoryGlConfigTest}). A taxable bill still
 * posts EXACTLY {@code Dr 5000 (net) / Dr 1300 (tax) / Cr 2000 (gross)} — the V28 shape, currently
 * resolved via V51's official (non-illustrative) version-2 supersession of that same shape — never
 * touching 2050 (GRNI) and never falling back to the 9999 suspense account (which is what WOULD
 * happen if V53's version-3 template had wrongly become effective, per the migration header's
 * analysis).
 */
@SpringBootTest
class BillPostingUnaffectedByV53Test extends PostgresRlsTestBase {

  private static final String TENANT = "cccccccc-cccc-cccc-cccc-ccccccccccc1";
  private static final String ACTOR = "ap-v53@isolation.co.id";

  @Autowired private VendorWriter vendorWriter;
  @Autowired private BillWriter billWriter;

  @Test
  void aTaxableBillPostsTheExactPreV53ThreeLineShape() throws Exception {
    UUID billId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () -> {
              VendorResponse vendor = vendorWriter.create("Acme V53", "ap@acme-v53.test", null);
              UUID id =
                  billWriter.createDraft(
                      vendor.id(),
                      "IDR",
                      true,
                      List.of(new BillLineInput("Supplies", 2, 500_000L, false)));
              billWriter.post(id, 30);
              return id;
            });

    // net = 1,000,000; tax = 11% = 110,000; gross = 1,110,000.
    Map<String, Long> byAccountDebit = accountAmountsAsAdmin(billId, "debit_minor");
    Map<String, Long> byAccountCredit = accountAmountsAsAdmin(billId, "credit_minor");

    assertThat(byAccountDebit)
        .as("Dr EXPENSE (5000, net) / Dr VAT_INPUT (1300, tax) — exactly the V28 shape")
        .containsExactlyInAnyOrderEntriesOf(Map.of("5000", 1_000_000L, "1300", 110_000L));
    assertThat(byAccountCredit)
        .as("Cr AP (2000, gross) — exactly the V28 shape")
        .containsExactlyInAnyOrderEntriesOf(Map.of("2000", 1_110_000L));

    assertThat(byAccountDebit)
        .as("2050 GRNI Clearing must NEVER appear — proves v2 did not resolve")
        .doesNotContainKey("2050");
    assertThat(byAccountDebit.keySet())
        .as("9999 suspense must never appear — proves the entry was NOT unbalanced/fallback")
        .doesNotContain("9999");
    assertThat(byAccountCredit.keySet()).doesNotContain("9999");
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
