package id.co.nativeapp.payment.settings.dto;

import java.util.List;

/**
 * The owner view of the company's QRIS configuration (ADR 0045): the company default scope (or
 * {@code null} when never configured — effective mode is then the implicit MANUAL) plus any
 * per-unit (outlet or division, ADR 0045 amendment) overrides. Field name kept as {@code
 * outletOverrides} — the list now also carries division rows; the console tells them apart from the
 * org tree it already holds.
 */
public record PaymentSettingsResponse(
    SettingsRowResponse companyDefault, List<SettingsRowResponse> outletOverrides) {}
