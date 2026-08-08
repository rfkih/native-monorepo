package id.co.nativeapp.org.devicecredential.controller;

import id.co.nativeapp.org.devicecredential.dto.DeviceCredentialResponse;
import id.co.nativeapp.org.devicecredential.service.DeviceCredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/org-units/{outletId}/device-credential} — the per-outlet device (kiosk) login
 * lifecycle for the Business app (ADR 0049 P3a).
 *
 * <p>Routed by the gateway's EXISTING {@code /api/v1/org-units/**} route — {@code DASHBOARD_ROLES}
 * (owner/manager only). This is deliberate: minting a credential that can ring sales must never be
 * reachable by a {@code cashier} (nor, transitively, by a device itself) — the same route that
 * gates the rest of org-tree management, not the POS-facing {@code /api/v1/outlets} route.
 *
 * <p>Tenant-scoped: the gateway/{@code DevTenantFilter} binds the tenant from the validated JWT
 * before the handler runs; RLS scopes every lookup to the bound company (rule 5). {@code outletId}
 * is validated to be an OUTLET-type org unit of the caller's tenant before anything is minted.
 *
 * <p><strong>Credential hygiene (rule 6).</strong> The password is returned in the response body
 * ONLY by create/reset (once, freshly generated) and by the reveal GET (the owner/manager
 * re-provisioning path) — never logged, never included in any other response.
 */
@Tag(
    name = "Device credentials",
    description = "Per-outlet Keycloak kiosk login for the Business app — owner/manager only")
@RestController
@RequestMapping("/api/v1/org-units")
public class DeviceCredentialController {

  private final DeviceCredentialService service;

  public DeviceCredentialController(DeviceCredentialService service) {
    this.service = service;
  }

  /**
   * Provisions a new device credential for the outlet: mints the Keycloak {@code cashier}/{@code
   * actor_type=device} login, binds it to this ONE outlet, and returns the username + password
   * ONCE. {@code 409} if one already exists for this outlet (use reset instead).
   */
  @Operation(
      summary = "Create a device (kiosk) credential for an outlet",
      description =
          "Mints a per-outlet Keycloak cashier login (actor_type=device), binds it to exactly this"
              + " outlet, and returns {username, password} ONCE. 400 if the target is not an"
              + " OUTLET; 404 unknown/cross-tenant outlet; 409 if a credential already exists for"
              + " this outlet. Requires owner or manager role.")
  @PostMapping("/{outletId}/device-credential")
  public ResponseEntity<DeviceCredentialResponse> create(@PathVariable UUID outletId) {
    DeviceCredentialResponse body = service.create(outletId);
    return ResponseEntity.created(
            URI.create("/api/v1/org-units/" + outletId + "/device-credential"))
        .body(body);
  }

  /**
   * Regenerates the device credential's password (e.g. after a lost/compromised device) and returns
   * the username + new password ONCE.
   */
  @Operation(
      summary = "Reset a device credential's password",
      description =
          "Regenerates the outlet's device-credential password (non-temporary — a kiosk cannot"
              + " complete a forced-change flow) and returns {username, password} ONCE. 404 if no"
              + " credential exists for this outlet. Requires owner or manager role.")
  @PostMapping("/{outletId}/device-credential/reset")
  public ResponseEntity<DeviceCredentialResponse> reset(@PathVariable UUID outletId) {
    return ResponseEntity.ok(service.reset(outletId));
  }

  /**
   * Reveals the stored username + decrypted password, for re-provisioning a physical device (a lost
   * or replaced till).
   */
  @Operation(
      summary = "Reveal a device credential",
      description =
          "Returns the stored {username, password} (decrypted) for re-provisioning a physical"
              + " device. 404 if no credential exists for this outlet. Requires owner or manager"
              + " role.")
  @GetMapping("/{outletId}/device-credential")
  public ResponseEntity<DeviceCredentialResponse> reveal(@PathVariable UUID outletId) {
    // no-store so the plaintext password is never cached by a browser/intermediary (P3a LOW-1).
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reveal(outletId));
  }

  /**
   * Permanently removes the outlet's device credential: the Keycloak login, its outlet assignment,
   * and the local record.
   */
  @Operation(
      summary = "Delete a device credential",
      description =
          "Permanently removes the outlet's device credential: the Keycloak login (disabled"
              + " immediately), its outlet assignment, and the local record. 404 if none exists."
              + " Requires owner or manager role.")
  @DeleteMapping("/{outletId}/device-credential")
  public ResponseEntity<Void> delete(@PathVariable UUID outletId) {
    service.delete(outletId);
    return ResponseEntity.noContent().build();
  }
}
