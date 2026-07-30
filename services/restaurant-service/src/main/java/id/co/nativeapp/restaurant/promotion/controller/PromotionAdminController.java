package id.co.nativeapp.restaurant.promotion.controller;

import id.co.nativeapp.restaurant.promotion.dto.CouponCreateRequest;
import id.co.nativeapp.restaurant.promotion.dto.CouponPatchRequest;
import id.co.nativeapp.restaurant.promotion.dto.CouponResponse;
import id.co.nativeapp.restaurant.promotion.dto.PromoRuleCreateRequest;
import id.co.nativeapp.restaurant.promotion.dto.PromoRulePatchRequest;
import id.co.nativeapp.restaurant.promotion.dto.PromoRuleResponse;
import id.co.nativeapp.restaurant.promotion.service.PromotionAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/promotions[/coupons]} — admin CRUD for the Phase-3 promotions engine (ADR 0026).
 * The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never the request body (rule 5).
 *
 * <p>Writes are gated to {@code owner}/{@code manager} by {@link PromotionAdminService}; reads are
 * not (browsing the configured rules/coupons carries no side-effect risk).
 */
@Tag(
    name = "Promotions",
    description = "Promo rules + coupons: the Phase-3 promotions engine admin CRUD")
@RestController
@RequestMapping("/api/v1/promotions")
public class PromotionAdminController {

  private final PromotionAdminService promotionAdminService;

  public PromotionAdminController(PromotionAdminService promotionAdminService) {
    this.promotionAdminService = promotionAdminService;
  }

  // ---------------------------------------------------------------------
  // Rules
  // ---------------------------------------------------------------------

  @Operation(
      summary = "List promo rules",
      description =
          "Lists promo rules for the bound tenant, restricted to active-only (default true), ordered"
              + " by priority then name.")
  @GetMapping
  public List<PromoRuleResponse> listRules(
      @RequestParam(defaultValue = "true") boolean activeOnly) {
    return promotionAdminService.listRules(activeOnly);
  }

  @Operation(
      summary = "Create a promo rule",
      description =
          "Creates a promo rule. Rejected with 422 on a type/parameter mismatch and 403 when the"
              + " caller is not owner/manager.")
  @PostMapping
  public ResponseEntity<PromoRuleResponse> createRule(
      @Valid @RequestBody PromoRuleCreateRequest request) {
    PromoRuleResponse created = promotionAdminService.createRule(request);
    return ResponseEntity.created(URI.create("/api/v1/promotions/" + created.id())).body(created);
  }

  @Operation(
      summary = "Update a promo rule",
      description =
          "Partially updates a promo rule (including the active toggle); unset fields are left"
              + " unchanged. 422 on a type/parameter mismatch, 403 when the caller is not"
              + " owner/manager.")
  @PatchMapping("/{id}")
  public PromoRuleResponse patchRule(
      @PathVariable UUID id, @Valid @RequestBody PromoRulePatchRequest request) {
    return promotionAdminService.patchRule(id, request);
  }

  // ---------------------------------------------------------------------
  // Coupons
  // ---------------------------------------------------------------------

  @Operation(
      summary = "List coupons",
      description =
          "Lists coupons for the bound tenant, restricted to active-only (default true), ordered by"
              + " code.")
  @GetMapping("/coupons")
  public List<CouponResponse> listCoupons(@RequestParam(defaultValue = "true") boolean activeOnly) {
    return promotionAdminService.listCoupons(activeOnly);
  }

  @Operation(
      summary = "Create a coupon",
      description =
          "Creates a coupon bound to an existing promo rule; the code is trimmed and uppercased."
              + " 422 if the rule is unknown, 409 on a duplicate code, 403 when the caller is not"
              + " owner/manager.")
  @PostMapping("/coupons")
  public ResponseEntity<CouponResponse> createCoupon(
      @Valid @RequestBody CouponCreateRequest request) {
    CouponResponse created = promotionAdminService.createCoupon(request);
    return ResponseEntity.created(URI.create("/api/v1/promotions/coupons/" + created.id()))
        .body(created);
  }

  @Operation(
      summary = "Update a coupon",
      description =
          "Partially updates a coupon (including the active toggle); unset fields are left unchanged."
              + " 403 when the caller is not owner/manager.")
  @PatchMapping("/coupons/{id}")
  public CouponResponse patchCoupon(
      @PathVariable UUID id, @Valid @RequestBody CouponPatchRequest request) {
    return promotionAdminService.patchCoupon(id, request);
  }
}
