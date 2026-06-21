package id.co.nativeapp.restaurant.order.projection;

import java.util.UUID;

/**
 * Read projection over the {@code order_line_modifier} row — only the columns the receipt needs.
 *
 * <p>Backs native read queries on {@code OrderLineModifierRepository}. Lives in the feature's
 * {@code projection} package (accessed only by service + repository per the ArchUnit rule).
 *
 * <p>The {@code orderLineId} is present only on the batch-load query {@code
 * findViewsByOrderLineIds} so the service can group modifiers by their parent line.
 */
public interface OrderLineModifierView {

  UUID getId();

  /**
   * The parent order line id — populated by the batch-load query for grouping. May be null on
   * single-line queries.
   */
  UUID getOrderLineId();

  UUID getOptionId();

  String getNameSnapshot();

  long getPriceDeltaMinor();
}
