package id.co.nativeapp.finance.assets.service;

import id.co.nativeapp.finance.assets.dto.RunResponse;
import id.co.nativeapp.finance.assets.repository.AmortizationRunRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-side service for amortization runs (Phase 6): the run history (most-recent period first)
 * and one run by id. Native + projection, RLS-scoped.
 */
@Service
public class RunReader {

  private final AmortizationRunRepository runRepository;

  public RunReader(AmortizationRunRepository runRepository) {
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
  }

  /** The run history for the bound tenant. */
  @Transactional(readOnly = true)
  public List<RunResponse> history() {
    return runRepository.findAllViews().stream()
        .map(v -> new RunResponse(v.getId(), v.getPeriod(), v.getLineCount(), v.getTotalMinor(), v.getRunAt()))
        .toList();
  }

  /** One run by id (used by the controller after a run to return the fresh resource). */
  @Transactional(readOnly = true)
  public RunResponse detail(UUID id) {
    return runRepository
        .findViewById(id)
        .map(v -> new RunResponse(v.getId(), v.getPeriod(), v.getLineCount(), v.getTotalMinor(), v.getRunAt()))
        .orElseThrow(() -> new IllegalStateException("amortization run vanished: " + id));
  }
}
