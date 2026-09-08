package id.co.nativeapp.finance.platform.domain;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Splits one payout's deduction across the sources it cleared (ADR 0076 phase 2).
 *
 * <p>A merchant reads ONE net figure off the bank statement; the deduction is whatever is left of
 * the gross. But that deduction is not one expense: a Shopee transfer covering ShopeeFood orders
 * and counter QRIS carries a marketplace commission on one part and an MDR on the other, and they
 * book to different accounts (5710 / 5720). Booking the lot to either one would quietly make "QRIS
 * fee" mean nothing.
 *
 * <p>Pure and total — no DB, no clock, no rounding surprises. Two properties the caller depends on
 * and {@code SettlementAllocationTest} pins:
 *
 * <ol>
 *   <li>the allocated fees sum EXACTLY to the payout's fee (the journal must balance to the rupiah,
 *       so the rounding remainder is not dropped — it lands on the largest line, where it is
 *       proportionally smallest);
 *   <li>no line is allocated a negative fee.
 * </ol>
 */
public final class SettlementAllocation {

  private SettlementAllocation() {}

  /** One source being cleared by a payout, with the gross it settles. */
  public record SourceLine(SettlementSourceKind kind, String channelCode, long grossMinor) {}

  /** A {@link SourceLine} with its share of the payout's deduction. */
  public record AllocatedLine(
      SettlementSourceKind kind, String channelCode, long grossMinor, long feeMinor) {}

  /**
   * Allocates {@code feeMinor} across {@code lines} in proportion to each line's gross.
   *
   * <p>Each line gets {@code floor(fee × gross_i ÷ Σgross)} — computed through {@link BigInteger}
   * because {@code fee × gross} overflows a long at realistic IDR magnitudes — and the shortfall
   * left by flooring goes to the largest line.
   *
   * @throws IllegalArgumentException if there are no lines, any gross is not positive, or the fee
   *     is negative
   */
  public static List<AllocatedLine> allocate(List<SourceLine> lines, long feeMinor) {
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("a payout must clear at least one source");
    }
    if (feeMinor < 0) {
      throw new IllegalArgumentException("fee must not be negative (net > gross is a subsidy)");
    }
    long totalGross = 0L;
    for (SourceLine line : lines) {
      if (line.grossMinor() <= 0) {
        throw new IllegalArgumentException(
            "each settled source must carry a positive gross: " + line.channelCode());
      }
      totalGross = Math.addExact(totalGross, line.grossMinor());
    }

    BigInteger fee = BigInteger.valueOf(feeMinor);
    BigInteger total = BigInteger.valueOf(totalGross);
    List<AllocatedLine> allocated = new ArrayList<>(lines.size());
    long distributed = 0L;
    for (SourceLine line : lines) {
      long share =
          fee.multiply(BigInteger.valueOf(line.grossMinor())).divide(total).longValueExact();
      distributed = Math.addExact(distributed, share);
      allocated.add(new AllocatedLine(line.kind(), line.channelCode(), line.grossMinor(), share));
    }

    long remainder = Math.subtractExact(feeMinor, distributed);
    if (remainder != 0) {
      int largest = 0;
      for (int i = 1; i < allocated.size(); i++) {
        if (allocated.get(i).grossMinor() > allocated.get(largest).grossMinor()) {
          largest = i;
        }
      }
      AllocatedLine line = allocated.get(largest);
      allocated.set(
          largest,
          new AllocatedLine(
              line.kind(),
              line.channelCode(),
              line.grossMinor(),
              Math.addExact(line.feeMinor(), remainder)));
    }
    return List.copyOf(allocated);
  }

  /**
   * The lines a payout clears, in a stable order (kind, then channel) so two requests naming the
   * same sources produce the same journal and compare equal on an idempotency replay.
   */
  public static List<SourceLine> normalize(List<SourceLine> lines) {
    return lines.stream()
        .sorted(
            Comparator.comparing((SourceLine l) -> l.kind().name())
                .thenComparing(SourceLine::channelCode))
        .toList();
  }
}
