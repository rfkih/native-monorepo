package id.co.nativeapp.restaurant.stocktake.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code stocktake} aggregate (ADR 0038 phase 3, daily close v2) — one physical inventory count
 * submission at an outlet. Each counted line adjusts the corresponding {@code menu_item}'s stock to
 * the counted quantity; the parent row carries the SIGNED net valued shrinkage ({@code
 * shrinkageMinor}) that {@code StocktakeCompleted} carries to finance for a single
 * inventory-adjustment posting.
 *
 * <p>{@code shrinkageMinor} is SERVER-computed by {@code StocktakeWriter} as {@code Σ (system_qty −
 * counted_qty) × unit_cost_minor} over cost-bearing lines (positive = net loss, negative = net
 * gain, zero = no ledger entry) — never client-supplied. All money is integer minor units in one
 * {@code currency} (rule 8).
 *
 * <p>Extends {@link Auditable} — mandatory audit + tenancy columns, covered by the V28 RLS policy
 * (rules 4 + 5). Producer idempotency: at most one stocktake per {@code (company_id,
 * idempotency_key)} — {@code uq_stocktake_company_idempotency}.
 */
@Entity
@Table(name = "stocktake")
public class Stocktake extends Auditable {

  @jakarta.persistence.Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "counted_at", nullable = false, updatable = false)
  private Instant countedAt;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  /** SIGNED net valued shrinkage: positive = net loss, negative = net gain, zero = no entry. */
  @Column(name = "shrinkage_minor", nullable = false, updatable = false)
  private long shrinkageMinor;

  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
  private String idempotencyKey;

  protected Stocktake() {
    // for JPA
  }

  private Stocktake(
      UUID businessId,
      Instant countedAt,
      String currency,
      long shrinkageMinor,
      String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.countedAt = Objects.requireNonNull(countedAt, "countedAt");
    this.currency = Objects.requireNonNull(currency, "currency");
    this.shrinkageMinor = shrinkageMinor;
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }

  /** Creates a new stocktake submission with the server-computed shrinkage. */
  public static Stocktake of(
      UUID businessId,
      Instant countedAt,
      String currency,
      long shrinkageMinor,
      String idempotencyKey) {
    return new Stocktake(businessId, countedAt, currency, shrinkageMinor, idempotencyKey);
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public Instant getCountedAt() {
    return countedAt;
  }

  /** ISO-4217 code; CHAR(3) padding is stripped for callers. */
  public String getCurrency() {
    return currency == null ? null : currency.strip();
  }

  public long getShrinkageMinor() {
    return shrinkageMinor;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
