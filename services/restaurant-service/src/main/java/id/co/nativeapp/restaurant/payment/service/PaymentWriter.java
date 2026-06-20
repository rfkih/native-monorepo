package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
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
}
