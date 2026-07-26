package id.co.nativeapp.restaurant.bill.domain;

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
 * Snapshot of one selected modifier option on a {@link BillLine}.
 *
 * <p>Extends {@link Auditable} (rule 4); covered by the {@code bill_line_modifier_tenant_isolation}
 * RLS policy (rule 5). The {@code priceDeltaMinor} is BIGINT — never a float (rule 8). The {@code
 * option_id} is stored as a snapshot column (NOT a FK) so the receipt remains reproducible even if
 * the option is later edited or deleted.
 *
 * <p>A {@code protected} no-arg constructor exists only for JPA.
 */
@Entity
@Table(name = "bill_line_modifier")
public class BillLineModifier extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "bill_line_id", nullable = false, updatable = false)
  private BillLine billLine;

  /** Snapshot: the modifier option id at append time (not a FK — may be deleted later). */
  @Column(name = "option_id", nullable = false, updatable = false)
  private UUID optionId;

  /** Snapshot: the option name at append time (receipt-reproducible). */
  @Column(name = "name_snapshot", nullable = false, length = 255, updatable = false)
  private String nameSnapshot;

  /** Signed price delta in minor units at append time — never a float (rule 8). */
  @Column(name = "price_delta_minor", nullable = false, updatable = false)
  private long priceDeltaMinor;

  protected BillLineModifier() {
    // for JPA
  }

  /**
   * Creates a new modifier snapshot with a freshly generated id.
   *
   * @param optionId the selected modifier option id (snapshot; not verified here)
   * @param nameSnapshot the option name at append time
   * @param priceDeltaMinor the signed price delta in minor units
   */
  public BillLineModifier(UUID optionId, String nameSnapshot, long priceDeltaMinor) {
    this.id = UUID.randomUUID();
    this.optionId = Objects.requireNonNull(optionId, "optionId");
    this.nameSnapshot = Objects.requireNonNull(nameSnapshot, "nameSnapshot");
    this.priceDeltaMinor = priceDeltaMinor;
  }

  /** Called by {@link BillLine#addModifier} to establish the bidirectional link. */
  void setBillLine(BillLine billLine) {
    this.billLine = Objects.requireNonNull(billLine, "billLine");
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
