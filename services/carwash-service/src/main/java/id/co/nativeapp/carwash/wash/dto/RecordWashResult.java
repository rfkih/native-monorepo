package id.co.nativeapp.carwash.wash.dto;

import id.co.nativeapp.carwash.wash.service.WashService;

/**
 * Outcome of {@link WashService#recordWash(RecordWashCommand)}.
 *
 * <p>Carries the recorded wash as a {@link WashResponse} (the projected business columns), not the
 * JPA entity: the create path builds it from the freshly saved aggregate, while the idempotent /
 * conflict-recovery paths build it from a native-query projection — so a read path never loads (nor
 * leaks) the full {@code Auditable} entity.
 *
 * @param wash the recorded (or pre-existing) wash, as its response projection
 * @param created {@code true} if this call inserted a new wash + emitted the {@code SaleRecorded} +
 *     {@code MetricPublished} events; {@code false} if an existing wash with the same {@code
 *     (company_id, idempotency_key)} was returned and NO second events were written (idempotency on
 *     retry)
 */
public record RecordWashResult(WashResponse wash, boolean created) {}
