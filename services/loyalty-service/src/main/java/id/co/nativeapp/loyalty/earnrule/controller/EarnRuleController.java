package id.co.nativeapp.loyalty.earnrule.controller;

import id.co.nativeapp.loyalty.earnrule.dto.EarnRuleCreateRequest;
import id.co.nativeapp.loyalty.earnrule.dto.EarnRuleResponse;
import id.co.nativeapp.loyalty.earnrule.service.EarnRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/loyalty/earn-rules} — the company-wide points-earning configuration (ADR 0027).
 * The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never the request body (rule 5). Writes are
 * gated to owner/manager service-side (see {@link EarnRuleService} class javadoc); reads are open
 * to any authenticated caller.
 */
@Tag(name = "Loyalty Earn Rules", description = "Company-wide points-earning configuration")
@RestController
@RequestMapping("/api/v1/loyalty/earn-rules")
public class EarnRuleController {

  private final EarnRuleService earnRuleService;

  public EarnRuleController(EarnRuleService earnRuleService) {
    this.earnRuleService = earnRuleService;
  }

  @Operation(
      summary = "List earn rules",
      description =
          "Lists earn rules for the bound tenant, restricted to active-only (default true), ordered"
              + " by rule_version descending (newest first).")
  @GetMapping
  public List<EarnRuleResponse> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
    return earnRuleService.list(activeOnly);
  }

  @Operation(
      summary = "Create an earn rule",
      description =
          "Creates a company-wide points-earning rule (points per minor unit, in basis points)."
              + " Rejected with 422 on an unknown provenance and 403 when the caller is not"
              + " owner/manager (enforced service-side).")
  @PostMapping
  public ResponseEntity<EarnRuleResponse> create(
      @Valid @RequestBody EarnRuleCreateRequest request) {
    EarnRuleResponse created = earnRuleService.create(request);
    return ResponseEntity.created(URI.create("/api/v1/loyalty/earn-rules/" + created.id()))
        .body(created);
  }
}
