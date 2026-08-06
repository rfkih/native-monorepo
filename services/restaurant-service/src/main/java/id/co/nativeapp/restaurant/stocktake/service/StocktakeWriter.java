package id.co.nativeapp.restaurant.stocktake.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.menu.domain.MenuItem;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.stocktake.domain.Stocktake;
import id.co.nativeapp.restaurant.stocktake.domain.StocktakeCurrencyMismatchException;
import id.co.nativeapp.restaurant.stocktake.domain.StocktakeLine;
import id.co.nativeapp.restaurant.stocktake.domain.StocktakeNotFoundException;
import id.co.nativeapp.restaurant.stocktake.dto.StocktakeLineInput;
import id.co.nativeapp.restaurant.stocktake.dto.StocktakeLineResponse;
import id.co.nativeapp.restaurant.stocktake.dto.StocktakeResponse;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeRequest;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeResult;
import id.co.nativeapp.restaurant.stocktake.messaging.StocktakeCompletedSchema;
import id.co.nativeapp.restaurant.stocktake.projection.StocktakeLineView;
import id.co.nativeapp.restaurant.stocktake.projection.StocktakeView;
import id.co.nativeapp.restaurant.stocktake.repository.StocktakeLineRepository;
import id.co.nativeapp.restaurant.stocktake.repository.StocktakeRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for inventory stocktakes (ADR 0038 phase 3, daily
 * close v2). A distinct bean from {@link StocktakeService} so the Spring proxy applies the
 * transaction + RLS-GUC advice ({@link RlsAutoApplyAspect}) — the {@code SaleWriter} pattern.
 *
 * <p><strong>Idempotency (the SaleWriter pattern).</strong> {@link #submit} probes {@code
 * (company_id, idempotency_key)} first — a hit returns the existing stocktake with no
 * re-adjustment, no second event. A different payload replayed under the same key is out of scope
 * (best-effort, matching {@code SaleWriter.create}). Under concurrency, two callers may both miss
 * the probe and race the INSERT; the {@code uq_stocktake_company_idempotency} unique constraint is
 * the backstop, and the loser is recovered by {@link StocktakeService} via {@link
 * #findExistingByKey} in a fresh transaction (a poisoned PostgreSQL transaction cannot simply
 * re-query itself).
 *
 * <p><strong>Per line.</strong> Each counted item must belong to {@code businessId} and be TRACKED
 * ({@code stockQuantity != null}) — otherwise a 400. {@code systemQty} is the item's current stock;
 * {@code varianceQty = countedQty − systemQty}; stock is adjusted to the physical count ({@code
 * menuItem.setStock(countedQty)}). {@code varianceValueMinor} is valued only when the item carries
 * a unit cost; the aggregate's {@code shrinkageMinor} is the SIGNED net loss ({@code −Σ
 * varianceValueMinor} over cost-bearing lines), overflow-safe (Math.*Exact). A stocktake does NOT
 * touch the cash window — no {@link id.co.nativeapp.restaurant.register.service.CashWindowLock}.
 */
@Component
public class StocktakeWriter {

  private final StocktakeRepository repository;
  private final StocktakeLineRepository lineRepository;
  private final MenuItemRepository menuItemRepository;
  private final OutboxWriter outboxWriter;
  private final OutletAccessGuard outletAccessGuard;

  public StocktakeWriter(
      StocktakeRepository repository,
      StocktakeLineRepository lineRepository,
      MenuItemRepository menuItemRepository,
      OutboxWriter outboxWriter,
      OutletAccessGuard outletAccessGuard) {
    this.repository = repository;
    this.lineRepository = lineRepository;
    this.menuItemRepository = menuItemRepository;
    this.outboxWriter = outboxWriter;
    this.outletAccessGuard = outletAccessGuard;
  }

