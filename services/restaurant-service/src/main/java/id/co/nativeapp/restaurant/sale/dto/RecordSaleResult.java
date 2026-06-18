package id.co.nativeapp.restaurant.sale.dto;

import id.co.nativeapp.restaurant.sale.service.SaleService;

/**
 * Outcome of {@link SaleService#recordSale(RecordSaleCommand)}.
 *
 * <p>Carries the recorded sale as a {@link SaleResponse} (the projected business columns), not the
 * JPA entity: the create path builds it from the freshly saved aggregate, while the idempotent /
 * conflict-recovery paths build it from a native-query projection — so a read path never loads (nor
 * leaks) the full {@code Auditable} entity.
 *
 * @param sale the recorded (or pre-existing) sale, as its response projection
 * @param created {@code true} if this call inserted a new sale + emitted the {@code SaleRecorded}
 *     event; {@code false} if an existing sale with the same {@code (company_id, idempotency_key)}
 *     was returned and NO second event was written (idempotency on retry)
 */
public record RecordSaleResult(SaleResponse sale, boolean created) {}
