package id.co.nativeapp.restaurant.menu.dto;

/**
 * Response for {@code POST /api/v1/menu/images/migrate} (ADR 0048): how many of the tenant's legacy
 * inline base64 images were converted to the object store, and how many were skipped (payload no
 * longer validates — those keep rendering via dual-read and need manual attention). {@code migrated
 * + skipped = 0} means the tenant is fully migrated (the idempotent re-run case).
 */
public record MigrateMenuImagesResponse(int migrated, int skipped) {}
