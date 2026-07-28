package id.co.nativeapp.finance.ar.controller;

import id.co.nativeapp.finance.ar.dto.CreateCustomerRequest;
import id.co.nativeapp.finance.ar.dto.CustomerResponse;
import id.co.nativeapp.finance.ar.dto.UpdateCustomerRequest;
import id.co.nativeapp.finance.ar.service.CustomerReader;
import id.co.nativeapp.finance.ar.service.CustomerWriter;
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
 * {@code /api/v1/customers} — AR customers for the bound tenant. All reads/writes are RLS-scoped;
 * the tenant comes from {@link id.co.nativeapp.tenant.TenantContext} (gateway-injected), never the
 * body. Exposes DTOs only (never the JPA entity).
 */
@Tag(name = "Customers", description = "AR customers (bill-to parties) for the bound tenant.")
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

  private final CustomerWriter customerWriter;
  private final CustomerReader customerReader;

  public CustomerController(CustomerWriter customerWriter, CustomerReader customerReader) {
    this.customerWriter = customerWriter;
    this.customerReader = customerReader;
  }

  @Operation(summary = "Create a customer")
  @PostMapping
  public ResponseEntity<CustomerResponse> create(
      @Valid @RequestBody CreateCustomerRequest request) {
    CustomerResponse created =
        customerWriter.create(request.name(), request.email(), request.taxId());
    return ResponseEntity.created(URI.create("/api/v1/customers/" + created.id())).body(created);
  }

  @Operation(summary = "List all customers in the tenant, ordered by name")
  @GetMapping
  public List<CustomerResponse> list() {
    return customerReader.list();
  }

  @Operation(summary = "Get a customer by id")
  @GetMapping("/{id}")
  public CustomerResponse get(@PathVariable UUID id) {
    return customerReader.get(id);
  }

  @Operation(summary = "Update a customer's contact details and/or active flag")
  @PatchMapping("/{id}")
  public CustomerResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest request) {
    return customerWriter.update(
        id, request.name(), request.email(), request.taxId(), request.active());
  }
}
