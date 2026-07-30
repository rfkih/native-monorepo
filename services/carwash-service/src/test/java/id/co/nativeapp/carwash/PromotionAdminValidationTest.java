package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import id.co.nativeapp.carwash.config.ActorRolesProvider;
import id.co.nativeapp.carwash.promotion.domain.ManualDiscountForbiddenException;
import id.co.nativeapp.carwash.promotion.domain.PromoRuleValidationException;
import id.co.nativeapp.carwash.promotion.dto.CouponCreateRequest;
import id.co.nativeapp.carwash.promotion.dto.PromoRuleCreateRequest;
import id.co.nativeapp.carwash.promotion.dto.PromoRuleResponse;
import id.co.nativeapp.carwash.promotion.repository.CouponRepository;
import id.co.nativeapp.carwash.promotion.repository.PromoRuleRepository;
import id.co.nativeapp.carwash.promotion.service.PromotionAdminReader;
import id.co.nativeapp.carwash.promotion.service.PromotionAdminService;
import id.co.nativeapp.carwash.promotion.service.PromotionAdminWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Pure-unit tests (no Spring context, no database) for {@code PromotionAdminService}/{@code
 * PromotionAdminWriter}: type/parameter mismatches on {@code promo_rule}/{@code coupon} CRUD ({@code
 * PromoRuleValidationException} → 422 via {@code config.PromotionAdvice}) and the owner/manager write
 * guard ({@code ManualDiscountForbiddenException} → 403). Ported from restaurant-service's
 * equivalent suite (verbatim logic; package/import renames only).
 *
 * <p>{@link PromoRuleRepository}/{@link CouponRepository} are Mockito mocks; {@link
 * ActorRolesProvider} is real (no dependencies) with its {@code X-Roles} header simulated via a
 * directly-bound {@link MockHttpServletRequest}, mirroring {@code TicketOutletAccessTest}'s idiom —
 * no MockMvc, no HTTP server needed to prove the guard.
 */
class PromotionAdminValidationTest {

  private static final String TENANT = "77777777-7777-7777-7777-777777777777";
  private static final String ACTOR = "actor@example.co.id";

  private final PromoRuleRepository promoRuleRepository = mock(PromoRuleRepository.class);
  private final CouponRepository couponRepository = mock(CouponRepository.class);
  private final PromotionAdminWriter writer =
      new PromotionAdminWriter(promoRuleRepository, couponRepository);
  private final PromotionAdminReader reader =
      new PromotionAdminReader(promoRuleRepository, couponRepository);
  private final ActorRolesProvider actorRoles = new ActorRolesProvider();
  private final PromotionAdminService service =
      new PromotionAdminService(writer, reader, actorRoles);

  @AfterEach
  void clearRequestContext() {
    RequestContextHolder.resetRequestAttributes();
  }

  private static void setRoles(String roles) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Roles", roles);
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private static PromoRuleCreateRequest ruleRequest(
      String name, String ruleType, String scopeKind, UUID scopeRefId, Long rateBp, Long amountMinor, String currency) {
    return new PromoRuleCreateRequest(
        name, ruleType, scopeKind, scopeRefId, rateBp, amountMinor, currency, null, null, null,
        null, null, null, null, null, LocalDate.of(2026, 1, 1), null);
  }

  // -------------------------------------------------------------------------
  // Type/parameter mismatches -> PromoRuleValidationException
  // -------------------------------------------------------------------------

  @Test
  void percentOffOrderWithoutRateBpIsRejected() {
    PromoRuleCreateRequest req = ruleRequest("10% off", "PERCENT_OFF_ORDER", null, null, null, null, null);
    assertThatThrownBy(() -> writer.createRule(req))
        .isInstanceOf(PromoRuleValidationException.class)
        .hasMessageContaining("rateBp");
  }

  @Test
  void percentOffOrderWithRateBpAboveTenThousandIsRejected() {
    PromoRuleCreateRequest req =
        ruleRequest("110% off", "PERCENT_OFF_ORDER", null, null, 10_001L, null, null);
    assertThatThrownBy(() -> writer.createRule(req))
        .isInstanceOf(PromoRuleValidationException.class)
        .hasMessageContaining("10000");
  }

  @Test
  void amountOffOrderWithoutAmountOrCurrencyIsRejected() {
    PromoRuleCreateRequest req =
        ruleRequest("Rp5000 off", "AMOUNT_OFF_ORDER", null, null, null, null, null);
    assertThatThrownBy(() -> writer.createRule(req))
        .isInstanceOf(PromoRuleValidationException.class)
        .hasMessageContaining("amountMinor");
  }

