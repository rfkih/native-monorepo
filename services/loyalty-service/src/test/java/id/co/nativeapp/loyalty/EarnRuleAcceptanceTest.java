package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.loyalty.earnrule.domain.EarnRuleValidationException;
import id.co.nativeapp.loyalty.earnrule.domain.EarnRuleWriteForbiddenException;
import id.co.nativeapp.loyalty.earnrule.dto.EarnRuleCreateRequest;
import id.co.nativeapp.loyalty.earnrule.dto.EarnRuleResponse;
import id.co.nativeapp.loyalty.earnrule.service.EarnRuleService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Earn-rule CRUD + the owner/manager write-role guard (ADR 0027). Mirrors barbershop's {@code
 * ManualDiscountRoleGuardTest} {@code setRoles} idiom: a {@link MockHttpServletRequest} is bound
 * directly to {@link RequestContextHolder} so {@code config.ActorRolesProvider} reads the simulated
 * {@code X-Roles} gateway header — no MockMvc, no HTTP server.
 */
@SpringBootTest
class EarnRuleAcceptanceTest extends KafkaPostgresTestBase {

  private static final String TENANT = "77777777-7777-7777-7777-777777777777";
  private static final String ACTOR = "actor@example.co.id";

  @Autowired private EarnRuleService earnRuleService;

  @BeforeEach
  void bindMockRequest() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  private void setRoles(String roles) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Roles", roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  private void clearRoles() {
    RequestContextHolder.setRequestAttributes(
        new ServletRequestAttributes(new MockHttpServletRequest()));
  }

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static EarnRuleCreateRequest request() {
    return new EarnRuleCreateRequest(
        100L, // 1% (100 bp)
        10_000_00L, // min sale floor
        "ILLUSTRATIVE_PLACEHOLDER",
        "Illustrative — dev seed",
        null,
        null);
  }

  @Test
  void ownerCanCreateAnEarnRuleAndItIsListed() throws Exception {
    setRoles("owner");
    EarnRuleResponse created =
        TenantContext.callAs(TENANT, ACTOR, () -> earnRuleService.create(request()));

    assertThat(created.pointsPerMinorBp()).isEqualTo(100L);
    assertThat(created.minSaleMinor()).isEqualTo(10_000_00L);
    assertThat(created.provenance()).isEqualTo("ILLUSTRATIVE_PLACEHOLDER");
    assertThat(created.active()).isTrue();

    List<EarnRuleResponse> listed =
        TenantContext.callAs(TENANT, ACTOR, () -> earnRuleService.list(true));
    assertThat(listed).extracting(EarnRuleResponse::id).contains(created.id());
  }

  @Test
  void managerCanCreateAnEarnRule() throws Exception {
    setRoles("manager");
    EarnRuleResponse created =
        TenantContext.callAs(TENANT, ACTOR, () -> earnRuleService.create(request()));
    assertThat(created.id()).isNotNull();
  }

  @Test
  void headerlessCallerCanCreateAnEarnRuleDevRecipeTrust() throws Exception {
    clearRoles();
    EarnRuleResponse created =
        TenantContext.callAs(TENANT, ACTOR, () -> earnRuleService.create(request()));
    assertThat(created.id()).isNotNull();
  }

  @Test
  void cashierIsRejectedFromCreatingAnEarnRule() throws Exception {
    setRoles("cashier");
    assertThatThrownBy(
            () -> TenantContext.callAs(TENANT, ACTOR, () -> earnRuleService.create(request())))
        .isInstanceOf(EarnRuleWriteForbiddenException.class);
  }

  @Test
  void anUnknownProvenanceIsRejected() throws Exception {
    setRoles("owner");
    EarnRuleCreateRequest bad =
        new EarnRuleCreateRequest(100L, null, "NOT_A_REAL_PROVENANCE", "note", null, null);
    assertThatThrownBy(() -> TenantContext.callAs(TENANT, ACTOR, () -> earnRuleService.create(bad)))
        .isInstanceOf(EarnRuleValidationException.class);
  }
}
