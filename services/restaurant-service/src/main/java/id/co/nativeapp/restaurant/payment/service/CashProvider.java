package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The live cash tender (ADR 0006). Cash settles instantly — no external call: it validates the
 * tendered amount covers the amount due, computes the change with {@link Money} (integer minor
 * units; whole rupiah for IDR — never a float), and returns {@link Payment.Status#CAPTURED}.
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
    return new TenderAuthorization(Payment.Status.CAPTURED, null, false, change);
  }
}
