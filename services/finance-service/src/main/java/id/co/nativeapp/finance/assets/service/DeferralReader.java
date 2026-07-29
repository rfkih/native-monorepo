package id.co.nativeapp.finance.assets.service;

import id.co.nativeapp.finance.assets.dto.DeferralResponse;
import id.co.nativeapp.finance.assets.repository.DeferralRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-side service for deferrals (Phase 6): the list with each deferral's amortized-to-date and
 * remaining balance (from the run-line sub-ledger). Native + projection, RLS-scoped.
 */
@Service
public class DeferralReader {

  private final DeferralRepository deferralRepository;

  public DeferralReader(DeferralRepository deferralRepository) {
    this.deferralRepository = Objects.requireNonNull(deferralRepository, "deferralRepository");
  }

  /** The deferral list for the bound tenant, most recent start first. */
  @Transactional(readOnly = true)
  public List<DeferralResponse> list() {
    return deferralRepository.findAllViews().stream()
        .map(
            v ->
                new DeferralResponse(
                    v.getId(),
                    v.getKind(),
                    v.getDescription(),
                    v.getTotalMinor(),
                    v.getMonths(),
                    v.getStartPeriod(),
                    v.getCurrency().strip(),
                    v.getAmortizedMinor(),
                    Math.subtractExact(v.getTotalMinor(), v.getAmortizedMinor())))
        .toList();
  }
}
