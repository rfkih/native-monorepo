package id.co.nativeapp.loyalty.ingest.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A parked sale reversal (QA sweep 2026-08-05, CRITICAL fix): a {@code SaleVoided}/{@code
 * SaleRefunded} that arrived BEFORE its {@code SaleRecorded} was ingested (cross-topic reorder —
 * the two travel on separate topics with independently-lagging consumers). The reversal event
 * itself is claimed by {@code ProcessedEventStore} in the same transaction that writes this row, so
 * the row IS the durable record; {@code SaleIngestWriter} applies it the moment the sale arrives
 * and stamps {@link #markApplied applied_at}.
 *
 * <p>{@code UNIQUE(company_id, sale_id)} — first reversal event wins, mirroring the
 * full-reversal-only invariant (review S2) the REVERSE-row guard enforces on the ingested path.
 *
 * <p>A row whose {@code applied_at} stays {@code NULL} marks a sale the ledger never saw (either a
 * pre-marker legacy sale or a sale with no loyalty facts that was ingested before this table
 * existed) — ops-visible, harmless, never blocks anything.
 */
@Entity
@Table(name = "pending_sale_reversal")
public class PendingSaleReversal extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "sale_id", nullable = false, updatable = false)
  private UUID saleId;

  /** The parked reversal event's durable id — replayed as the REVERSE rows' source_event_id. */
  @Column(name = "reversal_event_id", nullable = false, updatable = false)
  private UUID reversalEventId;

  @Column(name = "reversal_occurred_at", nullable = false, updatable = false)
  private Instant reversalOccurredAt;

  /** When ingest resolved this row; {@code null} while the sale is still unseen. */
  @Column(name = "applied_at")
  private Instant appliedAt;

  protected PendingSaleReversal() {
    // for JPA
  }

  public PendingSaleReversal(UUID saleId, UUID reversalEventId, Instant reversalOccurredAt) {
    this.id = UUID.randomUUID();
    this.saleId = Objects.requireNonNull(saleId, "saleId");
    this.reversalEventId = Objects.requireNonNull(reversalEventId, "reversalEventId");
    this.reversalOccurredAt = Objects.requireNonNull(reversalOccurredAt, "reversalOccurredAt");
  }

  public UUID getId() {
    return id;
  }

  public UUID getSaleId() {
    return saleId;
  }

  public UUID getReversalEventId() {
    return reversalEventId;
  }

  public Instant getReversalOccurredAt() {
    return reversalOccurredAt;
  }

  public Instant getAppliedAt() {
    return appliedAt;
  }

  public boolean isApplied() {
    return appliedAt != null;
  }

  public void markApplied(Instant at) {
    this.appliedAt = Objects.requireNonNull(at, "at");
  }
}
