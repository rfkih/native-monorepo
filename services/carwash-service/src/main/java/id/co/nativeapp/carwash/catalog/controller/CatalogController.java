package id.co.nativeapp.carwash.catalog.controller;

import id.co.nativeapp.carwash.catalog.dto.CatalogItemCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemPatchRequest;
import id.co.nativeapp.carwash.catalog.dto.CatalogItemResponse;
import id.co.nativeapp.carwash.catalog.dto.StaffProfileCreateRequest;
import id.co.nativeapp.carwash.catalog.dto.StaffProfilePatchRequest;
import id.co.nativeapp.carwash.catalog.dto.StaffProfileResponse;
import id.co.nativeapp.carwash.catalog.service.CatalogService;
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
 * {@code /api/v1/carwash/{packages,addons,staff-profiles}} — the carwash POS catalog (ADR 0023,
 * vertical path prefixing). The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never the request body (rule 5).
 *
 * <p>Writes are gated by the carwash entitlement ({@link
 * id.co.nativeapp.carwash.catalog.service.CatalogService CatalogService}); reads are not.
 */
@Tag(
    name = "Carwash Catalog",
    description = "Wash packages, addons, and staff (washer) profiles for the carwash POS")
@RestController
@RequestMapping("/api/v1/carwash")
public class CatalogController {

  private final CatalogService catalogService;

  public CatalogController(CatalogService catalogService) {
    this.catalogService = catalogService;
  }

  // ---------------------------------------------------------------------
  // Packages
  // ---------------------------------------------------------------------

  @Operation(
      summary = "List wash packages",
      description =
          "Lists wash packages for the bound tenant, optionally filtered by businessId (outlet) and"
              + " restricted to active-only (default true), ordered by display order then name.")
  @GetMapping("/packages")
  public List<CatalogItemResponse> listPackages(
      @RequestParam(required = false) UUID businessId,
      @RequestParam(defaultValue = "true") boolean activeOnly) {
    return catalogService.listPackages(businessId, activeOnly);
  }

  @Operation(
      summary = "Create a wash package",
      description = "Creates a wash package. Rejected with 403 when the company is not entitled to"
          + " the carwash module.")
  @PostMapping("/packages")
  public ResponseEntity<CatalogItemResponse> createPackage(
      @Valid @RequestBody CatalogItemCreateRequest request) {
    CatalogItemResponse created = catalogService.createPackage(request);
    return ResponseEntity.created(URI.create("/api/v1/carwash/packages/" + created.id()))
        .body(created);
  }

  @Operation(
      summary = "Update a wash package",
      description = "Partially updates a wash package; unset fields are left unchanged. 404 if"
          + " unknown or belonging to another tenant.")
  @PatchMapping("/packages/{id}")
  public CatalogItemResponse patchPackage(
      @PathVariable UUID id, @Valid @RequestBody CatalogItemPatchRequest request) {
    return catalogService.patchPackage(id, request);
  }

  // ---------------------------------------------------------------------
  // Addons
  // ---------------------------------------------------------------------

  @Operation(
      summary = "List wash addons",
      description =
          "Lists wash addons (upsells) for the bound tenant, optionally filtered by businessId and"
              + " restricted to active-only (default true), ordered by display order then name.")
  @GetMapping("/addons")
  public List<CatalogItemResponse> listAddons(
      @RequestParam(required = false) UUID businessId,
      @RequestParam(defaultValue = "true") boolean activeOnly) {
    return catalogService.listAddons(businessId, activeOnly);
  }

  @Operation(
      summary = "Create a wash addon",
      description = "Creates a wash addon. Rejected with 403 when the company is not entitled to"
          + " the carwash module.")
  @PostMapping("/addons")
  public ResponseEntity<CatalogItemResponse> createAddon(
      @Valid @RequestBody CatalogItemCreateRequest request) {
    CatalogItemResponse created = catalogService.createAddon(request);
    return ResponseEntity.created(URI.create("/api/v1/carwash/addons/" + created.id()))
        .body(created);
  }

  @Operation(
      summary = "Update a wash addon",
      description = "Partially updates a wash addon; unset fields are left unchanged. 404 if unknown"
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
      summary = "List staff (washer) profiles",
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
          "Creates a washer profile (a tenant-entered label, optionally linked to an employee id)."
              + " Rejected with 403 when the company is not entitled to the carwash module, and with"
              + " 409 when the display label is already used at this outlet.")
  @PostMapping("/staff-profiles")
  public ResponseEntity<StaffProfileResponse> createStaffProfile(
      @Valid @RequestBody StaffProfileCreateRequest request) {
    StaffProfileResponse created = catalogService.createStaffProfile(request);
    return ResponseEntity.created(URI.create("/api/v1/carwash/staff-profiles/" + created.id()))
        .body(created);
  }

  @Operation(
      summary = "Update a staff profile",
      description = "Partially updates a staff profile; unset fields are left unchanged. 404 if"
          + " unknown or belonging to another tenant.")
  @PatchMapping("/staff-profiles/{id}")
  public StaffProfileResponse patchStaffProfile(
      @PathVariable UUID id, @Valid @RequestBody StaffProfilePatchRequest request) {
    return catalogService.patchStaffProfile(id, request);
  }
}
