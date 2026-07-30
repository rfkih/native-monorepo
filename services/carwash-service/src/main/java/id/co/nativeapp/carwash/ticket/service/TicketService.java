package id.co.nativeapp.carwash.ticket.service;

import id.co.nativeapp.carwash.config.CarwashProperties;
import id.co.nativeapp.carwash.payment.domain.CarwashPayment;
import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import id.co.nativeapp.carwash.ticket.dto.CheckoutResult;
import id.co.nativeapp.carwash.ticket.dto.PriceBreakdownResponse;
import id.co.nativeapp.carwash.ticket.dto.QuoteRequest;
import id.co.nativeapp.carwash.ticket.dto.TicketResponse;
import id.co.nativeapp.carwash.wash.domain.NotEntitledException;
import id.co.nativeapp.entitlementcheck.EntitlementChecker;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates carwash ticket quote/checkout/capture/get — the {@link
 * id.co.nativeapp.carwash.wash.service.WashService WashService} contract, verbatim (ADR 0023).
 *
 * <p><strong>Entitlement gate (BEFORE any side effect).</strong> {@link #quote}, {@link #checkout},
 * and {@link #capture} all consult the shared {@link EntitlementChecker} for the bound tenant and
 * the configured {@code carwash} module before doing any work. A company NOT entitled gets {@link
 * NotEntitledException} → {@code 403}, and NO ticket row / event is produced. {@link #getById} is
 * deliberately UNGATED — a read carries no revenue/side-effect risk (the same asymmetry {@code
 * CatalogService} applies to catalog reads).
 *
 * <p><strong>Checkout idempotency (the {@code WashService} contract, verbatim).</strong> Not
 * {@code @Transactional} itself: {@link #checkout} runs the create in {@link TicketWriter}'s own
 * {@code REQUIRES_NEW} transaction so a concurrent-collision conflict — {@link
 * DataIntegrityViolationException} on the {@code (company_id, idempotency_key)} unique constraint —
 * can be recovered by a SEPARATE-transaction re-read (a failed transaction is poisoned for any
 * further query in PostgreSQL).
 */
@Service
public class TicketService {

  private final TicketWriter writer;
  private final TicketCaptureWriter captureWriter;
  private final TicketReader reader;
  private final EntitlementChecker entitlementChecker;
  private final String moduleKey;

  public TicketService(
      TicketWriter writer,
      TicketCaptureWriter captureWriter,
      TicketReader reader,
      EntitlementChecker entitlementChecker,
      CarwashProperties properties) {
    this.writer = writer;
    this.captureWriter = captureWriter;
    this.reader = reader;
    this.entitlementChecker = entitlementChecker;
    this.moduleKey = properties.moduleKey();
  }

  /** A stateless price quote — gated (BEFORE any DB read) exactly like checkout/capture. */
  public PriceBreakdownResponse quote(QuoteRequest request) {
    requireEntitled();
    return reader.quote(request);
  }

  /**
   * Checks out a ticket idempotently — GATED by the carwash entitlement. A retry — sequential OR
   * concurrent — with the same {@code idempotency_key} resolves to the existing ticket ({@code
   * created=false}) and emits no second event.
   *
   * @throws NotEntitledException if the bound company is not entitled to the carwash module (→ 403)
   */
  public CheckoutResult checkout(CheckoutRequest request) {
    requireEntitled();
    try {
      return writer.create(request);
    } catch (DataIntegrityViolationException conflict) {
      // A concurrent racer won the (company_id, idempotency_key) unique constraint. The create
      // transaction is now aborted; re-read the winner's ticket in a fresh transaction and hand the
      // loser the SAME idempotent result (created=false). No second event is written on this path.
      return writer
          .findExistingByKey(request.idempotencyKey())
          .map(ticket -> new CheckoutResult(ticket, false))
          .orElseThrow(() -> conflict);
    }
  }

  /**
   * Captures a PENDING digital tender's payment — GATED by the carwash entitlement.
   *
   * <p><strong>Concurrent capture.</strong> Two racing captures of the same ticket are serialized
   * by the optimistic {@code @Version} on the payment/ticket rows: the loser's flush fails BEFORE
   * its event is written, so exactly one {@code SaleRecorded} is ever emitted. The loser lands here
   * as an {@link OptimisticLockingFailureException}; its capture transaction is aborted, so we
   * re-read in a FRESH transaction and — the winner having captured — hand the loser the same
   * captured ticket (the idempotent-retry semantics a sequential re-capture already gets).
   *
   * @throws NotEntitledException if the bound company is not entitled to the carwash module (→ 403)
   */
  public TicketResponse capture(UUID ticketId) {
    requireEntitled();
    try {
      return captureWriter.capture(ticketId);
    } catch (OptimisticLockingFailureException raced) {
      TicketResponse current = reader.getById(ticketId);
      if (current.payment() != null
          && CarwashPayment.Status.CAPTURED.name().equals(current.payment().status())) {
        return current;
      }
      // The version bump came from something other than a winning capture — surface the conflict.
      throw raced;
    }
  }

  /** Fetches a single ticket by id. Deliberately UNGATED — a read carries no side effect. */
  public TicketResponse getById(UUID ticketId) {
    return reader.getById(ticketId);
  }

  private void requireEntitled() {
    String companyId = TenantContext.require().companyId();
    if (!entitlementChecker.isEntitled(companyId, moduleKey)) {
      throw new NotEntitledException(moduleKey);
    }
  }
}
