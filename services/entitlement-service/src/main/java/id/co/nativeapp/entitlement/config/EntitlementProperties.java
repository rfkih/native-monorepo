package id.co.nativeapp.entitlement.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Externalized entitlement-service config, bound to {@code native.entitlement} and validated at
 * startup (fail fast — ENGINEERING-STANDARDS §7). No business config is hardcoded in Java: the
 * DEFAULT modules a new company is granted and the entitlement-check cache TTL are data, not code.
 *
 * @param defaultModules the module keys a new company is granted on {@code CompanyCreated} (the
 *     base verticals + the platform modules); each is validated against {@code module_catalog} when
 *     the defaults are granted, so an unknown key fails loudly rather than granting a phantom
 *     module
 * @param cacheTtl the entitlement-check Redis cache TTL safety net; invalidation by event is the
 *     primary consistency mechanism, this is only the backstop
 */
@Validated
@ConfigurationProperties("native.entitlement")
public record EntitlementProperties(
    @NotEmpty List<@NotNull String> defaultModules, @NotNull Duration cacheTtl) {}
