package id.co.nativeapp.barbershop.metric.domain;

import java.util.List;

/**
 * barbershop-service's declared METRIC CONTRACT — which {@code metric_key}s this vertical emits, at
 * which grains (ARCHITECTURE.md §2: "each vertical declares its metric contract: which metric_keys
 * it emits at which grains employee/shift/outlet; the validation layer rejects commission rules
 * requesting grains the vertical cannot emit").
 *
 * <p>This is the data declaration of the contract; on checking out a ticket, barbershop emits one
 * {@code MetricPublished} per entry here (see {@code ticket.service.TicketEventEmitter}). The
 * "reject a commission rule requesting a grain the vertical cannot emit" validation belongs to the
 * consumer / employee side — here the vertical only DECLARES the contract and EMITS against it.
 *
 * <p>barbershop emits, at the {@link MetricGrain#OUTLET outlet} grain:
 *
 * <ul>
 *   <li>{@code service_count} — the number of tickets (a count); the subject is the outlet, the
 *       value is {@code 1} per checked-out ticket.
 * </ul>
 *
 * <p>Declared as data (a {@code static final} list), not hardcoded business logic scattered through
 * the emitter — ENGINEERING-STANDARDS §7 (business config is data, not code). A new metric key /
 * grain is one entry here.
 *
 * <p><strong>Deliberate difference from carwash-service.</strong> carwash declares TWO
 * unconditional outlet-grain metrics ({@code wash_count} + {@code upsell_amount}, the latter fed by
 * addon line totals). barbershop declares only {@link #SERVICE_COUNT} — there is no addon-revenue
 * analog to {@code upsell_amount} in scope for this phase (ADR 0024); inventing one would be an
 * unrequested feature.
 */
public final class BarbershopMetricContract {

  /** The {@code service_count} metric key: a count of checked-out tickets at the outlet grain. */
  public static final String SERVICE_COUNT = "service_count";

  /**
   * The {@code sales_amount} metric key: a ticket's grand total (minor units), emitted at the
   * {@link MetricGrain#EMPLOYEE employee} grain — the barbershop POS's barber-commission feed (ADR
   * 0024, mirroring ADR 0023 decision 4). Deliberately NOT in {@link #DECLARATIONS}: {@code
   * DECLARATIONS} lists only the unconditional {@link MetricGrain#OUTLET outlet}-grain metric;
   * {@code sales_amount} is CONDITIONAL — emitted by {@code ticket.service.TicketWriter}/{@code
   * TicketCaptureWriter} only when the ticket's barber link ({@code staff_profile.employee_id},
   * snapshotted onto {@code barbershop_ticket.barber_employee_id} at checkout) is non-null, with
   * {@code subject_id} = that employee id — the BARBER who did the work, deliberately unlike
   * restaurant (where the subject is the cashier who rang the sale).
   */
  public static final String SALES_AMOUNT = "sales_amount";

  /**
   * The declared contract: the (metric_key, grain) pairs barbershop emits UNCONDITIONALLY on every
   * ticket. Immutable; the emitter reads it to produce one {@code MetricPublished} per entry.
   * {@link #SALES_AMOUNT} is intentionally absent (see its Javadoc) — it is conditional, not
   * unconditional, so it is emitted by its own explicit call site rather than this loop.
   */
  public static final List<MetricDeclaration> DECLARATIONS =
      List.of(new MetricDeclaration(SERVICE_COUNT, MetricGrain.OUTLET));

  private BarbershopMetricContract() {
    // static holder
  }

  /**
   * One declared metric: a {@code metric_key} emitted at a {@link MetricGrain}.
   *
   * @param metricKey the metric key (e.g. {@code service_count})
   * @param grain the grain it is emitted at (e.g. {@link MetricGrain#OUTLET})
   */
  public record MetricDeclaration(String metricKey, MetricGrain grain) {}
}
