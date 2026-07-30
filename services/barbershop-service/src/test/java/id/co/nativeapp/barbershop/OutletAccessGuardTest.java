package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.barbershop.outletref.domain.OutletNotAssignedException;
import id.co.nativeapp.barbershop.outletref.messaging.UserOutletAssignmentEvent;
import id.co.nativeapp.barbershop.outletref.service.OutletAccessGuard;
import id.co.nativeapp.barbershop.outletref.service.UserOutletAssignmentRefService;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Integration tests for {@link OutletAccessGuard} — ported from carwash-service's {@code
 * OutletAccessGuardTest} (Phase 5 policy), exercising the guard DIRECTLY via a small test-only
 * {@code @Transactional} wrapper bean, mirroring how {@link
 * id.co.nativeapp.barbershop.ticket.service.TicketWriter} calls it (the guard's javadoc requires
 * the caller's transaction to already have the RLS GUC set).
 *
 * <p>Policy under test (signed-off, ported verbatim):
 *
 * <ol>
 *   <li>Owner / manager role → bypass (no assignment required).
 *   <li>Attendant/cashier with an ACTIVE assignment for {@code (user_id, outlet_id)} → allowed.
 *   <li>Attendant/cashier with no active assignment, company has rows (scoping adopted) → {@link
 *       OutletNotAssignedException}.
 *   <li>Attendant/cashier with no assignment, company has ZERO rows (grandfather: scoping never
 *       adopted) → allowed.
 * </ol>
 *
 * <p>The {@link id.co.nativeapp.barbershop.config.ActorRolesProvider} reads the {@code X-Roles}
 * header from the current HTTP request. These tests bind a {@link MockHttpServletRequest} directly
 * to Spring's {@link RequestContextHolder} before each test to simulate the roles header the
 * gateway stamps — the same minimal setup carwash-service's guard test uses: no MockMvc, no HTTP
 * server, no {@code DevTenantFilter}.
 */
@SpringBootTest
class OutletAccessGuardTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String OWNER_ACTOR = "owner@example.co.id";
  private static final String CASHIER_ACTOR = "attendant-01@example.co.id";
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OTHER_BUSINESS_ID =
      UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private GuardInvoker guardInvoker;
  @Autowired private UserOutletAssignmentRefService assignmentService;

  /**
   * A test-only {@code @Transactional} wrapper so calling {@link OutletAccessGuard#enforce} through
   * the Spring proxy engages the auto-RLS aspect — exactly how a real caller (a {@code *Writer}) is
   * required to invoke the guard.
   */
  @TestConfiguration
  static class GuardInvokerConfig {
    @Bean
    GuardInvoker guardInvoker(OutletAccessGuard guard) {
      return new GuardInvoker(guard);
    }
  }

  static class GuardInvoker {
    private final OutletAccessGuard guard;

    GuardInvoker(OutletAccessGuard guard) {
      this.guard = guard;
    }

    // public: a CGLIB transaction proxy must be able to override this method.
    @Transactional
    public void enforce(UUID businessId) {
      guard.enforce(businessId);
    }
  }

  private MockHttpServletRequest mockRequest;

  @BeforeEach
  void bindMockRequest() {
    mockRequest = new MockHttpServletRequest();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
  }

  /**
   * Rebinds a FRESH request carrying the given {@code X-Roles} header. {@link
   * MockHttpServletRequest} headers are additive (a second addHeader does not replace the first),
   * so switching roles mid-test requires a new request bound to the holder.
   */
  private void setRoles(String roles) {
    mockRequest = new MockHttpServletRequest();
    mockRequest.addHeader("X-Roles", roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  private void assignTo(String userId, UUID assignmentId, UUID outletId) {
    UserOutletAssignmentEvent seed =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(),
            assignmentId,
            userId,
            TENANT,
            outletId,
            "ASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(9999, 12, 31).toEpochDay());
    assignmentService.apply(seed);
  }

  private void unassign(String userId, UUID assignmentId, UUID outletId) {
    UserOutletAssignmentEvent seed =
        new UserOutletAssignmentEvent(
            UUID.randomUUID(), // fresh eventId: distinct delivery, not deduped
            assignmentId,
            userId,
            TENANT,
            outletId,
            "UNASSIGNED",
            (int) LocalDate.of(2026, 7, 27).toEpochDay(),
            (int) LocalDate.of(2026, 7, 28).toEpochDay());
    assignmentService.apply(seed);
  }

  /** Runs the guard (via {@link GuardInvoker}) bound to {@link #TENANT} as {@code actor}. */
  private void enforceAs(String actor, UUID businessId) {
    TenantContext.runAs(TENANT, actor, () -> guardInvoker.enforce(businessId));
  }

  // -----------------------------------------------------------------------
  // 1. Owner / manager bypass
  // -----------------------------------------------------------------------

  @Test
  void ownerBypassesTheOutletCheckEvenWhenScopingIsAdopted() {
    // Seed a row for a DIFFERENT user so the company has non-zero scoping state (no grandfather).
    assignTo("other-attendant", UUID.randomUUID(), BUSINESS_ID);

    setRoles("owner");

    assertThatCode(() -> enforceAs(OWNER_ACTOR, BUSINESS_ID)).doesNotThrowAnyException();
  }

  @Test
  void managerBypassesTheOutletCheckEvenWhenScopingIsAdopted() {
    assignTo("other-attendant", UUID.randomUUID(), BUSINESS_ID);

    setRoles("manager");

    assertThatCode(() -> enforceAs(OWNER_ACTOR, BUSINESS_ID)).doesNotThrowAnyException();
  }

  // -----------------------------------------------------------------------
  // 2. Cashier/attendant with active assignment → allowed
  // -----------------------------------------------------------------------

  @Test
  void cashierWithActiveAssignmentIsAllowed() {
    assignTo(CASHIER_ACTOR, UUID.randomUUID(), BUSINESS_ID);

    setRoles("cashier");

    assertThatCode(() -> enforceAs(CASHIER_ACTOR, BUSINESS_ID)).doesNotThrowAnyException();
  }

  // -----------------------------------------------------------------------
  // 3. Cashier not assigned to THIS outlet (but company has rows) → 403
  // -----------------------------------------------------------------------

  @Test
  void cashierNotAssignedToOutletIsRejectedWhenScopingIsAdopted() {
    // Assigned to a DIFFERENT outlet only.
    assignTo(CASHIER_ACTOR, UUID.randomUUID(), OTHER_BUSINESS_ID);

    setRoles("cashier");

    assertThatThrownBy(() -> enforceAs(CASHIER_ACTOR, BUSINESS_ID))
        .isInstanceOf(OutletNotAssignedException.class);
  }

  @Test
  void cashierUnassignedFromOutletIsRejectedWhenScopingIsAdopted() {
    UUID assignmentId = UUID.randomUUID();
    assignTo(CASHIER_ACTOR, assignmentId, BUSINESS_ID);
    unassign(CASHIER_ACTOR, assignmentId, BUSINESS_ID);

    setRoles("cashier");

    assertThatThrownBy(() -> enforceAs(CASHIER_ACTOR, BUSINESS_ID))
        .isInstanceOf(OutletNotAssignedException.class);
  }

  // -----------------------------------------------------------------------
  // 4. Grandfather clause — zero rows in company → allow cashier
  // -----------------------------------------------------------------------

  @Test
  void cashierIsAllowedWhenCompanyHasZeroAssignmentRows() {
    // user_outlet_assignment_ref is empty (truncated in resetTables()) → grandfather applies.
    setRoles("cashier");

    assertThatCode(() -> enforceAs(CASHIER_ACTOR, BUSINESS_ID)).doesNotThrowAnyException();
  }
}
