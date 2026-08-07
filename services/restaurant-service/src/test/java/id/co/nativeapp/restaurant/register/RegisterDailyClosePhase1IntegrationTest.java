package id.co.nativeapp.restaurant.register;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionAlreadyOpenException;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.RegisterExpectedResponse;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
import id.co.nativeapp.restaurant.register.service.RegisterSessionService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof, against real Postgres, of the register-session cadence rules as restored by
 * the owner decision reverting ADR 0038's once-per-day tightening back to ADR 0036's original
 * multi-session model: several sessions per outlet per business day are legal again — only {@code
 * uq_crs_one_open_per_outlet} (V21, unchanged) still enforces that at most ONE may be OPEN at a
 * time — and a session may span midnight. What mocked-unit coverage cannot prove (review W1): these
 * rules actually hold at the database (V30 dropped {@code uq_crs_one_session_per_outlet_day}, the
 * V25 day-final backstop; the partial unique from V21 remains), and the per-tender expected SUM
 * queries ({@code sumSalesByTender}/{@code sumRefundsByTender}) are valid SQL that execute (a
 * column-name typo would pass every unit test but fail here).
 */
@SpringBootTest
class RegisterDailyClosePhase1IntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-daily-close@example.co.id";
  private static final UUID OUTLET = UUID.fromString("44444444-4444-4444-4444-444444444444");
  private static final LocalDate DAY = LocalDate.of(2026, 8, 6);

  @Autowired private RegisterSessionService service;

  private static <T> T asTenant(java.util.concurrent.Callable<T> action) {
    try {
      return TenantContext.callAs(TENANT, ACTOR, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void secondSessionForSameOutletSameDayOpensCleanlyAfterFirstCloses() {
    // Open + close a first session for DAY.
    UUID firstSessionId =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(OUTLET, 100_000L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());
    asTenant(
        () ->
            service.close(
                firstSessionId, new CloseSessionRequest(100_000L), "close:" + firstSessionId));

    // A SECOND open for the SAME outlet, SAME business day now succeeds cleanly — legal again
    // under the restored multi-session model. It opens as a brand-new session; the first stays
    // CLOSED, untouched.
    UUID secondSessionId =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(OUTLET, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());
    assertThat(secondSessionId).isNotEqualTo(firstSessionId);

    RegisterSessionResponse first =
        asTenant(() -> service.history(OUTLET)).stream()
            .filter(s -> s.id().equals(firstSessionId))
            .findFirst()
            .orElseThrow();
    assertThat(first.status()).isEqualTo("CLOSED");

    RegisterSessionResponse current = asTenant(() -> service.current(OUTLET)).orElseThrow();
    assertThat(current.id()).isEqualTo(secondSessionId);
    assertThat(current.status()).isEqualTo("OPEN");
  }

  @Test
  void secondOpenAtTheSameOutletWhileOneIsStillOpenIsRejected() {
    UUID outlet = UUID.fromString("88888888-8888-8888-8888-888888888888");
    asTenant(
        () ->
            service.open(
                new OpenSessionRequest(outlet, 0L, "IDR", DAY), UUID.randomUUID().toString()));

    // uq_crs_one_open_per_outlet (V21) is UNCHANGED by this revert — at most one OPEN session per
    // outlet at a time is still the only cadence constraint.
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        service.open(
                            new OpenSessionRequest(outlet, 0L, "IDR", DAY),
                            UUID.randomUUID().toString())))
        .isInstanceOf(RegisterSessionAlreadyOpenException.class);
  }

  @Test
  void sessionMaySpanMidnightOpenedYesterdayClosedToday() {
    UUID outlet = UUID.fromString("99999999-9999-9999-9999-999999999999");
    LocalDate yesterday = DAY.minusDays(1);

    UUID sessionId =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(outlet, 50_000L, "IDR", yesterday),
                        UUID.randomUUID().toString())
                    .session()
                    .id());

    // Closing "now" against a session opened with YESTERDAY's business date closes cleanly with
    // the window [opened_at, closed_at) — a session spanning midnight was already legal (the
    // day-final rule never blocked the CLOSE side, only a second OPEN); this pins it with a test.
    RegisterSessionResponse closed =
        asTenant(
            () -> service.close(sessionId, new CloseSessionRequest(50_000L), "close:" + sessionId));

    assertThat(closed.status()).isEqualTo("CLOSED");
    assertThat(closed.businessDate()).isEqualTo(yesterday);
  }

  @Test
  void twoSessionsSameDayEachEmitTheirOwnExpectedWindowUnaffectedByEachOther() throws Exception {
    UUID outlet = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    // Session 1 opens, no sales ring, closes at 0 — its expected/cashSales stay 0.
    UUID session1Id =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(outlet, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());
    RegisterSessionResponse closed1 =
        asTenant(
            () -> service.close(session1Id, new CloseSessionRequest(0L), "close:" + session1Id));
    assertThat(closed1.cashSalesMinor()).isZero();
    assertThat(closed1.expectedCashMinor()).isZero();

    // Session 2 opens for the SAME outlet, SAME business day (legal under the restored
    // multi-session model). A CASH sale rings AFTER session 2 opens — inside ONLY session 2's
    // window, never session 1's (already closed).
    UUID session2Id =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(outlet, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());
    seedCashSale(outlet, Instant.now(), 25_000L);

    RegisterSessionResponse closed2 =
        asTenant(
            () ->
                service.close(session2Id, new CloseSessionRequest(25_000L), "close:" + session2Id));

    // Session 2's own window counted the sale.
    assertThat(closed2.cashSalesMinor()).isEqualTo(25_000L);
    assertThat(closed2.expectedCashMinor()).isEqualTo(25_000L);
    assertThat(closed2.overShortMinor()).isZero();

    // Session 1's snapshot, re-read, is unchanged by the later sale — it stays in session 2's
    // window only.
    RegisterSessionResponse session1Reread =
        asTenant(() -> service.history(outlet)).stream()
            .filter(s -> s.id().equals(session1Id))
            .findFirst()
            .orElseThrow();
    assertThat(session1Reread.cashSalesMinor()).isZero();
    assertThat(session1Reread.expectedCashMinor()).isZero();

    // Each close emitted its OWN RegisterSessionClosed outbox row (two distinct aggregate ids, in
    // close order).
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT aggregate_id FROM outbox WHERE event_type = 'RegisterSessionClosed'"
                    + " ORDER BY occurred_at")) {
      List<String> aggregateIds = new ArrayList<>();
      while (rs.next()) {
        aggregateIds.add(rs.getString("aggregate_id"));
      }
      assertThat(aggregateIds).containsExactly(session1Id.toString(), session2Id.toString());
    }
  }

  @Test
  void expectedBreakdownExecutesTheNativePerTenderQueriesAndReturnsAllFourTenders() {
    // A distinct outlet so this class's tests never collide on the one-OPEN-per-outlet unique.
    UUID outlet = UUID.fromString("55555555-5555-5555-5555-555555555555");
    UUID sessionId =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(outlet, 100_000L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());

    // No sales seeded: the per-tender native SUMs must still EXECUTE against Postgres (proving the
    // column names / joins are valid) and return a well-formed breakdown — cash = the float, the
    // three non-cash tenders = 0.
    RegisterExpectedResponse resp = asTenant(() -> service.expectedBreakdown(sessionId));

    assertThat(resp.currency()).isEqualTo("IDR");
    assertThat(resp.sessionId()).isEqualTo(sessionId);
    assertThat(expectedOf(resp, "CASH")).isEqualTo(100_000L);
    assertThat(expectedOf(resp, "CARD")).isZero();
    assertThat(expectedOf(resp, "QRIS")).isZero();
    assertThat(expectedOf(resp, "ONLINE")).isZero();
  }

  private static long expectedOf(RegisterExpectedResponse response, String tender) {
    return response.tenders().stream()
        .filter(t -> t.tenderType().equals(tender))
        .findFirst()
        .orElseThrow()
        .expectedMinor();
  }

  /** Seeds a raw CASH sale (bypassing SaleWriter) pinned to an explicit {@code occurred_at}. */
  private void seedCashSale(UUID outlet, Instant occurredAt, long amountMinor) throws Exception {
    try (Connection c =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO sale (id, business_id, amount_minor, currency, occurred_at,"
                    + " idempotency_key, tender_type, created_at, created_by, updated_at,"
                    + " updated_by, version, company_id)"
                    + " VALUES (?, ?, ?, 'IDR', ?, ?, 'CASH', now(), 'test', now(), 'test',"
                    + " 0, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, outlet);
      ps.setLong(3, amountMinor);
      ps.setObject(4, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
      ps.setString(5, UUID.randomUUID().toString());
      ps.setString(6, TENANT);
      ps.executeUpdate();
    }
  }
}
