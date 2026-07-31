package id.co.nativeapp.barbershop.pricing.controller;

import id.co.nativeapp.barbershop.pricing.dto.EffectiveRulesResponse;
import id.co.nativeapp.barbershop.pricing.service.TaxChargeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/barbershop/pricing/effective-rules} — Phase 5 (ADR 0028) offline-mode support:
 * the currently-effective tax/service-charge basis-point rates the POS device caches so it can
 * price a queued offline sale with the SAME formula the server applies on replay. Ported verbatim
 * from carwash-service (ADR 0024).
 *
 * <p>The tenant ({@code company_id}) comes from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the query string (rule 5).
 * Read-only, POS-role facing — the gateway handles role authorization; no service-side entitlement
 * check, mirroring {@link id.co.nativeapp.barbershop.ticket.service.TicketService#getById
 * TicketService#getById}'s deliberately-ungated read asymmetry (a read carries no side-effect
 * risk).
 */
@Tag(
    name = "Barbershop Pricing",
    description = "POS pricing snapshots (offline-mode provisional pricing)")
@RestController
@RequestMapping("/api/v1/barbershop/pricing")
public class PricingController {

  private final TaxChargeService taxChargeService;

  public PricingController(TaxChargeService taxChargeService) {
    this.taxChargeService = taxChargeService;
  }

  /**
   * Returns TODAY's (UTC) effective tax/service-charge rules for the bound tenant.
   *
   * <p>{@code businessId} is accepted (optionally — review W/S2) purely for POS-call-shape
   * symmetry with every other POS endpoint (and reserved for a future per-outlet rule override);
   * it is NOT an access key and is NOT currently consulted — {@code tax_charge_rule} rows are
   * resolved tenant-wide from the bound {@link id.co.nativeapp.tenant.TenantContext TenantContext},
   * exactly like {@link TaxChargeService#resolve}. Making it optional avoids a spurious 400 for a
   * caller that omits it; it can never widen or narrow which rules are returned.
   */
  @Operation(
      summary = "Resolve today's effective tax/service-charge rules",
      description =
          "Returns TODAY's (UTC) effective tax and service-charge basis-point rates plus their rule"
              + " version/provenance, for the offline POS's provisional-pricing snapshot (ADR 0028)."
              + " No-rule fall-through: bp=0, version/provenance=null.")
  @GetMapping("/effective-rules")
  public ResponseEntity<EffectiveRulesResponse> effectiveRules(
      @RequestParam(required = false) UUID businessId) {
    return ResponseEntity.ok(taxChargeService.resolveEffectiveRules());
  }
}
