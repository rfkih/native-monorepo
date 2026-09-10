package id.co.nativeapp.restaurant.sale.controller;

import id.co.nativeapp.restaurant.config.DevTenantFilter;
import id.co.nativeapp.restaurant.sale.dto.ChannelSalesSummaryResponse;
import id.co.nativeapp.restaurant.sale.dto.DailySalesResponse;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.dto.SaleHistoryResponse;
import id.co.nativeapp.restaurant.sale.dto.SaleRequest;
import id.co.nativeapp.restaurant.sale.dto.SaleResponse;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the sale feature:
 *
 * <ul>
 *   <li>{@code POST /api/v1/sales} — record a sale
 *   <li>{@code GET /api/v1/sales/daily?businessId=...&from=...&to=...} — the phone home's per-day
 *       net sales over an inclusive outlet-local day window (ADR 0082)
 *   <li>{@code GET /api/v1/sales?businessId=...&from=...&to=...} — the cashier's "today's
 *       transactions" list
 * </ul>
 *
 * <p>The request carries only the business payload; the tenant ({@code company_id}) and actor come
 * from the bound {@link id.co.nativeapp.tenant.TenantContext TenantContext} (set at the request
 * edge by {@link DevTenantFilter}, the documented stand-in for the JWT/gateway that arrives in
 * M1.1) — never from the body (rule 5).
 *
 * <p>The resource lives under {@code /api/v1} (URI versioning — ENGINEERING-STANDARDS §1.1), so the
 * gateway, which routes {@code /api/v1/sales/**} preserving the full path, reaches this handler.
 *
 * <p>Returns {@code 201 Created} (with a {@code Location} header pointing at the new resource) when
 * a new sale was recorded (and exactly one {@code SaleRecorded} emitted), and {@code 200 OK} when a
 * retry with the same {@code idempotency_key} returned the pre-existing sale (no second event, no
 * {@code Location}).
 */
@Tag(name = "Sales", description = "Record a sale and retrieve sale resources")
@RestController
@RequestMapping("/api/v1/sales")
@Validated
public class SaleController {

  /** The widest {@code /daily} window served — a quarter's worth, plenty for a week of bars. */
  private static final int MAX_DAILY_WINDOW_DAYS = 92;

  private final SaleService saleService;

  public SaleController(SaleService saleService) {
    this.saleService = saleService;
  }

  @Operation(
      summary = "Record a sale",
      description =
          "Records a new sale for the bound tenant and business. The tenant (company_id) and actor"
              + " come from TenantContext, never from the body. Returns 201 Created with a Location"
              + " header on success, or 200 OK when a retry with the same idempotency_key returns"
              + " the pre-existing sale (no second SaleRecorded event emitted).")
  @PostMapping
  public ResponseEntity<SaleResponse> recordSale(@Valid @RequestBody SaleRequest request) {
    // occurredAt is passed through AS-IS (possibly null): SaleWriter.create resolves a missing
    // value to Instant.now() itself, INSIDE its transaction, after acquiring the per-business
    // CashWindowLock (verified HIGH race fix) — resolving it here, before the transaction/lock,
    // would let a stale pre-lock instant land on a sale that had to wait behind a register close.
    RecordSaleCommand command =
        new RecordSaleCommand(
            request.businessId(),
            request.amountMinor(),
            request.currency(),
            request.occurredAt(),
            request.idempotencyKey());

    RecordSaleResult result = saleService.recordSale(command);
    SaleResponse body = result.sale();
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/sales/" + body.id())).body(body)
        : ResponseEntity.ok(body);
  }

  /**
   * The cashier's "today's transactions" list: a business unit's sales with {@code occurred_at} in
   * {@code [from, to)}, newest first, hard-capped at 200 rows. All three params are required — the
   * client computes the outlet's local-day bounds; no timezone math happens server-side. Returns
   * {@code 200 OK}.
   */
  @Operation(
      summary = "List a business unit's sales in a time window",
      description =
          "Lists the business unit's sales with occurred_at in [from, to), newest first, hard"
              + " LIMIT 200. All three params (businessId, from, to) are required; the client"
              + " computes the outlet's local-day bounds — no timezone math happens server-side.")
  @GetMapping
  public ResponseEntity<List<SaleHistoryResponse>> salesHistory(
      @RequestParam UUID businessId, @RequestParam Instant from, @RequestParam Instant to) {
    return ResponseEntity.ok(saleService.findHistory(businessId, from, to));
  }

  /**
   * The per-platform (GoFood/GrabFood/ShopeeFood/...) ONLINE sales summary for a {@code YYYY-MM}
   * period — one row per {@code (channelCode, currency)} bucket: gross sales total and transaction
   * count. READ-ONLY, tenant-scoped via RLS (rule 5); company-wide (no outlet scoping). Empty list
   * when the period has no ONLINE sales. Returns {@code 200 OK}.
   */
  @Operation(
      summary = "Per-channel ONLINE sales summary for a period",
      description =
          "Returns the per-channel (GoFood/GrabFood/ShopeeFood/...) ONLINE sales summary for the"
              + " given YYYY-MM period: gross sales total and transaction count per (channelCode,"
              + " currency) bucket. Only sales with a non-null channel_code (ONLINE) are"
              + " considered. Empty list when the period has no ONLINE sales.")
  @GetMapping("/channel-summary")
  public ResponseEntity<List<ChannelSalesSummaryResponse>> channelSalesSummary(
      @RequestParam
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period) {
    return ResponseEntity.ok(saleService.channelSalesSummary(period));
  }

  /**
   * An outlet's per-day sales over an INCLUSIVE outlet-local day window — one row per day that had
   * a tendered sale or a refund: net (total − refunds), transaction count, the sale-time COGS fold
   * and how many sales it covers. Days with neither are ABSENT (the client zero-fills). READ-ONLY,
   * tenant-scoped via RLS (rule 5) and outlet-gated like the history read. The literal {@code
   * /daily} segment cannot collide with an id path (this controller maps no {@code GET /{id}}).
   */
  @Operation(
      summary = "Per-day net sales for an outlet",
      description =
          "One row per outlet-local (Asia/Jakarta) calendar day in the inclusive [from, to] window"
              + " that had a tendered sale or a refund: net sales (total minus refunds attributed"
              + " to the day they were refunded on), transaction count, the summed sale-time COGS"
              + " (null when no sale carried one) and how many sales it covers, the currency, and"
              + " whether any sale used illustrative tax rules. Days with no activity are absent."
              + " The window may span at most "
              + MAX_DAILY_WINDOW_DAYS
              + " days.")
  @GetMapping("/daily")
  public ResponseEntity<List<DailySalesResponse>> dailySales(
      @RequestParam UUID businessId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    requireOrderedWindow(from, to);
    return ResponseEntity.ok(saleService.dailySummary(businessId, from, to));
  }

  /**
   * Rejects an inverted or oversized window with a 400 rather than silently returning an empty list
   * — an empty result would read as "no sales", which is a different and misleading answer.
   */
  private static void requireOrderedWindow(LocalDate from, LocalDate to) {
    if (to.isBefore(from)) {
      throw new IllegalArgumentException("'to' must not be before 'from'");
    }
    if (ChronoUnit.DAYS.between(from, to) >= MAX_DAILY_WINDOW_DAYS) {
      throw new IllegalArgumentException(
          "the window may span at most " + MAX_DAILY_WINDOW_DAYS + " days");
    }
  }
}
