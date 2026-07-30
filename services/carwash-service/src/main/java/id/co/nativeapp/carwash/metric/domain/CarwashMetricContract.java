package id.co.nativeapp.carwash.metric.domain;

import java.util.List;

/**
 * carwash-service's declared METRIC CONTRACT — which {@code metric_key}s this vertical emits, at
 * which grains (ARCHITECTURE.md §2: "each vertical declares its metric contract: which metric_keys
 * it emits at which grains employee/shift/outlet; the validation layer rejects commission rules
 * requesting grains the vertical cannot emit").
 *
 * <p>This is the data declaration of the contract; on recording a wash, carwash emits one {@code
 * MetricPublished} per entry here (see {@code WashWriter}). The "reject a commission rule
 * requesting a grain the vertical cannot emit" validation belongs to the consumer / employee side —
 * here the vertical only DECLARES the contract and EMITS against it.
 *
 * <p>carwash emits, at the {@link MetricGrain#OUTLET outlet} grain:
 *
 * <ul>
 *   <li>{@code wash_count} — the number of washes (a count); the subject is the outlet, the value
 *       is {@code 1} per recorded wash;
 *   <li>{@code upsell_amount} — the upsell revenue (minor units of the wash currency); the subject
 *       is the outlet, the value is the wash's upsell amount ({@code 0} when no upsell).
 * </ul>
 *
 * <p>Both are declared as data (a {@code static final} list), not hardcoded business logic
 * scattered through the emitter — ENGINEERING-STANDARDS §7 (business config is data, not code). A
 * new metric key / grain is one entry here.
 */
public final class CarwashMetricContract {

  /** The {@code wash_count} metric key: a count of washes at the outlet grain. */
  public static final String WASH_COUNT = "wash_count";

  /** The {@code upsell_amount} metric key: upsell revenue (minor units) at the outlet grain. */
  public static final String UPSELL_AMOUNT = "upsell_amount";

  /**
   * The {@code sales_amount} metric key: a ticket's grand total (minor units), emitted at the
   * {@link MetricGrain#EMPLOYEE employee} grain — the carwash POS's washer-commission feed (ADR
   * 0023 decision 4). Deliberately NOT in {@link #DECLARATIONS}: {@code DECLARATIONS} lists only the
   * unconditional {@link MetricGrain#OUTLET outlet}-grain wash metrics; {@code sales_amount} is
   * CONDITIONAL — emitted by {@code ticket.service.TicketWriter}/{@code TicketCaptureWriter} only
   * when the ticket's washer link ({@code staff_profile.employee_id}, snapshotted onto {@code
   * carwash_ticket.washer_employee_id} at checkout) is non-null, with {@code subject_id} = that
   * employee id — the WASHER who did the work, deliberately unlike restaurant (where the subject is
   * the cashier who rang the sale).
   */
  public static final String SALES_AMOUNT = "sales_amount";

  /**
   * The declared contract: the (metric_key, grain) pairs carwash emits UNCONDITIONALLY on every
   * wash/ticket. Immutable; the emitter reads it to produce one {@code MetricPublished} per entry.
   * {@link #SALES_AMOUNT} is intentionally absent (see its Javadoc) — it is conditional, not
   * unconditional, so it is emitted by its own explicit call site rather than this loop.
   */
  public static final List<MetricDeclaration> DECLARATIONS =
      List.of(
          new MetricDeclaration(WASH_COUNT, MetricGrain.OUTLET),
          new MetricDeclaration(UPSELL_AMOUNT, MetricGrain.OUTLET));

  private CarwashMetricContract() {
    // static holder
  }

  /**
   * One declared metric: a {@code metric_key} emitted at a {@link MetricGrain}.
   *
   * @param metricKey the metric key (e.g. {@code wash_count})
   * @param grain the grain it is emitted at (e.g. {@link MetricGrain#OUTLET})
   */
  public record MetricDeclaration(String metricKey, MetricGrain grain) {}
}
