package id.co.nativeapp.carwash.ticket.controller;

import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import id.co.nativeapp.carwash.ticket.dto.CheckoutResult;
import id.co.nativeapp.carwash.ticket.dto.PriceBreakdownResponse;
import id.co.nativeapp.carwash.ticket.dto.QuoteRequest;
import id.co.nativeapp.carwash.ticket.dto.TicketResponse;
import id.co.nativeapp.carwash.ticket.service.TicketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/carwash/tickets} — the carwash POS checkout (ADR 0023, vertical path prefixing).
 * The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never the request body (rule 5).
 *
 * <p>Every write ({@code quote}/{@code checkout}/{@code capture}) is gated by the carwash
 * entitlement ({@link TicketService}) — {@code 403} via {@code config.NotEntitledAdvice} when the
 * company is not entitled. {@code checkout} also enforces outlet-assignment scoping ({@code
 * config.OutletAccessAdvice}).
 */
@Tag(
    name = "Carwash Tickets",
    description = "Quote, checkout, capture, and retrieve carwash tickets")
@RestController
@RequestMapping("/api/v1/carwash/tickets")
public class TicketController {

  private final TicketService ticketService;

  public TicketController(TicketService ticketService) {
    this.ticketService = ticketService;
  }

  @Operation(
      summary = "Quote a ticket",
      description =
          "Stateless price preview for the requested lines — resolves + validates catalog items and"
              + " computes the breakdown exactly as checkout would. No ticket is persisted; no event"
              + " emitted. Rejected with 403 when the company is not entitled to the carwash module.")
  @PostMapping("/quote")
  public PriceBreakdownResponse quote(@Valid @RequestBody QuoteRequest request) {
    return ticketService.quote(request);
  }

  @Operation(
      summary = "Check out a ticket",
      description =
          "Records a carwash ticket: resolves + prices the requested lines, authorizes the tender,"
              + " and — for CASH — recognises revenue immediately (one SaleRecorded + the declared"
              + " metric set, atomically). A digital tender (QRIS/CARD) persists PENDING; revenue is"
              + " recognised only when POST /{id}/capture runs. Returns 201 Created with a Location"
              + " header on the first write; returns 200 OK on a retry carrying the same"
              + " idempotencyKey (no second event). Rejected with 403 when the company is not"
              + " entitled to the carwash module.")
  @PostMapping("/checkout")
  public ResponseEntity<TicketResponse> checkout(@Valid @RequestBody CheckoutRequest request) {
    CheckoutResult result = ticketService.checkout(request);
    TicketResponse body = result.ticket();
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/carwash/tickets/" + body.ticketId()))
            .body(body)
        : ResponseEntity.ok(body);
  }

  @Operation(
      summary = "Capture a pending digital payment",
      description =
          "Captures a PENDING QRIS/CARD tender on a ticket: transitions the payment to CAPTURED,"
              + " links the sale, and recognises revenue (SaleRecorded + metrics). Idempotent: capturing"
              + " an already-CAPTURED payment returns 200 with no second event. Rejected with 403 when"
              + " the company is not entitled to the carwash module, 404 if the ticket is unknown, and"
              + " 409 if the payment is not in a capturable state.")
  @PostMapping("/{id}/capture")
  public TicketResponse capture(@PathVariable UUID id) {
    return ticketService.capture(id);
  }

  @Operation(
      summary = "Get a ticket",
      description =
          "Fetches a single ticket by id (receipt shape: header, lines, payment). 404 if"
              + " unknown or belonging to another tenant.")
  @GetMapping("/{id}")
  public TicketResponse get(@PathVariable UUID id) {
    return ticketService.getById(id);
  }
}
