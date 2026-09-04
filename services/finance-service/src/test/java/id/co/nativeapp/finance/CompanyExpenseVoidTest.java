package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseStateException;
import id.co.nativeapp.finance.companyexpense.dto.RecordCompanyExpenseRequest;
import id.co.nativeapp.finance.companyexpense.service.CompanyExpenseService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0072 §4 — void is the exact mirror of the STORED journal (never recomputed), money-side only.
 * The dimensional/P&amp;L legs are negated into the VOID's own period iff the original wrote them;
 * a second void is a 409; stock is untouched by construction (no restaurant-side event on void —
 * asserted via the outbox).
 */
@SpringBootTest
class CompanyExpenseVoidTest extends PostgresRlsTestBase {

  private static final String ACTOR = "owner@companyexpense-void.test";
  private static final Instant OCCURRED = Instant.parse("2026-09-02T03:00:00Z");
  private static final String PERIOD = "2026-09";

  @Autowired private CompanyExpenseService service;

  @Test
  void voidingAGeneralExpenseMirrorsTheEntryAndUnwindsThePnl() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID outlet = seedOutlet(tenant);

    UUID expenseId =
        TenantContext.callAs(
            tenant,
            ACTOR,
            () ->
                service.record(
                    new RecordCompanyExpenseRequest(
                        "GENERAL",
                        outlet,
                        "cogs",
                        "Salah input",
                        90_000L,
                        "IDR",
                        OCCURRED,
                        List.of()),
                    null));
    TenantContext.callAs(tenant, ACTOR, () -> service.voidExpense(expenseId));

    // The contra: Dr 1900 / Cr 5100 — the exact mirror of the stored Dr 5100 / Cr 1900.
    UUID contraId = contraEntryIdAsAdmin(tenant);
    Map<String, Long> contraDebit = entryAmountsAsAdmin(contraId, "debit_minor");
    Map<String, Long> contraCredit = entryAmountsAsAdmin(contraId, "credit_minor");
    assertThat(contraDebit).containsExactlyInAnyOrderEntriesOf(Map.of("1900", 90_000L));
    assertThat(contraCredit).containsExactlyInAnyOrderEntriesOf(Map.of("5100", 90_000L));

    // The P&L nets to zero and the reversal dimensional row exists.
    assertThat(pnlExpenseMinorAsAdmin(tenant, PERIOD)).isZero();
    assertThat(reversalPostingCountAsAdmin(tenant)).isEqualTo(1L);

    // Status flipped; a second void is a 409; no stock-side event ever (GENERAL).
    assertThat(statusAsAdmin(expenseId)).isEqualTo("VOID");
    assertThatThrownBy(
            () -> TenantContext.callAs(tenant, ACTOR, () -> service.voidExpense(expenseId)))
        .isInstanceOf(CompanyExpenseStateException.class);
    assertThat(purchaseEventCountAsAdmin(tenant)).isZero();
  }

  @Test
  void voidingAPerpetualInventoryExpenseMirrorsGrniAndSkipsThePnl() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID outlet = seedOutlet(tenant);
    activatePerpetualInventory(tenant);

    UUID expenseId =
        TenantContext.callAs(
            tenant,
            ACTOR,
            () ->
                service.record(
                    new RecordCompanyExpenseRequest(
                        "INVENTORY",
                        outlet,
                        null,
                        "Belanja keliru",
                        null,
                        "IDR",
                        OCCURRED,
                        List.of(
                            new RecordCompanyExpenseRequest.LineRequest(
                                UUID.randomUUID(), "Beras", 5_000L, 60_000L))),
                    null));
    TenantContext.callAs(tenant, ACTOR, () -> service.voidExpense(expenseId));

    UUID contraId = contraEntryIdAsAdmin(tenant);
    assertThat(entryAmountsAsAdmin(contraId, "debit_minor"))
        .containsExactlyInAnyOrderEntriesOf(Map.of("1900", 60_000L));
    assertThat(entryAmountsAsAdmin(contraId, "credit_minor"))
        .as("the mirror leaves 2050 in credit until opname trues it (ADR 0072 §4)")
        .containsExactlyInAnyOrderEntriesOf(Map.of("2050", 60_000L));

    // The original wrote no dimensional/P&L legs, so the void negates none.
    assertThat(pnlExpenseMinorAsAdmin(tenant, PERIOD)).isZero();
    assertThat(reversalPostingCountAsAdmin(tenant)).isZero();

    // Exactly ONE purchase event (the record) — the void emits nothing stock-side.
    assertThat(purchaseEventCountAsAdmin(tenant)).isEqualTo(1L);
  }

  // ---------------------------------------------------------------- helpers

  private UUID seedOutlet(String tenant) throws Exception {
    UUID outletId = UUID.randomUUID();
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO org_unit_ref (org_unit_id, type, parent_id, name, active, created_at,"
                    + " created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, 'OUTLET', NULL, 'Outlet Uji', TRUE, now(), 'test', now(),"
                    + " 'test', 0, ?)")) {
      ps.setObject(1, outletId);
      ps.setString(2, tenant);
      ps.executeUpdate();
    }
    return outletId;
  }

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

  private UUID contraEntryIdAsAdmin(String tenant) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT id FROM journal_entry WHERE company_id = ? AND description = ?")) {
      ps.setString(1, tenant);
      ps.setString(2, "Company expense voided");
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("the void contra entry must exist").isTrue();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  private Map<String, Long> entryAmountsAsAdmin(UUID entryId, String amountColumn)
      throws Exception {
    Map<String, Long> byAccount = new LinkedHashMap<>();
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT account_code, "
                    + amountColumn
                    + " FROM journal_line WHERE entry_id = ? AND "
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

  private long pnlExpenseMinorAsAdmin(String tenant, String period) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT COALESCE(SUM(expense_minor), 0) FROM consolidated_pnl"
                    + " WHERE company_id = ? AND period = ?")) {
      ps.setString(1, tenant);
      ps.setString(2, period);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long reversalPostingCountAsAdmin(String tenant) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM ledger_posting WHERE company_id = ?"
                    + " AND posting_role = 'REVERSAL'")) {
      ps.setString(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private long purchaseEventCountAsAdmin(String tenant) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT count(*) FROM outbox WHERE event_type = 'InventoryPurchaseRecorded'"
                    + " AND company_id = ?::uuid")) {
      ps.setString(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private String statusAsAdmin(UUID expenseId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT status FROM company_expense WHERE id = ?")) {
      ps.setObject(1, expenseId);
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
}
