package id.co.nativeapp.restaurant.selforder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRedisTestBase;
import id.co.nativeapp.restaurant.config.SelfOrderPrincipal;
import id.co.nativeapp.restaurant.entitlement.dto.EntitlementProjectedEvent;
import id.co.nativeapp.restaurant.entitlement.service.EntitlementProjectionService;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.selforder.domain.SelfOrderCapExceededException;
import id.co.nativeapp.restaurant.selforder.dto.SelfOrderCreateRequest;
import id.co.nativeapp.restaurant.selforder.service.SelfOrderService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The unconfirmed self-order cap (Phase 6, ADR 0029): once an outlet's {@code PARKED SELF_ORDER}
 * count is at/over {@code native.self-order.park-cap}, a further create is rejected {@code 429}
 * ({@link SelfOrderCapExceededException}) — bounds the junk-row blast radius of an abused/leaked QR.
 * {@code park-cap} is overridden to {@code 2} for this class ONLY (a new {@code
 * @DynamicPropertySource} key the shared bases never touch, so no collision) so the test does not
 * need to create 50 real rows to prove the boundary.
 */
@SpringBootTest
class SelfOrderCapTest extends PostgresRedisTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String SELF_ORDER = "self_order";
  private static final String OWNER_ACTOR = "owner@example.co.id";
  private static final int PARK_CAP = 2;

  @DynamicPropertySource
  static void parkCapProperty(DynamicPropertyRegistry registry) {
    registry.add("native.self-order.park-cap", () -> PARK_CAP);
  }

  @Autowired private MenuService menuService;
  @Autowired private SelfOrderService selfOrderService;
  @Autowired private EntitlementProjectionService entitlementProjectionService;

  @BeforeEach
  void bindSelfOrderPrincipal() {
    MockHttpServletRequest mockRequest = new MockHttpServletRequest();
    mockRequest.setAttribute(
        SelfOrderPrincipal.REQUEST_ATTRIBUTE, new SelfOrderPrincipal(OUTLET, OUTLET, "T1"));
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(mockRequest));
  }

  @Test
  void theCapPlusOnethCreateIsRejectedWith429() throws Exception {
    entitlementProjectionService.apply(
        new EntitlementProjectedEvent(UUID.randomUUID(), TENANT, SELF_ORDER, true));
    UUID itemId =
        TenantContext.callAs(
                TENANT,
                OWNER_ACTOR,
                () ->
                    menuService.createItem(
                        new CreateMenuItemRequest(OUTLET, "Es Teh", "DRINK", 8_000L, "IDR")))
            .id();

    // Fill the outlet up to the cap — each succeeds.
    for (int i = 0; i < PARK_CAP; i++) {
      OrderResponse created = create(itemId, "k-fill-" + i);
      assertThat(created.status()).isEqualTo("PARKED");
    }

    // One more, over the cap, is rejected.
    assertThatThrownBy(() -> create(itemId, "k-over-cap"))
        .isInstanceOf(SelfOrderCapExceededException.class)
        .satisfies(
            ex -> {
              SelfOrderCapExceededException capEx = (SelfOrderCapExceededException) ex;
              assertThat(capEx.getBusinessId()).isEqualTo(OUTLET);
              assertThat(capEx.getUnconfirmedCount()).isEqualTo(PARK_CAP);
              assertThat(capEx.getCap()).isEqualTo(PARK_CAP);
            });
  }

  private OrderResponse create(UUID itemId, String idempotencyKey) throws Exception {
    return TenantContext.callAs(
        TENANT,
        "self-order:T1",
        () ->
            selfOrderService.createOrder(
                new SelfOrderCreateRequest(
                    idempotencyKey, List.of(new OrderLineRequest(itemId, 1)))));
  }
}
