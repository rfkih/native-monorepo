package id.co.nativeapp.finance.assets.service;

import id.co.nativeapp.finance.assets.domain.AmortizationRunLine;
import id.co.nativeapp.finance.assets.domain.AssetNotFoundException;
import id.co.nativeapp.finance.assets.domain.FixedAsset;
import id.co.nativeapp.finance.assets.dto.AssetDetailResponse;
import id.co.nativeapp.finance.assets.dto.AssetDetailResponse.ScheduleLine;
import id.co.nativeapp.finance.assets.dto.AssetResponse;
import id.co.nativeapp.finance.assets.projection.AssetView;
import id.co.nativeapp.finance.assets.repository.AmortizationRunLineRepository;
import id.co.nativeapp.finance.assets.repository.FixedAssetRepository;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read-side service for the asset register (Phase 6): the register list (each asset with its
 * accumulated depreciation + book value from the run-line sub-ledger — no per-asset GL query) and
 * the asset detail with its posted schedule history. Native + projection reads, RLS-scoped.
 */
@Service
public class AssetReader {

  private final FixedAssetRepository assetRepository;
  private final AmortizationRunLineRepository runLineRepository;

  public AssetReader(
      FixedAssetRepository assetRepository, AmortizationRunLineRepository runLineRepository) {
    this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository");
    this.runLineRepository = Objects.requireNonNull(runLineRepository, "runLineRepository");
  }

  /** The asset register for the bound tenant, most recently acquired first. */
  @Transactional(readOnly = true)
  public List<AssetResponse> register() {
    return assetRepository.findRegister().stream().map(AssetReader::toResponse).toList();
  }

  /** One asset + its posted schedule, or {@link AssetNotFoundException} (→ 404). */
  @Transactional(readOnly = true)
  public AssetDetailResponse detail(UUID id) {
    AssetView view =
        assetRepository.findRegisterRow(id).orElseThrow(() -> new AssetNotFoundException(id));
    List<ScheduleLine> schedule =
        runLineRepository.findScheduleLines(AmortizationRunLine.ItemType.ASSET.name(), id).stream()
            .map(
                l ->
                    new ScheduleLine(
                        l.getPeriod(),
                        l.getMonthIndex(),
                        l.getAmountMinor(),
                        l.getCurrency().strip()))
            .toList();
    return new AssetDetailResponse(toResponse(view), schedule);
  }

  private static AssetResponse toResponse(AssetView v) {
    // A DISPOSED asset is off the books: its register book value is zero (the pre-disposal value
    // is derivable from cost − accumulated; proceeds/date columns tell the disposal story).
    long bookValue =
        FixedAsset.Status.DISPOSED.name().equals(v.getStatus())
            ? 0L
            : Math.subtractExact(v.getCostMinor(), v.getAccumulatedMinor());
    return new AssetResponse(
        v.getId(),
        v.getName(),
        v.getAcquisitionDate(),
        v.getStartPeriod(),
        v.getCostMinor(),
        v.getSalvageMinor(),
        v.getUsefulLifeMonths(),
        v.getCurrency().strip(),
        v.getStatus(),
        v.getDisposalDate(),
        v.getProceedsMinor(),
        v.getAccumulatedMinor(),
        bookValue);
  }
}
