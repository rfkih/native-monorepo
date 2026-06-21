package id.co.nativeapp.restaurant.table.projection;

import java.util.UUID;

/**
 * Read projection over the {@code restaurant_table} row, optionally enriched with occupancy.
 *
 * <p>Backs the native read queries on {@code RestaurantTableRepository}. The {@code occupied} field
 * is {@code true} when an open order (status in {@code PENDING}, {@code AWAITING_PAYMENT}, {@code
 * PARKED}) currently references this table. Lives in its own {@code projection} package — a read
 * model is neither the write-side {@code domain} entity nor a request/response {@code dto}.
 */
public interface TableView {

  UUID getId();

  UUID getBusinessId();

  String getLabel();

  int getCapacity();

  String getArea();

  boolean isActive();

  /** {@code true} when an open/parked order currently references this table. */
  boolean isOccupied();
}
