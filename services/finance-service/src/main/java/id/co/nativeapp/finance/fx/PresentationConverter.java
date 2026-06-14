package id.co.nativeapp.finance.fx;

import id.co.nativeapp.finance.pnl.ConsolidatedPnl;
import id.co.nativeapp.money.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * READ-ONLY presentation-currency conversion of the consolidated read models, wrapping {@link
 * TranslationService}. It produces NEW {@link Money} values for display only — it NEVER mutates
 * {@code ledger_posting}, {@code consolidated_revenue}, {@code consolidated_pnl}, or the company
 * base currency. There is no write path, no upsert, no new stored currency row; the
 * transaction-currency books are untouched. The {@code presentation} currency is a transient view
 * lens chosen per request, never a base-currency change.
 *
 * <p>Revenue and each P&amp;L leg are FLOWS, so they translate at the AVERAGE rate for the period
 * (IAS-21). The P&amp;L net is computed IN the presentation currency from the presentation legs
 * (single rounding per leg, then subtract), and the stub flag is OR-ed across the legs.
 *
 * <p>A read-only, stateless {@code @Component} (a service-layer collaborator wrapping {@link
 * TranslationService}); it carries no {@code @Transactional} and no repository, so it sits cleanly
 * below the controllers and above nothing — it just transforms {@link Money} on the way out. The
 * P&amp;L variant accepts the {@code ConsolidatedPnl} read-model row so the entity-method access
 * ({@code revenue()}/{@code expense()}) stays OUT of the controller (the controllers expose DTOs,
 * never reach into a JPA entity).
 */
@Component
public class PresentationConverter {

  private final TranslationService translationService;

  public PresentationConverter(TranslationService translationService) {
    this.translationService = Objects.requireNonNull(translationService, "translationService");
  }

  /**
   * Converts a consolidated-revenue total into {@code presentation} at the period AVERAGE rate.
   *
   * @param nativeTotal the stored revenue total (in its transaction/base currency)
   * @param period the accounting period {@code YYYY-MM}
   * @param presentation the requested presentation currency
   * @return the presentation view (converted total + stub flag + as-of + applied rates)
   * @throws MissingFxRateException if no rate resolves (→ 422); the native figure is never returned
   *     mislabelled as converted
   */
  public RevenuePresentation convertRevenue(
      Money nativeTotal, String period, Currency presentation) {
    Translated translated = translationService.translateFlow(nativeTotal, period, presentation);
    return new RevenuePresentation(
        translated.amount(), translated.usesStubFx(), translated.asOf(), translated.appliedRates());
  }

  /**
   * Converts a consolidated P&amp;L row's revenue and expense legs into {@code presentation} at the
   * period AVERAGE rate, then derives the presentation net (revenue − expense) IN the presentation
   * currency. The conversion is ATOMIC: if EITHER leg has no rate the whole call fails loudly
   * ({@link MissingFxRateException}) — a half-converted P&amp;L is never returned. The stub flag is
   * the OR across both legs.
   *
   * <p>Takes the {@link ConsolidatedPnl} read-model row (not raw {@link Money}) so the
   * entity-method access stays out of the controller. The row is READ ONLY — never mutated.
   *
   * @param pnl the stored P&amp;L read-model row (its legs are in the transaction/base currency)
   * @param period the accounting period {@code YYYY-MM}
   * @param presentation the requested presentation currency
   * @return the presentation view (converted revenue/expense/net + OR-ed stub flag + as-of + rates)
   * @throws MissingFxRateException if either leg has no resolvable rate (→ 422)
   */
  public PnlPresentation convertPnl(ConsolidatedPnl pnl, String period, Currency presentation) {
    Objects.requireNonNull(pnl, "pnl");
    // Convert each leg independently at AVERAGE; both must succeed (atomic) before deriving net.
    Translated revenue = translationService.translateFlow(pnl.revenue(), period, presentation);
    Translated expense = translationService.translateFlow(pnl.expense(), period, presentation);

    // Net is computed IN the presentation currency from the already-rounded presentation legs, so
    // it is consistent with what the dashboard shows for revenue/expense (Money.minus refuses to
    // mix currencies — both legs are now in `presentation`).
    Money presentationNet = revenue.amount().minus(expense.amount());

    // Stub contamination is sticky/loud: OR across both legs.
    boolean usesStubFx = revenue.usesStubFx() || expense.usesStubFx();

    // Surface every applied rate (both legs typically share the same rate, but list them as used).
    List<AppliedRate> applied = new ArrayList<>();
    applied.addAll(revenue.appliedRates());
    applied.addAll(expense.appliedRates());

    return new PnlPresentation(
        revenue.amount(),
        expense.amount(),
        presentationNet,
        usesStubFx,
        revenue.asOf(),
        List.copyOf(applied));
  }
}
