package id.co.nativeapp.restaurant.integrity.service;

import id.co.nativeapp.restaurant.integrity.domain.LeakSignalType;
import id.co.nativeapp.restaurant.integrity.dto.LeakCoverageResponse;
import id.co.nativeapp.restaurant.integrity.dto.LeakDetailResponse;
import id.co.nativeapp.restaurant.integrity.dto.LeakSignalResponse;
import id.co.nativeapp.restaurant.integrity.dto.SalesIntegrityReportResponse;
import id.co.nativeapp.restaurant.integrity.projection.DarkHourView;
import id.co.nativeapp.restaurant.integrity.projection.IngredientShortfallView;
import id.co.nativeapp.restaurant.integrity.projection.MissingTrackedItemView;
import id.co.nativeapp.restaurant.integrity.projection.OutsideSessionSalesView;
import id.co.nativeapp.restaurant.integrity.projection.RecipeConsumerView;
import id.co.nativeapp.restaurant.integrity.projection.RegisterSessionHygieneView;
import id.co.nativeapp.restaurant.integrity.projection.SoldItemCoverageView;
import id.co.nativeapp.restaurant.integrity.projection.UnclosedTradingDayView;
import id.co.nativeapp.restaurant.integrity.repository.SalesIntegrityRepository;
import id.co.nativeapp.restaurant.inventory.domain.StockLedgerDay;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles one outlet's sales-leak report (ADR 0074) — the read-only transactional unit, so the
 * Spring proxy applies the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} GUC advice and every
 * query underneath is tenant-scoped (the {@code IngredientReader} pattern).
 *
 * <p><strong>Read-only, and structurally so.</strong> No writer is injected and no outbox row is
 * ever produced. The estimate is an inference about money that was never recorded; it informs a
 * conversation, it does not post to a ledger (ADR 0074 §3).
 *
 * <p>A signal that did not fire is OMITTED rather than returned at zero. Nine signals all reporting
 * nothing reads as a system that found nothing; an empty list reads as a clean window — and only
 * the second is what actually happened.
 */
@Component
public class SalesIntegrityReader {

  /**
   * How busy a weekday-and-hour has to normally be before its silence is worth reporting. At a
   * median of 1-2 sales, a zero is ordinary variation; at 3+ it is a hole. Deliberately a low bar —
   * this signal's job is to raise a question, and the report's severity ordering decides how
   * loudly.
   */
  private static final long DARK_HOUR_MIN_EXPECTED = 3L;

  /** How far back the dark-hour baseline looks: eight weeks, so each weekday gets ~8 samples. */
  private static final int BASELINE_WEEKS = 8;

  /** A session still open this long after it started was not closed — it was abandoned. */
  private static final Duration SESSION_STALE_AFTER = Duration.ofHours(24);

  /**
   * How many closes in a row must come out to EXACTLY zero before the run is worth mentioning. A
   * counted drawer that never disagrees with the system by a single unit is more often a figure
   * being copied than counted — but an honest, tiny, cash-only outlet genuinely looks like this,
   * which is why the signal is LOW and the bar is not lower.
   */
  private static final int EXACT_ZERO_RUN_MIN = 5;

  /**
   * How many closes must land the same way before a variance pattern is a pattern. Anything less is
   * two bad nights, and naming a person over two bad nights is exactly what this feature must not
   * do.
   */
  private static final int VARIANCE_PATTERN_MIN = 3;

  /** Evidence rows returned per signal. The full population is aggregated; the list is a sample. */
  private static final int MAX_DETAILS_PER_SIGNAL = 20;

  private final SalesIntegrityRepository repository;
  private final OutletAccessGuard outletAccessGuard;

  public SalesIntegrityReader(
      SalesIntegrityRepository repository, OutletAccessGuard outletAccessGuard) {
    this.repository = repository;
    this.outletAccessGuard = outletAccessGuard;
  }

