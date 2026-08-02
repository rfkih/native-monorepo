package id.co.nativeapp.restaurant.sale.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code sale} aggregate — restaurant-service's system of record for a recorded sale, and the
 * source of the {@code SaleRecorded} event.
 *
 * <p>It extends {@link Auditable}, so it inherits the mandatory audit + tenancy columns ({@code
 * created_at}/{@code created_by}, {@code updated_at}/{@code updated_by}, {@code version}, {@code
 * company_id}) and is covered by the {@code sale} RLS policy in the Flyway baseline (rule 4 + rule
 * 5).
 *
 * <p>The monetary amount is a {@code libs/money} {@link Money} (rule 8 — integer minor units +
 * ISO-4217 currency, never a float), persisted via {@link MoneyEmbeddable} as {@code amount_minor
 * BIGINT} + {@code currency CHAR(3)}.
 *
 * <p>{@code idempotency_key} is the client's request id; together with {@code company_id} it
 * carries a {@code UNIQUE} constraint so a retried record-sale resolves to the same row (producer
 * idempotency — exactly one {@code SaleRecorded} on retry).
 */
@Entity
@Table(name = "sale")
public class Sale extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Embedded private MoneyEmbeddable amount;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "idempotency_key", nullable = false, updatable = false)
  private String idempotencyKey;

  /**
   * The tender that settled this sale ({@code CASH | QRIS | CARD}, later {@code ONLINE}) — V21, ADR
   * 0036 (closing kasir). Nullable: legacy pre-V21 rows and no-payment paths have no tender; a NULL
   * row is simply outside every register session's cash window. Deliberately a String (not the
   * payment feature's enum) — the sale aggregate stores what the wire carries.
   */
  @Column(name = "tender_type", updatable = false)
  private String tenderType;

  protected Sale() {
    // for JPA
  }

  /**
   * Creates a new sale with a freshly generated id.
   *
   * @param businessId the originating business unit
   * @param amount the sale amount as {@link Money} (never a float)
   * @param occurredAt when the sale occurred
   * @param idempotencyKey the client's request id (dedupe key with company_id)
   */
  public Sale(UUID businessId, Money amount, Instant occurredAt, String idempotencyKey) {
    this(businessId, amount, occurredAt, idempotencyKey, null);
  }

  /**
   * Creates a new sale with a freshly generated id and the settling tender (ADR 0036).
   *
   * @param tenderType the tender enum name ({@code CASH | QRIS | CARD}), or null for
   *     legacy/no-payment sales
   */
  public Sale(
      UUID businessId, Money amount, Instant occurredAt, String idempotencyKey, String tenderType) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.amount = MoneyEmbeddable.of(amount);
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    this.tenderType = tenderType;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  /** The sale amount as a {@link Money} value (reconstructed from its columns). */
  public Money getAmount() {
    return amount.toMoney();
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  /** The settling tender ({@code CASH | QRIS | CARD}), or null for legacy/no-payment sales. */
  public String getTenderType() {
    return tenderType;
  }
}
