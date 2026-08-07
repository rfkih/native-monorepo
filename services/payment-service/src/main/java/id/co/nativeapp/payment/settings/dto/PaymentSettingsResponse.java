package id.co.nativeapp.payment.settings.dto;

import java.util.List;

/**
 * The owner view of the company's QRIS configuration (ADR 0045): the company default scope (or
 * {@code null} when never configured — effective mode is then the implicit MANUAL) plus any
 * per-outlet overrides.
 */
public record PaymentSettingsResponse(
    SettingsRowResponse companyDefault, List<SettingsRowResponse> outletOverrides) {}
