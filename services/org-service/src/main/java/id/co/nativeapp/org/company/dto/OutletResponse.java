package id.co.nativeapp.org.company.dto;

import java.util.UUID;

/**
 * Response item for {@code GET /api/v1/outlets} — the POS outlet picker.
 *
 * <p>Contains only what the picker needs: the outlet's {@code id} (used when opening a sale) and
 * its display {@code name}. Mapped from the {@link
 * id.co.nativeapp.org.company.projection.OutletView} projection in the service layer — never
 * directly from an entity (DTO-at-the-boundary, CODE-STRUCTURE §3.3).
 *
 * @param id the outlet's UUID
 * @param name the outlet's display name
 */
public record OutletResponse(UUID id, String name) {}
