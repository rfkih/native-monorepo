package id.co.nativeapp.restaurant.promotion.service;

import id.co.nativeapp.restaurant.config.ActorRolesProvider;
import id.co.nativeapp.restaurant.promotion.domain.ManualDiscountForbiddenException;
import id.co.nativeapp.restaurant.promotion.dto.CouponCreateRequest;
import id.co.nativeapp.restaurant.promotion.dto.CouponPatchRequest;
import id.co.nativeapp.restaurant.promotion.dto.CouponResponse;
import id.co.nativeapp.restaurant.promotion.dto.PromoRuleCreateRequest;
import id.co.nativeapp.restaurant.promotion.dto.PromoRulePatchRequest;
import id.co.nativeapp.restaurant.promotion.dto.PromoRuleResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates promotion admin CRUD (rules + coupons) for {@code PromotionAdminController}.
 *
 * <p><strong>Writes require {@code owner}/{@code manager}; reads are ungated.</strong> A promo rule
 * or coupon is money-routing configuration (it decides what a checkout discounts and by how much),
 * so — unlike restaurant-menu prices, which stay cashier-editable at POS parity — every
 * create/patch is held to the same owner/manager bar as {@code ManualDiscountGuard} and carwash's
 * {@code requireStaffWriteRole} (empty-roles-pass semantics: a headerless call is the gateway-less
 * dev recipe / a direct service-layer test, not a real cashier token).
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
