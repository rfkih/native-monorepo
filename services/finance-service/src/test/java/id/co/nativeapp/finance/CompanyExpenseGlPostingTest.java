package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseNotFoundException;
import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseSealedPeriodException;
import id.co.nativeapp.finance.companyexpense.domain.InvalidGlHintException;
import id.co.nativeapp.finance.companyexpense.domain.UnknownBusinessUnitException;
import id.co.nativeapp.finance.companyexpense.dto.RecordCompanyExpenseRequest;
import id.co.nativeapp.finance.companyexpense.messaging.InventoryPurchaseRecordedSchema;
import id.co.nativeapp.finance.companyexpense.service.CompanyExpenseReader;
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
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ADR 0072 — the company-expense posting matrix, against the real Flyway migration + RLS under the
 * unprivileged {@code app_user}:
 *
 * <ul>
 *   <li>GENERAL: {@code Dr resolveExpense(gl_hint) / Cr 1900} + dimensional posting + P&amp;L.
 *   <li>INVENTORY, periodic (default): {@code Dr 5100 / Cr 1900} + dimensional posting + P&amp;L +
 *       ONE {@code InventoryPurchaseRecorded} outbox row whose lines mirror the submit.
 *   <li>INVENTORY, perpetual-active: {@code Dr 2050 / Cr 1900}, NO dimensional/P&amp;L legs.
 *   <li>Input rejections: unknown hint, unknown outlet, sealed period.
 *   <li>Tenant isolation: another tenant sees nothing (RLS).
 * </ul>
 */
@SpringBootTest
class CompanyExpenseGlPostingTest extends PostgresRlsTestBase {

  private static final String ACTOR = "owner@companyexpense.test";
  private static final Instant OCCURRED = Instant.parse("2026-09-02T03:00:00Z");
  private static final String PERIOD = "2026-09";

  @Autowired private CompanyExpenseService service;
  @Autowired private CompanyExpenseReader reader;

