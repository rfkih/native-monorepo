package id.co.nativeapp.finance.outlet.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The {@code outlet_revenue} read model — one incrementally-accumulated row per {@code (company_id,
 * business_id, period, currency)} holding the period's net revenue for a single outlet.
 *
 * <p>As each {@link id.co.nativeapp.finance.revenue.domain.LedgerPosting} lands, its net-revenue
 * amount (subtotal − discount) is added onto the matching accumulator via the atomic upsert in
 * {@link id.co.nativeapp.finance.revenue.service.RevenuePostingWriter} — in the same transaction
 * that updates {@code consolidated_revenue}. Void and refund reversals unwind it symmetrically in
 * {@link id.co.nativeapp.finance.reversal.service.ReversalPostingWriter}.
 *
 * <p>This entity exists solely to back the Spring Data read query on {@link
 * id.co.nativeapp.finance.outlet.repository.OutletRevenueRepository}. The write path is an atomic
 * {@code INSERT … ON CONFLICT … DO UPDATE} executed directly via {@code JdbcTemplate}; there are no
 * JPA setters or mutators here (never use this entity on the write path).
 *
 * <p>Extends {@link Auditable} and is under FORCE RLS (see V21 migration): a tenant only ever sees
 * its own outlet revenue. {@code revenue_minor} is a {@code BIGINT} minor unit (rule 8; never a
 * float) and may go negative during a period where refunds arrive before the matching sales.
 */
@Entity
@Table(name = "outlet_revenue")
public class OutletRevenue extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "business_id", nullable = false, updatable = false)
  private UUID businessId;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @Column(name = "revenue_minor", nullable = false)
  private long revenueMinor;

  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  protected OutletRevenue() {
    // for JPA
  }

  public UUID getId() {
    return id;
  }

  public UUID getBusinessId() {
    return businessId;
  }

  public String getPeriod() {
    return period;
  }

  public long getRevenueMinor() {
    return revenueMinor;
  }

  public String getCurrency() {
    return currency;
  }
}
