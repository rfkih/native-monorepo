package id.co.nativeapp.entitlement.entitlement.controller;

import id.co.nativeapp.entitlement.entitlement.domain.TenantEntitlement;
import id.co.nativeapp.entitlement.entitlement.dto.EntitlementResponse;
import id.co.nativeapp.entitlement.entitlement.dto.GrantEntitlementRequest;
import id.co.nativeapp.entitlement.entitlement.service.EntitlementService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/entitlements} — grant / revoke / list the bound tenant's module entitlements.
 *
 * <p>A thin HTTP adapter (CODE-STRUCTURE §3.1): each method accepts a {@code @Valid} DTO (never an
 * entity), calls exactly one service method, and maps the result to a response. The tenant ({@code
 * company_id}) and actor come from the bound {@link id.co.nativeapp.tenant.TenantContext}
 * TenantContext (set at the request edge by the gateway / JWT, or the dev filter) — never from the
 * body or path (rule 5). RFC-7807 errors and JWT validation come from the platform ({@code
 * libs/security}); a malformed module key is a {@code 400} via the shared advice, an unknown module
 * a {@code 400}, and a revoke of a never-granted module a {@code 404}.
 */
@RestController
@RequestMapping("/api/v1/entitlements")
public class EntitlementController {

  private final EntitlementService entitlementService;

  public EntitlementController(EntitlementService entitlementService) {
    this.entitlementService = entitlementService;
  }

  /**
   * Grant a module to the bound tenant: persists the entitlement, emits {@code EntitlementGranted}
   * (via the outbox), and invalidates the entitlement-check cache. Returns {@code 201 Created} with
   * a {@code Location} header pointing at the module's entitlement resource.
   */
  @PostMapping
  public ResponseEntity<EntitlementResponse> grant(
      @Valid @RequestBody GrantEntitlementRequest request) {
    TenantEntitlement granted = entitlementService.grant(request.moduleKey());
    EntitlementResponse body = EntitlementResponse.from(granted);
    return ResponseEntity.created(URI.create("/api/v1/entitlements/" + body.moduleKey()))
        .body(body);
  }

  /**
   * Revoke a module from the bound tenant: flips the entitlement to {@code REVOKED}, emits {@code
   * EntitlementRevoked} (via the outbox), and invalidates the entitlement-check cache. Returns
   * {@code 204 No Content} (DELETE success, ENGINEERING-STANDARDS §1.1).
   */
  @DeleteMapping("/{moduleKey}")
  public ResponseEntity<Void> revoke(@PathVariable String moduleKey) {
    entitlementService.revoke(moduleKey);
    return ResponseEntity.noContent().build();
  }

  /** The bound tenant's entitlements (RLS-scoped). Returns {@code 200} with the list. */
  @GetMapping
  public ResponseEntity<List<EntitlementResponse>> list() {
    List<EntitlementResponse> body =
        entitlementService.list().stream().map(EntitlementResponse::from).toList();
    return ResponseEntity.ok(body);
  }
}