  /**
   * Builds the report for {@code businessId} over {@code [from, to)}.
   *
   * <p>Runs every detector, then folds their money into the low/high range. The guard is called
   * even though the gateway already restricts this surface to owners: defence in depth, and
   * consistent with every other outlet-scoped read in this service.
   */
  @Transactional(readOnly = true)
  public SalesIntegrityReportResponse report(UUID businessId, Instant from, Instant to) {
    outletAccessGuard.enforce(businessId);

    List<LeakSignalResponse> signals = new ArrayList<>();
    String currency = null;
    long tightRevenue = 0L;
    long looseRevenue = 0L;
    long confirmedCost = 0L;

    // --- Tracked menu items counted short: the tight signal ------------------------------------
    List<MissingTrackedItemView> missingItems =
        repository.findMissingTrackedItems(businessId, from, to);
    if (!missingItems.isEmpty()) {
      long total = 0L;
      long units = 0L;
      List<LeakDetailResponse> details = new ArrayList<>();
      for (MissingTrackedItemView item : missingItems) {
        currency = LeakEstimator.reconcileCurrency(currency, item.getCurrency());
        long value =
            LeakEstimator.estimateTrackedItemRevenue(
                item.getMissingQty(), item.getUnitPriceMinor());
        total = Math.addExact(total, value);
        units = Math.addExact(units, item.getMissingQty());
        if (details.size() < MAX_DETAILS_PER_SIGNAL) {
          details.add(
              new LeakDetailResponse(
                  item.getMenuItemId(),
                  item.getName(),
                  null,
                  null,
                  item.getMissingQty(),
                  value,
                  item.getCurrency()));
        }
      }
      tightRevenue = Math.addExact(tightRevenue, total);
      signals.add(signal(LeakSignalType.MISSING_TRACKED_ITEMS, units, total, currency, details));
    }

    // --- Ingredient shortfall → portion equivalent: the loose signal ----------------------------
    List<IngredientShortfallView> shortfalls =
        repository.findIngredientShortfalls(businessId, from, to);
    if (!shortfalls.isEmpty()) {
      for (IngredientShortfallView shortfall : shortfalls) {
        currency = LeakEstimator.reconcileCurrency(currency, shortfall.getCurrency());
        confirmedCost = Math.addExact(confirmedCost, Math.max(0L, shortfall.getMissingCostMinor()));
      }
      Map<UUID, List<RecipeConsumerView>> consumers =
          LeakEstimator.groupByIngredient(loadRecipeConsumers(businessId, shortfalls, from, to));

      long total = 0L;
      List<LeakDetailResponse> details = new ArrayList<>();
      for (IngredientShortfallView shortfall : shortfalls) {
        List<RecipeConsumerView> forIngredient =
            consumers.getOrDefault(shortfall.getIngredientId(), List.of());
        LeakEstimator.ShortfallEstimate estimate =
            LeakEstimator.estimateShortfallRevenue(
                shortfall.getMissingQty(), forIngredient, currency);
        total = Math.addExact(total, estimate.estimatedRevenueMinor());
        if (details.size() < MAX_DETAILS_PER_SIGNAL) {
          details.add(
              new LeakDetailResponse(
                  shortfall.getIngredientId(),
                  shortfall.getName(),
                  null,
                  null,
                  shortfall.getMissingQty(),
                  // An ingredient no recipe consumes cannot be priced into revenue, so its detail
                  // row carries its COST instead of a fabricated zero — the loss is real even
                  // though the menu cannot say what it would have sold as.
                  estimate.attributable()
                      ? estimate.estimatedRevenueMinor()
                      : shortfall.getMissingCostMinor(),
                  currency));
        }
      }
      looseRevenue = Math.addExact(looseRevenue, total);
      signals.add(
          signal(LeakSignalType.INGREDIENT_SHORTFALL, shortfalls.size(), total, currency, details));
    }

    // --- Dark hours -----------------------------------------------------------------------------
    List<DarkHourView> darkHours =
        repository.findDarkHours(
            businessId,
            from.minus(Duration.ofDays(7L * BASELINE_WEEKS)),
            LocalDate.ofInstant(from, StockLedgerDay.ZONE),
            to,
            DARK_HOUR_MIN_EXPECTED);
    if (!darkHours.isEmpty()) {
      List<LeakDetailResponse> details = new ArrayList<>();
      for (DarkHourView hour : darkHours) {
        if (details.size() >= MAX_DETAILS_PER_SIGNAL) {
          break;
        }
        details.add(
            new LeakDetailResponse(
                null,
                null,
                hour.getBusinessDate(),
                hour.getHourOfDay(),
                hour.getExpectedCount(),
                null,
                null));
      }
      signals.add(signal(LeakSignalType.DARK_HOUR, darkHours.size(), null, null, details));
    }

    // --- Sales rung outside any register session ------------------------------------------------
    OutsideSessionSalesView outside =
        repository.findSalesOutsideAnySession(businessId, from, to).orElse(null);
    if (outside != null && outside.getSaleCount() > 0) {
      currency = LeakEstimator.reconcileCurrency(currency, outside.getCurrency());
      signals.add(
          signal(
              LeakSignalType.SALES_OUTSIDE_SESSION,
              outside.getSaleCount(),
              outside.getTotalMinor(),
              currency,
              List.of(
                  new LeakDetailResponse(
                      null,
                      null,
                      null,
                      null,
                      outside.getSaleCount(),
                      outside.getTotalMinor(),
                      currency))));
    }

    // --- Trading days that never closed ---------------------------------------------------------
    List<UnclosedTradingDayView> unclosed =
        repository.findTradingDaysWithoutClose(businessId, from, to);
    if (!unclosed.isEmpty()) {
      long total = 0L;
      List<LeakDetailResponse> details = new ArrayList<>();
      for (UnclosedTradingDayView day : unclosed) {
        currency = LeakEstimator.reconcileCurrency(currency, day.getCurrency());
        total = Math.addExact(total, day.getTotalMinor());
        if (details.size() < MAX_DETAILS_PER_SIGNAL) {
          details.add(
              new LeakDetailResponse(
                  null,
                  null,
                  day.getBusinessDate(),
                  null,
                  day.getSaleCount(),
                  day.getTotalMinor(),
                  day.getCurrency()));
        }
      }
      // The day's takings are NOT a leak estimate — they are what went unreconciled. Reported as
      // the signal's value so an owner can see the exposure, and deliberately left out of the
      // low/high range, which only ever counts money that plausibly went missing.
      signals.add(
          signal(
              LeakSignalType.TRADING_DAY_WITHOUT_CLOSE, unclosed.size(), total, currency, details));
    }

    // --- Register-close hygiene -----------------------------------------------------------------
    List<RegisterSessionHygieneView> sessions =
        repository.findSessionsInWindow(businessId, from, to);
    currency = addSessionSignals(sessions, to, currency, signals);

    signals.sort(Comparator.comparing(s -> s.severity().ordinal()));

    return new SalesIntegrityReportResponse(
        businessId,
        from,
        to,
        currency,
        tightRevenue,
        Math.addExact(tightRevenue, looseRevenue),
        confirmedCost,
        List.copyOf(signals),
        buildCoverage(businessId, from, to));
  }

