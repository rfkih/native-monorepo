package id.co.nativeapp.restaurant.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.restaurant.PostgresRlsTestBase;
import id.co.nativeapp.restaurant.menu.dto.CreateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.dto.UpdateMenuItemRequest;
import id.co.nativeapp.restaurant.menu.service.MenuService;
import id.co.nativeapp.tenant.TenantContext;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for menu-item image support + the {@code PATCH /api/v1/menu/{itemId}} endpoint.
 *
 * <p>Convention tested: for {@code imageUrl} in PATCH — {@code null} (absent) = leave unchanged;
 * empty string = clear the image; any other non-null value = set/replace the image.
 *
 * <p>All tests use the same shared {@link PostgresRlsTestBase} Testcontainers setup (singleton
 * container, reset per-test via {@code @BeforeEach}).
 */
@SpringBootTest
class MenuItemImageEditTest extends PostgresRlsTestBase {

  private static final String TENANT = "a1a1a1a1-a1a1-a1a1-a1a1-a1a1a1a1a1a1";
  private static final String OTHER_TENANT = "b2b2b2b2-b2b2-b2b2-b2b2-b2b2b2b2b2b2";
  private static final String ACTOR = "admin-a1@example.co.id";
  private static final UUID BUSINESS = UUID.fromString("c3c3c3c3-c3c3-c3c3-c3c3-c3c3c3c3c3c3");

  // A compact fake data URL (well under the 3 MB cap)
  private static final String SAMPLE_IMAGE =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

  @Autowired private MenuService menuService;

  // ---------------------------------------------------------------------------
  // CREATE with imageUrl
  // ---------------------------------------------------------------------------

