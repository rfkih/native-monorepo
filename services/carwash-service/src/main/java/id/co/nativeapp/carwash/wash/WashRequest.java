package id.co.nativeapp.carwash.wash;

import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.UUID;

/**
 * Record-wash request body. {@code occurredAt} is optional (defaults to now); the monetary amount
 * is integer {@code amountMinor} + ISO-4217 {@code currency} (never a float). {@code company_id} is
 * intentionally absent — it is taken from the tenant scope, not trusted from the client.
 *
 * <p>The bean-validation constraints are checked by {@code @Valid} on the handler param: a
 * missing/blank/non-positive field fails fast with a {@code 400} from the shared {@link
 * ApiExceptionHandler}. {@code amountMinor} must be {@link Positive} — a wash is positive revenue.
 * The upsell is optional: when {@code upsellName} is present a {@code upsellAmountMinor} is
 * required (validated in the service); when absent both are ignored. The upsell shares the wash
 * currency (one transaction currency per wash). {@code amountMinor} is the wash TOTAL (wash + any
 * upsell).
 *
 * @param businessId the carwash outlet (org_unit) the wash was recorded at
 * @param bay the wash bay it ran on
 * @param amountMinor the wash total in the currency's minor units (never a float); must be positive
 * @param currency the ISO-4217 currency code
 * @param upsellName the optional upsell name; {@code null} for no upsell
 * @param upsellAmountMinor the optional upsell amount in minor units (required when {@code
 *     upsellName} is present); must be ≥ 0 when present
 * @param occurredAt when the wash occurred; the caller defaults a missing value to now
 * @param idempotencyKey the client's request id, the producer-idempotency dedupe key
 */
public record WashRequest(
    @NotNull UUID businessId,
    @NotBlank String bay,
    @NotNull @Positive Long amountMinor,
    @NotBlank String currency,
    String upsellName,
    @PositiveOrZero Long upsellAmountMinor,
    Instant occurredAt,
    @NotBlank String idempotencyKey) {}
