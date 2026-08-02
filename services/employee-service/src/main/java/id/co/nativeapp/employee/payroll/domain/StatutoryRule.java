package id.co.nativeapp.employee.payroll.domain;

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
 * A VERSIONED + EFFECTIVE-DATED statutory rule (design §1) — the DATA half of the
 * machinery-vs-numbers boundary (HR-9). The gross-to-net ALGORITHMS live in Java ({@link
 * GrossToNetCalculator}); every statutory FIGURE (bracket thresholds, rates, ceilings, PTKP relief)
 * is data here in {@code params_json}. Swapping illustrative placeholders for official DJP/BPJS
 * values is therefore a DATA change — a new effective-dated row with {@code provenance = OFFICIAL}
 * and a new {@code rule_version} — never a code change, and it never alters an already-posted run.
 *
 * <p>The first illustrative-flagging layer is {@link #provenance} ({@code NOT NULL}, CHECK-guarded,
 * no enum default); seeded rows are {@code ILLUSTRATIVE_PLACEHOLDER} and a run resolving any of
 * them is flagged loudly. It extends {@link Auditable} (rule 4) and is RLS-scoped (rule 5;
 * shareable defaults seeded per tenant).
 */
@Entity
@Table(name = "statutory_rule")
public class StatutoryRule extends Auditable {

  /** The far-future open-ended sentinel for {@code effective_to} (never NULL). */
  public static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  /**
   * The rule family key, e.g. {@code PPH21_PROGRESSIVE}, {@code BPJS_KESEHATAN}, {@code
   * PTKP_RELIEF}. The run resolves the single effective row per rule_key at run start.
   */
  @Column(name = "rule_key", nullable = false, length = 64)
  private String ruleKey;

  /**
   * Immutable version label, e.g. {@code "ILLUSTRATIVE-2026.1"}. Stamped onto every payslip line it
   * produced so a re-run is byte-identical (HR-7).
   */
  @Column(name = "rule_version", nullable = false, length = 64)
  private String ruleVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "calc_type", nullable = false, length = 32)
  private StatutoryCalcType calcType;

  /**
   * The rule numbers as JSON ({@code brackets[]}, {@code rate_bp}, {@code ceiling_minor}, {@code
   * employer_bp}, {@code employee_bp}, {@code ptkp} table). DATA, not code. Mapped as PostgreSQL
   * {@code jsonb}.
   */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "params_json", nullable = false, columnDefinition = "jsonb")
  private String paramsJson;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

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

  protected StatutoryRule() {
    // for JPA
  }

  /** Creates a statutory-rule row with a freshly generated id. */
  public StatutoryRule(
      String ruleKey,
      String ruleVersion,
      StatutoryCalcType calcType,
      String paramsJson,
      String currency,
      RuleProvenance provenance,
      String sourceNote,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    this.id = UUID.randomUUID();
    this.ruleKey = Objects.requireNonNull(ruleKey, "ruleKey");
    this.ruleVersion = Objects.requireNonNull(ruleVersion, "ruleVersion");
    this.calcType = Objects.requireNonNull(calcType, "calcType");
    this.paramsJson = Objects.requireNonNull(paramsJson, "paramsJson");
    this.currency = Objects.requireNonNull(currency, "currency");
    this.provenance = Objects.requireNonNull(provenance, "provenance");
    this.sourceNote = Objects.requireNonNull(sourceNote, "sourceNote");
    this.effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    this.effectiveTo = effectiveTo == null ? OPEN_ENDED : effectiveTo;
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

  public StatutoryCalcType getCalcType() {
    return calcType;
  }

  public String getParamsJson() {
    return paramsJson;
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
   * Whether this rule is illustrative (placeholder) — drives the run's {@code
   * uses_illustrative_rules} flag and the loud WARN.
   */
  public boolean isIllustrative() {
    return provenance == RuleProvenance.ILLUSTRATIVE_PLACEHOLDER;
  }

  /**
   * Supersedes this rule by a NEW row starting {@code supersedingEffectiveFrom} (Track P phase P2
   * dataset seed/override — never a delete, mirrors {@code CompensationPackage#end}/{@code
   * Assignment#end}'s effective-dated-close idiom). When the day before the superseding row still
   * leaves a valid (non-empty) range for THIS row, {@code effective_to} closes to that day. When it
   * does not — the superseding row starts on or before this row's own {@code effective_from} (the
   * same-day dataset-activation case, e.g. an illustrative and an official rule both dated {@code
   * 2026-01-01}) — closing to an earlier date would leave {@code effective_to < effective_from} (an
   * invalid range) or, worse, a same-day range that still resolves ALONGSIDE the new row and trips
   * {@code PayrollRunWriter}'s "more than one effective rule" guard. This row is DEACTIVATED
   * instead: {@code active=false} removes it from resolution regardless of its stale date range.
   */
  public void supersede(LocalDate supersedingEffectiveFrom) {
    Objects.requireNonNull(supersedingEffectiveFrom, "supersedingEffectiveFrom");
    LocalDate closeAt = supersedingEffectiveFrom.minusDays(1);
    if (!closeAt.isBefore(this.effectiveFrom)) {
      this.effectiveTo = closeAt;
    } else {
      this.active = false;
    }
  }

  @Override
  public String toString() {
    return "StatutoryRule[id="
        + id
        + ", ruleKey="
        + ruleKey
        + ", ruleVersion="
        + ruleVersion
        + ", provenance="
        + provenance
        + "]";
  }
}
