package id.co.nativeapp.carwash.ticket.domain;

import id.co.nativeapp.carwash.wash.domain.MoneyEmbeddable;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/**
 * One priced line on a {@link CarwashTicket}: a snapshot of a catalog item's name and unit price at
 * checkout time (V6), so a receipt stays reproducible even if the catalog row changes later.
 *
 * <p>{@code price} is the UNIT price (reused {@link MoneyEmbeddable} — one amount/currency pair per
 * row, exactly the table's {@code price_minor}/{@code currency} columns); {@link #getLineTotalMinor}
 * multiplies by {@code qty}. Extends {@link Auditable} (rule 4) and is covered by the {@code
 * carwash_ticket_line} RLS policy (rule 5).
 */
@Entity
@Table(name = "carwash_ticket_line")
public class CarwashTicketLine extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "ticket_id", nullable = false, updatable = false)
  private UUID ticketId;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Enumerated(EnumType.STRING)
  @Column(name = "item_type", nullable = false, updatable = false, length = 16)
  private ItemType itemType;

  @Column(name = "item_id", nullable = false, updatable = false)
  private UUID itemId;

  @Column(name = "name", nullable = false, updatable = false)
  private String name;

  @Embedded private MoneyEmbeddable price;

  @Column(name = "qty", nullable = false, updatable = false)
  private int qty;

  protected CarwashTicketLine() {
    // for JPA
  }

  /** Creates a new ticket line with a freshly generated id. {@code qty} must be strictly positive. */
  public CarwashTicketLine(
      UUID ticketId, UUID businessId, ItemType itemType, UUID itemId, String name, Money unitPrice,
      int qty) {
    this.id = UUID.randomUUID();
    this.ticketId = Objects.requireNonNull(ticketId, "ticketId");
    this.businessId = Objects.requireNonNull(businessId, "businessId");
    this.itemType = Objects.requireNonNull(itemType, "itemType");
    this.itemId = Objects.requireNonNull(itemId, "itemId");
    this.name = requireNonBlank(name, "name");
    this.price = MoneyEmbeddable.of(Objects.requireNonNull(unitPrice, "unitPrice"));
    if (qty <= 0) {
      throw new IllegalArgumentException("qty must be positive: " + qty);
    }
    this.qty = qty;
  }

  public UUID getId() {
    return id;
  }

  public UUID getTicketId() {
    return ticketId;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public ItemType getItemType() {
    return itemType;
  }

  public UUID getItemId() {
    return itemId;
  }

  public String getName() {
    return name;
  }

  /** The line's UNIT price as a {@link Money} value. */
  public Money getUnitPrice() {
    return price.toMoney();
  }

  public int getQty() {
    return qty;
  }

  /** {@code unitPrice * qty}, in the line's currency minor units. */
  public long getLineTotalMinor() {
    return Math.multiplyExact(price.getAmountMinor(), (long) qty);
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    if (value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
