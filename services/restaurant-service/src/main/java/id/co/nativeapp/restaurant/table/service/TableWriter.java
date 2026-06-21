package id.co.nativeapp.restaurant.table.service;

import id.co.nativeapp.restaurant.table.domain.RestaurantTable;
import id.co.nativeapp.restaurant.table.dto.CreateTableRequest;
import id.co.nativeapp.restaurant.table.dto.TableResponse;
import id.co.nativeapp.restaurant.table.repository.RestaurantTableRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write units of work for the table feature.
 *
 * <p>A distinct bean from {@link TableService} so each transactional method is invoked through the
 * Spring proxy — self-invocation would bypass the {@link RlsAutoApplyAspect} that sets the tenant
 * GUC (same pattern as {@code SaleWriter}).
 */
@Component
public class TableWriter {

  private final RestaurantTableRepository tableRepository;

  public TableWriter(RestaurantTableRepository tableRepository) {
    this.tableRepository = tableRepository;
  }

  /**
   * Creates a new restaurant table in {@code REQUIRES_NEW} so the create-or-duplicate check and the
   * insert are atomic within their own transaction.
   *
   * @throws IllegalArgumentException if a table with the same label already exists in this business
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public TableResponse create(CreateTableRequest request) {
    String companyId = TenantContext.require().companyId();

    // Friendly 400 before the DB constraint fires.
    tableRepository
        .findViewByBusinessIdAndLabel(request.businessId(), request.label())
        .ifPresent(
            existing -> {
              throw new IllegalArgumentException(
                  "A table with label '"
                      + request.label()
                      + "' already exists in business "
                      + request.businessId());
            });

    RestaurantTable table =
        new RestaurantTable(
            request.businessId(), request.label(), request.capacity(), request.area());
    table.setCompanyId(companyId);
    return TableResponse.from(tableRepository.saveAndFlush(table));
  }

  /**
   * Activates a table (makes it selectable for new orders). Idempotent — activating an already
   * active table is a no-op.
   *
   * @throws IllegalArgumentException if the table is not found or not visible to this tenant
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public TableResponse activate(UUID tableId) {
    TenantContext.require();
    RestaurantTable table =
        tableRepository
            .findById(tableId)
            .orElseThrow(() -> new IllegalArgumentException("Table not found: " + tableId));
    table.activate();
    return TableResponse.from(tableRepository.saveAndFlush(table));
  }

  /**
   * Deactivates a table (prevents assignment to new orders). Idempotent — deactivating an already
   * inactive table is a no-op.
   *
   * @throws IllegalArgumentException if the table is not found or not visible to this tenant
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public TableResponse deactivate(UUID tableId) {
    TenantContext.require();
    RestaurantTable table =
        tableRepository
            .findById(tableId)
            .orElseThrow(() -> new IllegalArgumentException("Table not found: " + tableId));
    table.deactivate();
    return TableResponse.from(tableRepository.saveAndFlush(table));
  }
}