  @Test
  void aGeneralExpensePostsTheHintAccountAndFeedsThePnl() throws Exception {
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
                        "utilities",
                        "Listrik toko",
                        750_000L,
                        "IDR",
                        OCCURRED,
                        List.of()),
                    null));

    Map<String, Long> debit = accountAmountsAsAdmin(expenseId, "debit_minor");
    Map<String, Long> credit = accountAmountsAsAdmin(expenseId, "credit_minor");
    assertThat(debit).containsExactlyInAnyOrderEntriesOf(Map.of("5300", 750_000L));
    assertThat(credit).containsExactlyInAnyOrderEntriesOf(Map.of("1900", 750_000L));
    assertThat(ledgerPostingAmountAsAdmin(expenseId)).isEqualTo(750_000L);
    assertThat(pnlExpenseMinorAsAdmin(tenant, PERIOD)).isEqualTo(750_000L);
    assertThat(outboxCountAsAdmin(tenant)).as("GENERAL emits no purchase event").isZero();
  }

  @Test
  void aPeriodicInventoryExpensePostsHppAndEmitsThePurchaseEvent() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID outlet = seedOutlet(tenant);
    UUID ingredientA = UUID.randomUUID();
    UUID ingredientB = UUID.randomUUID();

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
                        "Belanja pasar pagi",
                        null,
                        "IDR",
                        OCCURRED,
                        List.of(
                            new RecordCompanyExpenseRequest.LineRequest(
                                ingredientA, "Ayam fillet", 2_000L, 90_000L),
                            new RecordCompanyExpenseRequest.LineRequest(
                                ingredientB, "Cabai merah", 500L, 30_000L))),
                    null));

    // Money: Dr 5100 HPP (owner decision — periodic purchases-as-COGS) / Cr 1900.
    Map<String, Long> debit = accountAmountsAsAdmin(expenseId, "debit_minor");
    Map<String, Long> credit = accountAmountsAsAdmin(expenseId, "credit_minor");
    assertThat(debit).containsExactlyInAnyOrderEntriesOf(Map.of("5100", 120_000L));
    assertThat(credit).containsExactlyInAnyOrderEntriesOf(Map.of("1900", 120_000L));
    assertThat(debit.keySet()).as("GRNI never appears under periodic").doesNotContain("2050");
    assertThat(ledgerPostingAmountAsAdmin(expenseId)).isEqualTo(120_000L);
    assertThat(pnlExpenseMinorAsAdmin(tenant, PERIOD)).isEqualTo(120_000L);

    // The stock instruction: exactly one event, lines mirroring the submit, line_id = the
    // persisted company_expense_line ids (the consumer's goods_receipt idempotency anchor).
    List<GenericRecord> events = decodeOutboxAsAdmin(tenant);
    assertThat(events).hasSize(1);
    GenericRecord event = events.getFirst();
    assertThat(event.get("purchase_id").toString()).isEqualTo(expenseId.toString());
    assertThat(event.get("source").toString()).isEqualTo("EXPENSE");
    assertThat(event.get("company_id").toString()).isEqualTo(tenant);
    assertThat(event.get("currency").toString()).isEqualTo("IDR");
    @SuppressWarnings("unchecked")
    List<GenericRecord> lines = (List<GenericRecord>) event.get("lines");
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0).get("ingredient_id").toString()).isEqualTo(ingredientA.toString());
    assertThat(lines.get(0).get("qty_base")).isEqualTo(2_000L);
    assertThat(lines.get(0).get("value_minor")).isEqualTo(90_000L);
    assertThat(lines.get(1).get("ingredient_id").toString()).isEqualTo(ingredientB.toString());
    assertThat(lineIdsAsAdmin(expenseId))
        .containsExactlyInAnyOrder(
            UUID.fromString(lines.get(0).get("line_id").toString()),
            UUID.fromString(lines.get(1).get("line_id").toString()));
  }

  /**
   * Owner request 2026-09-04 — a receipt names things its own way ("AYAM BROILER 1KG") while the
   * inventory item is "Ayam fillet". A line keeps BOTH: the wording is stored for matching against
   * the physical nota, the ingredient link is what moves stock. Wording that matches the item (or
   * is blank) normalises to null, so "differs" stays a real signal.
   */
  @Test
  void anInventoryLineKeepsTheReceiptWordingAlongsideTheInventoryName() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID outlet = seedOutlet(tenant);
    UUID ingredientA = UUID.randomUUID();
    UUID ingredientB = UUID.randomUUID();
    UUID ingredientC = UUID.randomUUID();

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
                        "Belanja pasar",
                        null,
                        "IDR",
                        OCCURRED,
                        List.of(
                            // wording differs -> stored
                            new RecordCompanyExpenseRequest.LineRequest(
                                ingredientA, "Ayam fillet", 1_000L, 50_000L, "AYAM BROILER 1KG"),
                            // wording equals the item name -> normalised away
                            new RecordCompanyExpenseRequest.LineRequest(
                                ingredientB, "Cabai merah", 500L, 20_000L, "Cabai merah"),
                            // blank -> normalised away
                            new RecordCompanyExpenseRequest.LineRequest(
                                ingredientC, "Bawang", 300L, 10_000L, "   "))),
                    null));

    List<String> descriptions = lineDescriptionsAsAdmin(expenseId);
    assertThat(descriptions.get(0)).isEqualTo("AYAM BROILER 1KG");
    assertThat(descriptions.get(1)).as("same as the item name -> null").isNull();
    assertThat(descriptions.get(2)).as("blank -> null").isNull();

    // The stock instruction is keyed on the ingredient, never on either name.
    List<GenericRecord> events = decodeOutboxAsAdmin(tenant);
    assertThat(events).hasSize(1);
    @SuppressWarnings("unchecked")
    List<GenericRecord> lines = (List<GenericRecord>) events.getFirst().get("lines");
    assertThat(lines.get(0).get("ingredient_id").toString()).isEqualTo(ingredientA.toString());

    // And the read path surfaces it.
    var detail = TenantContext.callAs(tenant, ACTOR, () -> reader.getById(expenseId));
    assertThat(detail.lines().get(0).description()).isEqualTo("AYAM BROILER 1KG");
    assertThat(detail.lines().get(0).ingredientName()).isEqualTo("Ayam fillet");
    assertThat(detail.lines().get(1).description()).isNull();
  }

  @Test
  void aPerpetualActiveInventoryExpensePostsGrniAndSkipsThePnl() throws Exception {
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
                        "Belanja bahan (perpetual)",
                        null,
                        "IDR",
                        OCCURRED,
                        List.of(
                            new RecordCompanyExpenseRequest.LineRequest(
                                UUID.randomUUID(), "Daging sapi", 1_000L, 200_000L))),
                    null));

    Map<String, Long> debit = accountAmountsAsAdmin(expenseId, "debit_minor");
    Map<String, Long> credit = accountAmountsAsAdmin(expenseId, "credit_minor");
    assertThat(debit)
        .as("perpetual capitalizes via GRNI; StockReceived clears it later")
        .containsExactlyInAnyOrderEntriesOf(Map.of("2050", 200_000L));
    assertThat(credit).containsExactlyInAnyOrderEntriesOf(Map.of("1900", 200_000L));
    assertThat(ledgerPostingAmountAsAdmin(expenseId))
        .as("balance-sheet only: no dimensional EXPENSE leg")
        .isNull();
    assertThat(pnlExpenseMinorAsAdmin(tenant, PERIOD)).isZero();
    assertThat(outboxCountAsAdmin(tenant)).as("the purchase event still rides").isEqualTo(1L);
  }

  @Test
  void inputRejectionsFailBeforeAnyMoneyMoves() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID outlet = seedOutlet(tenant);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    tenant,
                    ACTOR,
                    () ->
                        service.record(
                            new RecordCompanyExpenseRequest(
                                "GENERAL",
                                outlet,
                                "makan-siang",
                                "hint tak dikenal",
                                10_000L,
                                "IDR",
                                OCCURRED,
                                List.of()),
                            null)))
        .isInstanceOf(InvalidGlHintException.class);

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    tenant,
                    ACTOR,
                    () ->
                        service.record(
                            new RecordCompanyExpenseRequest(
                                "GENERAL",
                                UUID.randomUUID(),
                                "",
                                "outlet tak dikenal",
                                10_000L,
                                "IDR",
                                OCCURRED,
                                List.of()),
                            null)))
        .isInstanceOf(UnknownBusinessUnitException.class);

    sealPeriodAsAdmin(tenant, PERIOD);
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    tenant,
                    ACTOR,
                    () ->
                        service.record(
                            new RecordCompanyExpenseRequest(
                                "GENERAL",
                                outlet,
                                "",
                                "periode tersegel",
                                10_000L,
                                "IDR",
                                OCCURRED,
                                List.of()),
                            null)))
        .isInstanceOf(CompanyExpenseSealedPeriodException.class);

    assertThat(journalCountAsAdmin(tenant)).as("nothing posted").isZero();
    assertThat(expenseCountAsAdmin(tenant)).isZero();
  }

  @Test
  void anotherTenantSeesNothing() throws Exception {
    String tenantA = UUID.randomUUID().toString();
    String tenantB = UUID.randomUUID().toString();
    UUID outlet = seedOutlet(tenantA);

    UUID expenseId =
        TenantContext.callAs(
            tenantA,
            ACTOR,
            () ->
                service.record(
                    new RecordCompanyExpenseRequest(
                        "GENERAL", outlet, "", "rahasia A", 50_000L, "IDR", OCCURRED, List.of()),
                    null));

    List<?> visibleToB = TenantContext.callAs(tenantB, ACTOR, () -> reader.listRecent(50));
    assertThat(visibleToB).as("RLS: tenant B lists zero rows of tenant A").isEmpty();
    assertThatThrownBy(() -> TenantContext.callAs(tenantB, ACTOR, () -> reader.getById(expenseId)))
        .isInstanceOf(CompanyExpenseNotFoundException.class);
    assertThat(TenantContext.callAs(tenantA, ACTOR, () -> reader.getById(expenseId)).expenseNo())
        .startsWith("EXP-");
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

  private void sealPeriodAsAdmin(String tenant, String period) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO tax_filing (id, period, tax_type, status, currency,"
                    + " output_vat_minor, input_vat_minor, net_minor, net_direction,"
                    + " filing_entry_id, filed_at, created_at, created_by, updated_at, updated_by,"
                    + " version, company_id)"
                    + " VALUES (?, ?, 'PPN', 'FILED', 'IDR', 0, 0, 0, 'CREDITABLE', ?, now(),"
                    + " now(), ?, now(), ?, 0, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setString(2, period);
      ps.setObject(3, UUID.randomUUID());
      ps.setString(4, ACTOR);
      ps.setString(5, ACTOR);
      ps.setString(6, tenant);
      ps.executeUpdate();
    }
  }

  private Map<String, Long> accountAmountsAsAdmin(UUID sourceEventId, String amountColumn)
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
      ps.setObject(1, sourceEventId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          byAccount.put(rs.getString(1), rs.getLong(2));
        }
      }
    }
    return byAccount;
  }

  private Long ledgerPostingAmountAsAdmin(UUID sourceEventId) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT amount_minor FROM ledger_posting WHERE source_event_id = ?")) {
      ps.setObject(1, sourceEventId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : null;
      }
    }
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

  private long outboxCountAsAdmin(String tenant) throws Exception {
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

  private List<String> lineDescriptionsAsAdmin(UUID expenseId) throws Exception {
    List<String> out = new java.util.ArrayList<>();
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT description FROM company_expense_line WHERE expense_id = ?"
                    + " ORDER BY line_no")) {
      ps.setObject(1, expenseId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private List<UUID> lineIdsAsAdmin(UUID expenseId) throws Exception {
    List<UUID> ids = new java.util.ArrayList<>();
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement(
                "SELECT id FROM company_expense_line WHERE expense_id = ? ORDER BY line_no")) {
      ps.setObject(1, expenseId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          ids.add(rs.getObject(1, UUID.class));
        }
      }
    }
    return ids;
  }

  private long journalCountAsAdmin(String tenant) throws Exception {
    return countAsAdmin("SELECT count(*) FROM journal_entry WHERE company_id = ?", tenant);
  }

  private long expenseCountAsAdmin(String tenant) throws Exception {
    return countAsAdmin("SELECT count(*) FROM company_expense WHERE company_id = ?", tenant);
  }

  private long countAsAdmin(String sql, String tenant) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps = admin.prepareStatement(sql)) {
      ps.setString(1, tenant);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