  @Test
  void amountOffOrderCarryingARateBpIsRejected() {
    PromoRuleCreateRequest req =
        ruleRequest("Rp5000 off", "AMOUNT_OFF_ORDER", null, null, 500L, 5_000L, "IDR");
    assertThatThrownBy(() -> writer.createRule(req))
        .isInstanceOf(PromoRuleValidationException.class)
        .hasMessageContaining("rateBp");
  }

  @Test
  void percentOffLineWithoutScopeIsRejected() {
    PromoRuleCreateRequest req =
        ruleRequest("50% off item", "PERCENT_OFF_LINE", null, null, 5_000L, null, null);
    assertThatThrownBy(() -> writer.createRule(req))
        .isInstanceOf(PromoRuleValidationException.class)
        .hasMessageContaining("scopeKind");
  }

  @Test
  void orderScopeRuleCarryingAScopeIsRejected() {
    PromoRuleCreateRequest req =
        ruleRequest("10% off", "PERCENT_OFF_ORDER", "ITEM", UUID.randomUUID(), 1_000L, null, null);
    assertThatThrownBy(() -> writer.createRule(req))
        .isInstanceOf(PromoRuleValidationException.class)
        .hasMessageContaining("scopeKind");
  }

  @Test
  void schemaReservedBuyXGetYIsRejectedAsUnsupported() {
    PromoRuleCreateRequest req = ruleRequest("BXGY", "BUY_X_GET_Y", null, null, null, null, null);
    assertThatThrownBy(() -> writer.createRule(req))
        .isInstanceOf(PromoRuleValidationException.class)
        .hasMessageContaining("BUY_X_GET_Y is schema-reserved");
  }

  @Test
  void unknownRuleTypeIsRejected() {
    PromoRuleCreateRequest req = ruleRequest("???", "NOT_A_TYPE", null, null, null, null, null);
    assertThatThrownBy(() -> writer.createRule(req)).isInstanceOf(PromoRuleValidationException.class);
  }

  @Test
  void couponLinkedToAnUnknownRuleIsRejected() {
    when(promoRuleRepository.existsById(any())).thenReturn(false);
    CouponCreateRequest req = new CouponCreateRequest("SAVE10", UUID.randomUUID(), 1, null);
    assertThatThrownBy(() -> writer.createCoupon(req))
        .isInstanceOf(PromoRuleValidationException.class)
        .hasMessageContaining("Unknown promo rule");
  }

  // -------------------------------------------------------------------------
  // Owner/manager write guard
  // -------------------------------------------------------------------------

  @Test
  void cashierCannotCreateAPromoRule() {
    setRoles("cashier");
    PromoRuleCreateRequest req =
        ruleRequest("10% off", "PERCENT_OFF_ORDER", null, null, 1_000L, null, null);
    assertThatThrownBy(() -> service.createRule(req))
        .isInstanceOf(ManualDiscountForbiddenException.class);
  }

  @Test
  void ownerCanCreateAPromoRule() throws Exception {
    setRoles("owner");
    when(promoRuleRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    PromoRuleCreateRequest req =
        ruleRequest("10% off", "PERCENT_OFF_ORDER", null, null, 1_000L, null, null);

    PromoRuleResponse response =
        TenantContext.callAs(TENANT, ACTOR, () -> service.createRule(req));

    assertThat(response.name()).isEqualTo("10% off");
    assertThat(response.ruleType()).isEqualTo("PERCENT_OFF_ORDER");
    assertThat(response.rateBp()).isEqualTo(1_000L);
  }

  @Test
  void headerlessCallerCanCreateAPromoRuleDevRecipeTrust() throws Exception {
    // No X-Roles header bound at all — empty-roles-pass (the gateway-less dev recipe / a direct
    // service-layer test).
    when(promoRuleRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
    PromoRuleCreateRequest req =
        ruleRequest("10% off", "PERCENT_OFF_ORDER", null, null, 1_000L, null, null);

    PromoRuleResponse response =
        TenantContext.callAs(TENANT, ACTOR, () -> service.createRule(req));

    assertThat(response.name()).isEqualTo("10% off");
  }

  @Test
  void cashierCannotCreateACoupon() {
    setRoles("cashier");
    CouponCreateRequest req = new CouponCreateRequest("SAVE10", UUID.randomUUID(), 1, null);
    assertThatThrownBy(() -> service.createCoupon(req))
        .isInstanceOf(ManualDiscountForbiddenException.class);
  }

  @Test
  void cashierCanListPromoRules() {
    setRoles("cashier");
    when(promoRuleRepository.findViews(true)).thenReturn(List.of());
    // Reads are ungated (vertical-prefixed, POS-routed — see PromotionAdminController) — must not
    // throw.
    assertThat(service.listRules(true)).isEmpty();
  }
}
