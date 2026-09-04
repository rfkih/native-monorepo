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
 * The {@code goods_receipt} aggregate (ADR 0067 Phase B, §1) — the durable, independently
 * meaningful record of ONE PRICED {@link Ingredient} receive (a moving-average receive, ADR 0056).
 * Two jobs: (a) its id becomes the {@code StockReceived} event's {@code receipt_id} — finance's
 * idempotency key for the {@code Dr 1100 Inventory / Cr 2050 GRNI} posting; (b) it closes ADR 0056
 * accepted-limitation #1 (a duplicated priced receive currently double-adds moving-average value
 * with nothing to detect the repeat) — once a caller-supplied {@link #idempotencyKey} exists (V43
 * migration note: no such API key exists yet, so this stays {@code null} until a later task adds
 * one; the partial-unique index is inert until then).
 *
 * <p>A REAL AGGREGATE, not a CDC-derived read model — the rule-4 audit exception does not apply.
 * Money ({@link #valueMinor} + {@link #currency}) is minor units + ISO-4217 (rule 8), never a
 * float. Extends {@link Auditable} (rule 4); covered by the V43 {@code
 * goods_receipt_tenant_isolation} RLS policy (rule 5).
 */
@Entity
@Table(name = "goods_receipt")
public class GoodsReceipt extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "ingredient_id", nullable = false, updatable = false)
  private UUID ingredientId;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  /** Units received, in the ingredient's own base unit (integer; ADR 0046 decimal ban). */
  @Column(name = "qty", nullable = false, updatable = false)
  private long qty;

  /**
   * The EXACT total paid for this receipt, minor units — the value added to the moving-average
   * bucket ({@link Ingredient#receive}). Never a float (rule 8).
   */
  @Column(name = "value_minor", nullable = false, updatable = false)
  private long valueMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Column(name = "received_at", nullable = false, updatable = false)
  private Instant receivedAt;

  /**
   * A caller-supplied idempotency key; {@code null} until the receive endpoint's API carries one
   * (see the class javadoc — the V43 migration's "IDEMPOTENCY KEY DECISION" note). Do NOT invent an
   * API change here.
   */
  @Column(name = "idempotency_key", updatable = false, length = 64)
  @Nullable private String idempotencyKey;

  protected GoodsReceipt() {
    // for JPA
  }

  private GoodsReceipt(
      UUID ingredientId,
      UUID businessId,
      long qty,
      long valueMinor,
      String currency,
      Instant receivedAt,
      @Nullable String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.ingredientId = Objects.requireNonNull(ingredientId, "ingredientId");
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    if (qty <= 0) {
      throw new IllegalArgumentException("qty must be positive, got: " + qty);
    }
    if (valueMinor < 0) {
      throw new IllegalArgumentException("valueMinor must be >= 0, got: " + valueMinor);
    }
    this.qty = qty;
    this.valueMinor = valueMinor;
    this.currency = Objects.requireNonNull(currency, "currency");
    this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    this.idempotencyKey = idempotencyKey;
  }

  /**
   * Creates a new goods-receipt fact. Mirrors the validation {@link Ingredient#receive} already
   * enforced on the aggregate the caller just mutated (a receipt row can never encode a receive the
   * ingredient itself would have rejected).
   *
   * @param idempotencyKey {@code null} until the receive API carries a caller-supplied key
   */
  public static GoodsReceipt of(
      UUID ingredientId,
      UUID businessId,
      long qty,
      long valueMinor,
      String currency,
      Instant receivedAt,
      @Nullable String idempotencyKey) {
    return new GoodsReceipt(
        ingredientId, businessId, qty, valueMinor, currency, receivedAt, idempotencyKey);
  }

  public UUID getId() {
    return id;
  }

  public UUID getIngredientId() {
    return ingredientId;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public long getQty() {
    return qty;
  }

  public long getValueMinor() {
    return valueMinor;
  }

  /** ISO-4217 code; {@code CHAR(3)} padding is stripped for callers. */
  public String getCurrency() {
    return currency.strip();
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  @Nullable public String getIdempotencyKey() {
    return idempotencyKey;
  }
}