  /**
   * Submits a stocktake: adjusts every counted item's stock to the physical count, persists the
   * stocktake + its lines, and writes the {@code StocktakeCompleted} outbox row in the SAME
   * transaction (rule 3).
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer already
   *     inserted the {@code (company_id, idempotency_key)} row
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public SubmitStocktakeResult submit(SubmitStocktakeRequest request, String idempotencyKey) {
    String companyId = TenantContext.require().companyId();

    // Idempotency fast path (the SaleWriter pattern): a prior stocktake under this tenant + key
    // short-circuits, no re-adjustment, no second event. The outlet is enforced on the replayed
    // stocktake's own business too — a valid key must not disclose another outlet's line data
    // (item names, quantities, unit costs) to a caller not assigned to that outlet, matching
    // findById. RLS already confines it to the tenant; this is the within-tenant guard.
    Optional<StocktakeView> replay = repository.findViewByIdempotencyKey(idempotencyKey);
    if (replay.isPresent()) {
      outletAccessGuard.enforce(replay.get().getBusinessId());
      return new SubmitStocktakeResult(toResponse(replay.get()), false);
    }

    outletAccessGuard.enforce(request.businessId());

    Set<UUID> seenMenuItemIds = new LinkedHashSet<>();
    for (StocktakeLineInput line : request.lines()) {
      if (!seenMenuItemIds.add(line.menuItemId())) {
        throw new IllegalArgumentException(
            "duplicate menu item in stocktake request: " + line.menuItemId());
      }
    }

    String currency = null;
    long varianceValueSum = 0L;
    List<ComputedLine> computed = new ArrayList<>(request.lines().size());

    for (StocktakeLineInput line : request.lines()) {
      MenuItem item =
          menuItemRepository
              .findById(line.menuItemId())
              .orElseThrow(
                  () -> new IllegalArgumentException("menu item not found: " + line.menuItemId()));
      if (!item.getBusinessId().equals(request.businessId())) {
        throw new IllegalArgumentException(
            "menu item " + item.getId() + " is not in outlet " + request.businessId());
      }
      if (!item.isTracked()) {
        throw new IllegalArgumentException("menu item " + item.getId() + " is not tracked");
      }

      String itemCurrency = item.getPrice().currency().getCurrencyCode();
      if (currency == null) {
        currency = itemCurrency;
      } else if (!currency.equals(itemCurrency)) {
        throw new StocktakeCurrencyMismatchException(currency, itemCurrency);
      }

      int systemQty = item.getStockQuantity();
      int countedQty = line.countedQty();
      int varianceQty = countedQty - systemQty;
      Long unitCostMinor = item.getUnitCostMinor();
      long varianceValueMinor =
          unitCostMinor == null ? 0L : Math.multiplyExact((long) varianceQty, unitCostMinor);
      varianceValueSum = Math.addExact(varianceValueSum, varianceValueMinor);

      item.setStock(countedQty);
      menuItemRepository.save(item);

      computed.add(
          new ComputedLine(
              item.getId(),
              item.getName(),
              systemQty,
              countedQty,
              varianceQty,
              unitCostMinor,
              varianceValueMinor));
    }

    // SIGNED net loss = -Σ varianceValueMinor over cost-bearing lines (non-cost lines contribute
    // 0, so summing every line is equivalent).
    long shrinkageMinor = Math.negateExact(varianceValueSum);
    Instant countedAt = Instant.now();

    Stocktake stocktake =
        Stocktake.of(request.businessId(), countedAt, currency, shrinkageMinor, idempotencyKey);
    // Tenant column set explicitly, as every write path does — the FORCE-RLS WITH CHECK requires
    // it (RegisterSessionTender precedent).
    stocktake.setCompanyId(companyId);
    Stocktake savedStocktake = repository.saveAndFlush(stocktake);

    List<StocktakeLineResponse> lineResponses = new ArrayList<>(computed.size());
    for (ComputedLine c : computed) {
      StocktakeLine persistedLine =
          StocktakeLine.of(
              savedStocktake.getId(),
              c.menuItemId(),
              c.systemQty(),
              c.countedQty(),
              c.varianceQty(),
              c.unitCostMinor(),
              c.varianceValueMinor());
      persistedLine.setCompanyId(companyId);
      lineRepository.save(persistedLine);
      lineResponses.add(
          new StocktakeLineResponse(
              c.menuItemId(),
              c.name(),
              c.systemQty(),
              c.countedQty(),
              c.varianceQty(),
              c.unitCostMinor(),
              c.varianceValueMinor()));
    }

    GenericRecord event =
        StocktakeCompletedSchema.toRecord(
            savedStocktake.getId(),
            companyId,
            request.businessId(),
            countedAt,
            shrinkageMinor,
            currency);
    outboxWriter.write(
        StocktakeCompletedSchema.AGGREGATE_TYPE,
        savedStocktake.getId().toString(),
        StocktakeCompletedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        UUID.fromString(companyId),
        countedAt);

    StocktakeResponse response =
        new StocktakeResponse(
            savedStocktake.getId(),
            savedStocktake.getBusinessId(),
            currency,
            countedAt,
            shrinkageMinor,
            lineResponses);
    return new SubmitStocktakeResult(response, true);
  }

  /**
   * Re-reads a stocktake by idempotency key in a FRESH transaction, used to recover the loser of a
   * concurrent insert race after its own submit transaction aborted (the SaleWriter pattern).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<StocktakeResponse> findExistingByKey(String idempotencyKey) {
    return repository.findViewByIdempotencyKey(idempotencyKey).map(this::toResponse);
  }

  /** A stocktake by id, outlet-gated. */
  @Transactional(readOnly = true)
  public StocktakeResponse findById(UUID id) {
    StocktakeView view =
        repository.findViewById(id).orElseThrow(() -> new StocktakeNotFoundException(id));
    outletAccessGuard.enforce(view.getBusinessId());
    return toResponse(view);
  }