  /**
   * Folds the window's sessions into the hygiene signals.
   *
   * <p>Done here rather than in SQL because the interesting property — a RUN of closes that came
   * out to exactly zero — is a statement about a sequence, and a fold over ordered rows states it
   * far more legibly, and pins it far more precisely in a test, than a window function would.
   *
   * @return the report currency, possibly established by these rows
   */
  private @Nullable String addSessionSignals(
      List<RegisterSessionHygieneView> sessions,
      Instant to,
      @Nullable String currency,
      List<LeakSignalResponse> signals) {

    List<LeakDetailResponse> stale = new ArrayList<>();
    List<LeakDetailResponse> shorts = new ArrayList<>();
    List<LeakDetailResponse> overs = new ArrayList<>();
    long shortTotal = 0L;
    long overTotal = 0L;
    int exactRun = 0;
    int longestExactRun = 0;
    LocalDate runStartedOn = null;
    LocalDate longestRunStartedOn = null;

    for (RegisterSessionHygieneView session : sessions) {
      if (!"CLOSED".equals(session.getStatus())) {
        // Measured against the window's end, not "now": a report on last month must judge that
        // month's sessions by what was true then, and re-running it later must not keep aging them.
        if (session.getOpenedAt().plus(SESSION_STALE_AFTER).isBefore(to)) {
          stale.add(
              new LeakDetailResponse(
                  session.getSessionId(),
                  session.getClosedBy(),
                  session.getBusinessDate(),
                  null,
                  null,
                  null,
                  null));
        }
        // An open session breaks a run of exact closes rather than continuing it — the run is a
        // claim about consecutive COUNTS, and an uncounted drawer is not a count of zero variance.
        exactRun = 0;
        runStartedOn = null;
        continue;
      }

      currency = LeakEstimator.reconcileCurrency(currency, session.getCurrency());
      long variance = session.getOverShortMinor() == null ? 0L : session.getOverShortMinor();

      if (variance == 0L) {
        if (exactRun == 0) {
          runStartedOn = session.getBusinessDate();
        }
        exactRun++;
        if (exactRun > longestExactRun) {
          longestExactRun = exactRun;
          longestRunStartedOn = runStartedOn;
        }
      } else {
        exactRun = 0;
        runStartedOn = null;
        LeakDetailResponse detail =
            new LeakDetailResponse(
                session.getSessionId(),
                session.getClosedBy(),
                session.getBusinessDate(),
                null,
                null,
                Math.abs(variance),
                session.getCurrency());
        if (variance < 0) {
          shorts.add(detail);
          shortTotal = Math.addExact(shortTotal, -variance);
        } else {
          overs.add(detail);
          overTotal = Math.addExact(overTotal, variance);
        }
      }
    }

    if (!stale.isEmpty()) {
      signals.add(
          signal(
              LeakSignalType.SESSION_LEFT_OPEN,
              stale.size(),
              null,
              null,
              stale.subList(0, Math.min(stale.size(), MAX_DETAILS_PER_SIGNAL))));
    }
    // Pattern-based, never amount-based: "the drawer came up short three times" is comparable
    // across currencies and outlet sizes, where any fixed threshold in minor units would be
    // meaningless in one currency and trivial in another.
    if (shorts.size() >= VARIANCE_PATTERN_MIN) {
      signals.add(
          signal(
              LeakSignalType.PERSISTENT_CASH_SHORT,
              shorts.size(),
              shortTotal,
              currency,
              shorts.subList(0, Math.min(shorts.size(), MAX_DETAILS_PER_SIGNAL))));
    }
    if (overs.size() >= VARIANCE_PATTERN_MIN) {
      signals.add(
          signal(
              LeakSignalType.UNEXPLAINED_CASH_OVER,
              overs.size(),
              overTotal,
              currency,
              overs.subList(0, Math.min(overs.size(), MAX_DETAILS_PER_SIGNAL))));
    }
    if (longestExactRun >= EXACT_ZERO_RUN_MIN) {
      signals.add(
          signal(
              LeakSignalType.EXACT_ZERO_CLOSE_RUN,
              longestExactRun,
              null,
              null,
              List.of(
                  new LeakDetailResponse(
                      null, null, longestRunStartedOn, null, (long) longestExactRun, null, null))));
    }
    return currency;
  }

