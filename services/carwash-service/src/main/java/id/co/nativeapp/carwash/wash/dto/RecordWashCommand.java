package id.co.nativeapp.carwash.wash.dto;

import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.UUID;

/**
 * The application command to record a wash, assembled at the request edge from the {@code POST
 * /api/v1/washes} body. The tenant ({@code company_id}) and actor are NOT here — they come from the
 * bound {@link id.co.nativeapp.tenant.TenantContext TenantContext}, never from the request body
 * (rule 5).
 *
 * <p>The bean-validation constraints reject malformed input at the controller edge (via
 * {@code @Valid}) so a bad request fails with a {@code 400} from the shared {@link
 * ApiExceptionHandler} rather than a downstream {@code 500}. {@code amountMinor} is {@link
 * Positive} because a wash is positive revenue. The currency is only checked for presence here; its
 * ISO-4217 validity is enforced by {@code libs/money} {@link id.co.nativeapp.money.Money} (also
 * mapped to {@code 400}).
 *
 * @param businessId the carwash outlet (org_unit) the wash was recorded at
 * @param bay the wash bay it ran on
 * @param amountMinor the wash total in the currency's minor units (never a float); must be positive
 * @param currency the ISO-4217 currency code
 * @param upsellName the optional upsell name; {@code null} for no upsell
 * @param upsellAmountMinor the optional upsell amount in minor units; {@code null} for no upsell
 * @param occurredAt when the wash occurred; the caller defaults a missing value to now
 * @param idempotencyKey the client's request id, the producer-idempotency dedupe key
 */
public record RecordWashCommand(
    @NotNull UUID businessId,
    @NotBlank String bay,
    @NotNull @Positive Long amountMinor,
    @NotBlank String currency,
    String upsellName,
    @PositiveOrZero Long upsellAmountMinor,
    Instant occurredAt,
    @NotBlank String idempotencyKey) {}
