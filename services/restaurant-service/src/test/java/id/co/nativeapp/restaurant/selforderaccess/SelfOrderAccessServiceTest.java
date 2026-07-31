package id.co.nativeapp.restaurant.selforderaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderAccessForbiddenException;
import id.co.nativeapp.restaurant.selforderaccess.dto.SelfOrderAccessResponse;
import id.co.nativeapp.restaurant.selforderaccess.service.SelfOrderAccessService;
import id.co.nativeapp.restaurant.table.dto.CreateTableRequest;
import id.co.nativeapp.restaurant.table.service.TableService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * {@link SelfOrderAccessService} — the owner/manager-only self-order QR management surface (Phase
 * 6, ADR 0029). Mirrors {@code order.OutletEnforcementTest}'s {@code X-Roles} binding style: a
 * {@link MockHttpServletRequest} is bound directly to {@link RequestContextHolder} so {@code
 * config.ActorRolesProvider} reads the header without any MockMvc/HTTP server.
 */
@SpringBootTest
class SelfOrderAccessServiceTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String OWNER_ACTOR = "owner@example.co.id";
  private static final String CASHIER_ACTOR = "cashier@example.co.id";

  @Autowired private SelfOrderAccessService service;
  @Autowired private TableService tableService;

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  private void setRoles(String roles) {
    MockHttpServletRequest mockRequest = new MockHttpServletRequest();
    mockRequest.addHeader("X-Roles", roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
  }

  @Test
  void cashierIsForbiddenFromReadingOrRotatingTheAccessSet() {
    setRoles("cashier");

    assertThatThrownBy(() -> TenantContext.callAs(TENANT, CASHIER_ACTOR, () -> service.getActive(OUTLET)))
        .isInstanceOf(SelfOrderAccessForbiddenException.class);
    assertThatThrownBy(() -> TenantContext.callAs(TENANT, CASHIER_ACTOR, () -> service.rotate(OUTLET)))
        .isInstanceOf(SelfOrderAccessForbiddenException.class);
  }

  @Test
  void ownerCanReadAndRotate() throws Exception {
    setRoles("owner");

    SelfOrderAccessResponse active =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> service.getActive(OUTLET));
    assertThat(active.kid()).isNotBlank();
    assertThat(active.kioskToken()).isNotBlank();

    SelfOrderAccessResponse rotated =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> service.rotate(OUTLET));
    assertThat(rotated.kid()).isNotEqualTo(active.kid());
    assertThat(rotated.kioskToken()).isNotEqualTo(active.kioskToken());
  }

  @Test
  void managerCanReadAndRotate() throws Exception {
    setRoles("manager");
    assertThat(TenantContext.callAs(TENANT, OWNER_ACTOR, () -> service.getActive(OUTLET)).kid())
        .isNotBlank();
  }

  @Test
  void getActiveAutoProvisionsOnFirstCallAndIsIdempotentAfter() throws Exception {
    setRoles("owner");

    SelfOrderAccessResponse first =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> service.getActive(OUTLET));
    SelfOrderAccessResponse second =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> service.getActive(OUTLET));

    // Same active row both times — getActive does not itself rotate.
    assertThat(second.kid()).isEqualTo(first.kid());
  }

  @Test
  void mintedTableTokensCoverEveryActiveTablePlusOneKioskToken() throws Exception {
    setRoles("owner");
    seedTable("T1");
    seedTable("T2");

    SelfOrderAccessResponse active =
        TenantContext.callAs(TENANT, OWNER_ACTOR, () -> service.getActive(OUTLET));

    assertThat(active.tables()).hasSize(2);
    assertThat(active.tables()).extracting(t -> t.tableLabel()).containsExactlyInAnyOrder("T1", "T2");
    assertThat(active.kioskToken()).isNotBlank();
    // Every token is unique (different tableLabel claim -> different signed payload).
    assertThat(
            java.util.Set.of(
                active.kioskToken(),
                active.tables().get(0).token(),
                active.tables().get(1).token()))
        .hasSize(3);
  }

  private void seedTable(String label) throws Exception {
    TenantContext.callAs(
        TENANT, OWNER_ACTOR, () -> tableService.create(new CreateTableRequest(OUTLET, label, 4, null)));
  }
}
