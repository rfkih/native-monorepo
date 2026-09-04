package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.companyexpense.dto.RecordCompanyExpenseRequest;
import id.co.nativeapp.finance.companyexpense.service.CompanyExpenseService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomicity (rule 3, ENGINEERING-STANDARDS §3.2 / HR-3) for the company-expense producer: the
 * expense row, its lines, the journal, the dimensional/P&amp;L legs AND the {@code
 * InventoryPurchaseRecorded} outbox row commit together or not at all.
 *
 * <p>{@code CompanyExpenseWriter.record} is {@code REQUIRED}-propagation, so a test-only
 * {@code @Transactional} harness opens the surrounding transaction, lets the FULL write sequence
 * complete — the outbox row is the last write inside {@code record} — and then throws. Everything
 * must roll back. Counted over the admin (BYPASSRLS) connection; a failure BEFORE the boom would
 * fail the exception-message assertion, so this cannot pass vacuously.
 */
@SpringBootTest
class CompanyExpenseAtomicityTest extends PostgresRlsTestBase {

  private static final String ACTOR = "owner@companyexpense-atomic.test";
  static final String BOOM = "forced failure after the outbox write (test harness)";

  @Autowired private CompanyExpenseAtomicityHarness harness;

  @Test
  void aFailureAfterTheOutboxWriteRollsBackEverything() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID outlet = seedOutlet(tenant);
    RecordCompanyExpenseRequest request =
        new RecordCompanyExpenseRequest(
            "INVENTORY",
            outlet,
            null,
            "Belanja atomik",
            null,
            "IDR",
            Instant.parse("2026-09-02T03:00:00Z"),
            List.of(
                new RecordCompanyExpenseRequest.LineRequest(
                    UUID.randomUUID(), "Tepung terigu", 2_000L, 45_000L)));

    assertThatThrownBy(
            () -> TenantContext.callAs(tenant, ACTOR, () -> harness.recordThenBoom(request)))
        .hasMessageContaining(BOOM);

    assertThat(countAsAdmin("company_expense", tenant)).isZero();
    assertThat(countAsAdmin("company_expense_line", tenant)).isZero();
    assertThat(countAsAdmin("journal_entry", tenant)).isZero();
    assertThat(countAsAdmin("ledger_posting", tenant)).isZero();
    assertThat(countAsAdmin("consolidated_pnl", tenant)).isZero();
    assertThat(outboxCountAsAdmin(tenant)).isZero();
  }

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

  private long countAsAdmin(String table, String tenant) throws Exception {
    try (Connection admin = admin();
        PreparedStatement ps =
            admin.prepareStatement("SELECT count(*) FROM " + table + " WHERE company_id = ?")) {
      ps.setString(1, tenant);
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

  private Connection admin() throws Exception {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  /**
   * Opens the transaction {@code record} (REQUIRED) joins, lets the full write sequence — expense,
   * lines, journal, dimensional/P&amp;L, outbox row — complete, then throws while still inside it.
   * Being {@code @Transactional}, it gets the tenant GUC bound by {@code RlsAutoApplyAspect}.
   */
  static class CompanyExpenseAtomicityHarness {
    private final CompanyExpenseService service;

    CompanyExpenseAtomicityHarness(CompanyExpenseService service) {
      this.service = service;
    }

    @Transactional
    public Void recordThenBoom(RecordCompanyExpenseRequest request) {
      service.record(request, null);
      throw new IllegalStateException(BOOM);
    }
  }

  /** Distinct context configuration — the harness bean never leaks into other test classes. */
  @TestConfiguration
  static class HarnessConfig {
    @Bean
    CompanyExpenseAtomicityHarness companyExpenseAtomicityHarness(CompanyExpenseService service) {
      return new CompanyExpenseAtomicityHarness(service);
    }
  }
}
