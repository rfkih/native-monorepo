package id.co.nativeapp.finance.assets.domain;

import id.co.nativeapp.tenant.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The {@code amortization_run} seal (Phase 6, ADR 0020) — the record that a company POSTED its
 * depreciation/amortization for a period. The asset analogue of {@code TaxFiling} / {@code
 * WithinCompanyClose}: {@code UNIQUE (company_id, period)} guards it — a re-run finds the row and
 * no-ops, posting nothing. Its {@link AmortizationRunLine}s carry the per-item detail.
 *
 * <p>Extends {@link Auditable}; covered by the {@code amortization_run} RLS policy (V34).
 */
@Entity
@Table(name = "amortization_run")
public class AmortizationRun extends Auditable {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "period", nullable = false, updatable = false, length = 7)
  private String period;

  @Column(name = "line_count", nullable = false, updatable = false)
  private int lineCount;

  @Column(name = "total_minor", nullable = false, updatable = false)
  private long totalMinor;

  @Column(name = "run_at", nullable = false, updatable = false)
  private Instant runAt;

  protected AmortizationRun() {
    // for JPA
  }

  /** Creates the sealed run record (the writer has already posted every line's entry). */
  public static AmortizationRun of(
      UUID id, String period, int lineCount, long totalMinor, Instant runAt) {
    AmortizationRun run = new AmortizationRun();
    run.id = Objects.requireNonNull(id, "id");
    run.period = Objects.requireNonNull(period, "period");
    run.lineCount = lineCount;
    run.totalMinor = totalMinor;
    run.runAt = Objects.requireNonNull(runAt, "runAt");
    return run;
  }

  public UUID getId() {
    return id;
  }

  public String getPeriod() {
    return period;
  }

  public int getLineCount() {
    return lineCount;
  }

  public long getTotalMinor() {
    return totalMinor;
  }

  public Instant getRunAt() {
    return runAt;
  }
}
