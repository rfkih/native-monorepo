package id.co.nativeapp.gateway.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Per-tenant token-bucket knobs (externalized — ENGINEERING-STANDARDS §7).
 *
 * <p>The bucket is keyed per {@code (company_id, sub)} and backed by Redis so the limit holds
 * across gateway replicas. {@code capacity} is the bucket size (max burst); the bucket refills at
 * {@code refillTokens} per {@code refillPeriod}. All three are required and positive, so an unset
 * or nonsensical limit fails fast at startup rather than silently disabling the limiter.
 */
@Validated
@ConfigurationProperties("native.gateway.rate-limit")
public record RateLimitProperties(
    @Min(1) long capacity, @Min(1) long refillTokens, @NotNull Duration refillPeriod) {}
