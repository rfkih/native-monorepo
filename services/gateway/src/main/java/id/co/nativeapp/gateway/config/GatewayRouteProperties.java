package id.co.nativeapp.gateway.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Downstream route targets (12-factor, externalized — ENGINEERING-STANDARDS §7).
 *
 * <p>The gateway routes {@code /api/v1/companies/**} to org-service, {@code /api/v1/sales/**} to
 * restaurant-service, {@code /api/v1/revenue/**} to finance-service, and {@code
 * /api/v1/employees/**} / {@code /api/v1/payroll-*} to employee-service. Targets default to the
 * docker dev-stack hostnames and are overridden per environment. Each is {@code @NotNull}, so the
 * app fails fast at startup if a required target is missing or malformed.
 */
@Validated
@ConfigurationProperties("native.gateway.routes")
public record GatewayRouteProperties(
    @NotNull URI orgService,
    @NotNull URI restaurantService,
    @NotNull URI financeService,
    @NotNull URI employeeService) {}
