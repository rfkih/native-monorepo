package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.Currency;
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
 */
@Component
public class PaymentWriter {

  private final PaymentProviderRegistry providers;
  private final PaymentRepository repository;

  public PaymentWriter(PaymentProviderRegistry providers, PaymentRepository repository) {
    this.providers = providers;
    this.repository = repository;
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
   * @param instruction the authorization instruction, must be a digital tender
   * @param occurredAt the checkout instant (stamped on the payment)
   * @return the PENDING payment response (status = PENDING, providerPending = true, saleId = null)
   * @throws IllegalArgumentException if the tender type is CASH
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PaymentResponse recordPendingDigitalInCurrentTx(
      PaymentInstruction instruction, Instant occurredAt) {
    if (!instruction.tenderType().isDigital()) {
      throw new IllegalArgumentException(
          "recordPendingDigital requires a digital tender; got " + instruction.tenderType());
    }
    String companyId = TenantContext.require().companyId();

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
}
