package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.companyexpense.domain.CompanyExpenseIdempotencyConflictException;
import id.co.nativeapp.finance.companyexpense.dto.RecordCompanyExpenseRequest;
import id.co.nativeapp.finance.companyexpense.service.CompanyExpenseService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The Idempotency-Key replay contract (ADR 0072): a retried submit with the same key returns the
 * SAME expense — one row, one journal entry, one outbox event — even when the two submits race
 * (CyclicBarrier); the same key with a DIFFERENT payload is a 409, never a second expense.
 */
@SpringBootTest
class CompanyExpenseIdempotencyTest extends PostgresRlsTestBase {

  private static final String ACTOR = "owner@companyexpense-idem.test";
  private static final Instant OCCURRED = Instant.parse("2026-09-02T03:00:00Z");

  @Autowired private CompanyExpenseService service;

  @Test
  void aRetriedKeyReplaysAndARacedKeyStillRecordsOnce() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID outlet = seedOutlet(tenant);
    String key = "retry-" + UUID.randomUUID();
    RecordCompanyExpenseRequest request = inventoryRequest(outlet, 40_000L);

    UUID first = TenantContext.callAs(tenant, ACTOR, () -> service.record(request, key));
    UUID replayed = TenantContext.callAs(tenant, ACTOR, () -> service.record(request, key));
    assertThat(replayed).isEqualTo(first);
    assertThat(countAsAdmin("company_expense", tenant)).isEqualTo(1L);
    assertThat(countAsAdmin("journal_entry", tenant)).isEqualTo(1L);
    assertThat(outboxCountAsAdmin(tenant)).isEqualTo(1L);

    // A different payload under the same key must conflict, not double-record.
    RecordCompanyExpenseRequest different = inventoryRequest(outlet, 99_999L);
    assertThatThrownBy(
            () -> TenantContext.callAs(tenant, ACTOR, () -> service.record(different, key)))
        .isInstanceOf(CompanyExpenseIdempotencyConflictException.class);
    assertThat(countAsAdmin("company_expense", tenant)).isEqualTo(1L);
  }

  @Test
  void twoSimultaneousSameKeySubmitsRecordExactlyOnce() throws Exception {
    String tenant = UUID.randomUUID().toString();
    UUID outlet = seedOutlet(tenant);
    String key = "race-" + UUID.randomUUID();
    RecordCompanyExpenseRequest request = inventoryRequest(outlet, 55_000L);

    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<UUID> a =
          pool.submit(
              () -> {
                barrier.await();
                return TenantContext.callAs(tenant, ACTOR, () -> service.record(request, key));
              });
      Future<UUID> b =
          pool.submit(
              () -> {
                barrier.await();
                return TenantContext.callAs(tenant, ACTOR, () -> service.record(request, key));
              });
      UUID idA = a.get(30, TimeUnit.SECONDS);
      UUID idB = b.get(30, TimeUnit.SECONDS);
      assertThat(idA).as("both racers converge on the one recorded expense").isEqualTo(idB);
    } finally {
      pool.shutdownNow();
    }
    assertThat(countAsAdmin("company_expense", tenant)).isEqualTo(1L);
    assertThat(countAsAdmin("journal_entry", tenant)).isEqualTo(1L);
    assertThat(outboxCountAsAdmin(tenant)).isEqualTo(1L);
  }

  private static RecordCompanyExpenseRequest inventoryRequest(UUID outlet, long valueMinor) {
    return new RecordCompanyExpenseRequest(
        "INVENTORY",
        outlet,
        null,
        "Belanja idempoten",
        null,
        "IDR",
        OCCURRED,
        List.of(
            new RecordCompanyExpenseRequest.LineRequest(
                UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                "Gula pasir",
                1_000L,
                valueMinor)));
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
}
