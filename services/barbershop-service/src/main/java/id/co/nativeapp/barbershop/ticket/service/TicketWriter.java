package id.co.nativeapp.barbershop.ticket.service;

import id.co.nativeapp.barbershop.catalog.domain.StaffProfile;
import id.co.nativeapp.barbershop.catalog.repository.StaffProfileRepository;
import id.co.nativeapp.barbershop.outletref.service.OutletAccessGuard;
import id.co.nativeapp.barbershop.payment.domain.BarbershopPayment;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import id.co.nativeapp.barbershop.payment.projection.BarbershopPaymentView;
import id.co.nativeapp.barbershop.payment.repository.BarbershopPaymentRepository;
import id.co.nativeapp.barbershop.payment.service.PaymentInstruction;
import id.co.nativeapp.barbershop.payment.service.PaymentProviderRegistry;
import id.co.nativeapp.barbershop.payment.service.TenderAuthorization;
import id.co.nativeapp.barbershop.pricing.domain.PriceBreakdown;
import id.co.nativeapp.barbershop.pricing.service.TaxChargeService;
import id.co.nativeapp.barbershop.ticket.domain.BarbershopTicket;
import id.co.nativeapp.barbershop.ticket.domain.BarbershopTicketLine;
import id.co.nativeapp.barbershop.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutRequest;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutResult;
import id.co.nativeapp.barbershop.ticket.dto.TicketResponse;
import id.co.nativeapp.barbershop.ticket.projection.TicketLineView;
import id.co.nativeapp.barbershop.ticket.projection.TicketView;
import id.co.nativeapp.barbershop.ticket.repository.BarbershopTicketLineRepository;
import id.co.nativeapp.barbershop.ticket.repository.BarbershopTicketRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write unit of work for barbershop ticket checkout — the carwash
 * {@code TicketWriter} idempotency/orchestration contract, verbatim (ADR 0024).
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
 *       the outlet guard (an idempotent replay of an already-recorded ticket still returns; only
 *       NEW work is rejected — the restaurant {@code OutletGate} ordering).
 *   <li>{@link OutletAccessGuard#enforce}: the attendant must be assigned to the outlet.
 *   <li>Resolves + validates the requested lines via {@link TicketItemReader} (server-side pricing
 *       — never trusts a client amount).
 *   <li>Resolves the MANDATORY barber {@link StaffProfile} and snapshots its employee link (ADR
 *       0024 — every cut has a barber; unlike carwash's optional washer, this is never skipped).
 *   <li>Resolves the price breakdown via {@link TaxChargeService}.
 *   <li>Persists the ticket + lines, then authorizes the tender via {@link PaymentProviderRegistry}
 *       and persists the {@link BarbershopPayment}.
 *   <li>CASH ({@code !tenderType.isDigital()}): links the sale (ticket id = sale id, ADR 0023
 *       decision 2), writes {@code SaleRecorded} + metrics via {@link TicketEventEmitter} — all in
 *       this same transaction (rule 3). Digital (PENDING): no sale id, no events — deferred to
 *       {@link TicketCaptureWriter}.
 * </ol>
 */
@Component
public class TicketWriter {

  private final BarbershopTicketRepository ticketRepository;
  private final BarbershopTicketLineRepository lineRepository;
  private final BarbershopPaymentRepository paymentRepository;
  private final StaffProfileRepository staffProfileRepository;
  private final TicketItemReader itemResolver;
  private final TaxChargeService taxChargeService;
  private final PaymentProviderRegistry paymentProviderRegistry;
  private final OutletAccessGuard outletAccessGuard;
  private final TicketEventEmitter eventEmitter;
  private final TicketPostOutboxHook postOutboxHook;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public TicketWriter(
      BarbershopTicketRepository ticketRepository,
      BarbershopTicketLineRepository lineRepository,
      BarbershopPaymentRepository paymentRepository,
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

    TicketItemReader.ResolvedCart cart =
        itemResolver.resolve(request.businessId(), request.lines());

    // MANDATORY barber attribution (ADR 0024): staffProfileId is @NotNull at the DTO boundary
    // (400 problem when missing), so this is defensive re-validation, not the primary guard.
    UUID barberEmployeeId = resolveBarber(request.businessId(), request.staffProfileId());

    Instant now = Instant.now();
    Money discount =
        request.discountMinor() != null
            ? Money.ofMinor(request.discountMinor(), cart.currencyCode())
            : null;
    PriceBreakdown breakdown = taxChargeService.resolve(cart.subtotal(), 0L, discount, now);

    // A zero (or negative) grand total must never record revenue: a fully-discounted or
    // zero-priced cart would otherwise emit a zero-amount SaleRecorded, which the finance journal
    // rejects (a balanced entry needs a positive total). 400 — mirrors carwash review S1.
    if (breakdown.grandTotal().amountMinor() <= 0L) {
      throw new IllegalArgumentException("ticket grand total must be positive");
    }

    BarbershopTicket ticket =
        new BarbershopTicket(
            request.businessId(),
            request.chair(),
            request.staffProfileId(),
            barberEmployeeId,
            breakdown,
            now,
            request.idempotencyKey());
    ticket.setCompanyId(companyId);
    BarbershopTicket savedTicket = ticketRepository.saveAndFlush(ticket);
    persistLines(savedTicket, cart.lines(), companyId);

    TenderType tenderType = request.payment().tenderType();
    PaymentInstruction instruction =
        new PaymentInstruction(
            savedTicket.getId(),
            tenderType,
            breakdown.grandTotal(),
            request.payment().tenderedMinor(),
            request.idempotencyKey());
    TenderAuthorization auth =
        paymentProviderRegistry.providerFor(tenderType).authorize(instruction);

    BarbershopPayment payment;
    if (!tenderType.isDigital()) {
      Money tendered = Money.ofMinor(request.payment().tenderedMinor(), cart.currencyCode());
      payment =
          BarbershopPayment.capturedCash(
              savedTicket.getId(),
              request.businessId(),
              breakdown.grandTotal(),
              tendered,
              auth.change());
    } else {
      payment =
          BarbershopPayment.pendingDigital(
              savedTicket.getId(),
              request.businessId(),
              tenderType,
              breakdown.grandTotal(),
              auth.providerRef());
    }
    payment.setCompanyId(companyId);
    paymentRepository.saveAndFlush(payment);

    if (!tenderType.isDigital()) {
      // CASH: revenue is recognised now (ADR 0006, preserved by ADR 0023/0024) — the sale id IS
      // the ticket id (decision 2).
      savedTicket.linkSale(savedTicket.getId());
      ticketRepository.saveAndFlush(savedTicket);
      eventEmitter.recognizeRevenue(
          savedTicket, breakdown, barberEmployeeId, companyId, tenderType.name(), now);
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
   * Resolves the MANDATORY barber {@link StaffProfile}, validating it exists, is active, and
   * belongs to {@code businessId}, and returns its snapshotted employee link (may be {@code null}
   * — the LINK stays optional even though the PROFILE selection is mandatory).
   *
   * @throws IllegalArgumentException if the profile is unknown, cross-business, or inactive (→ 400)
   */
  private UUID resolveBarber(UUID businessId, UUID staffProfileId) {
    Objects.requireNonNull(staffProfileId, "staffProfileId");
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
      BarbershopTicket ticket, List<TicketItemReader.ResolvedLine> lines, String companyId) {
    for (TicketItemReader.ResolvedLine rl : lines) {
      Money unitPrice = Money.ofMinor(rl.priceMinor(), rl.currency());
      BarbershopTicketLine line =
          new BarbershopTicketLine(
              ticket.getId(),
              ticket.getBusinessId(),
              rl.itemType(),
              rl.itemId(),
              rl.name(),
              unitPrice,
              rl.qty());
      line.setCompanyId(companyId);
      lineRepository.save(line);
    }
    // Explicit flush: the response is assembled via a NATIVE query later in this same transaction,
    // which does not auto-synchronize with Hibernate's persistence context — the physical INSERTs
    // must already be on the connection for the native SELECT to see them (CLAUDE.md convention).
    lineRepository.flush();
  }

  /**
   * Assembles the full response by re-reading the ticket + lines + payment (native projections).
   */
  private TicketResponse assembleResponse(UUID ticketId) {
    TicketView view =
        ticketRepository
            .findViewById(ticketId)
            .orElseThrow(() -> new TicketNotFoundException(ticketId));
    List<TicketLineView> lines = lineRepository.findViewsByTicketId(ticketId);
    BarbershopPaymentView payment = paymentRepository.findViewByTicketId(ticketId).orElse(null);
    return TicketResponseFactory.toResponse(view, lines, payment);
  }
}
