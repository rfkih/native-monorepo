package id.co.nativeapp.restaurant.register;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionDayClosedException;
import id.co.nativeapp.restaurant.register.dto.CloseSessionRequest;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.RegisterExpectedResponse;
import id.co.nativeapp.restaurant.register.service.RegisterSessionService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Testcontainers proof of ADR 0038 phase 1 against real Postgres — what mocked-unit coverage cannot
 * prove (review W1): the day-final once-per-outlet-per-day rule (V25 {@code
 * uq_crs_one_session_per_outlet_day} + the writer's day probe) actually rejects a second session
 * for a day, and the per-tender expected SUM queries ({@code sumSalesByTender}/{@code
 * sumRefundsByTender}) are valid SQL that execute (a column-name typo would pass every unit test
 * but fail here).
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
  void onlyOneSessionPerOutletPerBusinessDayThenNextDayOpensFine() {
    // Open + close a session for DAY.
    UUID sessionId =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(OUTLET, 100_000L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());
    asTenant(
        () -> service.close(sessionId, new CloseSessionRequest(100_000L), "close:" + sessionId));

    // A SECOND open for the SAME business day is day-final rejected (409).
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        service.open(
                            new OpenSessionRequest(OUTLET, 0L, "IDR", DAY),
                            UUID.randomUUID().toString())))
        .isInstanceOf(RegisterSessionDayClosedException.class);

    // The NEXT business day opens fine — the rule is per day, not forever.
    UUID nextDay =
        asTenant(
            () ->
                service
                    .open(
                        new OpenSessionRequest(OUTLET, 0L, "IDR", DAY.plusDays(1)),
                        UUID.randomUUID().toString())
                    .session()
                    .id());
    assertThat(nextDay).isNotNull();
  }

  @Test
  void expectedBreakdownExecutesTheNativePerTenderQueriesAndReturnsAllFourTenders() {
    // A distinct outlet so this class's two tests never collide on the day-final unique.
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
}
