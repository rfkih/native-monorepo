package id.co.nativeapp.barbershop.payment.service;

import id.co.nativeapp.barbershop.payment.domain.BarbershopPayment;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import id.co.nativeapp.money.Money;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The live cash tender (ADR 0006), ported verbatim from carwash-service's {@code payment} feature.
 * Cash settles instantly — no external call: it validates the tendered amount covers the amount
 * due, computes the change with {@link Money} (integer minor units; whole rupiah for IDR — never a
 * float), and returns {@link BarbershopPayment.Status#CAPTURED}.
 *
 * <p>Change is the only money math here and it is exact: {@code change = tendered − amount}. (Cash
 * rounding-to-increment — Rp100/500 — is a configurable, currently-off enhancement; the default is
 * exact whole-unit change.)
 */
@Component
public class CashProvider implements PaymentProvider {

  @Override
  public Set<TenderType> supportedTenders() {
    return Set.of(TenderType.CASH);
  }

  @Override
  public TenderAuthorization authorize(PaymentInstruction instruction) {
    Long tenderedMinor = instruction.tenderedMinor();
    if (tenderedMinor == null) {
      throw new IllegalArgumentException("cash payment requires a tendered amount");
    }
    Money amount = instruction.amount();
    Money tendered = Money.ofMinor(tenderedMinor, amount.currency().getCurrencyCode());
    Money change = tendered.minus(amount); // same currency by construction
    if (change.amountMinor() < 0L) {
      throw new IllegalArgumentException("tendered amount is less than the amount due");
    }
    return new TenderAuthorization(BarbershopPayment.Status.CAPTURED, null, false, change);
  }
}
