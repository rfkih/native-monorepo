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
 * <p>Disposal (ADR 0022): {@link #markDisposed} freezes the disposal facts onto the row — date,
 * posting period, proceeds, and the derecognized accumulated depreciation — after the writer posts
 * the disposal entry. A DISPOSED asset is excluded from every future amortization run ({@code
 * findByStatus(ACTIVE)}), so the frozen amounts never drift; the cash-flow reader reclassifies from
 * these columns, never by re-summing run lines. Extends {@link Auditable}; covered by the {@code
 * fixed_asset} RLS policy (V34), scoped to {@code app.current_tenant} (rules 4 + 5).
 */
@Entity
@Table(name = "fixed_asset")
public class FixedAsset extends Auditable {

  /** The lifecycle states (V36 widened the CHECK to allow DISPOSED — ADR 0022). */
  public enum Status {
    ACTIVE,
    DISPOSED
  }

  /**
   * How the asset entered the books (ADR 0037, V47). {@code ACQUIRED} — the capex-to-cash path (Dr
   * FIXED_ASSET_COST / Cr CASH_CLEARING). {@code BROUGHT_FORWARD} — a pre-owned asset registered
   * during business migration, cash-free (Dr FIXED_ASSET_COST / Cr ACCUMULATED_DEPRECIATION opening
   * / Cr OPENING_BALANCE_EQUITY net), carrying its depreciation-to-date in {@code
   * opening_accumulated_minor}.
   */
  public enum Origin {
    ACQUIRED,
    BROUGHT_FORWARD
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

  /**
   * Depreciation already taken BEFORE registration (ADR 0037) — 0 for an {@code ACQUIRED} asset,
   * the pre-go-live accumulated for a {@code BROUGHT_FORWARD} one. Reduces {@link
   * #depreciableBase()} so the run depreciates only the remaining base; added to the summed run
   * lines by the register and the disposal.
   */
  @Column(name = "opening_accumulated_minor", nullable = false, updatable = false)
  private long openingAccumulatedMinor;

  @Enumerated(EnumType.STRING)
  @Column(name = "origin", nullable = false, updatable = false, length = 16)
  private Origin origin;

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

  /** The user-entered disposal date — metadata only, never a posting period (ADR 0022). */
  @Column(name = "disposal_date")
  private LocalDate disposalDate;

  /** The POSTING period ({@code YYYY-MM}) of the disposal entry — keys the cash-flow reclass. */
  @Column(name = "disposal_period", length = 7)
  private String disposalPeriod;

  @Column(name = "proceeds_minor")
  private Long proceedsMinor;

  /** Accumulated depreciation derecognized by the disposal entry, frozen at disposal time. */
  @Column(name = "accumulated_disposed_minor")
  private Long accumulatedDisposedMinor;

  /** The disposal GL entry. */
  @Column(name = "disposal_entry_id")
  private UUID disposalEntryId;

  /** The dispose request's Idempotency-Key (partial {@code UNIQUE(company_id, key)}). */
  @Column(name = "disposal_idempotency_key", length = 128)
  private String disposalIdempotencyKey;

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
    asset.openingAccumulatedMinor = 0L;
    asset.origin = Origin.ACQUIRED;
    asset.currency = cost.currency().getCurrencyCode();
    asset.status = Status.ACTIVE;
    asset.acquisitionEntryId = Objects.requireNonNull(acquisitionEntryId, "acquisitionEntryId");
    asset.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    return asset;
  }

  /**
   * Registers a pre-owned (brought-forward) asset during business migration (ADR 0037). The caller
   * has already posted the cash-free opening entry keyed on {@code openingEntryId} (Dr
   * FIXED_ASSET_COST gross / Cr ACCUMULATED_DEPRECIATION opening / Cr OPENING_BALANCE_EQUITY net).
   * {@code startPeriod} is the first OPEN depreciation month (computed by the writer), {@code
   * usefulLifeMonths} is the REMAINING life, and {@code openingAccumulated} the depreciation
   * already taken; the run then depreciates {@code cost − salvage − openingAccumulated} over the
   * remaining life from {@code startPeriod}.
   *
   * @throws IllegalArgumentException if a bound is violated (incl. {@code openingAccumulated}
   *     outside {@code [0, cost − salvage]})
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static FixedAsset acquireBroughtForward(
      UUID id,
      String name,
      LocalDate asOfDate,
      String startPeriod,
      Money cost,
      Money salvage,
      Money openingAccumulated,
      int remainingLifeMonths,
      UUID openingEntryId,
      String idempotencyKey) {
    Objects.requireNonNull(asOfDate, "asOfDate");
    Objects.requireNonNull(cost, "cost");
    Objects.requireNonNull(salvage, "salvage");
    Objects.requireNonNull(openingAccumulated, "openingAccumulated");
    cost.requireSameCurrencyAs(salvage);
    cost.requireSameCurrencyAs(openingAccumulated);
    if (!cost.isPositive()) {
      throw new IllegalArgumentException("asset cost must be strictly positive: " + cost);
    }
    if (salvage.isNegative() || salvage.compareTo(cost) >= 0) {
      throw new IllegalArgumentException(
          "salvage must be non-negative and less than cost: salvage=" + salvage + " cost=" + cost);
    }
    if (remainingLifeMonths <= 0) {
      throw new IllegalArgumentException(
          "remaining life must be strictly positive months: " + remainingLifeMonths);
    }
    long depreciableBaseMinor = Math.subtractExact(cost.amountMinor(), salvage.amountMinor());
    if (openingAccumulated.isNegative()
        || openingAccumulated.amountMinor() > depreciableBaseMinor) {
      throw new IllegalArgumentException(
          "opening accumulated depreciation must be within [0, cost − salvage="
              + depreciableBaseMinor
              + "]: "
              + openingAccumulated);
    }
    FixedAsset asset = new FixedAsset();
    asset.id = Objects.requireNonNull(id, "id");
    asset.name = requireName(name);
    asset.acquisitionDate = asOfDate;
    asset.startPeriod = Objects.requireNonNull(startPeriod, "startPeriod");
    asset.costMinor = cost.amountMinor();
    asset.salvageMinor = salvage.amountMinor();
    asset.usefulLifeMonths = remainingLifeMonths;
    asset.openingAccumulatedMinor = openingAccumulated.amountMinor();
    asset.origin = Origin.BROUGHT_FORWARD;
    asset.currency = cost.currency().getCurrencyCode();
    asset.status = Status.ACTIVE;
    asset.acquisitionEntryId = Objects.requireNonNull(openingEntryId, "openingEntryId");
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

  /**
   * The remaining depreciable base = cost − salvage − opening-accumulated (ADR 0037). For an {@code
   * ACQUIRED} asset {@code openingAccumulatedMinor} is 0, so this is the classic {@code cost −
   * salvage}; for a {@code BROUGHT_FORWARD} asset the run spreads only what is left over the
   * remaining life. Non-negative by the factory bounds.
   */
  public Money depreciableBase() {
    return Money.ofMinor(
        Math.subtractExact(Math.subtractExact(costMinor, salvageMinor), openingAccumulatedMinor),
        getCurrency());
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

  /**
   * Depreciation taken before registration (0 for ACQUIRED; the pre-go-live sum for
   * BROUGHT_FORWARD).
   */
  public long getOpeningAccumulatedMinor() {
    return openingAccumulatedMinor;
  }

  public Origin getOrigin() {
    return origin;
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

  /**
   * Marks the asset disposed, freezing every disposal fact in one state change. The caller (the
   * disposal writer) has already posted the balanced disposal entry keyed on {@code
   * disposalEntryId} in the same transaction; the V36 all-or-nothing CHECK enforces the row shape.
   *
   * @param accumulatedDisposed the accumulated depreciation the entry derecognized (Dr 1590)
   * @throws IllegalStateException if the asset is not ACTIVE (a double dispose)
   * @throws IllegalArgumentException if a bound is violated
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public void markDisposed(
      LocalDate disposalDate,
      String disposalPeriod,
      Money proceeds,
      Money accumulatedDisposed,
      UUID disposalEntryId,
      String disposalIdempotencyKey) {
    if (status != Status.ACTIVE) {
      throw new IllegalStateException("asset is already disposed: " + id);
    }
    Objects.requireNonNull(disposalDate, "disposalDate");
    Objects.requireNonNull(proceeds, "proceeds");
    Objects.requireNonNull(accumulatedDisposed, "accumulatedDisposed");
    proceeds.requireSameCurrencyAs(accumulatedDisposed);
    if (proceeds.isNegative()) {
      throw new IllegalArgumentException("proceeds must be non-negative: " + proceeds);
    }
    if (accumulatedDisposed.isNegative()) {
      throw new IllegalArgumentException(
          "accumulated depreciation must be non-negative: " + accumulatedDisposed);
    }
    if (disposalDate.isBefore(acquisitionDate)) {
      throw new IllegalArgumentException(
          "disposal date must not precede acquisition: " + disposalDate + " < " + acquisitionDate);
    }
    this.status = Status.DISPOSED;
    this.disposalDate = disposalDate;
    this.disposalPeriod = Objects.requireNonNull(disposalPeriod, "disposalPeriod");
    this.proceedsMinor = proceeds.amountMinor();
    this.accumulatedDisposedMinor = accumulatedDisposed.amountMinor();
    this.disposalEntryId = Objects.requireNonNull(disposalEntryId, "disposalEntryId");
    this.disposalIdempotencyKey =
        Objects.requireNonNull(disposalIdempotencyKey, "disposalIdempotencyKey");
  }

  public LocalDate getDisposalDate() {
    return disposalDate;
  }

  public String getDisposalPeriod() {
    return disposalPeriod;
  }

  public Long getProceedsMinor() {
    return proceedsMinor;
  }

  public Long getAccumulatedDisposedMinor() {
    return accumulatedDisposedMinor;
  }

  public UUID getDisposalEntryId() {
    return disposalEntryId;
  }

  public String getDisposalIdempotencyKey() {
    return disposalIdempotencyKey;
  }
}
