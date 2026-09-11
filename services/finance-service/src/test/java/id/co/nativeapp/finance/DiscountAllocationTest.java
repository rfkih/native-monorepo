package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.ap.domain.DiscountAllocation;
import org.junit.jupiter.api.Test;

/** ADR 0084 — the header discount spreads across lines exactly, in integer minor units. */
class DiscountAllocationTest {

  @Test
  void sharesSumToTheDiscountAndFollowTheLineWeights() {
    long[] shares = DiscountAllocation.allocate(100L, new long[] {600L, 300L, 100L});
    assertThat(shares).containsExactly(60L, 30L, 10L);
  }

  @Test
  void leftoverUnitsGoToTheLargestRemaindersFirstComeOnTies() {
    // 10 over 3 equal lines: floors 3/3/3, one unit left → the first line (all remainders tie).
    assertThat(DiscountAllocation.allocate(10L, new long[] {100L, 100L, 100L}))
        .containsExactly(4L, 3L, 3L);
    // 7 over 5/4: floors 3 (35/9) + 3 (28/9) = 6, remainders 8 vs 1 → the first line gets the unit.
    assertThat(DiscountAllocation.allocate(7L, new long[] {5L, 4L})).containsExactly(4L, 3L);
  }

  @Test
  void aFullDiscountTakesEveryLineToZeroAndNeverMore() {
    assertThat(DiscountAllocation.allocate(900L, new long[] {600L, 300L}))
        .containsExactly(600L, 300L);
  }

  @Test
  void zeroDiscountOrEmptyLinesAllocateNothing() {
    assertThat(DiscountAllocation.allocate(0L, new long[] {600L, 300L})).containsExactly(0L, 0L);
    assertThat(DiscountAllocation.allocate(0L, new long[] {})).isEmpty();
  }

  @Test
  void aNegativeOrOversizedDiscountIsRefused() {
    assertThatThrownBy(() -> DiscountAllocation.allocate(-1L, new long[] {10L}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> DiscountAllocation.allocate(11L, new long[] {10L}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void largeFiguresDoNotOverflow() {
    long[] shares =
        DiscountAllocation.allocate(
            3_000_000_000L, new long[] {5_000_000_000_000L, 5_000_000_000_000L});
    assertThat(shares).containsExactly(1_500_000_000L, 1_500_000_000L);
  }
}
