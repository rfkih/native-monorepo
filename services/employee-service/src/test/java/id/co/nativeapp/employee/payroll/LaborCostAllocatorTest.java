package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.payroll.AllocationInputs.OutletShare;
import id.co.nativeapp.employee.payroll.AllocationInputs.PersonAllocation;
import id.co.nativeapp.employee.payroll.LaborCostAllocator.AllocatedRow;
import id.co.nativeapp.money.Money;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Allocation-sums-EXACTLY-to-total proof (design §4), including the largest-remainder residual rule
 * (no leakage) and the deterministic tie-break. The allocator is pure, so this is a fast unit test.
 */
class LaborCostAllocatorTest {

  private static final String IDR = "IDR";
  private final LaborCostAllocator allocator = new LaborCostAllocator();

  private static final UUID LE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  // Three outlet ids in a known order so the tie-break (lowest id) is deterministic.
  private static final UUID O1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID O2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID O3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

  private OutletShare share(UUID outlet, long earnings) {
    return new OutletShare(outlet, LE, "5100-SALARY", Money.ofMinor(earnings, IDR));
  }

  @Test
  void singleOutletGetsTheWholeCostExactly() {
    List<AllocatedRow> rows =
        allocator.allocate(
            new PersonAllocation(
                UUID.randomUUID(),
                Money.ofMinor(12_345_678L, IDR),
                Money.ofMinor(12_345_678L, IDR),
                List.of(share(O1, 12_345_678L))));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).amount()).isEqualTo(Money.ofMinor(12_345_678L, IDR));
    assertThat(rows.get(0).earningsShareBp()).isEqualTo(10_000);
  }

  @Test
  void equalThreeWaySplitAssignsResidualToTheLowestOutletIdAndSumsExactly() {
    // total 100, three equal outlets: floor(100 * 3333/10000) = 33 each -> sum 99, residual 1.
    // All remainders equal -> tie -> residual to the LOWEST outlet id (O1).
    List<AllocatedRow> rows =
        allocator.allocate(
            new PersonAllocation(
                UUID.randomUUID(),
                Money.ofMinor(100L, IDR),
                Money.ofMinor(300L, IDR),
                List.of(share(O1, 100L), share(O2, 100L), share(O3, 100L))));

    long sum = rows.stream().mapToLong(r -> r.amount().amountMinor()).sum();
    assertThat(sum).isEqualTo(100L); // EXACT — no leakage.

    assertThat(amountFor(rows, O1)).isEqualTo(34L); // lowest id got the residual
    assertThat(amountFor(rows, O2)).isEqualTo(33L);
    assertThat(amountFor(rows, O3)).isEqualTo(33L);
  }

  @Test
  void unevenSharesSumExactlyWithResidualToLargestRemainder() {
    // total 1000, earnings 10/20/70 (shares 1000/2000/7000 bp).
    // alloc: floor(1000*1000/10000)=100, floor(1000*2000/10000)=200, floor(1000*7000/10000)=700.
    // sum 1000 exactly -> residual 0.
    List<AllocatedRow> rows =
        allocator.allocate(
            new PersonAllocation(
                UUID.randomUUID(),
                Money.ofMinor(1000L, IDR),
                Money.ofMinor(100L, IDR),
                List.of(share(O1, 10L), share(O2, 20L), share(O3, 70L))));

    assertThat(rows.stream().mapToLong(r -> r.amount().amountMinor()).sum()).isEqualTo(1000L);
    assertThat(amountFor(rows, O1)).isEqualTo(100L);
    assertThat(amountFor(rows, O2)).isEqualTo(200L);
    assertThat(amountFor(rows, O3)).isEqualTo(700L);
  }

  @Test
  void residualGoesToTheTrulyLargestRemainderWhenNotATie() {
    // total 1000, earnings 1/1/1 but use shares that floor unevenly: 3 outlets, total earnings 7,
    // earnings 1,2,4 -> shareBp 1429,2857,5714 (HALF_EVEN). alloc floor:
    //   floor(1000*1429/10000)=142, floor(1000*2857/10000)=285, floor(1000*5714/10000)=571 -> 998,
    //   residual 2. remainders: 1000*1429=1429000 -> /10000=142 rem 9000; 2857->285 rem 7000;
    //   5714->571 rem 4000. Largest remainder is O1 (9000) -> gets the whole residual 2 -> 144.
    List<AllocatedRow> rows =
        allocator.allocate(
            new PersonAllocation(
                UUID.randomUUID(),
                Money.ofMinor(1000L, IDR),
                Money.ofMinor(7L, IDR),
                List.of(share(O1, 1L), share(O2, 2L), share(O3, 4L))));

    assertThat(rows.stream().mapToLong(r -> r.amount().amountMinor()).sum()).isEqualTo(1000L);
    assertThat(amountFor(rows, O1)).isEqualTo(144L); // largest remainder got residual 2
    assertThat(amountFor(rows, O2)).isEqualTo(285L);
    assertThat(amountFor(rows, O3)).isEqualTo(571L);
  }

  @Test
  void multiOutletUnevenBaseSumsExactlyWithResidualToLargestRemainder() {
    // One employee spanning THREE outlets where the base does NOT divide evenly: base 1,000,000
    // split equally is 333,333 per outlet (writer prorates), total earnings 999,999. Labor cost
    // 1,000,123 also does not divide evenly. The allocation MUST still sum EXACTLY to the labor
    // cost, with the residual going to the largest fractional remainder (equal shares -> tie ->
    // lowest outlet id O1).
    long perOutletEarnings = 333_333L;
    List<AllocatedRow> rows =
        allocator.allocate(
            new PersonAllocation(
                UUID.randomUUID(),
                Money.ofMinor(1_000_123L, IDR),
                Money.ofMinor(perOutletEarnings * 3, IDR),
                List.of(
                    share(O1, perOutletEarnings),
                    share(O2, perOutletEarnings),
                    share(O3, perOutletEarnings))));

    long sum = rows.stream().mapToLong(r -> r.amount().amountMinor()).sum();
    assertThat(sum).isEqualTo(1_000_123L); // EXACT — no minor unit leaks.
    // Equal shares -> tie on remainder -> residual to the lowest outlet id (O1).
    assertThat(amountFor(rows, O1)).isGreaterThanOrEqualTo(amountFor(rows, O2));
    assertThat(amountFor(rows, O1)).isGreaterThanOrEqualTo(amountFor(rows, O3));
  }

  @Test
  void noOutletFailsTheRun() {
    assertThatThrownBy(
            () ->
                allocator.allocate(
                    new PersonAllocation(
                        UUID.randomUUID(),
                        Money.ofMinor(100L, IDR),
                        Money.ofMinor(100L, IDR),
                        List.of())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no outlet");
  }

  private static long amountFor(List<AllocatedRow> rows, UUID outlet) {
    return rows.stream()
        .filter(r -> r.outletOrgUnitId().equals(outlet))
        .findFirst()
        .orElseThrow()
        .amount()
        .amountMinor();
  }
}
