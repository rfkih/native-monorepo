package id.co.nativeapp.finance.ap.domain;

import java.util.Arrays;

/**
 * Spreads a bill's HEADER discount across its lines (ADR 0084) so the expense/inventory split and
 * each purchase-event line value are net of it — integer minor units only, never a float.
 *
 * <p>Largest-remainder method: each line takes {@code floor(discount × line / subtotal)}, then the
 * leftover units go one at a time to the lines with the largest fractional remainder (ties broken
 * by position, first line first). The allocations always sum to the discount exactly, and a line
 * never carries more than its own total. A zero subtotal or a zero discount allocates nothing.
 */
public final class DiscountAllocation {

  private DiscountAllocation() {
    // static
  }

  /**
   * @param discountMinor the header discount, {@code 0 ≤ discount ≤ Σ lineTotals}
   * @param lineTotalsMinor each line's gross total, in the bill's order
   * @return each line's share of the discount, same order; {@code Σ == discountMinor}
   * @throws IllegalArgumentException when the discount is negative or exceeds the lines' sum
   */
  public static long[] allocate(long discountMinor, long[] lineTotalsMinor) {
    if (discountMinor < 0) {
      throw new IllegalArgumentException("discount must not be negative: " + discountMinor);
    }
    long subtotal = 0;
    for (long t : lineTotalsMinor) {
      if (t < 0) {
        throw new IllegalArgumentException("line total must not be negative: " + t);
      }
      subtotal += t;
    }
    if (discountMinor > subtotal) {
      throw new IllegalArgumentException(
          "discount " + discountMinor + " exceeds the lines' sum " + subtotal);
    }
    long[] shares = new long[lineTotalsMinor.length];
    if (discountMinor == 0 || subtotal == 0) {
      return shares;
    }
    // floor shares + the fractional remainders (scaled by the subtotal, so no division rounding)
    long[] remainders = new long[lineTotalsMinor.length];
    long allocated = 0;
    for (int i = 0; i < lineTotalsMinor.length; i++) {
      // discount × line may exceed long for absurd figures; Math.multiplyHigh-free path via
      // BigInteger
      java.math.BigInteger product =
          java.math.BigInteger.valueOf(discountMinor)
              .multiply(java.math.BigInteger.valueOf(lineTotalsMinor[i]));
      java.math.BigInteger[] qr =
          product.divideAndRemainder(java.math.BigInteger.valueOf(subtotal));
      shares[i] = qr[0].longValueExact();
      remainders[i] = qr[1].longValueExact();
      allocated += shares[i];
    }
    long leftover = discountMinor - allocated;
    // hand the leftover units to the largest remainders, first-come on ties
    Integer[] order = new Integer[lineTotalsMinor.length];
    for (int i = 0; i < order.length; i++) {
      order[i] = i;
    }
    Arrays.sort(
        order,
        (a, b) -> {
          int byRemainder = Long.compare(remainders[b], remainders[a]);
          return byRemainder != 0 ? byRemainder : Integer.compare(a, b);
        });
    for (int k = 0; k < order.length && leftover > 0; k++) {
      int i = order[k];
      if (shares[i] < lineTotalsMinor[i]) {
        shares[i] += 1;
        leftover -= 1;
      }
    }
    return shares;
  }
}