  @Test
  void createWithImageUrlReturnsItInGetMenu() throws Exception {
    UUID itemId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(
                            BUSINESS, "Nasi Goreng", "MAIN", 15_000L, "IDR", SAMPLE_IMAGE))
                    .id());

    List<MenuItemResponse> items =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    MenuItemResponse found =
        items.stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();

    assertThat(found.imageUrl()).isEqualTo(SAMPLE_IMAGE);
  }

  // ---------------------------------------------------------------------------
  // CREATE without imageUrl → null
  // ---------------------------------------------------------------------------

  @Test
  void createWithoutImageUrlReturnsNullImageUrl() throws Exception {
    UUID itemId =
        TenantContext.callAs(
            TENANT,
            ACTOR,
            () ->
                menuService
                    .createItem(
                        new CreateMenuItemRequest(BUSINESS, "Mie Goreng", "MAIN", 12_000L, "IDR"))
                    .id());

    List<MenuItemResponse> items =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    MenuItemResponse found =
        items.stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();

    assertThat(found.imageUrl()).isNull();
  }

  // ---------------------------------------------------------------------------
  // PATCH name
  // ---------------------------------------------------------------------------

  @Test
  void patchNameIsReflectedInGetMenu() throws Exception {
    UUID itemId = createItem("Soto Ayam", 10_000L, null);

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService.updateItem(
                itemId, new UpdateMenuItemRequest("Soto Betawi", null, null, null)));

    List<MenuItemResponse> items =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    MenuItemResponse found =
        items.stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
    assertThat(found.name()).isEqualTo("Soto Betawi");
    assertThat(found.priceMinor()).isEqualTo(10_000L);
    assertThat(found.imageUrl()).isNull(); // unchanged
  }

  // ---------------------------------------------------------------------------
  // PATCH price
  // ---------------------------------------------------------------------------

  @Test
  void patchPriceIsReflectedInGetMenu() throws Exception {
    UUID itemId = createItem("Bakso", 12_000L, null);

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> menuService.updateItem(itemId, new UpdateMenuItemRequest(null, null, 14_000L, null)));

    List<MenuItemResponse> items =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    MenuItemResponse found =
        items.stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
    assertThat(found.priceMinor()).isEqualTo(14_000L);
    assertThat(found.name()).isEqualTo("Bakso"); // unchanged
  }

  // ---------------------------------------------------------------------------
  // PATCH category
  // ---------------------------------------------------------------------------

  @Test
  void patchCategoryIsReflectedInGetMenu() throws Exception {
    UUID itemId = createItem("Ayam Bakar", 18_000L, null);

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> menuService.updateItem(itemId, new UpdateMenuItemRequest(null, "GRILL", null, null)));

    List<MenuItemResponse> items =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    MenuItemResponse found =
        items.stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
    assertThat(found.category()).isEqualTo("GRILL");
  }

  // ---------------------------------------------------------------------------
  // PATCH imageUrl — set new image
  // ---------------------------------------------------------------------------

  @Test
  void patchImageUrlSetsNewImage() throws Exception {
    UUID itemId = createItem("Es Teh", 5_000L, null);

    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService.updateItem(
                itemId, new UpdateMenuItemRequest(null, null, null, SAMPLE_IMAGE)));

    List<MenuItemResponse> items =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    MenuItemResponse found =
        items.stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
    assertThat(found.imageUrl()).isEqualTo(SAMPLE_IMAGE);
  }

  // ---------------------------------------------------------------------------
  // PATCH imageUrl — empty string clears the image
  // ---------------------------------------------------------------------------

  @Test
  void patchWithEmptyImageUrlClearsImage() throws Exception {
    UUID itemId = createItem("Kopi Susu", 8_000L, SAMPLE_IMAGE);

    // Verify image is present before clearing.
    List<MenuItemResponse> before =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    assertThat(
            before.stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow().imageUrl())
        .isEqualTo(SAMPLE_IMAGE);

    // PATCH with empty string = clear.
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () -> menuService.updateItem(itemId, new UpdateMenuItemRequest(null, null, null, "")));

    List<MenuItemResponse> after =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    assertThat(
            after.stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow().imageUrl())
        .isNull();
  }

  // ---------------------------------------------------------------------------
  // PATCH null imageUrl — image left unchanged
  // ---------------------------------------------------------------------------

  @Test
  void patchWithNullImageUrlLeavesImageUnchanged() throws Exception {
    UUID itemId = createItem("Teh Manis", 4_000L, SAMPLE_IMAGE);

    // PATCH with null imageUrl = leave unchanged.
    TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService.updateItem(
                itemId, new UpdateMenuItemRequest("Teh Manis Panas", null, null, null)));

    List<MenuItemResponse> items =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    MenuItemResponse found =
        items.stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
    assertThat(found.name()).isEqualTo("Teh Manis Panas");
    assertThat(found.imageUrl()).isEqualTo(SAMPLE_IMAGE); // still set
  }

  // ---------------------------------------------------------------------------
  // PATCH unknown id → NoSuchElementException (→ 404)
  // ---------------------------------------------------------------------------

  @Test
  void patchUnknownIdThrowsNoSuchElementException() {
    UUID unknown = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    TENANT,
                    ACTOR,
                    () ->
                        menuService.updateItem(
                            unknown, new UpdateMenuItemRequest("X", null, null, null))))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining(unknown.toString());
  }

  // ---------------------------------------------------------------------------
  // PATCH cross-tenant → same 404 (RLS hides the item)
  // ---------------------------------------------------------------------------

  @Test
  void patchItemBelongingToOtherTenantThrowsNoSuchElementException() throws Exception {
    UUID itemId = createItem("Rendang", 25_000L, null);

    // A different tenant cannot see (or edit) the item.
    assertThatThrownBy(
            () ->
                TenantContext.callAs(
                    OTHER_TENANT,
                    "hacker@other.co.id",
                    () ->
                        menuService.updateItem(
                            itemId, new UpdateMenuItemRequest("Hacked", null, null, null))))
        .isInstanceOf(NoSuchElementException.class);

    // The item is still visible and unmodified to the original tenant.
    List<MenuItemResponse> items =
        TenantContext.callAs(TENANT, ACTOR, () -> menuService.findActiveByBusiness(BUSINESS));
    MenuItemResponse found =
        items.stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
    assertThat(found.name()).isEqualTo("Rendang");
  }

  // ---------------------------------------------------------------------------
  // Oversized imageUrl on CREATE → Jakarta validation → ConstraintViolationException
  // ---------------------------------------------------------------------------

  @Test
  void oversizedImageUrlOnCreateFailsValidation() {
    // Build a string just over the 3 MB limit.
    String huge = "x".repeat(3_000_001);

    // The @Size constraint fires at the service boundary (Spring @Validated or bean-validation).
    // Because MenuService is NOT annotated with @Validated, validation fires in the controller
    // layer. In the service-layer integration test we call the service directly; the record's
    // @Size annotation is available for programmatic validation. We validate via the Jakarta
    // Validator directly here so the test exercises the same constraint without an HTTP layer.
    jakarta.validation.ValidatorFactory factory =
        jakarta.validation.Validation.buildDefaultValidatorFactory();
    jakarta.validation.Validator validator = factory.getValidator();

    var request = new CreateMenuItemRequest(BUSINESS, "X", "MAIN", 1_000L, "IDR", huge);
    var violations = validator.validate(request);

    assertThat(violations)
        .isNotEmpty()
        .anyMatch(v -> v.getPropertyPath().toString().equals("imageUrl"));
  }

  // ---------------------------------------------------------------------------
  // Oversized imageUrl on PATCH → Jakarta validation
  // ---------------------------------------------------------------------------

  @Test
  void oversizedImageUrlOnPatchFailsValidation() {
    String huge = "x".repeat(3_000_001);

    jakarta.validation.ValidatorFactory factory =
        jakarta.validation.Validation.buildDefaultValidatorFactory();
    jakarta.validation.Validator validator = factory.getValidator();

    var request = new UpdateMenuItemRequest(null, null, null, huge);
    var violations = validator.validate(request);

    assertThat(violations)
        .isNotEmpty()
        .anyMatch(v -> v.getPropertyPath().toString().equals("imageUrl"));
  }

  // ---------------------------------------------------------------------------
  // Helper
  // ---------------------------------------------------------------------------

  private UUID createItem(String name, long priceMinor, String imageUrl) throws Exception {
    return TenantContext.callAs(
        TENANT,
        ACTOR,
        () ->
            menuService
                .createItem(
                    new CreateMenuItemRequest(BUSINESS, name, "MAIN", priceMinor, "IDR", imageUrl))
                .id());
  }
}
