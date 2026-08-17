package id.co.nativeapp.restaurant.inventory.service;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.OutboxWriter;
import id.co.nativeapp.restaurant.inventory.domain.GoodsReceipt;
import id.co.nativeapp.restaurant.inventory.domain.Ingredient;
import id.co.nativeapp.restaurant.inventory.domain.IngredientNotFoundException;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.dto.UpdateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.messaging.StockReceivedSchema;
import id.co.nativeapp.restaurant.inventory.repository.GoodsReceiptRepository;
import id.co.nativeapp.restaurant.inventory.repository.IngredientRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write units of work for the ingredient catalog (ADR 0046 phase
 * 1).
 *
 * <p>A distinct bean from {@link IngredientService} so each transactional method is invoked through
 * the Spring proxy — self-invocation would bypass the {@code @Transactional} advice and the {@link
 * RlsAutoApplyAspect} that sets the tenant GUC (the {@code MenuWriter}/{@code StocktakeWriter}
 * pattern). {@link OutletAccessGuard#enforce} must run inside that same transaction (its repository
 * reads rely on the GUC already being set), so it is called here — after loading the aggregate for
 * update/deactivate/stock paths, since {@code businessId} is not known until then.
 *
 * <p><strong>ADR 0067 Phase B.</strong> A PRICED {@link #addStock} call (the {@code
 * Ingredient#receive} branch) additionally writes a {@link GoodsReceipt} row and the {@code
 * StockReceived} outbox event, in the SAME {@code REQUIRES_NEW} transaction as the moving-average
 * receive (rule 3) — restaurant emits the fact UNCONDITIONALLY; finance decides the accounting
 * treatment. A costless {@code addStock}/{@code setStock} call emits nothing (no landed value to
 * report).
 */
@Component
public class IngredientWriter {

  private final IngredientRepository repository;
  private final GoodsReceiptRepository goodsReceiptRepository;
  private final OutboxWriter outboxWriter;
  private final OutletAccessGuard outletAccessGuard;
  private final List<IngredientDeactivationGuard> deactivationGuards;

  public IngredientWriter(
      IngredientRepository repository,
      GoodsReceiptRepository goodsReceiptRepository,
      OutboxWriter outboxWriter,
      OutletAccessGuard outletAccessGuard,
      List<IngredientDeactivationGuard> deactivationGuards) {
    this.repository = repository;
    this.goodsReceiptRepository = goodsReceiptRepository;
    this.outboxWriter = outboxWriter;
    this.outletAccessGuard = outletAccessGuard;
    this.deactivationGuards = deactivationGuards;
  }

