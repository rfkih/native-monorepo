package id.co.nativeapp.restaurant.inventory.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.domain.IngredientStocktake;
import id.co.nativeapp.restaurant.inventory.domain.IngredientStocktakeLine;
import id.co.nativeapp.restaurant.inventory.domain.IngredientStocktakeNotFoundException;
import id.co.nativeapp.restaurant.inventory.domain.StockLedgerDay;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeLineInput;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeLineResponse;
import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeResponse;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeRequest;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeResult;
import id.co.nativeapp.restaurant.inventory.projection.IngredientStocktakeLineView;
import id.co.nativeapp.restaurant.inventory.projection.IngredientStocktakeView;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.restaurant.inventory.repository.IngredientStockDayRepository;
import id.co.nativeapp.restaurant.inventory.repository.IngredientStocktakeLineRepository;
import id.co.nativeapp.restaurant.inventory.repository.IngredientStocktakeRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.stocktake.domain.StocktakeCurrencyMismatchException;
import id.co.nativeapp.restaurant.stocktake.messaging.StocktakeCompletedSchema;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} units of work for ingredient stock opnames (ADR 0046 phase 1). A
 * clone of {@code id.co.nativeapp.restaurant.stocktake.service.StocktakeWriter} (ADR 0038 phase 3),
 * keyed by {@link Ingredient} instead of a menu item. A distinct bean from {@link
 * IngredientStocktakeService} so the Spring proxy applies the transaction + RLS-GUC advice ({@link
 * RlsAutoApplyAspect}).
 *
 * <p><strong>Idempotency (the StocktakeWriter/SaleWriter pattern).</strong> {@link #submit} probes
 * {@code (company_id, idempotency_key)} first — a hit returns the existing count with no
 * re-adjustment, no second event. Under concurrency, two callers may both miss the probe and race
 * the INSERT; the {@code uq_ingredient_stocktake_company_idempotency} unique constraint is the
 * backstop, and the loser is recovered by {@link IngredientStocktakeService} via {@link
 * #findExistingByKey} in a fresh transaction.
 *
 * <p><strong>Per line.</strong> Each counted ingredient must belong to {@code businessId} and be
 * ACTIVE — otherwise a 400. {@code systemQty} is the ingredient's current stock; {@code varianceQty
 * = countedQty − systemQty}; stock is adjusted to the physical count. {@code varianceValueMinor} is
 * valued only when the ingredient carries a unit cost; the aggregate's {@code shrinkageMinor} is
 * the SIGNED net loss over cost-bearing lines (overflow-safe, {@code Math.*Exact}).
 *
 * <p><strong>No-cost submissions emit no event (ADR 0046).</strong> Unlike the legacy stocktake
 * (every menu item always carries a price/currency), an ingredient's cost is independently
 * optional. {@code currency} tracks only COSTED lines: when zero lines carry a cost, the parent's
 * {@code currency} stays {@code null} and the outbox write is skipped entirely (nothing to post,
 * and the schema requires a currency). When &ge; 1 costed line exists, the event is emitted even at
 * net zero (legacy parity) — reusing {@code StocktakeCompletedSchema.toRecord} verbatim, same
 * aggregate type {@code "stocktake"}, partition key = this stocktake's id.
 */
@Component
public class IngredientStocktakeWriter {

  private final IngredientStocktakeRepository repository;
  private final IngredientStocktakeLineRepository lineRepository;
  private final IngredientStockDayRepository stockDayRepository;
  private final IngredientRepository ingredientRepository;
  private final OutboxWriter outboxWriter;
  private final OutletAccessGuard outletAccessGuard;

  public IngredientStocktakeWriter(
      IngredientStocktakeRepository repository,
      IngredientStocktakeLineRepository lineRepository,
      IngredientStockDayRepository stockDayRepository,
      IngredientRepository ingredientRepository,
      OutboxWriter outboxWriter,
      OutletAccessGuard outletAccessGuard) {
    this.repository = repository;
    this.lineRepository = lineRepository;
    this.stockDayRepository = stockDayRepository;
    this.ingredientRepository = ingredientRepository;
    this.outboxWriter = outboxWriter;
    this.outletAccessGuard = outletAccessGuard;
  }

  /**
   * Submits an ingredient stocktake: adjusts every counted ingredient's stock to the physical
   * count, persists the stocktake + its lines, and — only when at least one line carried a cost —
   * writes the {@code StocktakeCompleted} outbox row in the SAME transaction (rule 3).
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer already
   *     inserted the {@code (company_id, idempotency_key)} row
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public SubmitIngredientStocktakeResult submit(
      SubmitIngredientStocktakeRequest request, String idempotencyKey) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();

    // Idempotency fast path (the StocktakeWriter pattern): a prior count under this tenant + key
    // short-circuits, no re-adjustment, no second event. The outlet is enforced on the replayed
    // count's own business too — a valid key must not disclose another outlet's line data to a
    // caller not assigned to that outlet.
    Optional<IngredientStocktakeView> replay = repository.findViewByIdempotencyKey(idempotencyKey);
    if (replay.isPresent()) {
      outletAccessGuard.enforce(replay.get().getBusinessId());
      return new SubmitIngredientStocktakeResult(toResponse(replay.get()), false);
    }

    outletAccessGuard.enforce(request.businessId());

    Set<UUID> seenIngredientIds = new LinkedHashSet<>();
    for (IngredientStocktakeLineInput line : request.lines()) {
      if (!seenIngredientIds.add(line.ingredientId())) {
        throw new IllegalArgumentException(
            "duplicate ingredient in stocktake request: " + line.ingredientId());
      }
    }

    // currency tracks only COSTED lines — null until the first costed line is seen (ADR 0046).
    String currency = null;
    long varianceValueSum = 0L;
    List<ComputedLine> computed = new ArrayList<>(request.lines().size());
    // The daily ledger's correction bucket, keyed ascending by ingredient UUID — drained after
    // the loop, NOT inside it, so the UPSERTs follow the same ascending-UUID order the per-sale
    // depletion uses and the two paths cannot deadlock against each other on this table.
    TreeMap<UUID, Long> correctionByIngredient = new TreeMap<>();

    for (IngredientStocktakeLineInput line : request.lines()) {
      Ingredient ingredient =
          ingredientRepository
              .findById(line.ingredientId())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException("ingredient not found: " + line.ingredientId()));
      if (!ingredient.getBusinessId().equals(request.businessId())) {
        throw new IllegalArgumentException(
            "ingredient " + ingredient.getId() + " is not in outlet " + request.businessId());
      }
      if (!ingredient.isActive()) {
        throw new IllegalArgumentException("ingredient " + ingredient.getId() + " is not active");
      }

      int systemQty = ingredient.getStockQty();
      int countedQty = line.countedQty();
      int varianceQty = countedQty - systemQty;
      Long unitCostMinor = ingredient.getUnitCostMinor();
      long varianceValueMinor;
      if (unitCostMinor != null) {
        String ingredientCurrency = ingredient.getCostCurrency();
        if (currency == null) {
          currency = ingredientCurrency;
        } else if (!currency.equals(ingredientCurrency)) {
          throw new StocktakeCurrencyMismatchException(currency, ingredientCurrency);
        }
        varianceValueMinor = Math.multiplyExact((long) varianceQty, unitCostMinor);
      } else {
        varianceValueMinor = 0L;
      }
      varianceValueSum = Math.addExact(varianceValueSum, varianceValueMinor);

      ingredient.setStock(countedQty);
      ingredientRepository.save(ingredient);
      correctionByIngredient.put(ingredient.getId(), (long) varianceQty);

      computed.add(
          new ComputedLine(
              ingredient.getId(),
              ingredient.getName(),
              ingredient.getUnit(),
              systemQty,
              countedQty,
              varianceQty,
              unitCostMinor,
              varianceValueMinor));
    }

    // Book every counted line to the daily ledger (V47) as ONE manual correction. Zero-variance
    // lines are booked too: a recount that confirmed the figure still happened, and "berapa kali
    // stok dikoreksi manual" should count it. The first call flushes the pending setStock
    // updates, so each row's closing_qty is sourced from the already-corrected stock figure.
    LocalDate ledgerDay = StockLedgerDay.today();
    for (var correction : correctionByIngredient.entrySet()) {
      stockDayRepository.addAdjustment(
          correction.getKey(), ledgerDay, correction.getValue(), tenant.actor(), companyId);
    }

    // SIGNED net loss = -Σ varianceValueMinor over cost-bearing lines (non-cost lines contribute 0,
    // so summing every line is equivalent). Zero when currency is still null (nothing costed).
    long shrinkageMinor = Math.negateExact(varianceValueSum);
    Instant countedAt = Instant.now();

    IngredientStocktake stocktake =
        IngredientStocktake.of(
            request.businessId(), countedAt, currency, shrinkageMinor, idempotencyKey);
    // Tenant column set explicitly, as every write path does — the FORCE-RLS WITH CHECK requires
    // it.
    stocktake.setCompanyId(companyId);
    IngredientStocktake savedStocktake = repository.saveAndFlush(stocktake);

    List<IngredientStocktakeLineResponse> lineResponses = new ArrayList<>(computed.size());
    for (ComputedLine c : computed) {
      IngredientStocktakeLine persistedLine =
          IngredientStocktakeLine.of(
              savedStocktake.getId(),
              c.ingredientId(),
              c.systemQty(),
              c.countedQty(),
              c.varianceQty(),
              c.unitCostMinor(),
              c.varianceValueMinor());
      persistedLine.setCompanyId(companyId);
      lineRepository.save(persistedLine);
      lineResponses.add(
          new IngredientStocktakeLineResponse(
              c.ingredientId(),
              c.name(),
              c.unit(),
              c.systemQty(),
              c.countedQty(),
              c.varianceQty(),
              c.unitCostMinor(),
              c.varianceValueMinor()));
    }

    // No-cost submissions emit no event (ADR 0046) — zero costed lines means currency is still
    // null,
    // there is nothing valued to post, and the schema requires a currency.
    if (currency != null) {
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
    }

    IngredientStocktakeResponse response =
        new IngredientStocktakeResponse(
            savedStocktake.getId(),
            savedStocktake.getBusinessId(),
            currency,
            countedAt,
            shrinkageMinor,
            lineResponses);
    return new SubmitIngredientStocktakeResult(response, true);
  }

  /**
   * Re-reads an ingredient stocktake by idempotency key in a FRESH transaction, used to recover the
   * loser of a concurrent insert race after its own submit transaction aborted (the StocktakeWriter
   * pattern).
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<IngredientStocktakeResponse> findExistingByKey(String idempotencyKey) {
    return repository.findViewByIdempotencyKey(idempotencyKey).map(this::toResponse);
  }

  /** An ingredient stocktake by id, outlet-gated. */
  @Transactional(readOnly = true)
  public IngredientStocktakeResponse findById(UUID id) {
    IngredientStocktakeView view =
        repository.findViewById(id).orElseThrow(() -> new IngredientStocktakeNotFoundException(id));
    outletAccessGuard.enforce(view.getBusinessId());
    return toResponse(view);
  }

  /** The outlet's ingredient stocktake history (most recent first, capped at 50). */
  @Transactional(readOnly = true)
  public List<IngredientStocktakeResponse> findHistory(UUID businessId) {
    outletAccessGuard.enforce(businessId);
    List<IngredientStocktakeView> headers = repository.findHistoryViewsByBusinessId(businessId);
    if (headers.isEmpty()) {
      return List.of();
    }
    List<UUID> stocktakeIds = headers.stream().map(IngredientStocktakeView::getId).toList();
    List<IngredientStocktakeLineView> allLines =
        lineRepository.findViewsByStocktakeIds(stocktakeIds);
    Map<UUID, List<IngredientStocktakeLineView>> linesByStocktakeId = new HashMap<>();
    for (IngredientStocktakeLineView line : allLines) {
      linesByStocktakeId
          .computeIfAbsent(line.getIngredientStocktakeId(), k -> new ArrayList<>())
          .add(line);
    }
    return headers.stream()
        .map(h -> toResponse(h, linesByStocktakeId.getOrDefault(h.getId(), List.of())))
        .toList();
  }

  /** Loads a header's lines and maps to the response shape. */
  private IngredientStocktakeResponse toResponse(IngredientStocktakeView view) {
    return toResponse(view, lineRepository.findViewsByStocktakeId(view.getId()));
  }

  /**
   * Maps the native read projections to the response shape (CHAR(3) currency stripped). Lives in
   * the SERVICE layer — the dto boundary must not reach into projections (ArchUnit).
   */
  private static IngredientStocktakeResponse toResponse(
      IngredientStocktakeView view, List<IngredientStocktakeLineView> lines) {
    List<IngredientStocktakeLineResponse> lineResponses =
        lines.stream()
            .map(
                l ->
                    new IngredientStocktakeLineResponse(
                        l.getIngredientId(),
                        l.getName(),
                        l.getUnit(),
                        l.getSystemQty(),
                        l.getCountedQty(),
                        l.getVarianceQty(),
                        l.getUnitCostMinor(),
                        l.getVarianceValueMinor()))
            .toList();
    return new IngredientStocktakeResponse(
        view.getId(),
        view.getBusinessId(),
        view.getCurrency() == null ? null : view.getCurrency().strip(),
        view.getCountedAt(),
        view.getShrinkageMinor(),
        lineResponses);
  }

  /** Intermediate per-line computation, kept in memory until the parent's shrinkage is known. */
  private record ComputedLine(
      UUID ingredientId,
      String name,
      String unit,
      int systemQty,
      int countedQty,
      int varianceQty,
      Long unitCostMinor,
      long varianceValueMinor) {}
}
