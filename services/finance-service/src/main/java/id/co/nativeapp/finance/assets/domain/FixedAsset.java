package id.co.nativeapp.finance.assets.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code fixed_asset} aggregate (Phase 6, ADR 0020) — a capitalized asset on the straight-line
 * depreciation schedule. Acquiring one posts the capex entry ({@code Dr FIXED_ASSET_COST / Cr
 * CASH_CLEARING}) in the same transaction; the monthly amortization run then posts {@code Dr
 * DEPRECIATION_EXPENSE / Cr ACCUMULATED_DEPRECIATION} for each due month until the depreciable base
 * ({@code cost − salvage}) is fully depreciated.
 *
 * <p><strong>Start convention (ILLUSTRATIVE — SME-gated).</strong> Depreciation begins the month
 * AFTER acquisition (the full-month convention): {@code start_period = acquisition month + 1},
 * computed here at acquire time and immutable.
 *
 * <p>Status is {@code ACTIVE} only in this slice — disposal (gain/loss postings) is a deferred flow
 * the column reserves. Extends {@link Auditable}; covered by the {@code fixed_asset} RLS policy
 * (V34), scoped to {@code app.current_tenant} (rules 4 + 5).
 */
@Entity
@Table(name = "fixed_asset")
public class FixedAsset extends Auditable {

  /** The lifecycle states — ACTIVE only in this slice (disposal deferred, ADR 0020). */
  public enum Status {
    ACTIVE
  }

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "name", nullable = false, length = 255)
  private String name;

  @Column(name = "acquisition_date", nullable = false, updatable = false)
  private LocalDate acquisitionDate;

  /** The first depreciation month ({@code YYYY-MM}) — the month after acquisition. */
  @Column(name = "start_period", nullable = false, updatable = false, length = 7)
  private String startPeriod;

  @Column(name = "cost_minor", nullable = false, updatable = false)
  private long costMinor;

  @Column(name = "salvage_minor", nullable = false, updatable = false)
  private long salvageMinor;

  @Column(name = "useful_life_months", nullable = false, updatable = false)
  private int usefulLifeMonths;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private Status status;

  /** The capex GL entry raised at acquisition. */
  @Column(name = "acquisition_entry_id", nullable = false, updatable = false)
  private UUID acquisitionEntryId;

  /**
   * The client's Idempotency-Key — acquiring posts money, so a retried request replays the original
   * asset instead of posting a second capex entry ({@code UNIQUE(company_id, idempotency_key)}).
   */
  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
  private String idempotencyKey;

  protected FixedAsset() {
    // for JPA
  }

  /**
   * Acquires (capitalizes) an asset. The caller has already posted the capex entry keyed on {@code
   * acquisitionEntryId}.
   *
   * @param id the aggregate id (pre-allocated by the writer — also the capex entry's {@code
   *     source_event_id})
   * @param cost the acquisition cost (strictly positive)
   * @param salvage the residual value at end of life (non-negative, strictly less than cost; same
   *     currency as cost)
   * @param usefulLifeMonths the straight-line life in months (strictly positive)
   * @throws IllegalArgumentException if a bound is violated
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static FixedAsset acquire(
      UUID id,
      String name,
      LocalDate acquisitionDate,
      Money cost,
      Money salvage,
      int usefulLifeMonths,
      UUID acquisitionEntryId,
      String idempotencyKey) {
    Objects.requireNonNull(acquisitionDate, "acquisitionDate");
    Objects.requireNonNull(cost, "cost");
    Objects.requireNonNull(salvage, "salvage");
    cost.requireSameCurrencyAs(salvage);
    if (!cost.isPositive()) {
      throw new IllegalArgumentException("asset cost must be strictly positive: " + cost);
    }
    if (salvage.isNegative() || salvage.compareTo(cost) >= 0) {
      throw new IllegalArgumentException(
          "salvage must be non-negative and less than cost: salvage=" + salvage + " cost=" + cost);
    }
    if (usefulLifeMonths <= 0) {
      throw new IllegalArgumentException(
          "useful life must be strictly positive months: " + usefulLifeMonths);
    }
    FixedAsset asset = new FixedAsset();
    asset.id = Objects.requireNonNull(id, "id");
    asset.name = requireName(name);
    asset.acquisitionDate = acquisitionDate;
    // Full-month convention (ILLUSTRATIVE): depreciation starts the month AFTER acquisition.
    asset.startPeriod = YearMonth.from(acquisitionDate).plusMonths(1).toString();
    asset.costMinor = cost.amountMinor();
    asset.salvageMinor = salvage.amountMinor();
    asset.usefulLifeMonths = usefulLifeMonths;
    asset.currency = cost.currency().getCurrencyCode();
    asset.status = Status.ACTIVE;
    asset.acquisitionEntryId = Objects.requireNonNull(acquisitionEntryId, "acquisitionEntryId");
    asset.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    return asset;
  }

  private static String requireName(String name) {
    Objects.requireNonNull(name, "name");
    String trimmed = name.strip();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("asset name must not be blank");
    }
    return trimmed;
  }

  /** The depreciable base = cost − salvage (strictly positive by the factory bounds). */
  public Money depreciableBase() {
    return Money.ofMinor(Math.subtractExact(costMinor, salvageMinor), getCurrency());
  }

  public UUID getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public LocalDate getAcquisitionDate() {
    return acquisitionDate;
  }

  public String getStartPeriod() {
    return startPeriod;
  }

  public long getCostMinor() {
    return costMinor;
  }

  public long getSalvageMinor() {
    return salvageMinor;
  }

  public int getUsefulLifeMonths() {
    return usefulLifeMonths;
  }

  /** The asset currency (ISO-4217, stripped of any {@code CHAR} padding). */
  public String getCurrency() {
    return currency.strip();
  }

  public Status getStatus() {
    return status;
  }

  public UUID getAcquisitionEntryId() {
    return acquisitionEntryId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
