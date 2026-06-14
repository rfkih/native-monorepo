package id.co.nativeapp.restaurant;

/**
 * Outcome of {@link SaleService#recordSale(RecordSaleCommand)}.
 *
 * @param sale the recorded (or pre-existing) sale
 * @param created {@code true} if this call inserted a new sale + emitted the {@code SaleRecorded}
 *     event; {@code false} if an existing sale with the same {@code (company_id, idempotency_key)}
 *     was returned and NO second event was written (idempotency on retry)
 */
public record RecordSaleResult(Sale sale, boolean created) {}
