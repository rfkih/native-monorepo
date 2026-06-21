package id.co.nativeapp.restaurant.order.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * Snapshot of one selected modifier option on an {@link OrderLine} — the receipt-reproducible
 * record of which option was chosen and its price delta at order time.
 *
 * <p>Extends {@link Auditable} (rule 4); covered by the {@code order_line_modifier} RLS policy in
 * {@code V6__catalog_phase3.sql} (rule 5). The {@code option_id} is stored as a snapshot column
 * (NOT a FK) because the underlying option may be edited or deleted, but the receipt must remain
 * reproducible.
 *
 * <p>The {@code priceDeltaMinor} is stored as a {@code BIGINT} — signed, never a float (rule 8).
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA.
 */
@Entity
@Table(name = "order_line_modifier")
public class OrderLineModifier extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "order_line_id", nullable = false, updatable = false)
  private OrderLine orderLine;

  /** Snapshot: the modifier option id at order time (not a FK — may be deleted later). */
  @Column(name = "option_id", nullable = false, updatable = false)
  private UUID optionId;

  /** Snapshot: the option name at order time (receipt-reproducible). */
  @Column(name = "name_snapshot", nullable = false, length = 255, updatable = false)
  private String nameSnapshot;

  /**
   * Signed price delta in minor units at order time — never a float (rule 8). Positive = surcharge,
   * zero = no change, negative = discount-variant.
   */
  @Column(name = "price_delta_minor", nullable = false, updatable = false)
  private long priceDeltaMinor;

  protected OrderLineModifier() {
    // for JPA
  }

  /**
   * Creates a new modifier snapshot with a freshly generated id.
   *
   * @param optionId the selected modifier option id (snapshot; not verified here)
   * @param nameSnapshot the option name at order time
   * @param priceDeltaMinor the signed price delta in minor units (never a float)
   */
  public OrderLineModifier(UUID optionId, String nameSnapshot, long priceDeltaMinor) {
    this.id = UUID.randomUUID();
    this.optionId = Objects.requireNonNull(optionId, "optionId");
    this.nameSnapshot = Objects.requireNonNull(nameSnapshot, "nameSnapshot");
    this.priceDeltaMinor = priceDeltaMinor;
  }

  /** Called by {@link OrderLine#addModifier} to establish the bidirectional link. */
  void setOrderLine(OrderLine orderLine) {
    this.orderLine = Objects.requireNonNull(orderLine, "orderLine");
  }

  public UUID getId() {
    return id;
  }

  public UUID getOptionId() {
    return optionId;
  }

  public String getNameSnapshot() {
    return nameSnapshot;
  }

  public long getPriceDeltaMinor() {
    return priceDeltaMinor;
  }
}
