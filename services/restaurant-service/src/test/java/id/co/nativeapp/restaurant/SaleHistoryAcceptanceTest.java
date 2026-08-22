package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.dto.SaleHistoryResponse;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Review follow-up (finding W1): {@code SaleRepository.findHistory} — the cashier's "today's
 * transactions" list ({@code GET /api/v1/sales}) — had only {@code @WebMvcTest} + mocked-writer
 * coverage, which never executes the native SQL. This proves the actual query against real
 * Postgres: the {@code [from, to)} half-open boundary, the {@code restaurant_order} LEFT JOIN,
 * newest-first ordering, and cross-tenant RLS isolation. Mirrors {@code
 * order.OrderReadPathPaymentTest}'s style — {@code @SpringBootTest extends PostgresRlsTestBase}, an
 * admin/BYPASSRLS JDBC connection to insert a test-only {@code restaurant_order} row linking a sale
 * (no public API links an order to a pre-existing sale directly), and {@link TenantContext#callAs}
 * to scope each call.
 *
 * <p>Sales are recorded via {@link SaleService#recordSale} with an explicit {@code occurredAt} (the
 * five-arg {@link RecordSaleCommand} convenience constructor) so the boundary timestamps are exact,
 * not "whatever {@code Instant.now()} happened to be".
 *
 * <p>Also proves the {@code payment} LEFT JOIN added for the payment-reversal status ({@code
 * paymentStatus}) and cumulative refunded amount ({@code refundedMinor}): a sale backed by a VOIDED
 * payment surfaces that status (refunded stays 0 — a void is not a refund), a PARTIALLY_REFUNDED
 * payment surfaces its non-zero {@code refunded_minor}, an unbacked sale stays {@code null} status
 * with refunded COALESCEd to 0, and — critically — the join does not duplicate or drop rows (the
 * existing {@code containsExactly} ordering assertion below still holds with the payment rows
 * present).
 */
@SpringBootTest
class SaleHistoryAcceptanceTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "cashier-a@example.co.id";
  private static final String ACTOR_B = "cashier-b@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("22222222-2222-2222-2222-222222222222");

  private static final Instant FROM = Instant.parse("2026-08-08T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-08-09T00:00:00Z");

  @Autowired private SaleService saleService;

  @Test
  void findHistoryHonoursTheHalfOpenBoundaryNewestFirstOrderJoinAndTenantIsolation()
      throws Exception {
    // s1: occurred_at == FROM exactly -> INCLUDED (lower bound is inclusive). No linked order,
    // but linked to a PARTIALLY_REFUNDED payment — proves a non-zero refunded_minor surfaces.
    UUID s1 = recordSale(TENANT_A, ACTOR_A, "hist-at-from", 10_000L, FROM);
    insertPaymentForSale(TENANT_A, s1, "PARTIALLY_REFUNDED", 4_000L, FROM);

    // s3: occurred_at == FROM + 1h -> the middle result, no linked order.
    UUID s3 = recordSale(TENANT_A, ACTOR_A, "hist-middle", 15_000L, FROM.plusSeconds(3600));

    // s2: occurred_at == FROM + 2h -> the newest result, LINKED to a restaurant_order row.
    UUID s2 = recordSale(TENANT_A, ACTOR_A, "hist-later", 20_000L, FROM.plusSeconds(7200));
    UUID linkedOrderId = linkOrderToSale(TENANT_A, s2, FROM.plusSeconds(7200), 20_000L);

    // s3 is also linked to a VOIDED payment — proves the payment LEFT JOIN surfaces
    // paymentStatus without duplicating or dropping the sale row. A void takes no refund,
    // so its refunded_minor stays 0.
    insertPaymentForSale(TENANT_A, s3, "VOIDED", 0L, FROM.plusSeconds(3600));

    // s4: occurred_at == TO exactly -> EXCLUDED (upper bound is exclusive).
    UUID s4 = recordSale(TENANT_A, ACTOR_A, "hist-at-to", 99_000L, TO);

    // sB: tenant B, same businessId, well inside the window -> must NEVER appear for tenant A.
    UUID saleB = recordSale(TENANT_B, ACTOR_B, "hist-tenant-b", 77_000L, FROM.plusSeconds(5400));

    List<SaleHistoryResponse> history =
        TenantContext.callAs(TENANT_A, ACTOR_A, () -> saleService.findHistory(BUSINESS, FROM, TO));

    // Exactly the three tenant-A sales inside [FROM, TO), newest first — s4 (== TO, upper bound
    // exclusive) and sB (tenant B) excluded. This one assertion proves both the boundary and the
    // ordering; the explicit doesNotContain calls below name each excluded id for a clearer
    // failure message if either regresses independently.
    assertThat(history).extracting(SaleHistoryResponse::saleId).containsExactly(s2, s3, s1);
    assertThat(history.stream().map(SaleHistoryResponse::saleId))
        .as("occurred_at == TO must be excluded (upper bound exclusive)")
        .doesNotContain(s4);
    assertThat(history.stream().map(SaleHistoryResponse::saleId))
        .as("a sale recorded under tenant B must never leak into tenant A's history")
        .doesNotContain(saleB);

    // Newest-first ordering, explicit per-row check.
    assertThat(history.get(0).occurredAt()).isEqualTo(FROM.plusSeconds(7200));
    assertThat(history.get(1).occurredAt()).isEqualTo(FROM.plusSeconds(3600));
    assertThat(history.get(2).occurredAt()).isEqualTo(FROM);

    // The restaurant_order LEFT JOIN: the linked sale carries its order id, the others are null.
    SaleHistoryResponse newest = history.get(0);
    assertThat(newest.saleId()).isEqualTo(s2);
    assertThat(newest.orderId()).isEqualTo(linkedOrderId);

    SaleHistoryResponse middle = history.get(1);
    assertThat(middle.saleId()).isEqualTo(s3);
    assertThat(middle.orderId()).as("no restaurant_order row backs this sale").isNull();

    SaleHistoryResponse atFrom = history.get(2);
    assertThat(atFrom.saleId()).isEqualTo(s1);
    assertThat(atFrom.orderId()).as("no restaurant_order row backs this sale").isNull();

    // The payment LEFT JOIN: the payment-backed sales (s3 voided, s1 partially refunded) carry
    // their status + refunded amount; the unbacked sale (s2, despite its order link) stays a
    // null status with refunded COALESCEd to 0.
    assertThat(newest.paymentStatus()).as("no payment row backs this sale").isNull();
    assertThat(newest.refundedMinor()).as("unbacked sale COALESCEs to 0").isZero();
    assertThat(middle.paymentStatus()).isEqualTo("VOIDED");
    assertThat(middle.refundedMinor()).as("a void takes no refund").isZero();
    assertThat(atFrom.paymentStatus()).isEqualTo("PARTIALLY_REFUNDED");
    assertThat(atFrom.refundedMinor()).isEqualTo(4_000L);
  }

  private UUID recordSale(
      String tenant, String actor, String idempotencyKey, long amountMinor, Instant occurredAt)
      throws Exception {
    RecordSaleCommand command =
        new RecordSaleCommand(BUSINESS, amountMinor, "IDR", occurredAt, idempotencyKey);
    RecordSaleResult result =
        TenantContext.callAs(tenant, actor, () -> saleService.recordSale(command));
    return result.sale().id();
  }

  /**
   * Inserts a {@code restaurant_order} row linking back to an already-recorded sale (test-only
   * whole-row write over the admin/BYPASSRLS connection — no public API links an order to a
   * pre-existing sale directly; checkout creates both together). Exercises the repository's LEFT
   * JOIN {@code o.sale_id = s.id} path.
   */
  private UUID linkOrderToSale(String companyId, UUID saleId, Instant occurredAt, long totalMinor)
      throws Exception {
    UUID orderId = UUID.randomUUID();
    Timestamp ts = Timestamp.from(occurredAt);
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO restaurant_order (id, business_id, status, total_minor, currency,"
                    + " sale_id, occurred_at, idempotency_key,"
                    + " created_at, created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, 'COMPLETED', ?, 'IDR', ?, ?, ?, ?, 'test', ?, 'test', 0, ?)")) {
      ps.setObject(1, orderId);
      ps.setObject(2, BUSINESS);
      ps.setLong(3, totalMinor);
      ps.setObject(4, saleId);
      ps.setTimestamp(5, ts);
      ps.setString(6, "hist-link-" + orderId);
      ps.setTimestamp(7, ts);
      ps.setTimestamp(8, ts);
      ps.setString(9, companyId);
      ps.executeUpdate();
    }
    return orderId;
  }

  /**
   * Inserts a {@code payment} row linking back to an already-recorded sale (test-only whole-row
   * write over the admin/BYPASSRLS connection, mirroring {@link #linkOrderToSale} — no public API
   * flips a payment's status directly in a test). Exercises the repository's LEFT JOIN {@code
   * p.sale_id = s.id} path. Uses {@code bill_id} (nullable, unconstrained by an FK) rather than
   * {@code order_id} to satisfy V38's {@code ck_payment_order_xor_bill} without needing a real
   * {@code restaurant_order} row.
   */
  private UUID insertPaymentForSale(
      String companyId, UUID saleId, String status, long refundedMinor, Instant occurredAt)
      throws Exception {
    UUID paymentId = UUID.randomUUID();
    Timestamp ts = Timestamp.from(occurredAt);
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "INSERT INTO payment (id, bill_id, business_id, tender_type, status,"
                    + " amount_minor, currency, tendered_minor, change_minor, refunded_minor,"
                    + " sale_id, occurred_at, idempotency_key,"
                    + " created_at, created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, ?, 'CASH', ?, ?, 'IDR', ?, ?, ?, ?, ?, ?, ?, 'test', ?,"
                    + " 'test', 0, ?)")) {
      ps.setObject(1, paymentId);
      ps.setObject(2, UUID.randomUUID());
      ps.setObject(3, BUSINESS);
      ps.setString(4, status);
      ps.setLong(5, 10_000L);
      ps.setLong(6, 10_000L);
      ps.setLong(7, 0L);
      ps.setLong(8, refundedMinor);
      ps.setObject(9, saleId);
      ps.setTimestamp(10, ts);
      ps.setString(11, "hist-payment-" + paymentId);
      ps.setTimestamp(12, ts);
      ps.setTimestamp(13, ts);
      ps.setString(14, companyId);
      ps.executeUpdate();
    }
    return paymentId;
  }
}
