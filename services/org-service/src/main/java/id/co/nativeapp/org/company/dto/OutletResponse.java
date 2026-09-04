package id.co.nativeapp.org.company.dto;

import java.util.UUID;

/**
 * Response item for {@code GET /api/v1/outlets} — the POS outlet picker.
 *
 * <p>Contains only what the picker needs: the outlet's {@code id} (used when opening a sale), its
 * display {@code name}, the parent business unit's {@code vertical} — the POS gates its surfaces on
 * it (cashiers cannot read the dashboard-only org-units endpoint) — and {@code divisionId}, the
 * outlet's parent org-unit id (the business unit / "division" in console vocabulary;
 * payment-service's ADR 0045 amendment consumes it to resolve DIVISION-scoped QRIS settings).
 * Mapped from the {@link id.co.nativeapp.org.company.projection.OutletView} projection in the
 * service layer — never directly from an entity (DTO-at-the-boundary, CODE-STRUCTURE §3.3).
 *
 * @param id the outlet's UUID
 * @param name the outlet's display name
 * @param vertical the parent business unit's LOWERCASE vertical key (restaurant | carwash |
 *     barbershop); null only for an anomalous parentless outlet — clients fail open
 * @param divisionId the outlet's parent org-unit id (its business unit); null only for an anomalous
 *     parentless outlet — clients fail open
 */
public record OutletResponse(UUID id, String name) {}
