package id.co.nativeapp.restaurant.register.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * A cashier's counted / settled amount for one NON-CASH tender at close (ADR 0038 phase 2). Cash is
 * carried separately by {@code CloseSessionRequest.countedCashMinor} (it has the drawer-accurate
 * gift-card terms); this covers CARD / QRIS / ONLINE — the actual batch/report figure the cashier
 * reconciles against the recorded expected. Optional per tender: a tender left out of the close
 * request is simply not settled (no variance posted), so "online settles later via the platform
 * payout" still works.
 *
 * @param tenderType {@code CARD} | {@code QRIS} | {@code ONLINE} (CASH is rejected — use {@code
 *     countedCashMinor})
 * @param countedMinor the actual settled/batch amount, minor units (≥ 0)
 */
public record TenderCount(
    @NotBlank @Pattern(regexp = "CARD|QRIS|ONLINE", message = "must be CARD, QRIS or ONLINE") String tenderType,
    @NotNull @PositiveOrZero Long countedMinor) {}
