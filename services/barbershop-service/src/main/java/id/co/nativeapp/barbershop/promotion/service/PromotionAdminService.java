package id.co.nativeapp.barbershop.promotion.service;

import id.co.nativeapp.barbershop.config.ActorRolesProvider;
import id.co.nativeapp.barbershop.promotion.domain.ManualDiscountForbiddenException;
import id.co.nativeapp.barbershop.promotion.dto.CouponCreateRequest;
import id.co.nativeapp.barbershop.promotion.dto.CouponPatchRequest;
import id.co.nativeapp.barbershop.promotion.dto.CouponResponse;
import id.co.nativeapp.barbershop.promotion.dto.PromoRuleCreateRequest;
import id.co.nativeapp.barbershop.promotion.dto.PromoRulePatchRequest;
import id.co.nativeapp.barbershop.promotion.dto.PromoRuleResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates promotion admin CRUD (rules + coupons) for {@code PromotionAdminController}. Ported
 * verbatim from restaurant-service via carwash-service (ADR 0026).
 *
 * <p><strong>Writes require {@code owner}/{@code manager}; reads are ungated.</strong> A promo rule
 * or coupon is money-routing configuration (it decides what a checkout discounts and by how much),
 * so every create/patch is held to the same owner/manager bar as {@link ManualDiscountGuard} and
 * barbershop's {@code requireStaffWriteRole} (empty-roles-pass semantics: a headerless call is the
 * gateway-less dev recipe / a direct service-layer test, not a real cashier/barber token).
 *
 * <p><strong>Vertical-prefixed routing (difference from restaurant).</strong> Restaurant's admin
 * endpoints ride the DASHBOARD gateway route (owner/manager enforced at the gateway too).
 * Barbershop's {@code /api/v1/barbershop/promotions[/coupons]} endpoints ride the existing POS-roles
 * gateway route instead (see {@code PromotionAdminController} class javadoc) — this service-side
 * write guard is therefore the ONLY guard for a write; reads stay open to any authenticated POS
 * caller (a barber/cashier sees the rule/coupon catalog, acceptable — they already see prices).
 *
 * <p>Not itself {@code @Transactional} — transactional units live in {@link PromotionAdminWriter}
 * (writes) and {@link PromotionAdminReader} (reads) so the proxy and the RLS aspect engage.
 */
@Service
public class PromotionAdminService {

  private final PromotionAdminWriter writer;
  private final PromotionAdminReader reader;
  private final ActorRolesProvider actorRoles;

  public PromotionAdminService(
      PromotionAdminWriter writer, PromotionAdminReader reader, ActorRolesProvider actorRoles) {
    this.writer = writer;
    this.reader = reader;
    this.actorRoles = actorRoles;
  }

  public PromoRuleResponse createRule(PromoRuleCreateRequest request) {
    requireWriteRole();
    return writer.createRule(request);
  }

  public PromoRuleResponse patchRule(UUID id, PromoRulePatchRequest request) {
    requireWriteRole();
    return writer.patchRule(id, request);
  }

  public List<PromoRuleResponse> listRules(boolean activeOnly) {
    return reader.listRules(activeOnly);
  }

  public CouponResponse createCoupon(CouponCreateRequest request) {
    requireWriteRole();
    return writer.createCoupon(request);
  }

  public CouponResponse patchCoupon(UUID id, CouponPatchRequest request) {
    requireWriteRole();
    return writer.patchCoupon(id, request);
  }

  public List<CouponResponse> listCoupons(boolean activeOnly) {
    return reader.listCoupons(activeOnly);
  }

  /**
   * Empty-roles-pass semantics (see class javadoc): an EMPTY role set is let through; a non-empty
   * set that is neither {@code owner} nor {@code manager} is denied.
   */
  private void requireWriteRole() {
    if (!actorRoles.currentRoles().isEmpty() && !actorRoles.isOwnerOrManager()) {
      throw new ManualDiscountForbiddenException(
          "Promotion rule/coupon writes require the owner or manager role");
    }
  }
}
