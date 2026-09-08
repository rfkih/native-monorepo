package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.platform.domain.SettlementAllocation;
import id.co.nativeapp.finance.platform.domain.SettlementAllocation.AllocatedLine;
import id.co.nativeapp.finance.platform.domain.SettlementAllocation.SourceLine;
import id.co.nativeapp.finance.platform.domain.SettlementSourceKind;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payout's deduction is split across the sources it cleared, and each share books to a
 * different expense account. The journal has to balance to the rupiah, so the split may never lose
 * or invent a unit (ADR 0076 phase 2).
 */
class SettlementAllocationTest {

  private static SourceLine marketplace(String channel, long gross) {
    return new SourceLine(SettlementSourceKind.MARKETPLACE, channel, gross);
  }

  private static SourceLine qris(long gross) {
    return new SourceLine(SettlementSourceKind.QRIS, "TENDER:QRIS", gross);
  }

  private static long totalFee(List<AllocatedLine> lines) {
    return lines.stream().mapToLong(AllocatedLine::feeMinor).sum();
  }

  @Test
  @DisplayName("splits the deduction in proportion to what each source settled")
  void splitsProRata() {
    // The worked example from the ADR: Shopee pays 2.490.000 gross as 2.216.100 net.
    List<AllocatedLine> allocated =
        SettlementAllocation.allocate(
            List.of(marketplace("SHOPEE", 1_850_000L), qris(640_000L)), 273_900L);

    // 273.900 × 1.850.000 ÷ 2.490.000 divides exactly here — no remainder to place.
    assertThat(allocated).extracting(AllocatedLine::feeMinor).containsExactly(203_500L, 70_400L);
    assertThat(totalFee(allocated)).isEqualTo(273_900L);
  }

  @Test
  @DisplayName("a rounding remainder is kept, landing on the largest line")
  void neverLosesARupiahToRounding() {
    // 10 across grosses of 1 and 2 floors to 3 + 6 = 9; the missing rupiah must not vanish, and
    // it belongs on the larger line where it distorts the rate least.
    List<AllocatedLine> allocated =
        SettlementAllocation.allocate(List.of(marketplace("A", 1L), qris(2L)), 10L);

    assertThat(totalFee(allocated)).isEqualTo(10L);
    assertThat(allocated).extracting(AllocatedLine::feeMinor).containsExactly(3L, 7L);
  }

  @Test
  @DisplayName("survives amounts whose product would overflow a long")
  void doesNotOverflowOnRealisticRupiahAmounts() {
    // gross × fee here is ~10^23 — past Long.MAX_VALUE, which is why the maths runs in BigInteger.
    List<AllocatedLine> allocated =
        SettlementAllocation.allocate(
            List.of(marketplace("BIG", 900_000_000_000L), qris(100_000_000_000L)),
            100_000_000_000L);

    assertThat(totalFee(allocated)).isEqualTo(100_000_000_000L);
    assertThat(allocated.get(0).feeMinor()).isEqualTo(90_000_000_000L);
  }

  @Test
  @DisplayName("a fee-free payout allocates nothing to anyone")
  void handlesAZeroFee() {
    List<AllocatedLine> allocated =
        SettlementAllocation.allocate(List.of(marketplace("A", 500L), qris(500L)), 0L);

    assertThat(totalFee(allocated)).isZero();
    assertThat(allocated).allSatisfy(l -> assertThat(l.feeMinor()).isZero());
  }

  @Test
  @DisplayName("a single-source payout takes the whole deduction")
  void handlesOneLine() {
    List<AllocatedLine> allocated =
        SettlementAllocation.allocate(List.of(marketplace("GOJEK", 1_000L)), 137L);

    assertThat(allocated).singleElement().extracting(AllocatedLine::feeMinor).isEqualTo(137L);
  }

  @Test
  @DisplayName("rejects the shapes that would make the journal a lie")
  void rejectsImpossibleInput() {
    assertThatThrownBy(() -> SettlementAllocation.allocate(List.of(), 100L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SettlementAllocation.allocate(List.of(marketplace("A", 0L)), 100L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SettlementAllocation.allocate(List.of(marketplace("A", 10L)), -1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("orders lines so the same sources always produce the same journal")
  void normalizesOrderForIdempotentReplay() {
    List<SourceLine> shuffled = List.of(qris(1L), marketplace("SHOPEE", 2L), marketplace("A", 3L));

    assertThat(SettlementAllocation.normalize(shuffled))
        .extracting(SourceLine::channelCode)
        .containsExactly("A", "SHOPEE", "TENDER:QRIS");
  }
}
