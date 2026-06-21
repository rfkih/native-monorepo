package id.co.nativeapp.restaurant.table.service;

import id.co.nativeapp.restaurant.table.dto.CreateTableRequest;
import id.co.nativeapp.restaurant.table.dto.TableResponse;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates restaurant-table CRUD.
 *
 * <p>Not itself {@code @Transactional} — transactional units live in {@link TableWriter} (writes)
 * and {@link TableReader} (reads) so the proxy and the RLS aspect engage. Follows the same pattern
 * as {@code OrderService} / {@code SaleService}.
 */
@Service
public class TableService {

  private final TableWriter writer;
  private final TableReader reader;

  public TableService(TableWriter writer, TableReader reader) {
    this.writer = writer;
    this.reader = reader;
  }

  /**
   * Creates a new restaurant table. The table is active on creation.
   *
   * @throws IllegalArgumentException if a table with the same label already exists
   */
  public TableResponse create(CreateTableRequest request) {
    TenantContext.require();
    return writer.create(request);
  }

  /**
   * Lists all tables for a business with per-table occupancy.
   *
   * @param businessId the business unit whose tables to list
   */
  public List<TableResponse> listByBusiness(UUID businessId) {
    TenantContext.require();
    return reader.listByBusiness(businessId);
  }

  /**
   * Activates a table.
   *
   * @throws IllegalArgumentException if the table is not found
   */
  public TableResponse activate(UUID tableId) {
    TenantContext.require();
    return writer.activate(tableId);
  }

  /**
   * Deactivates a table.
   *
   * @throws IllegalArgumentException if the table is not found
   */
  public TableResponse deactivate(UUID tableId) {
    TenantContext.require();
    return writer.deactivate(tableId);
  }
}
