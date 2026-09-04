package id.co.nativeapp.restaurant.register;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.register.domain.RegisterSessionNotFoundException;
import id.co.nativeapp.restaurant.register.dto.OpenSessionRequest;
import id.co.nativeapp.restaurant.register.dto.RegisterSummaryResponse;
import id.co.nativeapp.restaurant.register.service.RegisterSessionService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Tenancy isolation for the POS daily summary endpoint ({@code GET
 * /api/v1/register-sessions/{id}/summary}), mirroring {@link
 * id.co.nativeapp.restaurant.order.CheckoutTenancyIsolationTest} and {@link
 * RegisterSessionTenderRlsIsolationTest}: a cross-tenant summary request must be rejected exactly
 * like every other register-session read ({@code RegisterSessionWriter#summarize} loads via {@code
 * RegisterSessionRepository#findViewById}, RLS-scoped — a session belonging to another tenant is
 * indistinguishable from a missing one, mapped to {@code 404} by {@code RegisterAdvice}).
 */
@SpringBootTest
class RegisterSummaryTenancyIsolationTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "99999999-9999-9999-9999-999999999999";
  private static final String ACTOR_A = "cashier-summary-a@example.co.id";
  private static final String ACTOR_B = "cashier-summary-b@example.co.id";
  private static final UUID OUTLET_A = UUID.fromString("bbbbbbbb-1111-1111-1111-111111111111");
  private static final LocalDate DAY = LocalDate.of(2026, 8, 6);

  @Autowired private RegisterSessionService service;

  @Test
  void tenantBCannotSummarizeTenantAsRegisterSession() {
    UUID sessionId =
        callAsA(
            () ->
                service
                    .open(
                        new OpenSessionRequest(OUTLET_A, 0L, "IDR", DAY),
                        UUID.randomUUID().toString())
                    .session()
                    .id());

    // Baseline: tenant A can summarize its own session.
    RegisterSummaryResponse ownSummary = callAsA(() -> service.summarize(sessionId));
    assertThat(ownSummary.sessionId()).isEqualTo(sessionId);
    assertThat(ownSummary.businessId()).isEqualTo(OUTLET_A);

    // Tenant B requests the SAME session id — under B's RLS scope the row is invisible, so it
    // must be rejected exactly as an unknown session id would be, never leaked.
    assertThatThrownBy(() -> callAsB(() -> service.summarize(sessionId)))
        .isInstanceOf(RegisterSessionNotFoundException.class);

    // Tenant A's own read still works after the failed cross-tenant attempt.
    assertThat(callAsA(() -> service.summarize(sessionId)).sessionId()).isEqualTo(sessionId);
  }

  private static <T> T callAsA(Callable<T> action) {
    return callAs(TENANT_A, ACTOR_A, action);
  }

  private static <T> T callAsB(Callable<T> action) {
    return callAs(TENANT_B, ACTOR_B, action);
  }

  private static <T> T callAs(String tenant, String actor, Callable<T> action) {
    try {
      return TenantContext.callAs(tenant, actor, action);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
