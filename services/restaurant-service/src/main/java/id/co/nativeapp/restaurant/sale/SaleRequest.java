package id.co.nativeapp.restaurant.sale;

import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;

/**
 * Record-sale request body. {@code occurredAt} is optional (defaults to now); the monetary amount
 * is integer {@code amountMinor} + ISO-4217 {@code currency} (never a float). {@code company_id} is
 * intentionally absent — it is taken from the tenant scope, not trusted from the client.
 *
 * <p>The bean-validation constraints are checked by {@code @Valid} on the handler param: a
 * missing/blank/non-positive field fails fast with a {@code 400} from {@link ApiExceptionHandler}
 * (a {@link org.springframework.web.bind.MethodArgumentNotValidException
 * MethodArgumentNotValidException}) instead of reaching the service. {@code amountMinor} must be
 * {@link Positive} — a sale is positive revenue. The same constraints are mirrored on {@link
 * RecordSaleCommand} so the application command is valid by construction even when assembled
 * outside this controller.
 */
public record SaleRequest(
    @NotNull UUID businessId,
    @NotNull @Positive Long amountMinor,
    @NotBlank String currency,
    Instant occurredAt,
    @NotBlank String idempotencyKey) {}
