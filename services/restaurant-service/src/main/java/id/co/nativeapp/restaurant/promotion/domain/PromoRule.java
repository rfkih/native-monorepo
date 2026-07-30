package id.co.nativeapp.restaurant.promotion.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A configured promotion rule (ADR 0026) — the catalog row {@link
 * id.co.nativeapp.restaurant.promotion.service.PromotionEngineService PromotionEngineService} resolves at
 * checkout/pay time. Maps to the {@code promo_rule} table (V16__promotions.sql).
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code promo_rule_tenant_isolation} RLS
 * policy (rule 5). {@code rate_bp}/{@code amount_minor}/{@code currency} are plain nullable columns
 * rather than a {@link id.co.nativeapp.money.Money Money}/{@code MoneyEmbeddable} pairing — exactly
 * the same reasoning as {@code Order.discountMinor}: a percent-type rule legitimately carries no
 * amount and vice versa, so the "amount, always present" invariant a {@code MoneyEmbeddable} implies
 * does not hold here (rule 8 — never a float; still true, just not wrapped in {@code Money}).
 *
 * <p><strong>{@code buy_qty}/{@code get_qty}/{@code get_scope_ref_id} are intentionally UNMAPPED.</strong>
 * They are schema-reserved columns for the not-yet-shipped {@code BUY_X_GET_Y} rule type ({@link
 * PromoRuleType}); {@code ddl-auto=validate} only checks that mapped entity attributes agree with the
 * migrated schema, so leaving them out of this entity is safe and avoids carrying dead fields.
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA.
 */
@Entity
@Table(name = "promo_rule")
public class PromoRule extends Auditable {

  /** The far-future open-ended sentinel for {@code effective_to} (never NULL — rule 4). */
  public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 120)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "rule_type", nullable = false, length = 32)
  private PromoRuleType ruleType;

  @Enumerated(EnumType.STRING)
  @Column(name = "scope_kind", length = 16)
  private PromoScopeKind scopeKind;

  @Column(name = "scope_ref_id")
  private UUID scopeRefId;

  @Column(name = "rate_bp")
  private Long rateBp;

  @Column(name = "amount_minor")
  private Long amountMinor;

  /**
   * ISO-4217 currency code, or {@code null} for a percent-type rule. {@link SqlTypes#CHAR} keeps
   * {@code ddl-auto=validate} agreeing with the migrated fixed-width {@code CHAR(3)} column (the
   * same idiom as {@code MoneyEmbeddable}/{@code TaxChargeRule}), even though — unlike those — this
   * column is nullable.
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", length = 3)
  private String currency;

  @Column(name = "min_subtotal_minor")
  private Long minSubtotalMinor;

  /** Bit 0 = Monday .. bit 6 = Sunday; {@code null} = every day of the week. */
  @Column(name = "dow_mask")
  private Short dowMask;

  @Column(name = "window_start")
  private LocalTime windowStart;

  @Column(name = "window_end")
  private LocalTime windowEnd;

  @Column(name = "tz", nullable = false, length = 48)
  private String tz;

  @Column(name = "priority", nullable = false)
  private int priority;

  @Column(name = "exclusive", nullable = false)
  private boolean exclusive;

  @Column(name = "requires_coupon", nullable = false)
  private boolean requiresCoupon;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  @Column(name = "effective_to", nullable = false)
  private LocalDate effectiveTo;

  protected PromoRule() {
    // for JPA
  }

  /** Creates a promo rule row with a freshly generated id, active by default. */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public PromoRule(
      String name,
      PromoRuleType ruleType,
      PromoScopeKind scopeKind,
      UUID scopeRefId,
      Long rateBp,
      Long amountMinor,
      String currency,
      Long minSubtotalMinor,
      Short dowMask,
      LocalTime windowStart,
      LocalTime windowEnd,
      String tz,
      Integer priority,
      boolean exclusive,
      boolean requiresCoupon,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    this.id = UUID.randomUUID();
    this.name = Objects.requireNonNull(name, "name");
    this.ruleType = Objects.requireNonNull(ruleType, "ruleType");
    this.scopeKind = scopeKind;
    this.scopeRefId = scopeRefId;
    this.rateBp = rateBp;
    this.amountMinor = amountMinor;
    this.currency = currency;
    this.minSubtotalMinor = minSubtotalMinor;
    this.dowMask = dowMask;
    this.windowStart = windowStart;
    this.windowEnd = windowEnd;
    this.tz = (tz == null || tz.isBlank()) ? "Asia/Jakarta" : tz;
    this.priority = (priority == null) ? 100 : priority;
    this.exclusive = exclusive;
    this.requiresCoupon = requiresCoupon;
    this.active = true;
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = (effectiveTo == null) ? OPEN_ENDED : effectiveTo;
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public void rename(String name) {
    this.name = Objects.requireNonNull(name, "name");
  }

  public PromoRuleType getRuleType() {
    return ruleType;
  }

  public PromoScopeKind getScopeKind() {
    return scopeKind;
  }

  public UUID getScopeRefId() {
    return scopeRefId;
  }

  public Long getRateBp() {
    return rateBp;
  }

  public Long getAmountMinor() {
    return amountMinor;
  }

  public String getCurrency() {
    return currency == null ? null : currency.strip();
  }

  public Long getMinSubtotalMinor() {
    return minSubtotalMinor;
  }

  public void setMinSubtotalMinor(Long minSubtotalMinor) {
    this.minSubtotalMinor = minSubtotalMinor;
  }

  public Short getDowMask() {
    return dowMask;
  }

  public LocalTime getWindowStart() {
    return windowStart;
  }

  public LocalTime getWindowEnd() {
    return windowEnd;
  }

  public void setHappyHourWindow(Short dowMask, LocalTime windowStart, LocalTime windowEnd) {
    this.dowMask = dowMask;
    this.windowStart = windowStart;
    this.windowEnd = windowEnd;
  }

  public String getTz() {
    return tz;
  }

  public void setTz(String tz) {
    this.tz = Objects.requireNonNull(tz, "tz");
  }

  public int getPriority() {
    return priority;
  }

  public void setPriority(int priority) {
    this.priority = priority;
  }

  public boolean isExclusive() {
    return exclusive;
  }

  public void setExclusive(boolean exclusive) {
    this.exclusive = exclusive;
  }

  public boolean isRequiresCoupon() {
    return requiresCoupon;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public void setEffectiveWindow(LocalDate effectiveFrom, LocalDate effectiveTo) {
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = (effectiveTo == null) ? OPEN_ENDED : effectiveTo;
  }

  public void reprice(Long rateBp, Long amountMinor, String currency) {
    this.rateBp = rateBp;
    this.amountMinor = amountMinor;
    this.currency = currency;
  }

  public void rescope(PromoScopeKind scopeKind, UUID scopeRefId) {
    this.scopeKind = scopeKind;
    this.scopeRefId = scopeRefId;
  }
}
