package id.co.nativeapp.restaurant.sale.service;

import id.co.nativeapp.restaurant.sale.dto.ChannelSalesSummaryResponse;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.dto.SaleHistoryResponse;
import id.co.nativeapp.restaurant.sale.dto.SaleResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Records sales and emits {@code SaleRecorded} — the validation-slice core (M1.4).
 *
 * <p>The actual transactional unit of work lives in {@link SaleWriter}; this service orchestrates
 * it and owns the <em>concurrency-safe idempotency</em> contract: exactly one {@code SaleRecorded}
 * per {@code (company_id, idempotency_key)}, and a successful idempotent result for <em>every</em>
 * caller (the first creates, every other reads the existing sale back) — never an unhandled {@code
 * 500}.
 *
 * <p><strong>Why the create is a separate transaction from the conflict re-read.</strong> Two
 * concurrent record-sale calls with the same key can both pass the {@code findViewByIdempotencyKey}
 * short-circuit and then race to {@code INSERT}; the loser trips the {@code (company_id,
 * idempotency_key)} unique constraint, which raises a {@link DataIntegrityViolationException}. In
 * PostgreSQL a constraint violation <em>aborts the current transaction</em>, so the loser CANNOT
 * simply re-query inside the same transaction — every statement after the error fails until
 * rollback. So the create runs in its own transaction ({@link SaleWriter#create}); when it fails
 * with a conflict, this (non-transactional) method catches it and performs the re-read in a FRESH
 * transaction ({@link SaleWriter#findExistingByKey}), returning the winner's sale with {@code
 * created=false}. No second {@code SaleRecorded} is written on that path — the outbox row is only
 * ever written inside the successful create.
 */
@Service
public class SaleService {

  private final SaleWriter writer;

  public SaleService(SaleWriter writer) {
    this.writer = writer;
  }

  /**
   * Records a sale idempotently and emits exactly one {@code SaleRecorded} on first record. A retry
   * — sequential OR concurrent — with the same {@code idempotency_key} resolves to the existing
   * sale ({@code created=false}) and emits no second event.
   *
   * <p>Not {@code @Transactional} itself: it runs the create in its own transaction so a
   * concurrent-collision conflict can be recovered by a separate-transaction re-read (a failed
   * transaction is poisoned for any further query in PostgreSQL).
   */
  public RecordSaleResult recordSale(RecordSaleCommand command) {
    // Establish the tenant once, before either transaction, so a missing scope fails
    // loudly here rather than deep inside a transaction. The actual company_id is
    // stamped from the bound scope inside SaleWriter (never from the request body).
    TenantContext.require();
    try {
      return writer.create(command);
    } catch (DataIntegrityViolationException conflict) {
      // A concurrent racer won the (company_id, idempotency_key) unique constraint.
      // The create transaction is now aborted; re-read the winner's sale in a fresh
      // transaction and hand the loser the SAME idempotent result (created=false).
      // No SaleRecorded is written on this path — the winner already emitted it.
      return writer
          .findExistingByKey(command.idempotencyKey())
          .map(response -> new RecordSaleResult(response, false))
          .orElseThrow(() -> conflict);
    }
  }

  /**
   * All sales visible to the bound tenant. Deliberately unfiltered in code: the result set is
   * constrained solely by the auto-applied RLS policy (no {@code WHERE company_id}). This is the
   * read path the tenancy isolation test relies on to prove a sale recorded under tenant A is
   * invisible to tenant B.
   */
  public List<SaleResponse> findAllForCurrentTenant() {
    return writer.findAllForCurrentTenant();
  }

  /**
   * The cashier's "today's transactions" list ({@code GET /api/v1/sales}) — a business unit's sales
   * with {@code occurredAt} in {@code [from, to)}, newest first, hard-capped at 200 rows. The
   * client computes the outlet's local-day bounds; no timezone math happens server-side.
   */
  public List<SaleHistoryResponse> findHistory(UUID businessId, Instant from, Instant to) {
    return writer.findHistory(businessId, from, to);
  }

  /**
   * The per-channel ONLINE sales summary ({@code GET /api/v1/sales/channel-summary}) for a {@code
   * YYYY-MM} period — one row per {@code (channelCode, currency)} bucket: gross sales total and
   * transaction count. Company-wide (no outlet scoping); RLS scopes the tenant.
   */
  public List<ChannelSalesSummaryResponse> channelSalesSummary(String period) {
    return writer.channelSalesSummary(period);
  }
}
