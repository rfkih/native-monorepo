package id.co.nativeapp.restaurant.register.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request to open a drawer session (ADR 0036).
 *
 * @param businessId the outlet whose drawer opens (a real OUTLET id — ADR 0012)
 * @param openingFloatMinor the counted change fund, minor units (null → 0; must be ≥ 0)
 * @param currency ISO-4217 code of the drawer (must match the sales currency)
 * @param businessDate the outlet-local calendar day, or null → the server derives it from now in
 *     the v1 default zone (Asia/Jakarta — a v1 simplification documented in ADR 0036; a per-outlet
 *     timezone is the additive follow-up)
 */
public record OpenSessionRequest(
    UUID businessId, Long openingFloatMinor, String currency, LocalDate businessDate) {}
