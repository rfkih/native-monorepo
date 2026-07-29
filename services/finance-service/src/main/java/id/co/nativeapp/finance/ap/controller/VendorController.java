package id.co.nativeapp.finance.ap.controller;

import id.co.nativeapp.finance.ap.dto.CreateVendorRequest;
import id.co.nativeapp.finance.ap.dto.UpdateVendorRequest;
import id.co.nativeapp.finance.ap.dto.VendorResponse;
import id.co.nativeapp.finance.ap.service.VendorReader;
import id.co.nativeapp.finance.ap.service.VendorWriter;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/vendors} — AP vendors for the bound tenant. All reads/writes are RLS-scoped; the
 * tenant comes from {@link id.co.nativeapp.tenant.TenantContext} (gateway-injected), never the
 * body. Exposes DTOs only (never the JPA entity).
 */
@Tag(name = "Vendors", description = "AP vendors (bill-from parties) for the bound tenant.")
@RestController
@RequestMapping("/api/v1/vendors")
public class VendorController {

  private final VendorWriter vendorWriter;
  private final VendorReader vendorReader;

  public VendorController(VendorWriter vendorWriter, VendorReader vendorReader) {
    this.vendorWriter = vendorWriter;
    this.vendorReader = vendorReader;
  }

  @Operation(summary = "Create a vendor")
  @PostMapping
  public ResponseEntity<VendorResponse> create(@Valid @RequestBody CreateVendorRequest request) {
    VendorResponse created = vendorWriter.create(request.name(), request.email(), request.taxId());
    return ResponseEntity.created(URI.create("/api/v1/vendors/" + created.id())).body(created);
  }

  @Operation(summary = "List all vendors in the tenant, ordered by name")
  @GetMapping
  public List<VendorResponse> list() {
    return vendorReader.list();
  }

  @Operation(summary = "Get a vendor by id")
  @GetMapping("/{id}")
  public VendorResponse get(@PathVariable UUID id) {
    return vendorReader.get(id);
  }

  @Operation(summary = "Update a vendor's contact details and/or active flag")
  @PatchMapping("/{id}")
  public VendorResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateVendorRequest request) {
    return vendorWriter.update(
        id, request.name(), request.email(), request.taxId(), request.active());
  }
}