  /**
   * Loads the recipe edges for every short ingredient, chunking the {@code IN} clause to the
   * fleet-wide 1000-element convention.
   */
  private List<RecipeConsumerView> loadRecipeConsumers(
      UUID businessId, List<IngredientShortfallView> shortfalls, Instant from, Instant to) {
    List<UUID> ids = shortfalls.stream().map(IngredientShortfallView::getIngredientId).toList();
    List<RecipeConsumerView> rows = new ArrayList<>();
    for (int i = 0; i < ids.size(); i += 1000) {
      rows.addAll(
          repository.findRecipeConsumers(
              businessId, ids.subList(i, Math.min(i + 1000, ids.size())), from, to));
    }
    return rows;
  }

  /** The report's disclosure of its own blind spots. */
  private LeakCoverageResponse buildCoverage(UUID businessId, Instant from, Instant to) {
    SoldItemCoverageView coverage = repository.findSoldItemCoverage(businessId, from, to);
    long corrections =
        repository.countManualStockCorrections(
            businessId,
            LocalDate.ofInstant(from, StockLedgerDay.ZONE),
            LocalDate.ofInstant(to, StockLedgerDay.ZONE));
    return new LeakCoverageResponse(
        coverage == null ? 0L : coverage.getTotalSoldQty(),
        coverage == null ? 0L : coverage.getRecipeBackedSoldQty(),
        daysSince(repository.findLastIngredientCountAt(businessId).orElse(null), to),
        daysSince(repository.findLastItemCountAt(businessId).orElse(null), to),
        corrections);
  }

  /**
   * Days between a count and the window's end, or {@code null} when there has never been one.
   *
   * <p>{@code null} rather than a large number, because "never counted" and "counted a very long
   * time ago" call for different responses and a sentinel would blur them. Floors at 0 so a count
   * taken after the window's end (a report re-run on an old period) reads as "counted since", never
   * as a negative age.
   */
  private static @Nullable Long daysSince(@Nullable Instant countedAt, Instant to) {
    if (countedAt == null) {
      return null;
    }
    return Math.max(0L, Duration.between(countedAt, to).toDays());
  }

  private static LeakSignalResponse signal(
      LeakSignalType type,
      long occurrences,
      @Nullable Long valueMinor,
      @Nullable String currency,
      List<LeakDetailResponse> details) {
    return new LeakSignalResponse(
        type,
        type.defaultSeverity(),
        occurrences,
        valueMinor,
        valueMinor == null ? null : currency,
        List.copyOf(details));
  }
}