  /**
   * Creates and persists a new active {@link Ingredient} in its own transaction. The {@code
   * company_id} is stamped from the bound tenant scope, never from the request body (rule 5) —
   * required explicitly because the FORCE-RLS {@code WITH CHECK} policy rejects an insert whose
   * {@code company_id} does not match the session tenant.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IngredientResponse create(CreateIngredientRequest request) {
    String companyId = TenantContext.require().companyId();
    outletAccessGuard.enforce(request.businessId());

    Ingredient ingredient =
        new Ingredient(
            request.businessId(),
            request.name(),
            request.unit(),
            request.unitCostMinor(),
            request.costCurrency());
    ingredient.setDisplayUnit(request.displayUnit());
    if (request.initialStockQty() != null && request.initialStockQty() > 0) {
      ingredient.setStock(request.initialStockQty());
    }
    ingredient.setCompanyId(companyId);
    Ingredient saved = repository.saveAndFlush(ingredient);
    return IngredientResponse.from(saved);
  }

  /**
   * Applies a partial update ({@code PATCH}) to an existing ingredient. RLS restricts the load to
   * the current tenant — an ingredient from another company is invisible and triggers the same 404
   * as a genuinely missing one.
   *
   * @throws IngredientNotFoundException if not found or not visible to the current tenant
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IngredientResponse update(UUID id, UpdateIngredientRequest request) {
    TenantContext.require();
    Ingredient ingredient = load(id);
    outletAccessGuard.enforce(ingredient.getBusinessId());
    ingredient.update(
        request.name(),
        request.unit(),
        request.displayUnit(),
        request.unitCostMinor(),
        request.costCurrency());
    Ingredient saved = repository.saveAndFlush(ingredient);
    return IngredientResponse.from(saved);
  }

  /**
   * Soft-deactivates an ingredient by calling {@link Ingredient#deactivate()}, which sets {@code
   * active = false}. The row disappears from {@code GET /api/v1/ingredients} (which filters to
   * active rows) but historical ingredient-stocktake lines that reference it are unaffected.
   *
   * <p>Registered {@link IngredientDeactivationGuard}s may veto first (ADR 0050: the recipe feature
   * blocks deactivating an ingredient a recipe still references — 409).
   *
   * @throws IngredientNotFoundException if not found or not visible to the current tenant
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deactivate(UUID id) {
    TenantContext.require();
    Ingredient ingredient = load(id);
    outletAccessGuard.enforce(ingredient.getBusinessId());
    for (IngredientDeactivationGuard guard : deactivationGuards) {
      guard.checkDeactivate(id);
    }
    ingredient.deactivate();
    repository.saveAndFlush(ingredient);
  }

  /**
   * Sets the absolute stock quantity for an ingredient (always tracked — no infinite/untracked
   * state, ADR 0046).
   *
   * @throws IngredientNotFoundException if not found or not visible to the current tenant
   * @throws IllegalArgumentException if {@code quantity} is negative
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IngredientResponse setStock(UUID id, int quantity) {
    TenantContext.require();
    Ingredient ingredient = load(id);
    outletAccessGuard.enforce(ingredient.getBusinessId());
    ingredient.setStock(quantity);
    Ingredient saved = repository.saveAndFlush(ingredient);
    return IngredientResponse.from(saved);
  }

  /**
   * Adds a signed delta to an ingredient's stock, flooring at 0. When a price ({@code
   * amountPaidMinor} + {@code costCurrency}) is supplied it is a PRICED positive receive that
   * updates the moving weighted-average cost (V36) AND records the {@code goods_receipt} fact +
   * {@code StockReceived} outbox event (ADR 0067 Phase B) in this SAME transaction; otherwise it is
   * a costless adjustment that preserves the unit cost and emits nothing. Price fields are
   * both-or-neither (400 otherwise), mirroring how the aggregate validates the cost pair; the
   * positive-amount rule is enforced by {@link Ingredient#receive}.
   *
   * @throws IngredientNotFoundException if not found or not visible to the current tenant
   * @throws IllegalArgumentException if exactly one price field is present (→ 400)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public IngredientResponse addStock(
      UUID id, int amount, @Nullable Long amountPaidMinor, @Nullable String costCurrency) {
    String companyId = TenantContext.require().companyId();
    Ingredient ingredient = load(id);
    outletAccessGuard.enforce(ingredient.getBusinessId());
    if (amountPaidMinor != null || costCurrency != null) {
      if (amountPaidMinor == null || costCurrency == null) {
        throw new IllegalArgumentException(
            "amountPaidMinor and costCurrency must both be present or both absent");
      }
      ingredient.receive(amount, amountPaidMinor, costCurrency);
      Ingredient saved = repository.saveAndFlush(ingredient);
      recordGoodsReceipt(companyId, saved, amount, amountPaidMinor, costCurrency);
      return IngredientResponse.from(saved);
    } else {
      ingredient.addStock(amount);
      Ingredient saved = repository.saveAndFlush(ingredient);
      return IngredientResponse.from(saved);
    }
  }

  /**
   * Persists the {@link GoodsReceipt} idempotency anchor and writes the {@code StockReceived}
   * outbox row, in the caller's already-open transaction (rule 3). {@code valueMinor} is the EXACT
   * amount paid for THIS receipt (never the cumulative moving-average bucket). No idempotency key
   * is threaded yet — the receive endpoint carries none today (ADR 0056 accepted-limitation #1
   * stays open pending an API key; see the V43 migration + {@link GoodsReceipt} class docs).
   */
  private void recordGoodsReceipt(
      String companyId, Ingredient ingredient, int qty, long valueMinor, String currency) {
    Instant receivedAt = Instant.now();
    GoodsReceipt receipt =
        GoodsReceipt.of(
            ingredient.getId(),
            ingredient.getBusinessId(),
            qty,
            valueMinor,
            currency,
            receivedAt,
            null);
    receipt.setCompanyId(companyId);
    GoodsReceipt saved = goodsReceiptRepository.saveAndFlush(receipt);

    GenericRecord event =
        StockReceivedSchema.toRecord(
            saved.getId(),
            companyId,
            ingredient.getBusinessId(),
            ingredient.getId(),
            qty,
            valueMinor,
            currency,
            receivedAt);
    outboxWriter.write(
        StockReceivedSchema.AGGREGATE_TYPE,
        saved.getId().toString(),
        StockReceivedSchema.EVENT_TYPE,
        AvroSerde.serialize(event),
        null,
        UUID.fromString(companyId),
        receivedAt);
  }

  private Ingredient load(UUID id) {
    return repository.findById(id).orElseThrow(() -> new IngredientNotFoundException(id));
  }
}
