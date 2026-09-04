package id.co.nativeapp.finance.inventory.controller;

import id.co.nativeapp.finance.inventory.dto.ActivatePerpetualInventoryRequest;
import id.co.nativeapp.finance.inventory.dto.ActivationResult;
import id.co.nativeapp.finance.inventory.dto.InventoryMethodStatusResponse;
import id.co.nativeapp.finance.inventory.service.InventoryMethodActivationWriter;
import id.co.nativeapp.finance.inventory.service.InventoryMethodStatusReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Perpetual-inventory election & activation (ADR 0067 §5, Phase D4/D5).
 *
 * <p>{@code POST .../activate} is OWNER-ONLY at the gateway (narrower than the general
 * FINANCE_ROLES this controller's {@code GET} sits behind — see {@code
 * RoutingConfig#inventoryMethodActivateRoute} / {@code #inventoryMethodRoute}): activation books
 * money (an opening {@code Dr 1100 / Cr 3900} entry) and is a once-per-company, effectively
 * irreversible election, so it carries the same highest-sensitivity gate as QRIS payment-settings
 * administration and the payroll bank file.
 */
@Tag(
    name = "Inventory method",
    description = "Perpetual-inventory election & activation (ADR 0067)")
@RestController
@RequestMapping("/api/v1/inventory-method")
public class InventoryMethodController {

  private final InventoryMethodActivationWriter writer;
  private final InventoryMethodStatusReader reader;

  public InventoryMethodController(
      InventoryMethodActivationWriter writer, InventoryMethodStatusReader reader) {
    this.writer = writer;
    this.reader = reader;
  }

  @Operation(
      summary =
          "Activate perpetual inventory accounting for this company (OWNER-ONLY) — elects PERPETUAL"
              + " from the given cutover period and books a one-time opening Dr 1100 / Cr 3900 entry"
              + " for the counted on-hand value (skipped when the value is zero). Once-only per"
              + " company.")
  @PostMapping("/activate")
  public ResponseEntity<InventoryMethodStatusResponse> activate(
      @Valid @RequestBody ActivatePerpetualInventoryRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    // The key is REQUIRED (the opening-balances/AP/AR/assets idiom): activation moves the books, so
    // a keyless request — which could silently double-activate on a retry — is rejected 400.
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "the Idempotency-Key header is required to activate perpetual inventory");
    }
    if (idempotencyKey.length() > 64) {
      throw new IllegalArgumentException("the Idempotency-Key header must be at most 64 chars");
    }
    ActivationResult result =
        writer.activate(
            request.cutoverPeriod(),
            request.openingInventoryValueMinor(),
            request.currency(),
            idempotencyKey);
    InventoryMethodStatusResponse body = reader.read();
    // Idempotent-POST contract (ENGINEERING-STANDARDS §1.1): fresh -> 201 + Location; replay ->
    // 200.
    if (!result.created()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.created(URI.create("/api/v1/inventory-method")).body(body);
  }

  @Operation(
      summary =
          "The bound company's perpetual-inventory election status, including the live 1100 GL"
              + " balance and the negative-inventory monitor flag.")
  @GetMapping
  public ResponseEntity<InventoryMethodStatusResponse> status() {
    return ResponseEntity.ok(reader.read());
  }
}
