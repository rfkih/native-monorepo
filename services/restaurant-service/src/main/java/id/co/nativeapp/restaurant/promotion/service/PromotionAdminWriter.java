package id.co.nativeapp.restaurant.promotion.service;

import id.co.nativeapp.restaurant.promotion.domain.Coupon;
import id.co.nativeapp.restaurant.promotion.domain.PromoRule;
import id.co.nativeapp.restaurant.promotion.domain.PromoRuleType;
import id.co.nativeapp.restaurant.promotion.domain.PromoRuleValidationException;
import id.co.nativeapp.restaurant.promotion.domain.PromoScopeKind;
import id.co.nativeapp.restaurant.promotion.dto.CouponCreateRequest;
import id.co.nativeapp.restaurant.promotion.dto.CouponPatchRequest;
import id.co.nativeapp.restaurant.promotion.dto.CouponResponse;
import id.co.nativeapp.restaurant.promotion.dto.PromoRuleCreateRequest;
import id.co.nativeapp.restaurant.promotion.dto.PromoRulePatchRequest;
import id.co.nativeapp.restaurant.promotion.dto.PromoRuleResponse;
import id.co.nativeapp.restaurant.promotion.repository.CouponRepository;
import id.co.nativeapp.restaurant.promotion.repository.PromoRuleRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write units of work for the promotion admin feature (rules +
 * coupons). A distinct bean from {@link PromotionAdminService} so each transactional method is
 * invoked through the Spring proxy — the {@code @Transactional} advice and the {@code
 * RlsAutoApplyAspect} that sets the tenant GUC only engage through the proxy (the {@code
 * OrderWriter}/{@code CatalogWriter} pattern).
 *
 * <p>The write path loads/produces the FULL entity — it legitimately needs the whole aggregate to
 * mutate and persist, and the freshly-saved entity maps directly to the response, never a
 * projection (that pattern is reserved for reads).
 */
@Component
public class PromotionAdminWriter {

  private final PromoRuleRepository promoRuleRepository;
  private final CouponRepository couponRepository;

  public PromotionAdminWriter(
      PromoRuleRepository promoRuleRepository, CouponRepository couponRepository) {
    this.promoRuleRepository = promoRuleRepository;
    this.couponRepository = couponRepository;
  }

