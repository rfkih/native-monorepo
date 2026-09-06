package id.co.nativeapp.restaurant.integrity.service;

import id.co.nativeapp.restaurant.config.OutletZone;
import id.co.nativeapp.restaurant.integrity.domain.LeakSignalType;
import id.co.nativeapp.restaurant.integrity.dto.LeakCoverageResponse;
import id.co.nativeapp.restaurant.integrity.dto.LeakDetailResponse;
import id.co.nativeapp.restaurant.integrity.dto.LeakSignalResponse;
import id.co.nativeapp.restaurant.integrity.dto.SalesIntegrityReportResponse;
import id.co.nativeapp.restaurant.integrity.projection.CancelledBillView;
import id.co.nativeapp.restaurant.integrity.projection.DarkHourView;
import id.co.nativeapp.restaurant.integrity.projection.IngredientShortfallView;
import id.co.nativeapp.restaurant.integrity.projection.MissingTrackedItemView;
import id.co.nativeapp.restaurant.integrity.projection.OperatorActivityView;
import id.co.nativeapp.restaurant.integrity.projection.OperatorRefundView;
import id.co.nativeapp.restaurant.integrity.projection.OutsideSessionSalesView;
import id.co.nativeapp.restaurant.integrity.projection.RecipeEdgeView;
import id.co.nativeapp.restaurant.integrity.projection.RegisterSessionHygieneView;
import id.co.nativeapp.restaurant.integrity.projection.SoldItemCoverageView;
import id.co.nativeapp.restaurant.integrity.projection.SoldQuantityView;
import id.co.nativeapp.restaurant.integrity.projection.UnclosedTradingDayView;
import id.co.nativeapp.restaurant.integrity.repository.SalesIntegrityRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

  /**
   * How many times an operator's rate must exceed everyone else's before it is worth a name. Two
   * times is a wide gap in practice — narrow enough to catch a real pattern, wide enough that
   * ordinary differences in who works the busy shift do not put somebody on a list.
   */
  private static final long OUTLIER_FACTOR = 2L;

  /**
   * The fewest events an operator needs before their rate means anything. Two voids out of three
   * sales is a 67% rate and evidence of nothing; without a floor the quietest person on the roster
   * tops every list by arithmetic alone.
   */
  private static final long OUTLIER_MIN_COUNT = 5L;

  /**
   * The floor for the tender-mix check, which counts SALES rather than incidents and so needs a
   * higher bar than the rest: cash share only carries information across a decent number of them.
   */
  private static final long TENDER_SKEW_MIN_COUNT = 20L;

  /**
   * The floor for the discount check, counted in DISCOUNTS GIVEN rather than in money. The
   * numerator there is an amount, so a minimum expressed in minor units would be no minimum at all
   * — a single one-rupiah discount at an outlet where nobody else discounted would clear it and put
   * a name in front of the owner.
   */
  private static final long DISCOUNT_MIN_COUNT = 5L;

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

    // The caller's `to` is routinely in the FUTURE: the console's default period is the current
    // month, so it asks for the whole month while only part of it has happened. Every detector that
    // reasons about ABSENCE — a session not closed, a day not reconciled, an hour with no sales,
    // how long since a stock count — has to be measured against what has actually elapsed, or it
    // reports the future as evidence: tonight's dinner service as a dark hour, today as a day that
    // never closed, and this morning's stock count as weeks old. `to` still bounds every query, so
    // nothing outside the requested period is ever included; `observedTo` only stops a detector
    // from concluding something about time that has not passed.
    Instant now = Instant.now();
    Instant observedTo = to.isBefore(now) ? to : now;
    // A day can only be judged for "never closed" once it is over. The day in progress has not
    // failed to produce a Z-report — it has not finished yet.
    Instant lastCompleteDayEnd =
        earlier(
            to,
            LocalDate.ofInstant(now, OutletZone.ZONE).atStartOfDay(OutletZone.ZONE).toInstant());

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
                  // No unit: a tracked menu item is counted in whole items, and the row already
                  // names which item. "2 missing" of a named bottle cannot be misread.
                  null,
                  null,
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
      Map<UUID, List<LeakEstimator.ConsumerEdge>> consumers =
          loadConsumerEdges(businessId, shortfalls, from, to);

      long total = 0L;
      List<LeakDetailResponse> details = new ArrayList<>();
      for (IngredientShortfallView shortfall : shortfalls) {
        List<LeakEstimator.ConsumerEdge> forIngredient =
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
                  // BOTH units. The base one is what the quantity is counted in; the display label
                  // is what every other stock surface shows this ingredient as, and the console
                  // renders through it so the leak report and Persediaan do not disagree about one
                  // fact.
                  shortfall.getUnit(),
                  shortfall.getDisplayUnit(),
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
            LocalDate.ofInstant(from, OutletZone.ZONE),
            observedTo,
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
                null,
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
                      null,
                      null,
                      outside.getTotalMinor(),
                      currency))));
    }

    // --- Trading days that never closed ---------------------------------------------------------
    List<UnclosedTradingDayView> unclosed =
        repository.findTradingDaysWithoutClose(businessId, from, lastCompleteDayEnd);
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
                  null,
                  null,
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
    currency = addSessionSignals(sessions, observedTo, currency, signals);

    // --- Per-operator patterns ------------------------------------------------------------------
    currency = addOperatorSignals(businessId, from, to, currency, signals);

    // --- Bills cancelled with food already on them ----------------------------------------------
    List<CancelledBillView> cancelled =
        repository.findCancelledBillsWithLines(businessId, from, to);
    if (!cancelled.isEmpty()) {
      long total = 0L;
      List<LeakDetailResponse> details = new ArrayList<>();
      for (CancelledBillView bill : cancelled) {
        currency = LeakEstimator.reconcileCurrency(currency, bill.getCurrency());
        total = Math.addExact(total, bill.getTotalMinor());
        if (details.size() < MAX_DETAILS_PER_SIGNAL) {
          details.add(
              new LeakDetailResponse(
                  bill.getBillId(),
                  bill.getActor(),
                  LocalDate.ofInstant(bill.getCancelledAt(), OutletZone.ZONE),
                  null,
                  bill.getLineCount(),
                  null,
                  null,
                  bill.getTotalMinor(),
                  bill.getCurrency()));
        }
      }
      signals.add(
          signal(
              LeakSignalType.CANCELLED_BILLS_WITH_ITEMS,
              cancelled.size(),
              total,
              currency,
              details));
    }

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
        buildCoverage(businessId, from, to, observedTo));
  }

  /**
   * Folds per-operator activity into the void / refund / discount / tender-mix signals.
   *
   * <p>Every one of these compares an operator against the REST of the outlet, never against a
   * fixed target and never against an average that includes themselves — see {@link
   * OperatorOutliers}. A signal here puts a person's name in front of an owner, so the bar is
   * deliberately a wide gap sustained over a real number of events, not a nudge above average.
   *
   * @return the report currency, possibly established by these rows
   */
  private @Nullable String addOperatorSignals(
      UUID businessId,
      Instant from,
      Instant to,
      @Nullable String currency,
      List<LeakSignalResponse> signals) {

    List<OperatorActivityView> activity = repository.findOperatorActivity(businessId, from, to);
    if (activity.isEmpty()) {
      return currency;
    }

    List<OperatorOutliers.OperatorRate> voidRates = new ArrayList<>();
    List<OperatorOutliers.OperatorRate> discountRates = new ArrayList<>();
    List<OperatorOutliers.OperatorRate> cashRates = new ArrayList<>();
    Map<String, Long> paymentsByActor = new HashMap<>();

    for (OperatorActivityView row : activity) {
      currency = LeakEstimator.reconcileCurrency(currency, row.getCurrency());
      paymentsByActor.put(row.getActor(), row.getPaymentCount());
      voidRates.add(
          OperatorOutliers.OperatorRate.counting(
              row.getActor(), row.getVoidCount(), row.getPaymentCount(), row.getVoidMinor()));
      // The discount rate's numerator is MONEY, so its floor has to be counted in discounts given —
      // hence the explicit event count rather than the `counting` shorthand.
      discountRates.add(
          new OperatorOutliers.OperatorRate(
              row.getActor(),
              row.getDiscountMinor(),
              row.getGrossMinor(),
              row.getDiscountMinor(),
              row.getDiscountCount()));
      cashRates.add(
          OperatorOutliers.OperatorRate.counting(
              row.getActor(), row.getCashCount(), row.getPaymentCount(), 0L));
    }

    addOutlierSignal(
        LeakSignalType.HIGH_VOID_RATE,
        OperatorOutliers.outliers(voidRates, OUTLIER_FACTOR, OUTLIER_MIN_COUNT),
        currency,
        true,
        signals);

    addOutlierSignal(
        LeakSignalType.HIGH_DISCOUNT_RATE,
        OperatorOutliers.outliers(discountRates, OUTLIER_FACTOR, DISCOUNT_MIN_COUNT),
        currency,
        true,
        signals);

    List<OperatorOutliers.OperatorRate> skew =
        OperatorOutliers.outliers(cashRates, OUTLIER_FACTOR, TENDER_SKEW_MIN_COUNT);
    addOutlierSignal(LeakSignalType.CASH_TENDER_SKEW, skew, currency, false, signals);

    List<OperatorRefundView> refunds = repository.findOperatorRefunds(businessId, from, to);
    if (!refunds.isEmpty()) {
      Map<String, OperatorRefundView> refundByActor = new HashMap<>();
      for (OperatorRefundView row : refunds) {
        currency = LeakEstimator.reconcileCurrency(currency, row.getCurrency());
        refundByActor.put(row.getActor(), row);
      }
      // Built from EVERY operator, not only the ones who refunded. The refund query returns rows
      // only where a refund happened, and feeding just those into the outlier test would silently
      // drop every clean operator out of the rest-of-outlet baseline — comparing two refunders
      // against each other instead of against the whole roster, and inflating both their rates.
      // An operator who refunded nothing is not absent from the question; they are the answer to
      // it.
      List<OperatorOutliers.OperatorRate> refundRates = new ArrayList<>();
      for (Map.Entry<String, Long> operator : paymentsByActor.entrySet()) {
        OperatorRefundView row = refundByActor.get(operator.getKey());
        refundRates.add(
            OperatorOutliers.OperatorRate.counting(
                operator.getKey(),
                row == null ? 0L : row.getRefundCount(),
                // Denominator is the operator's payment count from the SAME window; an operator who
                // refunded here but took no payments (a refund of an older sale) has no rate to
                // speak of and is skipped by the guard rather than divided by zero.
                operator.getValue(),
                row == null ? 0L : row.getRefundMinor()));
      }
      addOutlierSignal(
          LeakSignalType.HIGH_REFUND_RATE,
          OperatorOutliers.outliers(refundRates, OUTLIER_FACTOR, OUTLIER_MIN_COUNT),
          currency,
          true,
          signals);
    }
    return currency;
  }

  /** Adds one operator-outlier signal, or nothing at all when nobody stood out. */
  private void addOutlierSignal(
      LeakSignalType type,
      List<OperatorOutliers.OperatorRate> flagged,
      @Nullable String currency,
      boolean carriesMoney,
      List<LeakSignalResponse> signals) {
    if (flagged.isEmpty()) {
      return;
    }
    List<LeakDetailResponse> details = new ArrayList<>();
    for (OperatorOutliers.OperatorRate rate : flagged) {
      if (details.size() >= MAX_DETAILS_PER_SIGNAL) {
        break;
      }
      details.add(
          new LeakDetailResponse(
              null,
              rate.actor(),
              null,
              null,
              // eventCount, NOT numerator: for the discount signal the numerator is an AMOUNT in
              // minor units, and rendering it as a bare count would show "25000" beside the same
              // figure formatted as "Rp 25.000" — the identical fact twice, once mislabelled and
              // off by the currency's exponent. eventCount is how many times it happened, which is
              // what a count means for every one of these signals (and equals the numerator for
              // the three that already count events).
              rate.eventCount(),
              null,
              null,
              carriesMoney ? rate.valueMinor() : null,
              carriesMoney ? currency : null));
    }
    signals.add(
        signal(
            type,
            flagged.size(),
            carriesMoney ? OperatorOutliers.totalValue(flagged) : null,
            currency,
            details));
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
      Instant observedTo,
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
        // Measured against whichever came first, the window's end or now. A report on a PAST
        // month must judge that month's sessions by what was true then and not keep aging them on
        // every re-run; a report on the CURRENT month must not call a session opened this morning
        // abandoned merely because the requested window runs to month-end.
        if (session.getOpenedAt().plus(SESSION_STALE_AFTER).isBefore(observedTo)) {
          stale.add(
              new LeakDetailResponse(
                  session.getSessionId(),
                  session.getClosedBy(),
                  session.getBusinessDate(),
                  null,
                  null,
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
      Long variance = session.getOverShortMinor();
      if (variance == null) {
        // A close with no recorded variance is not a close that came out to zero — it is a drawer
        // that was never compared, the same reasoning applied to an OPEN session above. (V21's
        // ck_crs_closed_shape makes this unreachable for a CLOSED row; treating null as zero would
        // still be the wrong default if that ever changed, and costs nothing to get right.)
        exactRun = 0;
        runStartedOn = null;
        continue;
      }

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
                      null,
                      null,
                      longestRunStartedOn,
                      null,
                      (long) longestExactRun,
                      null,
                      null,
                      null,
                      null))));
    }
    return currency;
  }

  /**
   * Builds each short ingredient's consumer edges, weighted by what actually sold.
   *
   * <p>The sales mix is fetched ONCE and joined in memory. Embedding it in the edge query would
   * re-scan and re-group every order and bill line of the period for each 1000-id chunk and then
   * discard all but that chunk's items — the same roll-up, recomputed per chunk, on top of the one
   * the coverage figure already performs in this request.
   *
   * <p>The {@code IN} clause is chunked to the fleet-wide 1000-element convention.
   */
  private Map<UUID, List<LeakEstimator.ConsumerEdge>> loadConsumerEdges(
      UUID businessId, List<IngredientShortfallView> shortfalls, Instant from, Instant to) {

    Map<UUID, Long> soldByItem = new HashMap<>();
    for (SoldQuantityView sold : repository.findSoldQuantities(businessId, from, to)) {
      soldByItem.put(sold.getMenuItemId(), sold.getSoldQty());
    }

    List<UUID> ids = shortfalls.stream().map(IngredientShortfallView::getIngredientId).toList();
    Map<UUID, List<LeakEstimator.ConsumerEdge>> byIngredient = new LinkedHashMap<>();
    for (int i = 0; i < ids.size(); i += 1000) {
      for (RecipeEdgeView edge :
          repository.findRecipeEdges(businessId, ids.subList(i, Math.min(i + 1000, ids.size())))) {
        byIngredient
            .computeIfAbsent(edge.getIngredientId(), id -> new ArrayList<>())
            .add(
                new LeakEstimator.ConsumerEdge(
                    edge.getName(),
                    edge.getUnitPriceMinor(),
                    edge.getCurrency(),
                    edge.getQtyPerPortion(),
                    // Absent means the dish sold nothing this window — a dormant consumer, which
                    // the estimator treats as carrying no weight rather than as missing data.
                    soldByItem.getOrDefault(edge.getMenuItemId(), 0L)));
      }
    }
    return byIngredient;
  }

  /** The report's disclosure of its own blind spots. */
  private LeakCoverageResponse buildCoverage(
      UUID businessId, Instant from, Instant to, Instant observedTo) {
    SoldItemCoverageView coverage = repository.findSoldItemCoverage(businessId, from, to);
    // Both date bounds come from the SAME instants the rest of the report uses, and the upper one
    // stays EXCLUSIVE: taking the calendar date of an exclusive instant and comparing it
    // inclusively
    // would fold a whole extra day of corrections into the period.
    long corrections =
        repository.countManualStockCorrections(
            businessId,
            LocalDate.ofInstant(from, OutletZone.ZONE),
            LocalDate.ofInstant(to, OutletZone.ZONE));
    return new LeakCoverageResponse(
        coverage == null ? 0L : coverage.getTotalSoldQty(),
        coverage == null ? 0L : coverage.getRecipeBackedSoldQty(),
        daysSince(repository.findLastIngredientCountAt(businessId).orElse(null), observedTo),
        daysSince(repository.findLastItemCountAt(businessId).orElse(null), observedTo),
        corrections);
  }

  /**
   * Days between a count and the window's end, or {@code null} when there has never been one.
   *
   * <p>{@code null} rather than a large number, because "never counted" and "counted a very long
   * time ago" call for different responses and a sentinel would blur them. Floors at 0 so a count
   * taken after the reference point (a report re-run on an old period) reads as "counted since",
   * never as a negative age.
   *
   * <p>The reference point is {@code observedTo}, not the requested window end: on the current
   * month the window runs to month-end, and measuring against that would tell an owner who counted
   * yesterday that their stock was last counted three weeks ago — turning the caveat that exists to
   * calibrate their trust into the thing that misleads them.
   */
  private static @Nullable Long daysSince(@Nullable Instant countedAt, Instant observedTo) {
    if (countedAt == null) {
      return null;
    }
    return Math.max(0L, Duration.between(countedAt, observedTo).toDays());
  }

  /** The earlier of two instants. */
  private static Instant earlier(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
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
