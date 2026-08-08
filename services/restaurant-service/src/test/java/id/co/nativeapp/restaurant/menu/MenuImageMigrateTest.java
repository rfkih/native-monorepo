package id.co.nativeapp.restaurant.menu;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.dto.MigrateMenuImagesResponse;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Dual-read + owner-triggered backfill (ADR 0048): a pre-migration row still carrying an inline
 * base64 {@code image_url} keeps serving verbatim, {@code POST /api/v1/menu/images/migrate}
 * converts exactly the CURRENT tenant's legacy rows to object-store keys, and a re-run is a no-op.
 *
 * <p>Legacy rows are planted over the admin (BYPASSRLS) connection — the write path can no longer
 * produce them (convert-on-write), which is precisely the point.
 */
@SpringBootTest
class MenuImageMigrateTest extends PostgresRlsTestBase {

  private static final String TENANT = "a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1";
  private static final String OTHER_TENANT = "b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2";
  private static final String ACTOR = "admin-a1@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("c3c3c3c3-c3c3-c3c3-c3c3-c3c3c3c3c3c3");

  private static final String LEGACY_IMAGE =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

  @Autowired private MenuService menuService;

  @Test
  void legacyInlineImageServesVerbatimThenMigratesToPublicUrl() throws Exception {
    UUID itemId = createItemWithLegacyInlineImage(TENANT, "Gado-Gado");

    // Dual-read: the un-migrated row's data URL passes through unchanged.
    assertThat(imageUrlOf(itemId)).isEqualTo(LEGACY_IMAGE);

    // Owner-triggered backfill converts it…
    MigrateMenuImagesResponse first =
        TenantContext.callAs(TENANT, ACTOR, menuService::migrateImages);
    assertThat(first.migrated()).isEqualTo(1);
    assertThat(first.skipped()).isZero();

    // …to the content-addressed public media URL…
    assertThat(imageUrlOf(itemId))
        .startsWith("http://localhost:8090/api/media/restaurant/" + TENANT + "/menu/")
        .endsWith(".png");

    // …and a re-run finds nothing left (idempotent).
    MigrateMenuImagesResponse second =
        TenantContext.callAs(TENANT, ACTOR, menuService::migrateImages);
    assertThat(second.migrated()).isZero();
    assertThat(second.skipped()).isZero();
  }

  @Test
  void migrateTouchesOnlyTheCallingTenantsRows() throws Exception {
    UUID mine = createItemWithLegacyInlineImage(TENANT, "Sate Ayam");
    createItemWithLegacyInlineImage(OTHER_TENANT, "Sate Kambing");

    // The OTHER tenant's migrate run must not see (or convert) this tenant's legacy row — the
    // work list is RLS-scoped like every other query.
    MigrateMenuImagesResponse other =
        TenantContext.callAs(OTHER_TENANT, "admin-b2@example.co.id", menuService::migrateImages);
    assertThat(other.migrated()).isEqualTo(1); // its own row only

    assertThat(imageUrlOf(mine)).isEqualTo(LEGACY_IMAGE); // untouched
  }

  /**
   * Creates an item via the normal service path, then rewrites it into the PRE-ADR-0048 shape
   * (inline data URL, no object key) over the admin/BYPASSRLS connection.
   */
  private UUID createItemWithLegacyInlineImage(String tenant, String name) throws Exception {
    UUID itemId =
        TenantContext.callAs(
            tenant,
            ACTOR,
            () ->
                menuService
                    .createItem(new CreateMenuItemRequest(BUSINESS, name, "MAIN", 20_000L, "IDR"))
                    .id());
    try (Connection admin =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement ps =
            admin.prepareStatement(
                "UPDATE menu_item SET image_url = ?, image_key = NULL WHERE id = ?")) {
      ps.setString(1, LEGACY_IMAGE);
      ps.setObject(2, itemId);
      assertThat(ps.executeUpdate()).isEqualTo(1);
    }
    return itemId;
  }

  private String imageUrlOf(UUID itemId) throws Exception {
    List<MenuItemResponse> items =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    return items.stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow().imageUrl();
  }
}
