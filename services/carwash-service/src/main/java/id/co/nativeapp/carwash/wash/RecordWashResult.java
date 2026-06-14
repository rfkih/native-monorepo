package id.co.nativeapp.carwash.wash;

/**
 * Outcome of {@link WashService#recordWash(RecordWashCommand)}.
 *
 * @param wash the recorded (or pre-existing) wash
 * @param created {@code true} if this call inserted a new wash + emitted the {@code SaleRecorded} +
 *     {@code MetricPublished} events; {@code false} if an existing wash with the same {@code
 *     (company_id, idempotency_key)} was returned and NO second events were written (idempotency on
 *     retry)
 */
public record RecordWashResult(Wash wash, boolean created) {}
