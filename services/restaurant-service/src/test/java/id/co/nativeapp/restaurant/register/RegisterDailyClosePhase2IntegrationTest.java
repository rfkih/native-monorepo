package id.co.nativeapp.restaurant.register;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.RegisterSessionResponse;
import id.co.nativeapp.restaurant.register.dto.TenderCount;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof of ADR 0038 phase 2 against real Postgres: a close carrying a NON-cash
 * tender count persists a {@code register_session_tender} reconciliation line (V26 table + entity
 * mapping + RLS insert), with the server-computed expected / counted / signed over-short. Read back
 * over the admin (RLS-bypassing) connection since the table is FORCE RLS.
 */
@SpringBootTest
class RegisterDailyClosePhase2IntegrationTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "cashier-phase2@example.co.id";
  private static final UUID OUTLET = UUID.fromString("66666666-6666-6666-6666-666666666666");
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
  void closeWithACardTenderCountPersistsAReconciliationLine() throws Exception {
    UUID sessionId =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(OUTLET, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());

    // No CARD sales seeded → expected CARD = 0; counted 5_000 → SIGNED over-short = +5_000.
    asTenant(
        () ->
            service.close(
                sessionId,
                new CloseSessionRequest(0L, List.of(new TenderCount("CARD", 5_000L))),
                "close:" + sessionId));

    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT tender_type, expected_minor, counted_minor, over_short_minor"
                    + " FROM register_session_tender WHERE session_id = '"
                    + sessionId
                    + "'")) {
      assertThat(rs.next()).as("a register_session_tender row was persisted").isTrue();
      assertThat(rs.getString("tender_type")).isEqualTo("CARD");
      assertThat(rs.getLong("expected_minor")).isZero();
      assertThat(rs.getLong("counted_minor")).isEqualTo(5_000L);
      assertThat(rs.getLong("over_short_minor")).isEqualTo(5_000L);
      assertThat(rs.next()).as("exactly one line").isFalse();
    }
  }

  @Test
  void nonCashExpectedNetsTheGiftCardPortionSoNoPhantomShort() throws Exception {
    UUID outlet = UUID.fromString("77777777-7777-7777-7777-777777777777");
    RegisterSessionResponse opened =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(outlet, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session());
    UUID sessionId = opened.id();

    // A CARD sale of 100k where 30k was paid on a gift card → only 70k is charged to the card, so
    // CARD_CLEARING accrues 70k (the net-tender leg). Seeded raw as the RLS-bypassing superuser,
    // with
    // occurred_at PINNED to the session's openedAt (the window lower bound is inclusive) so it is
    // in-window regardless of any Testcontainers-DB vs JVM clock skew.
    seedCardSaleWithGiftCard(outlet, opened.openedAt(), 100_000L, 30_000L);

    // The cashier counts the EDC batch = 70k (the net). expected must be the NET 70k, not the gross
    // 100k — otherwise a phantom 30k short posts (code-review C1).
    asTenant(
        () ->
            service.close(
                sessionId,
                new CloseSessionRequest(0L, List.of(new TenderCount("CARD", 70_000L))),
                "close:" + sessionId));

    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT expected_minor, counted_minor, over_short_minor"
                    + " FROM register_session_tender WHERE session_id = '"
                    + sessionId
                    + "'")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getLong("expected_minor")).isEqualTo(70_000L); // NET, not the 100k gross
      assertThat(rs.getLong("counted_minor")).isEqualTo(70_000L);
      assertThat(rs.getLong("over_short_minor")).isZero(); // no phantom short
    }
  }

  private void seedCardSaleWithGiftCard(
      UUID outlet, Instant occurredAt, long amountMinor, long giftCardMinor) throws Exception {
    try (Connection c =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO sale (id, business_id, amount_minor, currency, occurred_at,"
                    + " idempotency_key, tender_type, gift_card_redeemed_minor, created_at,"
                    + " created_by, updated_at, updated_by, version, company_id)"
                    + " VALUES (?, ?, ?, 'IDR', ?, ?, 'CARD', ?, now(), 'test', now(), 'test',"
                    + " 0, ?)")) {
      ps.setObject(1, UUID.randomUUID());
      ps.setObject(2, outlet);
      ps.setLong(3, amountMinor);
      ps.setObject(4, OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
      ps.setString(5, UUID.randomUUID().toString());
      ps.setLong(6, giftCardMinor);
      ps.setString(7, TENANT);
      ps.executeUpdate();
    }
  }
}
