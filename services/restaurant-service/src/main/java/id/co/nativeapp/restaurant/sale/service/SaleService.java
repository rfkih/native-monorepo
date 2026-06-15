package id.co.nativeapp.restaurant.sale.service;

import id.co.nativeapp.restaurant.sale.domain.Sale;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
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
 * concurrent record-sale calls with the same key can both pass the {@code findByIdempotencyKey}
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
          .map(sale -> new RecordSaleResult(sale, false))
          .orElseThrow(() -> conflict);
    }
  }

  /**
   * All sales visible to the bound tenant. Deliberately unfiltered in code: the result set is
   * constrained solely by the auto-applied RLS policy (no {@code WHERE company_id}). This is the
   * read path the tenancy isolation test relies on to prove a sale recorded under tenant A is
   * invisible to tenant B.
   */
  public List<Sale> findAllForCurrentTenant() {
    return writer.findAllForCurrentTenant();
  }
}
