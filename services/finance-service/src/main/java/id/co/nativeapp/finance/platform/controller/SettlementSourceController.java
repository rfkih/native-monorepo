package id.co.nativeapp.finance.platform.controller;

import id.co.nativeapp.finance.platform.domain.SettlementSourceKind;
import id.co.nativeapp.finance.platform.dto.SetSettlementSourceRequest;
import id.co.nativeapp.finance.platform.dto.SettlementSourceResponse;
import id.co.nativeapp.finance.platform.service.SettlementSourceReader;
import id.co.nativeapp.finance.platform.service.SettlementSourceWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who pays out each tender family (ADR 0076) — dashboard-facing. A merchant on Shopee's QRIS names
 * Shopee here, and that QRIS balance then groups into the same payout as its ShopeeFood orders.
 *
 * <p>Lives in finance rather than beside the sales-channel CRUD because that table belongs to
 * restaurant-service: finance may neither read it (hard rule 1) nor call for it (hard rule 2).
 */
@Tag(
    name = "Settlement sources",
    description = "Who pays out each tender family — QRIS/card acquirers (ADR 0076)")
@RestController
@RequestMapping("/api/v1/settlement-sources")
@Validated
public class SettlementSourceController {

  private final SettlementSourceReader reader;
  private final SettlementSourceWriter writer;

  public SettlementSourceController(SettlementSourceReader reader, SettlementSourceWriter writer) {
    this.reader = reader;
    this.writer = writer;
  }

  @Operation(
      summary =
          "Every tender family the merchant has named a payer for. A family with no row is"
              + " unconfigured — its balance settles under its own name until one is set.")
  @GetMapping
  public List<SettlementSourceResponse> list() {
    return reader.listConfigured().stream()
        .map(ref -> new SettlementSourceResponse(ref.kind().name(), ref.sourceCode()))
        .toList();
  }

  @Operation(
      summary =
          "Name who pays out a tender family, and move that family's already-accrued balance under"
              + " the new payer in the same transaction (so switching acquirer never strands it)")
  @PutMapping
  public ResponseEntity<Void> setSource(@Valid @RequestBody SetSettlementSourceRequest request) {
    writer.setSource(SettlementSourceKind.valueOf(request.sourceKind()), request.sourceCode());
    return ResponseEntity.noContent().build();
  }
}
