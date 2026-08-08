package id.co.nativeapp.restaurant.inventory.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.lang.Nullable;

/**
 * The {@code ingredient_stocktake} aggregate (ADR 0046 phase 1) — one physical count submission of
 * the {@link Ingredient} catalog at an outlet. A clone of {@code
 * id.co.nativeapp.restaurant.stocktake.domain.Stocktake} (ADR 0038 phase 3) with ONE deliberate
 * difference: {@link #currency} is NULLABLE — a count with zero costed lines has nothing to value
 * or post, so the parent carries no currency at all and {@code IngredientStocktakeWriter} skips the
 * outbox entirely (ADR 0046).
 *
 * <p>Each counted line adjusts the corresponding {@link Ingredient}'s stock to the counted
 * quantity; the parent row carries the SIGNED net valued shrinkage ({@code shrinkageMinor}) that
 * {@code StocktakeCompleted} (reused verbatim, ADR 0046) carries to finance for a single inventory-
 * adjustment posting — server-computed, never client-supplied. All money is integer minor units in
 * one currency (rule 8).
 *
 * <p>Extends {@link Auditable} — mandatory audit + tenancy columns, covered by the V31 RLS policy
 * (rules 4 + 5). Producer idempotency: at most one count per {@code (company_id, idempotency_key)}
 * — {@code uq_ingredient_stocktake_company_idempotency}.
 */
@Entity
@Table(name = "ingredient_stocktake")
public class IngredientStocktake extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "counted_at", nullable = false, updatable = false)
  private Instant countedAt;

  /** {@code null} when the count carries zero costed lines (nothing to post — ADR 0046). */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", updatable = false, length = 3)
  @Nullable private String currency;

  /** SIGNED net valued shrinkage: positive = net loss, negative = net gain, zero = no entry. */
  @Column(name = "shrinkage_minor", nullable = false, updatable = false)
  private long shrinkageMinor;

  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 64)
  private String idempotencyKey;

  protected IngredientStocktake() {
    // for JPA
  }

  private IngredientStocktake(
      UUID businessId,
      Instant countedAt,
      @Nullable String currency,
      long shrinkageMinor,
      String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.countedAt = Objects.requireNonNull(countedAt, "countedAt");
    this.currency = currency;
    this.shrinkageMinor = shrinkageMinor;
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }

  /**
   * Creates a new ingredient stocktake submission with the server-computed shrinkage.
   *
   * @param currency {@code null} when zero lines carried a cost (ADR 0046)
   */
  public static IngredientStocktake of(
      UUID businessId,
      Instant countedAt,
      @Nullable String currency,
      long shrinkageMinor,
      String idempotencyKey) {
    return new IngredientStocktake(businessId, countedAt, currency, shrinkageMinor, idempotencyKey);
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

  /**
   * ISO-4217 code; {@code CHAR(3)} padding is stripped for callers. {@code null} = no costed line.
   */
  @Nullable public String getCurrency() {
    return currency == null ? null : currency.strip();
  }

  public long getShrinkageMinor() {
    return shrinkageMinor;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
