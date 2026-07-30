package id.co.nativeapp.carwash.pricing.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A VERSIONED + EFFECTIVE-DATED tax/service-charge configuration rule for the carwash POS checkout
 * pricing pipeline — ported from restaurant-service's {@code pricing} feature (V5 mirror; the
 * carwash migration seeds no {@code SERVICE_CHARGE} row, so resolution falls through to zero for
 * that rule family, same as restaurant does before a service-charge row is seeded).
 *
 * <p>Mirrors the {@code statutory_rule} pattern from {@code employee-service}: the ALGORITHM lives
 * in Java ({@code TaxChargeService.resolve}); every FIGURE (rates, flags) is data here. Swapping
 * illustrative placeholders for real jurisdiction values is a DATA change — a new effective-dated
 * row — never a code change, and it never alters already-posted sales.
 *
 * <p><strong>Supported rule keys:</strong>
 *
 * <ul>
 *   <li>{@code VAT_CARWASH} — the car-wash-sale tax rate in basis points. Whether this is PPN
 *       (national VAT, currently 11%), a regional service tax, or a combination, is
 *       <strong>SME-gated</strong>; any seeded value is ILLUSTRATIVE only.
 *   <li>{@code SERVICE_CHARGE} — the service-charge rate in basis points. Whether service charge is
 *       revenue or a liability (tip pool) is <strong>SME-gated</strong>.
 * </ul>
 *
 * <p>This table is Auditable (rule 4) and under {@code FORCE ROW LEVEL SECURITY} (rule 5):
 * tax/charge rates are per-tenant. Money is integer minor units (rule 8); rates are in basis points
 * (never a float).
 *
 * <p>The checkout resolves effective rules at the order's {@code occurredAt} timestamp (mirroring
 * {@code PostingTemplateResolver} / {@code GlAccountResolver}). If any resolved rule has {@code
 * provenance = ILLUSTRATIVE_PLACEHOLDER}, the checkout sets {@code uses_illustrative_rules = true}
 * on the {@code SaleRecorded} event, which propagates to finance to flag derived GL entries.
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA; application code uses the public
 * constructor.
 */
@Entity
@Table(name = "tax_charge_rule")
public class TaxChargeRule extends Auditable {

  /** The far-future open-ended sentinel for {@code effective_to} (never NULL — rule 4). */
  public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

  /**
   * Rule key for the car-wash-sale tax (VAT/regional service tax — regime is SME-gated).
   *
   * <p>Named {@code KEY_VAT} (not {@code KEY_PB1}, restaurant-service's name for its equivalent
   * constant) because PB1 is a restaurant/entertainment-specific regional tax that does not apply
   * to carwash; the carwash regime is more likely PPN (VAT) or a general regional service tax, but
   * that too is SME-gated — hence the neutral name.
   */
  public static final String KEY_VAT = "VAT_CARWASH";

  /** Rule key for the service charge applied on top of the taxable base. */
  public static final String KEY_SERVICE_CHARGE = "SERVICE_CHARGE";

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /**
   * The rule family key: {@code VAT_CARWASH} or {@code SERVICE_CHARGE}. New rule types may be added
   * in future migrations without breaking existing rows.
   */
  @Column(name = "rule_key", nullable = false, length = 64)
  private String ruleKey;

  /**
   * Immutable version label, e.g. {@code "ILLUSTRATIVE-2026.1"}. Stamped on the {@code
   * SaleRecorded} event's {@code tax_rule_version} field so downstream finance can identify which
   * rule produced the breakdown.
   */
  @Column(name = "rule_version", nullable = false, length = 64)
  private String ruleVersion;

  /**
   * The rate in basis points (1 bp = 0.01%, 10000 bp = 100%, 1000 bp = 10%). Applied with a single
   * HALF_EVEN rounding via {@link id.co.nativeapp.money.Money#applyBasisPoints}. NEVER stored as a
   * float.
   */
  @Column(name = "rate_bp", nullable = false)
  private long rateBp;

  /**
   * Whether the service charge is included in the tax base before applying the tax rate. If {@code
   * true}, {@code taxBase = taxableBase + serviceCharge}; if {@code false}, {@code taxBase =
   * taxableBase} only. SME-gated: the correct treatment depends on jurisdiction and contract.
   */
  @Column(name = "service_charge_in_tax_base", nullable = false)
  private boolean serviceChargeInTaxBase;

  /**
   * The provenance of this rule's figures. {@code NOT NULL} and CHECK-guarded. A checkout resolving
   * this rule copies the flag to the {@code SaleRecorded} event's {@code uses_illustrative_rules}
   * field.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "provenance", nullable = false, length = 32)
  private RuleProvenance provenance;

  /**
   * A human-readable note explaining the source of the figures (e.g. the regulation reference, or
   * the SME sign-off date). Mandatory — never null.
   */
  @Column(name = "source_note", nullable = false, columnDefinition = "text")
  private String sourceNote;

  /** ISO-4217 currency code this rule applies to (e.g. {@code "IDR"}). */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  /** The date from which this rule version is effective (inclusive). */
  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  /**
   * The date up to which this rule version is effective (inclusive). The far-future sentinel {@code
   * 9999-12-31} marks an open-ended rule (never NULL — rule 4).
   */
  @Column(name = "effective_to", nullable = false)
  private LocalDate effectiveTo;

  /** Whether this rule version is active. Soft-delete: inactive rules are not resolved. */
  @Column(name = "active", nullable = false)
  private boolean active;

  protected TaxChargeRule() {
    // for JPA
  }

  /** Creates a tax-charge rule row with a freshly generated id. */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public TaxChargeRule(
      String ruleKey,
      String ruleVersion,
      long rateBp,
      boolean serviceChargeInTaxBase,
      String currency,
      RuleProvenance provenance,
      String sourceNote,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    this.id = UUID.randomUUID();
    this.ruleKey = Objects.requireNonNull(ruleKey, "ruleKey");
    this.ruleVersion = Objects.requireNonNull(ruleVersion, "ruleVersion");
    this.rateBp = rateBp;
    this.serviceChargeInTaxBase = serviceChargeInTaxBase;
    this.currency = Objects.requireNonNull(currency, "currency");
    this.provenance = Objects.requireNonNull(provenance, "provenance");
    this.sourceNote = Objects.requireNonNull(sourceNote, "sourceNote");
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = (effectiveTo == null) ? OPEN_ENDED : effectiveTo;
    this.active = true;
  }

  public UUID getId() {
    return id;
  }

  public String getRuleKey() {
    return ruleKey;
  }

  public String getRuleVersion() {
    return ruleVersion;
  }

  public long getRateBp() {
    return rateBp;
  }

  public boolean isServiceChargeInTaxBase() {
    return serviceChargeInTaxBase;
  }

  public String getCurrency() {
    return currency.strip();
  }

  public RuleProvenance getProvenance() {
    return provenance;
  }

  public String getSourceNote() {
    return sourceNote;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public boolean isActive() {
    return active;
  }

  /**
   * Whether this rule carries illustrative-placeholder figures (drives the checkout's {@code
   * uses_illustrative_rules} flag).
   */
  public boolean isIllustrative() {
    return provenance == RuleProvenance.ILLUSTRATIVE_PLACEHOLDER;
  }

  @Override
  public String toString() {
    return "TaxChargeRule[id="
        + id
        + ", ruleKey="
        + ruleKey
        + ", ruleVersion="
        + ruleVersion
        + ", rateBp="
        + rateBp
        + ", provenance="
        + provenance
        + "]";
  }
}
