package id.co.nativeapp.barbershop.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized barbershop-service config, bound to {@code native.barbershop} and validated at
 * startup (fail fast — ENGINEERING-STANDARDS §7). No business config is hardcoded in Java.
 *
 * @param moduleKey the entitlement module this vertical is gated on (the {@code "barbershop"}
 *     module). Every ticket write is rejected with {@code 403} unless the company is entitled to
 *     it (the "real gating in the verticals", Phase-2).
 * @param cacheTtl the entitlement-check Redis cache TTL safety net; invalidation by the {@code
 *     EntitlementGranted}/{@code EntitlementRevoked} events is the primary consistency mechanism,
 *     this is only the backstop.
 */
@Validated
@ConfigurationProperties("native.barbershop")
public record BarbershopProperties(@NotBlank String moduleKey, @NotNull Duration cacheTtl) {}
