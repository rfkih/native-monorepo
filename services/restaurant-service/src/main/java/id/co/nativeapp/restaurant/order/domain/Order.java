package id.co.nativeapp.restaurant.order.domain;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.sale.domain.MoneyEmbeddable;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code restaurant_order} aggregate — a customer order composed of {@link OrderLine}s.
 *
 * <p>Extends {@link Auditable} (rule 4) and is covered by the {@code restaurant_order} RLS policy
 * in {@code V2__pos.sql} (rule 5). The order total is a {@link Money} persisted as {@code
 * total_minor BIGINT + currency CHAR(3)} via {@link MoneyEmbeddable} (rule 8 — never a float).
 *
 * <p>On checkout the owning service calls {@code SaleWriter.create} with the order total so exactly
 * one {@code SaleRecorded} event flows into finance (rule 3). The returned sale id is stored on
 * this aggregate for traceability.
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA; application code uses the public
 * constructor which validates its invariants.
 */
@Entity
@Table(name = "restaurant_order")
public class Order extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "status", nullable = false, length = 16)
  private String status;

  @Embedded
  @AttributeOverrides({
    @AttributeOverride(
        name = "amountMinor",
        column = @Column(name = "total_minor", nullable = false)),
    @AttributeOverride(
        name = "currency",
        column = @Column(name = "currency", nullable = false, length = 3))
  })
  private MoneyEmbeddable total;

  @Column(name = "sale_id")
  private UUID saleId;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  @Column(name = "idempotency_key", nullable = false, updatable = false)
  private String idempotencyKey;

  @OneToMany(
      mappedBy = "order",
      cascade = CascadeType.ALL,
      fetch = FetchType.LAZY,
      orphanRemoval = true)
  private List<OrderLine> lines = new ArrayList<>();

  protected Order() {
    // for JPA
  }

  /**
   * Creates a new order in PENDING status with a freshly generated id. Lines are added via {@link
   * #addLine}.
   *
   * @param businessId the originating business unit
   * @param total the pre-computed order total as {@link Money} (never a float)
   * @param occurredAt when the order was placed
   * @param idempotencyKey the client's request id (dedupe key with company_id)
   */
  public Order(UUID businessId, Money total, Instant occurredAt, String idempotencyKey) {
    this.id = UUID.randomUUID();
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.status = "PENDING";
    this.total = MoneyEmbeddable.of(Objects.requireNonNull(total, "total"));
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }

  /**
   * Adds a line to this order. The line's {@code company_id} is set by the caller (must match the
   * order's company_id so RLS applies).
   */
  public void addLine(OrderLine line) {
    lines.add(line);
    line.setOrder(this);
  }

  /** Links the sale record created on checkout to this order. */
  public void linkSale(UUID saleId) {
    this.saleId = Objects.requireNonNull(saleId, "saleId");
    this.status = "COMPLETED";
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getStatus() {
    return status;
  }

  /** The order total as a {@link Money} value (reconstructed from its columns). */
  public Money getTotal() {
    return total.toMoney();
  }

  public UUID getSaleId() {
    return saleId;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public List<OrderLine> getLines() {
    return Collections.unmodifiableList(lines);
  }
}
