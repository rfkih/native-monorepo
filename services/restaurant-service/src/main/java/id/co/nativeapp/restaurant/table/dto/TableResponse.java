package id.co.nativeapp.restaurant.table.dto;

import id.co.nativeapp.restaurant.table.domain.RestaurantTable;
import java.util.UUID;

/**
 * Response body for a restaurant table, with optional occupancy information.
 *
 * <p>Mapping from the read-path projection lives in the service layer ({@code TableReader}) so that
 * the ArchUnit {@code Projection accessed only by Service + Repository} rule is respected — the
 * {@code dto} layer must not depend on {@code projection}.
 *
 * @param tableId the table id
 * @param businessId the owning business unit
 * @param label the table label (e.g. "T1")
 * @param capacity the seating capacity
 * @param area optional area/zone descriptor
 * @param active whether this table is currently active
 * @param occupied {@code true} when an open order (PENDING/AWAITING_PAYMENT/PARKED) references it
 */
public record TableResponse(
    UUID tableId,
    UUID businessId,
    String label,
    int capacity,
    String area,
    boolean active,
    boolean occupied) {

  /** Maps the write-path aggregate to the response shape (occupancy unknown — defaults false). */
  public static TableResponse from(RestaurantTable table) {
    return new TableResponse(
        table.getId(),
        table.getBusinessId(),
        table.getLabel(),
        table.getCapacity(),
        table.getArea(),
        table.isActive(),
        false);
  }
}
