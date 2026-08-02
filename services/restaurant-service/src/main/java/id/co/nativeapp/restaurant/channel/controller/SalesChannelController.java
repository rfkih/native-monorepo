package id.co.nativeapp.restaurant.channel.controller;

import id.co.nativeapp.restaurant.channel.dto.CreateSalesChannelRequest;
import id.co.nativeapp.restaurant.channel.dto.SalesChannelResponse;
import id.co.nativeapp.restaurant.channel.dto.UpdateSalesChannelRequest;
import id.co.nativeapp.restaurant.channel.service.SalesChannelWriter;
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
 * {@code /api/v1/sales-channels} — the company-managed catalog of online sales channels (ADR 0036
 * Phase B2) a checkout can ring an {@code ONLINE}-tender sale through. The tenant ({@code
 * company_id}) and actor come from the bound {@link id.co.nativeapp.tenant.TenantContext
 * TenantContext}, never the request body (rule 5).
 *
 * <p>LIST is open to every POS role (the checkout channel picker); CREATE/PATCH are gated to
 * {@code owner}/{@code manager} by {@link SalesChannelWriter} (a channel is money-routing
 * configuration).
 */
@Tag(
    name = "Sales channels",
    description = "Company-managed online sales channels + the ONLINE tender (ADR 0036 Phase B2)")
@RestController
@RequestMapping("/api/v1/sales-channels")
public class SalesChannelController {

  private final SalesChannelWriter writer;

  public SalesChannelController(SalesChannelWriter writer) {
    this.writer = writer;
  }

  @Operation(
      summary = "List sales channels",
      description = "Lists every sales channel for the bound tenant, ordered by code.")
  @GetMapping
  public List<SalesChannelResponse> list() {
    return writer.list();
  }

  @Operation(
      summary = "Create a sales channel",
      description =
          "Creates a sales channel; the code is normalized to uppercase/trim then IMMUTABLE. 409 on"
              + " a duplicate code, 403 when the caller is not owner/manager.")
  @PostMapping
  public ResponseEntity<SalesChannelResponse> create(
      @Valid @RequestBody CreateSalesChannelRequest request) {
    SalesChannelResponse created = writer.create(request);
    return ResponseEntity.created(URI.create("/api/v1/sales-channels/" + created.id()))
        .body(created);
  }

  @Operation(
      summary = "Update a sales channel",
      description =
          "Partially updates a sales channel's name and/or active flag; code is immutable and never"
              + " accepted here. 404 if unknown, 403 when the caller is not owner/manager.")
  @PatchMapping("/{id}")
  public SalesChannelResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateSalesChannelRequest request) {
    return writer.update(id, request);
  }
}
