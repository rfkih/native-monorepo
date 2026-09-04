package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.projection.PaymentReceiptView;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.restaurant.sale.domain.OperatorMismatchException;
import id.co.nativeapp.restaurant.sale.service.OperatorRequiredGuard;
import id.co.nativeapp.security.OperatorContextProvider;
import id.co.nativeapp.security.OperatorPrincipal;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} writes for the payment feature — a distinct bean so each method
 * is invoked through the Spring proxy and the {@link RlsAutoApplyAspect} sets the tenant GUC (the
 * {@code SaleWriter} pattern).
 *
 * <p>{@link #captureCashInCurrentTx} runs with propagation {@code MANDATORY} so it <em>joins</em>
 * the checkout transaction opened by {@code OrderWriter.checkout}: the order rows, the sale row,
 * the {@code SaleRecorded} outbox row, and the captured {@code payment} row all commit — or all
 * roll back — as one physical transaction (rule 3). Cash settles synchronously, so revenue (the
 * linked sale) and the captured tender are recorded together.
 *
 * <p>{@link #recordPendingDigitalInCurrentTx} similarly joins the checkout transaction to create a
 * {@link Payment.Status#PENDING} row for a digital tender (QRIS/CARD). No sale is recorded at this
 * point — revenue is deferred to the explicit {@code capture} call (ADR 0006 invariant). The order,
 * its lines, and the pending payment all commit together; no {@code SaleRecorded} is emitted until
 * capture.
 *
 * <p><strong>ADR 0049 P4.</strong> {@link #recordPendingDigitalInCurrentTx} is the SINGLE
 * PENDING-minting choke point for every digital-tender checkout path (legacy order checkout,
 * park-then-pay) — so it is where the {@code operator-required} guard for a device (outlet-
 * terminal) digital sale is centralized, and where the ring-time operator (if any) is stamped onto
 * the PENDING {@link Payment} row for {@code PaymentCaptureWriter#capture} to read back and thread
 * onto the sale at async capture time.
 */
@Component
public class PaymentWriter {

  private final PaymentProviderRegistry providers;
  private final PaymentRepository repository;
  private final OperatorContextProvider operatorContextProvider;
  private final OperatorRequiredGuard operatorRequiredGuard;

  public PaymentWriter(
      PaymentProviderRegistry providers,
      PaymentRepository repository,
      OperatorContextProvider operatorContextProvider,
      OperatorRequiredGuard operatorRequiredGuard) {
    this.providers = providers;
    this.repository = repository;
    this.operatorContextProvider = operatorContextProvider;
    this.operatorRequiredGuard = operatorRequiredGuard;
  }

  /**
   * Records a synchronously-captured CASH tender against a just-recorded sale, joining the caller's
   * transaction. The amount due is the server-computed order total (never trusted from the client).
   *
   * @throws IllegalArgumentException if the tender is not cash (digital tenders are not captured at
   *     checkout — they are PENDING until {@code capture}), if no tendered amount is supplied, or
   *     if the tendered amount is less than the amount due
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PaymentResponse captureCashInCurrentTx(
      PaymentInstruction instruction, UUID saleId, Instant capturedAt) {
    if (instruction.tenderType() != TenderType.CASH) {
      throw new IllegalArgumentException(
          "only CASH is captured synchronously at checkout; got " + instruction.tenderType());
    }
    String companyId = TenantContext.require().companyId();

    Long tenderedMinor = instruction.tenderedMinor();
    if (tenderedMinor == null) {
      throw new IllegalArgumentException("cash payment requires a tendered amount");
    }
    Money amount = instruction.amount();
    Money tendered = Money.ofMinor(tenderedMinor, amount.currency().getCurrencyCode());

    // The provider validates (tendered >= amount) and computes the change.
    TenderAuthorization auth =
        providers.providerFor(instruction.tenderType()).authorize(instruction);

    Payment payment =
        Payment.capturedCash(
            instruction.orderId(),
            instruction.businessId(),
            amount,
            tendered,
            auth.change(),
            saleId,
            capturedAt,
            instruction.idempotencyKey());
    payment.setCompanyId(companyId);
    return PaymentResponse.from(repository.saveAndFlush(payment));
  }

  /**
   * Records a PENDING digital tender (QRIS/CARD) by joining the caller's checkout transaction
   * (propagation {@code MANDATORY}). No sale is recorded here; revenue is deferred to the explicit
   * capture endpoint (ADR 0006: revenue-at-capture invariant). The digital provider returns a
   * {@code PENDING} authorization with a placeholder {@code providerRef}; the {@code
   * providerPending = true} flag is set on the payment row as the illustrative-data marker (ADR
   * 0007).
   *
   * <p>The order, its lines, and the pending payment all commit in one physical transaction. If the
   * checkout rolls back (e.g. an invalid item) the pending row is never persisted — there is no
   * dangling PENDING payment for a non-existent order.
   *
   * <p><strong>ADR 0049 P4.</strong> The digital-tender CHECKOUT is the synchronous choke point for
   * a device (outlet-terminal) sale — the actual revenue-recognizing {@code SaleRecorded} does not
   * emit until the async capture, which has no live operator session to enforce against (see {@code
   * OperatorRequiredGuard} javadoc). So the guard is enforced HERE: a device actor with no verified
   * operator session is rejected outright ({@code 409 operator-required}) before the PENDING
   * payment is ever minted. When an operator session IS present, its {@code operatorUserId} is
   * stamped onto the PENDING payment ({@link Payment#stampSeller}) so {@code
   * PaymentCaptureWriter#capture} can thread it onto the sale (and the commission metric) at async
   * capture time — otherwise a PIN-rung digital sale would silently fall back to the bound (device)
   * actor once outlet credentials exist.
   *
   * @param instruction the authorization instruction, must be a digital tender
   * @param occurredAt the checkout instant (stamped on the payment)
   * @return the PENDING payment response (status = PENDING, providerPending = true, saleId = null)
   * @throws IllegalArgumentException if the tender type is CASH
   * @throws id.co.nativeapp.restaurant.sale.domain.OperatorRequiredException if the current request
   *     is a device (outlet-terminal) actor with no verified operator session bound
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PaymentResponse recordPendingDigitalInCurrentTx(
      PaymentInstruction instruction, Instant occurredAt) {
    if (!instruction.tenderType().isDigital()) {
      throw new IllegalArgumentException(
          "recordPendingDigital requires a digital tender; got " + instruction.tenderType());
    }
    String companyId = TenantContext.require().companyId();

    // ADR 0049 P4: read once, up front, so the SAME operator context both enforces the device
    // guard AND stamps the ring-time seller onto the PENDING payment below.
    Optional<OperatorPrincipal> operator = operatorContextProvider.current();
    operatorRequiredGuard.enforce(instruction.businessId(), operator);

    TenderAuthorization auth =
        providers.providerFor(instruction.tenderType()).authorize(instruction);

    Payment payment =
        Payment.pendingDigital(
            instruction.orderId(),
            instruction.businessId(),
            instruction.tenderType(),
            instruction.amount(),
            auth.providerRef(),
            occurredAt,
            instruction.idempotencyKey());
    // ADR 0049 P4: stamp the ring-time operator (if any) BEFORE the first save — sold_by_user_id
    // is updatable=false, so this must happen at creation, exactly like Sale#stampSeller. The
    // operator token's companyId/businessId MUST match the bound tenant / this checkout's outlet
    // before we trust it as the ring-time seller — the HMAC signature proves authenticity fleet-
    // wide (the key is shared), NOT that the token belongs HERE, so a stolen/misdirected but
    // validly-signed token is rejected outright (OperatorMismatchException, 409) before any PENDING
    // payment is minted. This is the SAME assertion the synchronous cash path enforces
    // (OperatorMismatchException.requireMatch), applied here so the seller PaymentCaptureWriter
    // reads back at async capture is guaranteed already-validated (SaleWriter trusts it as-is).
    operator.ifPresent(
        principal -> {
          OperatorMismatchException.requireMatch(
              principal.companyId(), principal.businessId(), companyId, instruction.businessId());
          payment.stampSeller(principal.operatorUserId());
        });
    payment.setCompanyId(companyId);
    return PaymentResponse.from(repository.saveAndFlush(payment));
  }

  /**
   * Records a PENDING digital tender (QRIS/CARD) against a BILL (V38) by joining the caller's
   * transaction (propagation {@code MANDATORY}) — {@code BillWriter.initiatePendingPayment}'s own
   * unit of work. No sale is recorded here; revenue is deferred to {@code
   * BillPaymentCaptureWriter#capture} (ADR 0006's revenue-at-capture invariant, extended to bills).
   *
   * <p>Mirrors {@link #recordPendingDigitalInCurrentTx} closely, including the ADR 0049 P4
   * device-operator guard (a device actor with no verified operator session is rejected outright
   * before the PENDING payment is ever minted) — this IS the second PENDING-digital-mint choke
   * point the guard's javadoc anticipates. The one structural difference: there is no {@code
   * orderId} to authorize against, so the {@link PaymentInstruction} built here carries {@code
   * orderId = null} — safe because neither {@link PaymentProviderRegistry#providerFor} nor {@link
   * DigitalProvider#authorize} ever reads it (only {@code tenderType}/{@code idempotencyKey}).
   *
   * @param billId the bill this payment settles
   * @param amount the check's grand total (the caller — {@code BillWriter} — has already run the
   *     SAME promotions + tax computation {@code payBill} uses)
   * @param discountMinor the manual discount requested, minor units, or {@code null} — stamped onto
   *     the PENDING payment so capture can reproduce this same grand total deterministically
   * @param occurredAt the mint instant (stamped on the payment; also the pricing instant a capture
   *     re-resolves effective-dated tax rules against, so a later capture reproduces this same
   *     breakdown even if a newer rule version has since become effective)
   * @param paymentIdempotencyKey this PENDING payment's OWN idempotency key — the caller mints a
   *     fresh one per attempt, since a self-healed retry must not collide with an already-ABANDONED
   *     row under the {@code uq_payment_company_idempotency} constraint. NOTE (HIGH fix): this is
   *     NOT the eventual check's sale idempotency key — {@code BillPaymentCaptureWriter#capture}
   *     derives that separately, on-the-fly, from the persisted payment's OWN id at capture time
   *     (mirroring the order path), so it can never collide across a bill's multiple checks
   * @return the PENDING payment response (status = PENDING, providerPending = true, saleId = null)
   * @throws IllegalArgumentException if {@code tenderType} is not digital
   * @throws id.co.nativeapp.restaurant.sale.domain.OperatorRequiredException if the current request
   *     is a device (outlet-terminal) actor with no verified operator session bound
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  @Transactional(propagation = Propagation.MANDATORY)
  public PaymentResponse recordPendingBillDigital(
      UUID billId,
      UUID businessId,
      TenderType tenderType,
      Money amount,
      Long discountMinor,
      Instant occurredAt,
      String paymentIdempotencyKey) {
    if (tenderType == null || !tenderType.isDigital()) {
      throw new IllegalArgumentException(
          "recordPendingBillDigital requires a digital tender; got " + tenderType);
    }
    String companyId = TenantContext.require().companyId();

    Optional<OperatorPrincipal> operator = operatorContextProvider.current();
    operatorRequiredGuard.enforce(businessId, operator);

    PaymentInstruction instruction =
        new PaymentInstruction(null, businessId, tenderType, amount, null, paymentIdempotencyKey);
    TenderAuthorization auth = providers.providerFor(tenderType).authorize(instruction);

    Payment payment =
        Payment.pendingDigitalForBill(
            billId,
            businessId,
            tenderType,
            amount,
            discountMinor,
            auth.providerRef(),
            occurredAt,
            paymentIdempotencyKey);
    // Same tenant/outlet assertion as recordPendingDigitalInCurrentTx — see its comment.
    operator.ifPresent(
        principal -> {
          OperatorMismatchException.requireMatch(
              principal.companyId(), principal.businessId(), companyId, businessId);
          payment.stampSeller(principal.operatorUserId());
        });
    payment.setCompanyId(companyId);
    return PaymentResponse.from(repository.saveAndFlush(payment));
  }

  /**
   * Records a synchronously-captured ONLINE (platform-collected) tender against a just-recorded
   * sale, joining the caller's transaction (ADR 0036 Phase B2). Mirrors {@link
   * #captureCashInCurrentTx} but with NO tendered requirement: the platform already remitted the
   * exact {@code amount} at acceptance, so {@code Payment.capturedOnline} stores {@code tendered ==
   * amount} and {@code change == 0} unconditionally — there is nothing to authorize via a {@link
   * PaymentProvider} (no provider call, mirroring {@link #captureZeroResidualInCurrentTx}).
   *
   * @param channelCode the company-managed {@code sales_channel.code} this sale rang through
   *     (REQUIRED — the calling writer validates it exists and is active before calling this)
   * @throws IllegalArgumentException if the tender is not ONLINE
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PaymentResponse captureOnlineInCurrentTx(
      PaymentInstruction instruction, String channelCode, UUID saleId, Instant capturedAt) {
    if (instruction.tenderType() != TenderType.ONLINE) {
      throw new IllegalArgumentException(
          "captureOnlineInCurrentTx requires the ONLINE tender; got " + instruction.tenderType());
    }
    String companyId = TenantContext.require().companyId();

    Payment payment =
        Payment.capturedOnline(
            instruction.orderId(),
            instruction.businessId(),
            instruction.amount(),
            channelCode,
            saleId,
            capturedAt,
            instruction.idempotencyKey());
    payment.setCompanyId(companyId);
    return PaymentResponse.from(repository.saveAndFlush(payment));
  }

  /**
   * Phase 4 (ADR 0027): persists a ZERO-amount CAPTURED payment when a gift-card redemption fully
   * covers the sale (residual == 0) — the "fully-gift-card-paid" contract. NO {@link
   * PaymentProviderRegistry} call is made (there is nothing left to authorize); the row is built
   * directly via {@link Payment#capturedCash} (cash-path semantics, per the task's pinned wording),
   * regardless of what tender the till originally requested — {@code tendered = change = amount =
   * 0}, which trivially satisfies {@code change >= 0}.
   *
   * <p>Joins the caller's transaction (propagation {@code MANDATORY}), exactly like {@link
   * #captureCashInCurrentTx}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PaymentResponse captureZeroResidualInCurrentTx(
      UUID orderId,
      UUID businessId,
      String currencyCode,
      UUID saleId,
      Instant capturedAt,
      String idempotencyKey) {
    String companyId = TenantContext.require().companyId();
    Money zero = Money.zero(Currency.getInstance(currencyCode));
    Payment payment =
        Payment.capturedCash(
            orderId, businessId, zero, zero, zero, saleId, capturedAt, idempotencyKey);
    payment.setCompanyId(companyId);
    return PaymentResponse.from(repository.saveAndFlush(payment));
  }

  /**
   * The most recent payment against an order — backs the order read path's receipt-rebuild need
   * ({@code GET /api/v1/orders/{id}}, {@code OrderWriter.findById}) ONLY; the checkout/pay-parked
   * write paths already attach the payment they just captured via {@code OrderResponse.withPayment}
   * and never call this. Read path: a native-query projection ({@link PaymentReceiptView}), mapped
   * to the response HERE in the service layer (a {@code dto} may not depend on the {@code
   * projection} layer — CODE-STRUCTURE §3.3/§6).
   *
   * @param orderId the order to find the latest payment for
   * @return the mapped response, or empty when the order has no payment (e.g. a parked, unpaid
   *     order) — the caller leaves {@code OrderResponse.payment} {@code null} in that case
   */
  @Transactional(readOnly = true)
  public Optional<PaymentResponse> findLatestForOrder(UUID orderId) {
    return repository.findLatestReceiptViewByOrderId(orderId).map(PaymentWriter::toResponse);
  }

  /**
   * Maps a read projection to the response shape (currency CHAR(3) is right-padded — strip it).
   * {@code billId} is always {@code null} here — {@link PaymentReceiptView} backs ONLY the order
   * read path's receipt-rebuild need ({@link #findLatestForOrder}), which is always order-scoped.
   */
  private static PaymentResponse toResponse(PaymentReceiptView view) {
    return new PaymentResponse(
        view.getId(),
        view.getOrderId(),
        null,
        view.getTenderType(),
        view.getStatus(),
        view.getAmountMinor(),
        view.getCurrency().strip(),
        view.getTenderedMinor(),
        view.getChangeMinor(),
        view.isProviderPending(),
        view.getSaleId(),
        view.getRefundedMinor());
  }
}
