package id.co.nativeapp.employee.payroll;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Binds a non-statutory {@link PayComponent} to an employee's {@link CompensationPackage} with its
 * parameter, effective-dated (design §1). The parameter shape is driven by {@link
 * EarningParamKind}:
 *
 * <ul>
 *   <li>{@code FIXED_AMOUNT} — a fixed {@link Money} amount; this is PII (an individual pay figure)
 *       so it is <strong>column-encrypted at rest</strong> via {@link MoneyPiiConverter} into
 *       {@code fixed_amount_enc} and never logged/evented.
 *   <li>{@code PERCENT_OF_BASE} — {@code percent_basis_points} (non-PII config).
 *   <li>{@code PER_METRIC_UNIT} — {@code metric_key} + {@code rate_minor}/{@code rate_currency}
 *       (non-PII config; the rate per metric unit, multiplied by the consumed metric value).
 * </ul>
 *
 * <p>Extends {@link Auditable} (rule 4); under the {@code earning_rule} RLS policy (rule 5).
 */
@Entity
@Table(name = "earning_rule")
public class EarningRule extends Auditable {

  /** The far-future open-ended sentinel for {@code effective_to} (never NULL). */
  public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "compensation_package_id", nullable = false)
  private UUID compensationPackageId;

  @Column(name = "pay_component_id", nullable = false)
  private UUID payComponentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "param_kind", nullable = false, length = 32)
  private EarningParamKind paramKind;

  /**
   * The fixed amount when {@code param_kind = FIXED_AMOUNT}; PII, encrypted at rest. Null
   * otherwise.
   */
  @Convert(converter = MoneyPiiConverter.class)
  @Column(name = "fixed_amount_enc", columnDefinition = "text")
  private Money fixedAmount;

  /**
   * The basis-point rate when {@code param_kind = PERCENT_OF_BASE}; non-PII config. Null otherwise.
   */
  @Column(name = "percent_basis_points")
  private Integer percentBasisPoints;

  /** The metric key when {@code param_kind = PER_METRIC_UNIT}; non-PII config. Null otherwise. */
  @Column(name = "metric_key", length = 64)
  private String metricKey;

  /**
   * The per-unit rate (minor units) when {@code param_kind = PER_METRIC_UNIT}; non-PII (a config
   * rate, not an individual pay amount). Null otherwise.
   */
  @Column(name = "rate_minor")
  private Long rateMinor;

  @Column(name = "rate_currency", length = 3)
  private String rateCurrency;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  @Column(name = "effective_to", nullable = false)
  private LocalDate effectiveTo;

  protected EarningRule() {
    // for JPA
  }

  private EarningRule(
      UUID compensationPackageId,
      UUID payComponentId,
      EarningParamKind paramKind,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    this.id = UUID.randomUUID();
    this.compensationPackageId =
        Objects.requireNonNull(compensationPackageId, "compensationPackageId");
    this.payComponentId = Objects.requireNonNull(payComponentId, "payComponentId");
    this.paramKind = Objects.requireNonNull(paramKind, "paramKind");
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = effectiveTo == null ? OPEN_ENDED : effectiveTo;
  }

  /** A fixed-amount earning rule (the amount is PII, encrypted at rest). */
  public static EarningRule fixedAmount(
      UUID compensationPackageId,
      UUID payComponentId,
      Money fixedAmount,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    EarningRule rule =
        new EarningRule(
            compensationPackageId,
            payComponentId,
            EarningParamKind.FIXED_AMOUNT,
            effectiveFrom,
            effectiveTo);
    rule.fixedAmount = Objects.requireNonNull(fixedAmount, "fixedAmount");
    return rule;
  }

  /** A percent-of-base earning rule (basis points; non-PII config). */
  public static EarningRule percentOfBase(
      UUID compensationPackageId,
      UUID payComponentId,
      int percentBasisPoints,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    EarningRule rule =
        new EarningRule(
            compensationPackageId,
            payComponentId,
            EarningParamKind.PERCENT_OF_BASE,
            effectiveFrom,
            effectiveTo);
    rule.percentBasisPoints = percentBasisPoints;
    return rule;
  }

  /** A per-metric-unit earning rule (rate per unit of a consumed metric; non-PII config). */
  public static EarningRule perMetricUnit(
      UUID compensationPackageId,
      UUID payComponentId,
      String metricKey,
      Money rate,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    EarningRule rule =
        new EarningRule(
            compensationPackageId,
            payComponentId,
            EarningParamKind.PER_METRIC_UNIT,
            effectiveFrom,
            effectiveTo);
    rule.metricKey = Objects.requireNonNull(metricKey, "metricKey");
    Objects.requireNonNull(rate, "rate");
    rule.rateMinor = rate.amountMinor();
    rule.rateCurrency = rate.currency().getCurrencyCode();
    return rule;
  }

  /** Whether this rule's effective period covers the given as-of date (inclusive). */
  public boolean coversAsOf(LocalDate asOf) {
    Objects.requireNonNull(asOf, "asOf");
    return !asOf.isBefore(effectiveFrom) && !asOf.isAfter(effectiveTo);
  }

  public UUID getId() {
    return id;
  }

  public UUID getCompensationPackageId() {
    return compensationPackageId;
  }

  public UUID getPayComponentId() {
    return payComponentId;
  }

  public EarningParamKind getParamKind() {
    return paramKind;
  }

  /** The decrypted fixed amount (FIXED_AMOUNT only); null otherwise. Restricted; never logged. */
  public Money getFixedAmount() {
    return fixedAmount;
  }

  public Integer getPercentBasisPoints() {
    return percentBasisPoints;
  }

  public String getMetricKey() {
    return metricKey;
  }

  /** The per-metric-unit rate as Money (PER_METRIC_UNIT only); null otherwise. */
  public Money getRate() {
    return rateMinor == null || rateCurrency == null
        ? null
        : Money.ofMinor(rateMinor, rateCurrency.strip());
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  /** PII-safe: id + kind only, NEVER the fixed amount (rule 6). */
  @Override
  public String toString() {
    return "EarningRule[id=" + id + ", paramKind=" + paramKind + "]";
  }
}
