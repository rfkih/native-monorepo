package id.co.nativeapp.restaurant.order.dto;

/**
 * Outcome of a checkout operation.
 *
 * @param order the order response
 * @param created {@code true} if this call inserted a new order + emitted one {@code SaleRecorded};
 *     {@code false} if an existing order with the same {@code (company_id, idempotency_key)} was
 *     returned and no second event was written
 */
public record CheckoutResult(OrderResponse order, boolean created) {}
