package id.co.nativeapp.restaurant.table.controller;

import id.co.nativeapp.restaurant.table.dto.CreateTableRequest;
import id.co.nativeapp.restaurant.table.dto.TableResponse;
import id.co.nativeapp.restaurant.table.service.TableService;
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
 * REST endpoints for restaurant-table management.
 *
 * <ul>
 *   <li>{@code POST /api/v1/tables} — create a table (201 Created + Location)
 *   <li>{@code GET /api/v1/tables?businessId=...} — list tables with occupancy
 *   <li>{@code PATCH /api/v1/tables/{id}/activate} — activate a table
 *   <li>{@code PATCH /api/v1/tables/{id}/deactivate} — deactivate a table
 * </ul>
 *
 * <p>The tenant ({@code company_id}) comes from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the body (rule 5).
 */
@Tag(name = "Tables", description = "Restaurant table management and occupancy")
@RestController
@RequestMapping("/api/v1/tables")
public class TableController {

  private final TableService tableService;

  public TableController(TableService tableService) {
    this.tableService = tableService;
  }

  @Operation(
      summary = "Create a table",
      description =
          "Creates a new restaurant table for the bound business. Returns 201 Created with a"
              + " Location header.")
  @PostMapping
  public ResponseEntity<TableResponse> create(@Valid @RequestBody CreateTableRequest request) {
    TableResponse body = tableService.create(request);
    return ResponseEntity.created(URI.create("/api/v1/tables/" + body.tableId())).body(body);
  }

  @Operation(
      summary = "List tables for a business",
      description =
          "Lists tables with occupancy for the given business, scoped by the bound tenant (RLS).")
  @GetMapping
  public ResponseEntity<List<TableResponse>> list(@RequestParam("businessId") UUID businessId) {
    return ResponseEntity.ok(tableService.listByBusiness(businessId));
  }

  @Operation(
      summary = "Activate a table",
      description = "Activates a table, making it available for seating in the POS.")
  @PatchMapping("/{id}/activate")
  public ResponseEntity<TableResponse> activate(@PathVariable UUID id) {
    return ResponseEntity.ok(tableService.activate(id));
  }

  @Operation(
      summary = "Deactivate a table",
      description = "Deactivates a table, removing it from the POS seating view.")
  @PatchMapping("/{id}/deactivate")
  public ResponseEntity<TableResponse> deactivate(@PathVariable UUID id) {
    return ResponseEntity.ok(tableService.deactivate(id));
  }
}