  // -------------------------------------------------------------------------
  // promo_rule
  // -------------------------------------------------------------------------

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PromoRuleResponse createRule(PromoRuleCreateRequest request) {
    PromoRuleType ruleType = parseRuleType(request.ruleType());
    PromoScopeKind scopeKind = parseScopeKind(request.scopeKind());
    validateConsistency(
        ruleType,
        scopeKind,
        request.scopeRefId(),
        request.rateBp(),
        request.amountMinor(),
        request.currency());

    PromoRule rule =
        new PromoRule(
            request.name(),
            ruleType,
            scopeKind,
            request.scopeRefId(),
            request.rateBp(),
            request.amountMinor(),
            request.currency(),
            request.minSubtotalMinor(),
            request.dowMask(),
            request.windowStart(),
            request.windowEnd(),
            request.tz(),
            request.priority(),
            Boolean.TRUE.equals(request.exclusive()),
            Boolean.TRUE.equals(request.requiresCoupon()),
            request.effectiveFrom() == null ? LocalDate.now() : request.effectiveFrom(),
            request.effectiveTo());
    rule.setCompanyId(TenantContext.require().companyId());
    return toResponse(promoRuleRepository.saveAndFlush(rule));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PromoRuleResponse patchRule(UUID id, PromoRulePatchRequest request) {
    PromoRule rule =
        promoRuleRepository
            .findById(id)
            .orElseThrow(() -> new PromoRuleValidationException("Unknown promo rule: " + id));

    if (request.name() != null) {
      rule.rename(request.name());
    }
    PromoScopeKind newScopeKind =
        request.scopeKind() != null ? parseScopeKind(request.scopeKind()) : rule.getScopeKind();
    UUID newScopeRefId = request.scopeRefId() != null ? request.scopeRefId() : rule.getScopeRefId();
    if (request.scopeKind() != null || request.scopeRefId() != null) {
      rule.rescope(newScopeKind, newScopeRefId);
    }
    Long newRateBp = request.rateBp() != null ? request.rateBp() : rule.getRateBp();
    Long newAmountMinor =
        request.amountMinor() != null ? request.amountMinor() : rule.getAmountMinor();
    String newCurrency = request.currency() != null ? request.currency() : rule.getCurrency();
    if (request.rateBp() != null || request.amountMinor() != null || request.currency() != null) {
      rule.reprice(newRateBp, newAmountMinor, newCurrency);
    }
    if (request.minSubtotalMinor() != null) {
      rule.setMinSubtotalMinor(request.minSubtotalMinor());
    }
    if (request.dowMask() != null || request.windowStart() != null || request.windowEnd() != null) {
      rule.setHappyHourWindow(
          request.dowMask() != null ? request.dowMask() : rule.getDowMask(),
          request.windowStart() != null ? request.windowStart() : rule.getWindowStart(),
          request.windowEnd() != null ? request.windowEnd() : rule.getWindowEnd());
    }
    if (request.tz() != null) {
      rule.setTz(request.tz());
    }
    if (request.priority() != null) {
      rule.setPriority(request.priority());
    }
    if (request.exclusive() != null) {
      rule.setExclusive(request.exclusive());
    }
    if (request.active() != null) {
      rule.setActive(request.active());
    }
    if (request.effectiveFrom() != null || request.effectiveTo() != null) {
      rule.setEffectiveWindow(
          request.effectiveFrom() != null ? request.effectiveFrom() : rule.getEffectiveFrom(),
          request.effectiveTo() != null ? request.effectiveTo() : rule.getEffectiveTo());
    }

    validateConsistency(
        rule.getRuleType(),
        rule.getScopeKind(),
        rule.getScopeRefId(),
        rule.getRateBp(),
        rule.getAmountMinor(),
        rule.getCurrency());

    return toResponse(promoRuleRepository.saveAndFlush(rule));
  }

  // -------------------------------------------------------------------------
  // coupon
  // -------------------------------------------------------------------------

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CouponResponse createCoupon(CouponCreateRequest request) {
    if (!promoRuleRepository.existsById(request.ruleId())) {
      throw new PromoRuleValidationException("Unknown promo rule: " + request.ruleId());
    }
    String code = request.code().strip().toUpperCase(Locale.ROOT);
    int maxRedemptions = request.maxRedemptions() == null ? 1 : request.maxRedemptions();
    Coupon coupon = new Coupon(code, request.ruleId(), maxRedemptions, request.expiresAt());
    coupon.setCompanyId(TenantContext.require().companyId());
    return toResponse(couponRepository.saveAndFlush(coupon));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CouponResponse patchCoupon(UUID id, CouponPatchRequest request) {
    Coupon coupon =
        couponRepository
            .findById(id)
            .orElseThrow(() -> new PromoRuleValidationException("Unknown coupon: " + id));

    if (request.maxRedemptions() != null) {
      if (request.maxRedemptions() < coupon.getRedeemedCount()) {
        throw new PromoRuleValidationException(
            "maxRedemptions ("
                + request.maxRedemptions()
                + ") cannot be lower than the current redeemedCount ("
                + coupon.getRedeemedCount()
                + ")");
      }
      coupon.setMaxRedemptions(request.maxRedemptions());
    }
    if (request.expiresAt() != null) {
      coupon.setExpiresAt(request.expiresAt());
    }
    if (request.active() != null) {
      coupon.setActive(request.active());
    }
    return toResponse(couponRepository.saveAndFlush(coupon));
  }

  // -------------------------------------------------------------------------
  // Validation
  // -------------------------------------------------------------------------

  private static PromoRuleType parseRuleType(String raw) {
    try {
      return PromoRuleType.valueOf(raw);
    } catch (IllegalArgumentException ex) {
      throw new PromoRuleValidationException(
          "Unknown or unsupported rule_type: "
              + raw
              + " — supported: PERCENT_OFF_ORDER, AMOUNT_OFF_ORDER, PERCENT_OFF_LINE"
              + " (BUY_X_GET_Y is schema-reserved, not shipped this phase)");
    }
  }

  private static PromoScopeKind parseScopeKind(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return PromoScopeKind.valueOf(raw);
    } catch (IllegalArgumentException ex) {
      throw new PromoRuleValidationException(
          "Unknown scope_kind: " + raw + " — supported: ITEM, CATEGORY");
    }
  }

  private static void validateConsistency(
      PromoRuleType ruleType,
      PromoScopeKind scopeKind,
      UUID scopeRefId,
      Long rateBp,
      Long amountMinor,
      String currency) {
    switch (ruleType) {
      case PERCENT_OFF_ORDER, PERCENT_OFF_LINE -> {
        if (rateBp == null) {
          throw new PromoRuleValidationException(ruleType + " requires rateBp");
        }
        if (rateBp < 0 || rateBp > 10_000) {
          throw new PromoRuleValidationException(
              "rateBp must be between 0 and 10000, got: " + rateBp);
        }
        if (amountMinor != null || currency != null) {
          throw new PromoRuleValidationException(ruleType + " must not carry amountMinor/currency");
        }
      }
      case AMOUNT_OFF_ORDER -> {
        if (amountMinor == null) {
          throw new PromoRuleValidationException("AMOUNT_OFF_ORDER requires amountMinor");
        }
        if (amountMinor < 0) {
          throw new PromoRuleValidationException("amountMinor must be >= 0, got: " + amountMinor);
        }
        if (currency == null || currency.isBlank()) {
          throw new PromoRuleValidationException("AMOUNT_OFF_ORDER requires currency");
        }
        if (rateBp != null) {
          throw new PromoRuleValidationException("AMOUNT_OFF_ORDER must not carry rateBp");
        }
      }
      default -> throw new PromoRuleValidationException("Unsupported rule_type: " + ruleType);
    }

    if (ruleType == PromoRuleType.PERCENT_OFF_LINE) {
      if (scopeKind == null || scopeRefId == null) {
        throw new PromoRuleValidationException(
            "PERCENT_OFF_LINE requires scopeKind and scopeRefId");
      }
    } else if (scopeKind != null || scopeRefId != null) {
      throw new PromoRuleValidationException(
          ruleType + " must not carry scopeKind/scopeRefId (order-scope)");
    }
  }

  // -------------------------------------------------------------------------
  // Mapping
  // -------------------------------------------------------------------------

  private static PromoRuleResponse toResponse(PromoRule rule) {
    return new PromoRuleResponse(
        rule.getId(),
        rule.getName(),
        rule.getRuleType().name(),
        rule.getScopeKind() == null ? null : rule.getScopeKind().name(),
        rule.getScopeRefId(),
        rule.getRateBp(),
        rule.getAmountMinor(),
        rule.getCurrency(),
        rule.getMinSubtotalMinor(),
        rule.getDowMask(),
        rule.getWindowStart(),
        rule.getWindowEnd(),
        rule.getTz(),
        rule.getPriority(),
        rule.isExclusive(),
        rule.isRequiresCoupon(),
        rule.isActive(),
        rule.getEffectiveFrom(),
        rule.getEffectiveTo());
  }

  private static CouponResponse toResponse(Coupon coupon) {
    return new CouponResponse(
        coupon.getId(),
        coupon.getCode(),
        coupon.getRuleId(),
        coupon.getMaxRedemptions(),
        coupon.getRedeemedCount(),
        coupon.getExpiresAt(),
        coupon.isActive());
  }
}
