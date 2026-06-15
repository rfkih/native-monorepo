package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.finance.revenue.domain.ConsolidatedRevenue;
import id.co.nativeapp.finance.revenue.domain.LedgerPosting;
import id.co.nativeapp.finance.revenue.domain.MoneyEmbeddable;
import id.co.nativeapp.finance.revenue.domain.PostingType;
import id.co.nativeapp.money.MismatchedCurrencyException;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-JUnit unit tests for the ledger domain (no Spring, no DB): period derivation, the
 * consolidated-revenue accumulator's {@code Money} arithmetic and its mixed-currency guard, and the
 * {@code MoneyEmbeddable} round-trip. These pin the rule-8 Money behaviour at the cheapest level
 * (ENGINEERING-STANDARDS §3.1 — boot the least context that proves the assertion).
 */
class LedgerDomainTest {

  private static final UUID BUSINESS = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Test
  void periodIsDerivedFromOccurredAtInUtc() {
    // 2026-06-30T23:30:00Z is still June in UTC.
    assertThat(LedgerPosting.periodOf(Instant.parse("2026-06-30T23:30:00Z"))).isEqualTo("2026-06");
    // 2026-12-31T23:59:59Z is December.
    assertThat(LedgerPosting.periodOf(Instant.parse("2026-12-31T23:59:59Z"))).isEqualTo("2026-12");
  }

  @Test
  void aRevenuePostingCarriesItsMoneyTypeAndGlAccount() {
    Money amount = Money.ofMinor(1_500_000L, "IDR");
    LedgerPosting posting =
        new LedgerPosting(BUSINESS, "2026-06", amount, "4000", UUID.randomUUID());
    assertThat(posting.getPostingType()).isEqualTo(PostingType.REVENUE);
    assertThat(posting.getAmount()).isEqualTo(amount);
    assertThat(posting.getPeriod()).isEqualTo("2026-06");
    assertThat(posting.getBusinessId()).isEqualTo(BUSINESS);
    assertThat(posting.getGlAccountCode()).isEqualTo("4000");
  }

  @Test
  void anExpensePostingCarriesItsTypeAndDimension() {
    Money amount = Money.ofMinor(750_000L, "IDR");
    LedgerPosting posting =
        new LedgerPosting(
            PostingType.EXPENSE, BUSINESS, "2026-06", amount, "5200", UUID.randomUUID());
    assertThat(posting.getPostingType()).isEqualTo(PostingType.EXPENSE);
    assertThat(posting.getGlAccountCode()).isEqualTo("5200");
    assertThat(posting.getAmount()).isEqualTo(amount);
  }

  @Test
  void consolidatedRevenueAccumulatesSameCurrencyAmounts() {
    ConsolidatedRevenue revenue = new ConsolidatedRevenue("2026-06", "IDR");
    revenue.add(Money.ofMinor(1_500_000L, "IDR"));
    revenue.add(Money.ofMinor(500_000L, "IDR"));
    assertThat(revenue.getTotal()).isEqualTo(Money.ofMinor(2_000_000L, "IDR"));
  }

  @Test
  void consolidatedRevenueRefusesToMixCurrencies() {
    ConsolidatedRevenue revenue = new ConsolidatedRevenue("2026-06", "IDR");
    assertThatThrownBy(() -> revenue.add(Money.ofMinor(1_00L, "USD")))
        .isInstanceOf(MismatchedCurrencyException.class);
  }

  @Test
  void moneyEmbeddableRoundTripsThroughItsTwoColumns() {
    Money money = Money.ofMinor(1_234_567L, "IDR");
    MoneyEmbeddable embeddable = MoneyEmbeddable.of(money);
    assertThat(embeddable.getAmountMinor()).isEqualTo(1_234_567L);
    assertThat(embeddable.getCurrency()).isEqualTo("IDR");
    assertThat(embeddable.toMoney()).isEqualTo(money);
  }
}
