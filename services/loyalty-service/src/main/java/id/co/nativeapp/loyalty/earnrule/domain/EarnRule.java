package id.co.nativeapp.loyalty.earnrule.domain;

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

/**
 * A VERSIONED + EFFECTIVE-DATED, COMPANY-WIDE points-earning configuration rule — the task's
 * explicit instruction to "mirror {@code tax_charge_rule}'s shape" applied to a single-purpose,
 * company-wide rule family (unlike {@code tax_charge_rule}, which resolves per {@code rule_key}
 * across several distinct rule families, {@code earn_rule} has exactly one family — points earning
 * — so there is no {@code rule_key} column here; {@code rule_version} is kept for the SAME
 * highest-version-wins resolution discipline).
 *
 * <p><strong>NO seed row</strong> (by design, per the task): earning is company-configured, so a
 * fresh company earns ZERO points on every sale until an owner/manager creates the first {@code
 * earn_rule} row via {@code POST /api/v1/loyalty/earn-rules}.
 *
 * <p>{@code pointsPerMinorBp} is the points-earning rate in basis points PER MINOR UNIT of the sale
 * currency — e.g. {@code 100} bp (1%) means 1 point per 100 minor units of taxable base. {@code
 * minSaleMinor} is an optional floor: a sale whose taxable base is below it earns nothing at all
 * (not even a zero-point ledger entry — see {@code ingest.service.SaleIngestWriter}).
 *
 * <p>This table is Auditable (rule 4) and under {@code FORCE ROW LEVEL SECURITY} (rule 5) — rates
 * are per-tenant. Rates are basis points (never a float, rule 8).
 */
@Entity
@Table(name = "earn_rule")
public class EarnRule extends Auditable {

  /** The far-future open-ended sentinel for {@code effective_to} (never NULL — rule 4). */
  public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /** Immutable version label, e.g. {@code "ILLUSTRATIVE-2026.1"} or a company-chosen label. */
  @Column(name = "rule_version", nullable = false, length = 64)
  private String ruleVersion;

  /** Points earned per minor unit of the sale currency, in basis points. Never a float. */
  @Column(name = "points_per_minor_bp", nullable = false)
  private long pointsPerMinorBp;

  /**
   * Optional minimum sale (taxable base) in minor units to earn ANY points; {@code null} means no
   * floor.
   */
  @Column(name = "min_sale_minor")
  private Long minSaleMinor;

  @Enumerated(EnumType.STRING)
  @Column(name = "provenance", nullable = false, length = 32)
  private RuleProvenance provenance;

  @Column(name = "source_note", nullable = false, columnDefinition = "text")
  private String sourceNote;

  @Column(name = "effective_from", nullable = false)
  private LocalDate effectiveFrom;

  @Column(name = "effective_to", nullable = false)
  private LocalDate effectiveTo;

  @Column(name = "active", nullable = false)
  private boolean active;

  protected EarnRule() {
    // for JPA
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  public EarnRule(
      String ruleVersion,
      long pointsPerMinorBp,
      Long minSaleMinor,
      RuleProvenance provenance,
      String sourceNote,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    this.id = UUID.randomUUID();
    this.ruleVersion = Objects.requireNonNull(ruleVersion, "ruleVersion");
    this.pointsPerMinorBp = pointsPerMinorBp;
    this.minSaleMinor = minSaleMinor;
    this.provenance = Objects.requireNonNull(provenance, "provenance");
    this.sourceNote = Objects.requireNonNull(sourceNote, "sourceNote");
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = (effectiveTo == null) ? OPEN_ENDED : effectiveTo;
    this.active = true;
  }

  public UUID getId() {
    return id;
  }

  public String getRuleVersion() {
    return ruleVersion;
  }

  public long getPointsPerMinorBp() {
    return pointsPerMinorBp;
  }

  public Long getMinSaleMinor() {
    return minSaleMinor;
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
}
