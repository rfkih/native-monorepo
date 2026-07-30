package id.co.nativeapp.restaurant.promotion.service;

import id.co.nativeapp.restaurant.promotion.dto.CouponResponse;
import id.co.nativeapp.restaurant.promotion.dto.PromoRuleResponse;
import id.co.nativeapp.restaurant.promotion.projection.CouponView;
import id.co.nativeapp.restaurant.promotion.projection.PromoRuleView;
import id.co.nativeapp.restaurant.promotion.repository.CouponRepository;
import id.co.nativeapp.restaurant.promotion.repository.PromoRuleRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional(readOnly = true)} promotion admin list reads. A distinct bean from
 * {@link PromotionAdminWriter} and {@link PromotionAdminService} so it is invoked through the Spring
 * proxy — the {@code @Transactional} advice and the {@code RlsAutoApplyAspect} that binds the tenant
 * GUC only apply through the proxy (the {@code CatalogReader} pattern).
 */
@Component
public class PromotionAdminReader {

  private final PromoRuleRepository promoRuleRepository;
  private final CouponRepository couponRepository;

  public PromotionAdminReader(PromoRuleRepository promoRuleRepository, CouponRepository couponRepository) {
    this.promoRuleRepository = promoRuleRepository;
    this.couponRepository = couponRepository;
  }

  @Transactional(readOnly = true)
  public List<PromoRuleResponse> listRules(boolean activeOnly) {
    return promoRuleRepository.findViews(activeOnly).stream().map(PromotionAdminReader::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public List<CouponResponse> listCoupons(boolean activeOnly) {
    return couponRepository.findViews(activeOnly).stream().map(PromotionAdminReader::toResponse).toList();
  }

  private static PromoRuleResponse toResponse(PromoRuleView v) {
    return new PromoRuleResponse(
        v.getId(),
        v.getName(),
        v.getRuleType(),
        v.getScopeKind(),
        v.getScopeRefId(),
        v.getRateBp(),
        v.getAmountMinor(),
        v.getCurrency() == null ? null : v.getCurrency().strip(),
        v.getMinSubtotalMinor(),
        v.getDowMask(),
        v.getWindowStart(),
        v.getWindowEnd(),
        v.getTz(),
        v.getPriority(),
        v.isExclusive(),
        v.isRequiresCoupon(),
        v.isActive(),
        v.getEffectiveFrom(),
        v.getEffectiveTo());
  }

  private static CouponResponse toResponse(CouponView v) {
    return new CouponResponse(
        v.getId(), v.getCode(), v.getRuleId(), v.getMaxRedemptions(), v.getRedeemedCount(), v.getExpiresAt(), v.isActive());
  }
}
