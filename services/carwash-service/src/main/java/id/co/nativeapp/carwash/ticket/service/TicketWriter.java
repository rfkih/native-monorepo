package id.co.nativeapp.carwash.ticket.service;

import id.co.nativeapp.carwash.catalog.domain.StaffProfile;
import id.co.nativeapp.carwash.catalog.repository.StaffProfileRepository;
import id.co.nativeapp.carwash.outletref.service.OutletAccessGuard;
import id.co.nativeapp.carwash.payment.domain.CarwashPayment;
import id.co.nativeapp.carwash.payment.domain.TenderType;
import id.co.nativeapp.carwash.payment.projection.CarwashPaymentView;
import id.co.nativeapp.carwash.payment.repository.CarwashPaymentRepository;
import id.co.nativeapp.carwash.payment.service.PaymentInstruction;
import id.co.nativeapp.carwash.payment.service.PaymentProviderRegistry;
import id.co.nativeapp.carwash.payment.service.TenderAuthorization;
import id.co.nativeapp.carwash.pricing.domain.PriceBreakdown;
import id.co.nativeapp.carwash.pricing.service.TaxChargeService;
import id.co.nativeapp.carwash.ticket.domain.CarwashTicket;
import id.co.nativeapp.carwash.ticket.domain.CarwashTicketLine;
import id.co.nativeapp.carwash.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import id.co.nativeapp.carwash.ticket.dto.CheckoutResult;
import id.co.nativeapp.carwash.ticket.dto.TicketResponse;
import id.co.nativeapp.carwash.ticket.projection.TicketLineView;
import id.co.nativeapp.carwash.ticket.projection.TicketView;
import id.co.nativeapp.carwash.ticket.repository.CarwashTicketLineRepository;
import id.co.nativeapp.carwash.ticket.repository.CarwashTicketRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write unit of work for carwash ticket checkout — the {@code
 * WashWriter} idempotency/orchestration contract applied to the POS-parity ticket (ADR 0023).
 *
 * <p>A distinct bean (not private methods on {@link TicketService}) so each transactional method is
 * invoked through the Spring proxy — a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC. {@link TicketService} calls
 * {@link #create} and, only on a concurrent-collision conflict, {@link #findExistingByKey} in a
 * <em>separate</em> transaction (a PostgreSQL transaction is poisoned once a constraint fires).
 *
 * <p><strong>Checkout = one atomic unit of work.</strong> {@link #create} runs in its own {@code
 * REQUIRES_NEW} transaction and:
 *
 * <ol>
 *   <li>Idempotency fast path: returns the existing ticket if the key is already present — BEFORE
 *       the outlet guard (an idempotent replay of an already-recorded ticket still returns; only NEW
 *       work is rejected — the restaurant {@code OutletGate} ordering).
 *   <li>{@link OutletAccessGuard#enforce}: the attendant must be assigned to the outlet.
 *   <li>Resolves + validates the requested lines via {@link TicketItemReader} (server-side pricing
 *       — never trusts a client amount).
 *   <li>Resolves the optional washer {@link StaffProfile} and snapshots its employee link.
 *   <li>Resolves the price breakdown via {@link TaxChargeService}.
 *   <li>Persists the ticket + lines, then authorizes the tender via {@link PaymentProviderRegistry}
 *       and persists the {@link CarwashPayment}.
 *   <li>CASH ({@code !tenderType.isDigital()}): links the sale (ticket id = sale id, ADR 0023
 *       decision 2), writes {@code SaleRecorded} + metrics via {@link TicketEventEmitter} — all in
 *       this same transaction (rule 3). Digital (PENDING): no sale id, no events — deferred to
 *       {@link TicketCaptureWriter}.
 * </ol>
 */
@Component
public class TicketWriter {

  private final CarwashTicketRepository ticketRepository;
  private final CarwashTicketLineRepository lineRepository;
  private final CarwashPaymentRepository paymentRepository;
  private final StaffProfileRepository staffProfileRepository;
  private final TicketItemReader itemResolver;
  private final TaxChargeService taxChargeService;
  private final PaymentProviderRegistry paymentProviderRegistry;
  private final OutletAccessGuard outletAccessGuard;
  private final TicketEventEmitter eventEmitter;
  private final TicketPostOutboxHook postOutboxHook;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public TicketWriter(
      CarwashTicketRepository ticketRepository,
      CarwashTicketLineRepository lineRepository,
      CarwashPaymentRepository paymentRepository,
      StaffProfileRepository staffProfileRepository,
      TicketItemReader itemResolver,
      TaxChargeService taxChargeService,
      PaymentProviderRegistry paymentProviderRegistry,
      OutletAccessGuard outletAccessGuard,
      TicketEventEmitter eventEmitter,
      TicketPostOutboxHook postOutboxHook) {
    this.ticketRepository = ticketRepository;
    this.lineRepository = lineRepository;
    this.paymentRepository = paymentRepository;
    this.staffProfileRepository = staffProfileRepository;
    this.itemResolver = itemResolver;
    this.taxChargeService = taxChargeService;
    this.paymentProviderRegistry = paymentProviderRegistry;
    this.outletAccessGuard = outletAccessGuard;
    this.eventEmitter = eventEmitter;
    this.postOutboxHook = postOutboxHook;
  }

  /**
   * Persists a ticket, its lines, and its payment — and, for CASH, the {@code SaleRecorded} +
   * metrics outbox rows — in ONE transaction.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer already
   *     inserted the {@code (company_id, idempotency_key)} row
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CheckoutResult create(CheckoutRequest request) {
    String companyId = TenantContext.require().companyId();

    // Idempotency fast path — BEFORE the outlet guard (restaurant OutletGate ordering: an
    // idempotent replay of an already-recorded ticket still returns; only NEW work is rejected).
    Optional<TicketView> existing =
        ticketRepository.findViewByIdempotencyKey(request.idempotencyKey());
    if (existing.isPresent()) {
      return new CheckoutResult(assembleResponse(existing.get().getId()), false);
    }

    outletAccessGuard.enforce(request.businessId());

    TicketItemReader.ResolvedCart cart = itemResolver.resolve(request.businessId(), request.lines());

    UUID washerEmployeeId = resolveWasher(request.businessId(), request.staffProfileId());

    Instant now = Instant.now();
    Money discount =
        request.discountMinor() != null
            ? Money.ofMinor(request.discountMinor(), cart.currencyCode())
            : null;
    PriceBreakdown breakdown = taxChargeService.resolve(cart.subtotal(), 0L, discount, now);

    CarwashTicket ticket =
        new CarwashTicket(
            request.businessId(),
            request.bay(),
            request.vehiclePlate(),
            request.staffProfileId(),
            washerEmployeeId,
            breakdown,
            now,
            request.idempotencyKey());
    ticket.setCompanyId(companyId);
    CarwashTicket savedTicket = ticketRepository.saveAndFlush(ticket);
    persistLines(savedTicket, cart.lines(), companyId);

    TenderType tenderType = request.payment().tenderType();
    PaymentInstruction instruction =
        new PaymentInstruction(
            savedTicket.getId(),
            tenderType,
            breakdown.grandTotal(),
            request.payment().tenderedMinor(),
            request.idempotencyKey());
    TenderAuthorization auth = paymentProviderRegistry.providerFor(tenderType).authorize(instruction);

    CarwashPayment payment;
    if (!tenderType.isDigital()) {
      Money tendered = Money.ofMinor(request.payment().tenderedMinor(), cart.currencyCode());
      payment =
          CarwashPayment.capturedCash(
              savedTicket.getId(), request.businessId(), breakdown.grandTotal(), tendered,
              auth.change());
    } else {
      payment =
          CarwashPayment.pendingDigital(
              savedTicket.getId(), request.businessId(), tenderType, breakdown.grandTotal(),
              auth.providerRef());
    }
    payment.setCompanyId(companyId);
    paymentRepository.saveAndFlush(payment);

    if (!tenderType.isDigital()) {
      // CASH: revenue is recognised now (ADR 0006, preserved by ADR 0023) — the sale id IS the
      // ticket id (decision 2).
      savedTicket.linkSale(savedTicket.getId());
      ticketRepository.saveAndFlush(savedTicket);
      eventEmitter.recognizeRevenue(
          savedTicket, breakdown, washerEmployeeId, cart.addonTotal(), companyId, tenderType.name(),
          now);
      // Test seam: a no-op in production; a test can install a hook that throws here to prove the
      // ticket, its lines, its payment, AND the outbox rows roll back together (atomicity, rule 3).
      postOutboxHook.afterOutboxWrite(savedTicket);
    }
    // Digital (PENDING): no sale id, no events — deferred to TicketCaptureWriter.

    return new CheckoutResult(assembleResponse(savedTicket.getId()), true);
  }

  /**
   * Re-reads a ticket by idempotency key in a FRESH transaction, used to recover the loser of a
   * concurrent insert race after its own create transaction aborted.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<TicketResponse> findExistingByKey(String idempotencyKey) {
    return ticketRepository
        .findViewByIdempotencyKey(idempotencyKey)
        .map(view -> assembleResponse(view.getId()));
  }

  /**
   * Resolves the optional washer {@link StaffProfile}, validating it exists, is active, and belongs
   * to {@code businessId}, and returns its snapshotted employee link (may be {@code null}).
   *
   * @throws IllegalArgumentException if the profile is unknown, cross-business, or inactive (→ 400)
   */
  private UUID resolveWasher(UUID businessId, UUID staffProfileId) {
    if (staffProfileId == null) {
      return null;
    }
    StaffProfile profile =
        staffProfileRepository
            .findById(staffProfileId)
            .orElseThrow(
                () -> new IllegalArgumentException("Staff profile not found: " + staffProfileId));
    if (!businessId.equals(profile.getBusinessId())) {
      throw new IllegalArgumentException(
          "Staff profile " + staffProfileId + " belongs to a different business");
    }
    if (!profile.isActive()) {
      throw new IllegalArgumentException("Staff profile is inactive: " + staffProfileId);
    }
    return profile.getEmployeeId();
  }

  private void persistLines(
      CarwashTicket ticket, List<TicketItemReader.ResolvedLine> lines, String companyId) {
    for (TicketItemReader.ResolvedLine rl : lines) {
      Money unitPrice = Money.ofMinor(rl.priceMinor(), rl.currency());
      CarwashTicketLine line =
          new CarwashTicketLine(
              ticket.getId(), ticket.getBusinessId(), rl.itemType(), rl.itemId(), rl.name(),
              unitPrice, rl.qty());
      line.setCompanyId(companyId);
      lineRepository.save(line);
    }
    // Explicit flush: the response is assembled via a NATIVE query later in this same transaction,
    // which does not auto-synchronize with Hibernate's persistence context — the physical INSERTs
    // must already be on the connection for the native SELECT to see them (CLAUDE.md convention).
    lineRepository.flush();
  }

  /** Assembles the full response by re-reading the ticket + lines + payment (native projections). */
  private TicketResponse assembleResponse(UUID ticketId) {
    TicketView view =
        ticketRepository.findViewById(ticketId).orElseThrow(() -> new TicketNotFoundException(ticketId));
    List<TicketLineView> lines = lineRepository.findViewsByTicketId(ticketId);
    CarwashPaymentView payment = paymentRepository.findViewByTicketId(ticketId).orElse(null);
    return TicketResponseFactory.toResponse(view, lines, payment);
  }
}
