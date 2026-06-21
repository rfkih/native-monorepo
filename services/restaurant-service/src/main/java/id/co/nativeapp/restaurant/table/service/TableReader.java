package id.co.nativeapp.restaurant.table.service;

import id.co.nativeapp.restaurant.table.dto.TableResponse;
import id.co.nativeapp.restaurant.table.projection.TableView;
import id.co.nativeapp.restaurant.table.repository.RestaurantTableRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the read-only {@code @Transactional} unit of work for the table feature.
 *
 * <p>A distinct bean from {@link TableService} so the {@link RlsAutoApplyAspect} engages via the
 * Spring proxy (same pattern as {@code OrderReader}).
 *
 * <p>Projection-to-DTO mapping lives here (service layer) — the ArchUnit rule requires that the
 * {@code projection} layer is accessed only by {@code service} and {@code repository}.
 */
@Component
public class TableReader {

  private final RestaurantTableRepository tableRepository;

  public TableReader(RestaurantTableRepository tableRepository) {
    this.tableRepository = tableRepository;
  }

  /**
   * Lists all tables for a business with their current occupancy status. RLS-scoped automatically.
   *
   * @param businessId the business unit whose tables to list
   * @return tables ordered by label, each with occupied=true when an open order references it
   */
  @Transactional(readOnly = true)
  public List<TableResponse> listByBusiness(UUID businessId) {
    TenantContext.require();
    return tableRepository.findViewsByBusinessId(businessId).stream()
        .map(TableReader::fromView)
        .toList();
  }

  /** Maps a read-path projection (with occupancy) to the response shape. Service-layer only. */
  static TableResponse fromView(TableView view) {
    return new TableResponse(
        view.getId(),
        view.getBusinessId(),
        view.getLabel(),
        view.getCapacity(),
        view.getArea(),
        view.isActive(),
        view.isOccupied());
  }
}