  /** The outlet's stocktake history (most recent first, capped at 50). */
  @Transactional(readOnly = true)
  public List<StocktakeResponse> findHistory(UUID businessId) {
    outletAccessGuard.enforce(businessId);
    List<StocktakeView> headers = repository.findHistoryViewsByBusinessId(businessId);
    if (headers.isEmpty()) {
      return List.of();
    }
    List<UUID> stocktakeIds = headers.stream().map(StocktakeView::getId).toList();
    List<StocktakeLineView> allLines = lineRepository.findViewsByStocktakeIds(stocktakeIds);
    Map<UUID, List<StocktakeLineView>> linesByStocktakeId = new HashMap<>();
    for (StocktakeLineView line : allLines) {
      linesByStocktakeId.computeIfAbsent(line.getStocktakeId(), k -> new ArrayList<>()).add(line);
    }
    return headers.stream()
        .map(h -> toResponse(h, linesByStocktakeId.getOrDefault(h.getId(), List.of())))
        .toList();
  }

  /** Loads a header's lines and maps to the response shape. */
  private StocktakeResponse toResponse(StocktakeView view) {
    return toResponse(view, lineRepository.findViewsByStocktakeId(view.getId()));
  }

  /**
   * Maps the native read projections to the response shape (CHAR(3) currency stripped). Lives in
   * the SERVICE layer — the dto boundary must not reach into projections (ArchUnit).
   */
  private static StocktakeResponse toResponse(StocktakeView view, List<StocktakeLineView> lines) {
    List<StocktakeLineResponse> lineResponses =
        lines.stream()
            .map(
                l ->
                    new StocktakeLineResponse(
                        l.getMenuItemId(),
                        l.getName(),
                        l.getSystemQty(),
                        l.getCountedQty(),
                        l.getVarianceQty(),
                        l.getUnitCostMinor(),
                        l.getVarianceValueMinor()))
            .toList();
    return new StocktakeResponse(
        view.getId(),
        view.getBusinessId(),
        view.getCurrency() == null ? null : view.getCurrency().strip(),
        view.getCountedAt(),
        view.getShrinkageMinor(),
        lineResponses);
  }

  /** Intermediate per-line computation, kept in memory until the parent's shrinkage is known. */
  private record ComputedLine(
      UUID menuItemId,
      String name,
      int systemQty,
      int countedQty,
      int varianceQty,
      Long unitCostMinor,
      long varianceValueMinor) {}
}
