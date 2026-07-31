package id.co.nativeapp.restaurant.selforderaccess.controller;

import id.co.nativeapp.restaurant.selforderaccess.dto.RotateSelfOrderAccessRequest;
import id.co.nativeapp.restaurant.selforderaccess.dto.SelfOrderAccessResponse;
import id.co.nativeapp.restaurant.selforderaccess.service.SelfOrderAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The owner/manager-only console surface for managing an outlet's self-order QR codes (Phase 6, ADR
 * 0029). These ride the existing authenticated DASHBOARD routes — unlike {@code
 * selforder.controller.SelfOrderController}, which is the ANONYMOUS surface a diner's phone talks
 * to.
 *
 * <ul>
 *   <li>{@code GET /api/v1/self-order-access?outletId=} — the current token set (kiosk + one per
 *       active table), provisioning the outlet's first secret on demand.
 *   <li>{@code POST /api/v1/self-order-access/rotate} — revokes every printed QR for the outlet at
 *       once and returns a fresh token set.
 * </ul>
 *
 * <p>The tenant ({@code company_id}) comes from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the body (rule 5). The
 * owner/manager role check happens in {@link SelfOrderAccessService} (server-side, never trust the
 * client).
 */
@Tag(name = "Self-Order Access", description = "Owner/manager management of self-order QR tokens")
@RestController
@RequestMapping("/api/v1/self-order-access")
public class SelfOrderAccessController {

  private final SelfOrderAccessService service;

  public SelfOrderAccessController(SelfOrderAccessService service) {
    this.service = service;
  }

  @Operation(
      summary = "Get the outlet's current self-order token set",
      description =
          "Returns the active kid plus minted tokens for each active table and a kiosk token,"
              + " provisioning the outlet's first secret on demand. Owner/manager only.")
  @GetMapping
  public ResponseEntity<SelfOrderAccessResponse> getActive(
      @RequestParam("outletId") UUID outletId) {
    return ResponseEntity.ok(service.getActive(outletId));
  }

  @Operation(
      summary = "Rotate the outlet's self-order secret",
      description =
          "Retires the current ACTIVE row and mints a fresh one — every previously-printed QR for"
              + " this outlet stops verifying immediately. Owner/manager only.")
  @PostMapping("/rotate")
  public ResponseEntity<SelfOrderAccessResponse> rotate(
      @Valid @RequestBody RotateSelfOrderAccessRequest request) {
    return ResponseEntity.ok(service.rotate(request.outletId()));
  }
}
