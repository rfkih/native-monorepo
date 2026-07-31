package id.co.nativeapp.restaurant.selforder;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.config.SelfOrderPrincipal;
import id.co.nativeapp.restaurant.config.SelfOrderTokenFilter;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderAccess;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderTokenCodec;
import id.co.nativeapp.restaurant.selforderaccess.domain.SelfOrderTokenPayload;
import id.co.nativeapp.restaurant.selforderaccess.dto.SelfOrderAccessResponse;
import id.co.nativeapp.restaurant.selforderaccess.service.SelfOrderAccessReader;
import id.co.nativeapp.restaurant.selforderaccess.service.SelfOrderAccessService;
import id.co.nativeapp.restaurant.table.dto.CreateTableRequest;
import id.co.nativeapp.restaurant.table.service.TableService;
import id.co.nativeapp.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The {@code SelfOrderTokenFilter} matrix (Phase 6, ADR 0029): the ONLY authentication step the
 * anonymous {@code /api/v1/self-order/**} surface sees.
 *
 * <p>Tokens are minted through the REAL management path ({@link SelfOrderAccessService}, called
 * with an EMPTY role set — the guard's documented empty-roles-pass semantics, so no {@code X-Roles}
 * header setup is needed), so this test exercises the exact same {@link SelfOrderTokenCodec}
 * encode/verify round trip production traffic does. The filter is instantiated directly (not via
 * the {@code FilterRegistrationBean}) since its constructor takes only the autowired {@link
 * SelfOrderAccessReader} — a plain unit-level invocation of {@code doFilter} is the most direct way
 * to assert the TenantContext/{@link SelfOrderPrincipal} binding a passing request produces.
 */
@SpringBootTest
class SelfOrderTokenFilterTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final UUID OUTLET_A = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID OUTLET_B = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final String OWNER_ACTOR = "owner@example.co.id";

  @Autowired private SelfOrderAccessReader reader;
  @Autowired private SelfOrderAccessService selfOrderAccessService;
  @Autowired private TableService tableService;
  @Autowired private MenuService menuService;

  private SelfOrderTokenFilter filter() {
    return new SelfOrderTokenFilter(reader);
  }

  @Test
  void missingTokenHeaderIs401() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/self-order/menu");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter().doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  @Test
  void garbageTokenIs401() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/self-order/menu");
    request.addHeader(SelfOrderTokenFilter.TOKEN_HEADER, "not-a-real-token");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter().doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  @Test
  void validTableTokenBindsTenantAndPrincipalAndReachesTheMenu() throws Exception {
    createMenuItem(OUTLET_A, TENANT_A, "Kopi Susu");
    seedTable(TENANT_A, OUTLET_A, "T1");
    SelfOrderAccessResponse access = getActive(TENANT_A, OUTLET_A);
    String tableToken =
        access.tables().stream()
            .filter(t -> "T1".equals(t.tableLabel()))
            .findFirst()
            .orElseThrow()
            .token();

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/self-order/menu");
    request.addHeader(SelfOrderTokenFilter.TOKEN_HEADER, tableToken);
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter()
        .doFilter(
            request,
            response,
            (req, res) -> {
              // Inside the chain: TenantContext + SelfOrderPrincipal are bound, exactly as the
              // real controller would see them.
              assertThat(TenantContext.currentCompanyId()).contains(TENANT_A);
              assertThat(TenantContext.currentActor()).contains("self-order:T1");
              SelfOrderPrincipal principal =
                  (SelfOrderPrincipal) req.getAttribute(SelfOrderPrincipal.REQUEST_ATTRIBUTE);
              assertThat(principal.businessId()).isEqualTo(OUTLET_A);
              assertThat(principal.tableLabel()).isEqualTo("T1");
              assertThat(menuService.findActiveByBusiness(OUTLET_A)).hasSize(1);
            });

    // No error status was ever written — the mock response defaults to 200.
    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
  }

  @Test
  void kioskTokenBindsANullTableLabelAndAKioskActor() throws Exception {
    seedTable(TENANT_A, OUTLET_A, "T1");
    SelfOrderAccessResponse access = getActive(TENANT_A, OUTLET_A);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/self-order/menu");
    request.addHeader(SelfOrderTokenFilter.TOKEN_HEADER, access.kioskToken());
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter()
        .doFilter(
            request,
            response,
            (req, res) -> {
              assertThat(TenantContext.currentActor()).contains("self-order:kiosk");
              SelfOrderPrincipal principal =
                  (SelfOrderPrincipal) req.getAttribute(SelfOrderPrincipal.REQUEST_ATTRIBUTE);
              assertThat(principal.tableLabel()).isNull();
            });
    assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
  }

  @Test
  void retiredKidAfterRotationIs401() throws Exception {
    seedTable(TENANT_A, OUTLET_A, "T1");
    String staleKioskToken = getActive(TENANT_A, OUTLET_A).kioskToken();

    // Rotate: retires the row the stale token's kid points to.
    TenantContext.callAs(TENANT_A, OWNER_ACTOR, () -> selfOrderAccessService.rotate(OUTLET_A));

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/self-order/menu");
    request.addHeader(SelfOrderTokenFilter.TOKEN_HEADER, staleKioskToken);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter().doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  /**
   * "A token for outlet B used on outlet A's data" — the LOOKUP KEY, not merely the signature, is
   * what enforces isolation: a payload claiming {@code outletId = OUTLET_A} but carrying OUTLET_B's
   * {@code kid}, VALIDLY signed with OUTLET_B's REAL secret (recovered here only because the test
   * has white-box repository access an attacker never would — see {@link
   * SelfOrderAccess#getSecretPlaintext()} javadoc: "never returned by any API"), still finds NO
   * row: {@code findByOutletIdAndKidAndStatus(OUTLET_A, kidB, ACTIVE)} matches nothing, because
   * OUTLET_B's kid was never registered under OUTLET_A. So even a cryptographically PERFECT forgery
   * of this shape is rejected — the isolation does not rely on an attacker's inability to sign.
   */
  @Test
  void tokenForOutletBSplicedOntoOutletAClaimIsRejectedEvenWithAValidSignature() throws Exception {
    seedTable(TENANT_A, OUTLET_A, "T1");
    seedTable(TENANT_A, OUTLET_B, "T1");
    // Auto-provisions OUTLET_B's self_order_access row.
    getActive(TENANT_A, OUTLET_B);
    SelfOrderAccess rowB =
        TenantContext.callAs(TENANT_A, OWNER_ACTOR, () -> reader.findActive(OUTLET_B))
            .orElseThrow();
    byte[] secretBBytes = java.util.Base64.getDecoder().decode(rowB.getSecretPlaintext());

    // A payload that claims OUTLET_A but carries OUTLET_B's kid, VALIDLY signed with OUTLET_B's
    // real secret.
    String forgedToken =
        SelfOrderTokenCodec.encode(
            new SelfOrderTokenPayload(
                SelfOrderTokenPayload.CURRENT_VERSION,
                rowB.getKid(),
                TENANT_A,
                OUTLET_A,
                OUTLET_A,
                null),
            secretBBytes);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/self-order/menu");
    request.addHeader(SelfOrderTokenFilter.TOKEN_HEADER, forgedToken);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter().doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  /**
   * The RLS/tenancy proof: company A's outlet/kid is real, but the payload's {@code companyId}
   * claim is forged to company B. Verified against company B's RLS scope, company A's row is
   * invisible (RLS keys strictly on {@code company_id}), so no row is found — even though the
   * {@code (outletId, kid)} pair is completely genuine under its REAL owner, company A. This is the
   * "company B's data never readable with company A's token" guarantee: a forged tenant claim can
   * never smuggle a lookup into a foreign company's scope.
   */
  @Test
  void aTokenWithAForgedCompanyIdClaimNeverResolvesUnderTheForeignTenantsRls() throws Exception {
    seedTable(TENANT_A, OUTLET_A, "T1");
    SelfOrderAccessResponse accessA = getActive(TENANT_A, OUTLET_A);
    SelfOrderTokenPayload realPayloadA =
        SelfOrderTokenCodec.decodePayloadUnverified(accessA.kioskToken());
    SelfOrderAccess rowA =
        TenantContext.callAs(TENANT_A, OWNER_ACTOR, () -> reader.findActive(OUTLET_A))
            .orElseThrow();
    byte[] secretABytes = java.util.Base64.getDecoder().decode(rowA.getSecretPlaintext());

    String tenantB = "44444444-4444-4444-4444-444444444444";
    // Same real (outletId, kid), validly signed with the REAL secret — only the companyId claim
    // is forged to a foreign tenant.
    String forgedToken =
        SelfOrderTokenCodec.encode(
            new SelfOrderTokenPayload(
                SelfOrderTokenPayload.CURRENT_VERSION,
                realPayloadA.kid(),
                tenantB,
                OUTLET_A,
                OUTLET_A,
                null),
            secretABytes);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/self-order/menu");
    request.addHeader(SelfOrderTokenFilter.TOKEN_HEADER, forgedToken);
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter().doFilter(request, response, chainThatSets(chainCalled));

    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
  }

  private FilterChain chainThatSets(AtomicBoolean flag) {
    return (req, res) -> flag.set(true);
  }

  private void seedTable(String companyId, UUID outletId, String label) throws Exception {
    TenantContext.callAs(
        companyId,
        OWNER_ACTOR,
        () -> tableService.create(new CreateTableRequest(outletId, label, 4, null)));
  }

  private void createMenuItem(UUID outletId, String companyId, String name) throws Exception {
    TenantContext.callAs(
        companyId,
        OWNER_ACTOR,
        () ->
            menuService.createItem(
                new CreateMenuItemRequest(outletId, name, "BEVERAGE", 18_000L, "IDR")));
  }

  private SelfOrderAccessResponse getActive(String companyId, UUID outletId) throws Exception {
    return TenantContext.callAs(
        companyId, OWNER_ACTOR, () -> selfOrderAccessService.getActive(outletId));
  }
}
