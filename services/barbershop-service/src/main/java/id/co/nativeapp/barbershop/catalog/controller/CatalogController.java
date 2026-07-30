package id.co.nativeapp.barbershop.catalog.controller;

import id.co.nativeapp.barbershop.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemPatchRequest;
import id.co.nativeapp.barbershop.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfilePatchRequest;
import id.co.nativeapp.barbershop.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.barbershop.catalog.service.CatalogService;
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
 * {@code /api/v1/barbershop/{services,addons,staff-profiles}} — the barbershop POS catalog (ADR
 * 0024, vertical path prefixing per ADR 0023). The tenant ({@code company_id}) and actor come from
 * the bound {@link id.co.nativeapp.tenant.TenantContext TenantContext}, never the request body
 * (rule 5).
 *
 * <p>Writes are gated by the barbershop entitlement ({@link
 * id.co.nativeapp.barbershop.catalog.service.CatalogService CatalogService}); reads are not.
 */
@Tag(
    name = "Barbershop Catalog",
    description = "Services, addons, and staff (barber) profiles for the barbershop POS")
@RestController
@RequestMapping("/api/v1/barbershop")
public class CatalogController {

  private final CatalogService catalogService;

  public CatalogController(CatalogService catalogService) {
    this.catalogService = catalogService;
  }

  // ---------------------------------------------------------------------
  // Services
  // ---------------------------------------------------------------------

  @Operation(
      summary = "List services",
      description =
          "Lists barbershop services for the bound tenant, optionally filtered by businessId"
              + " (outlet) and restricted to active-only (default true), ordered by display order"
              + " then name.")
  @GetMapping("/services")
  public List<CatalogItemResponse> listServices(
      @RequestParam(required = false) UUID businessId,
      @RequestParam(defaultValue = "true") boolean activeOnly) {
    return catalogService.listServices(businessId, activeOnly);
  }

  @Operation(
      summary = "Create a service",
      description =
          "Creates a barbershop service (e.g. haircut, shave, coloring). Rejected with 403 when the"
              + " company is not entitled to the barbershop module.")
  @PostMapping("/services")
  public ResponseEntity<CatalogItemResponse> createService(
      @Valid @RequestBody CatalogItemCreateRequest request) {
    CatalogItemResponse created = catalogService.createService(request);
    return ResponseEntity.created(URI.create("/api/v1/barbershop/services/" + created.id()))
        .body(created);
  }

  @Operation(
      summary = "Update a service",
      description =
          "Partially updates a service; unset fields are left unchanged. 404 if unknown or"
              + " belonging to another tenant.")
  @PatchMapping("/services/{id}")
  public CatalogItemResponse patchService(
      @PathVariable UUID id, @Valid @RequestBody CatalogItemPatchRequest request) {
    return catalogService.patchService(id, request);
  }

  // ---------------------------------------------------------------------
  // Addons
  // ---------------------------------------------------------------------

  @Operation(
      summary = "List addons",
      description =
          "Lists service addons (upsells) for the bound tenant, optionally filtered by businessId"
              + " and restricted to active-only (default true), ordered by display order then"
              + " name.")
  @GetMapping("/addons")
  public List<CatalogItemResponse> listAddons(
      @RequestParam(required = false) UUID businessId,
      @RequestParam(defaultValue = "true") boolean activeOnly) {
    return catalogService.listAddons(businessId, activeOnly);
  }

  @Operation(
      summary = "Create an addon",
      description =
          "Creates a service addon (e.g. hot towel). Rejected with 403 when the company is not"
              + " entitled to the barbershop module.")
  @PostMapping("/addons")
  public ResponseEntity<CatalogItemResponse> createAddon(
      @Valid @RequestBody CatalogItemCreateRequest request) {
    CatalogItemResponse created = catalogService.createAddon(request);
    return ResponseEntity.created(URI.create("/api/v1/barbershop/addons/" + created.id()))
        .body(created);
  }

  @Operation(
      summary = "Update an addon",
      description =
          "Partially updates a service addon; unset fields are left unchanged. 404 if unknown"
              + " or belonging to another tenant.")
  @PatchMapping("/addons/{id}")
  public CatalogItemResponse patchAddon(
      @PathVariable UUID id, @Valid @RequestBody CatalogItemPatchRequest request) {
    return catalogService.patchAddon(id, request);
  }

  // ---------------------------------------------------------------------
  // Staff profiles
  // ---------------------------------------------------------------------

  @Operation(
      summary = "List staff (barber) profiles",
      description =
          "Lists staff profiles for the bound tenant, optionally filtered by businessId and"
              + " restricted to active-only (default true), ordered by display label.")
  @GetMapping("/staff-profiles")
  public List<StaffProfileResponse> listStaffProfiles(
      @RequestParam(required = false) UUID businessId,
      @RequestParam(defaultValue = "true") boolean activeOnly) {
    return catalogService.listStaffProfiles(businessId, activeOnly);
  }

  @Operation(
      summary = "Create a staff profile",
      description =
          "Creates a barber profile (a tenant-entered label, optionally linked to an employee id)."
              + " Rejected with 403 when the company is not entitled to the barbershop module, and"
              + " with 409 when the display label is already used at this outlet.")
  @PostMapping("/staff-profiles")
  public ResponseEntity<StaffProfileResponse> createStaffProfile(
      @Valid @RequestBody StaffProfileCreateRequest request) {
    StaffProfileResponse created = catalogService.createStaffProfile(request);
    return ResponseEntity.created(URI.create("/api/v1/barbershop/staff-profiles/" + created.id()))
        .body(created);
  }

  @Operation(
      summary = "Update a staff profile",
      description =
          "Partially updates a staff profile; unset fields are left unchanged. 404 if"
              + " unknown or belonging to another tenant.")
  @PatchMapping("/staff-profiles/{id}")
  public StaffProfileResponse patchStaffProfile(
      @PathVariable UUID id, @Valid @RequestBody StaffProfilePatchRequest request) {
    return catalogService.patchStaffProfile(id, request);
  }
}
