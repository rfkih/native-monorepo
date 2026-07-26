package id.co.nativeapp.restaurant.menu.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.money.Money;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Domain-unit tests for per-item stock tracking on {@link MenuItem}.
 *
 * <p>Covers all combinations of tracked/untracked, the boundary conditions on setStock/addStock,
 * the deduct no-op for untracked items, the InsufficientStockException path, and the isOrderable
 * matrix.
 */
class MenuItemStockTest {

  private static final UUID BUSINESS_ID = UUID.randomUUID();
  private static final Money PRICE = Money.ofMinor(15_000L, Currency.getInstance("IDR"));

  private MenuItem item;

  @BeforeEach
  void setUp() {
    item = new MenuItem(BUSINESS_ID, "Nasi Goreng", "MAIN", PRICE);
    item.setCompanyId("11111111-1111-1111-1111-111111111111");
  }

  // -----------------------------------------------------------------------
  // Default state (newly created item)
  // -----------------------------------------------------------------------

  @Test
  void newItemIsUntrackedByDefault() {
    assertThat(item.getStockQuantity()).isNull();
    assertThat(item.isTracked()).isFalse();
  }

  @Test
  void newItemIsOrderable() {
    // active=true, available=true, stockQuantity=null (untracked)
    assertThat(item.isOrderable()).isTrue();
  }

  // -----------------------------------------------------------------------
  // setStock
  // -----------------------------------------------------------------------

  @Test
  void setStockWithPositiveValueMakesItemTracked() {
    item.setStock(50);
    assertThat(item.getStockQuantity()).isEqualTo(50);
    assertThat(item.isTracked()).isTrue();
  }

  @Test
  void setStockWithZeroMakesItemTrackedAtZero() {
    item.setStock(0);
    assertThat(item.getStockQuantity()).isEqualTo(0);
    assertThat(item.isTracked()).isTrue();
  }

  @Test
  void setStockWithNullReturnsToUntracked() {
    item.setStock(100);
    item.setStock(null);
    assertThat(item.getStockQuantity()).isNull();
    assertThat(item.isTracked()).isFalse();
  }

  @Test
  void setStockWithNegativeThrows() {
    assertThatThrownBy(() -> item.setStock(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("stock_quantity must be >= 0");
  }

  // -----------------------------------------------------------------------
  // addStock
  // -----------------------------------------------------------------------

  @Test
  void addStockIncreasesTrackedQuantity() {
    item.setStock(10);
    item.addStock(5);
    assertThat(item.getStockQuantity()).isEqualTo(15);
  }

  @Test
  void addStockWithNegativeDeltaReducesQuantity() {
    item.setStock(10);
    item.addStock(-3);
    assertThat(item.getStockQuantity()).isEqualTo(7);
  }

  @Test
  void addStockFloorsAtZeroOnLargeMagnitudeNegativeDelta() {
    item.setStock(5);
    item.addStock(-100);
    assertThat(item.getStockQuantity()).isEqualTo(0);
  }

  @Test
  void addStockOnUntrackedItemThrowsUntrackedStockException() {
    // item is untracked by default
    assertThatThrownBy(() -> item.addStock(10))
        .isInstanceOf(UntrackedStockException.class)
        .hasMessageContaining("untracked")
        .hasMessageContaining("Set a stock quantity first");
  }

  // -----------------------------------------------------------------------
  // deduct
  // -----------------------------------------------------------------------

  @Test
  void deductOnUntrackedItemIsNoOp() {
    // no exception, no state change
    item.deduct(5);
    assertThat(item.getStockQuantity()).isNull();
  }

  @Test
  void deductOnTrackedItemReducesQuantity() {
    item.setStock(20);
    item.deduct(7);
    assertThat(item.getStockQuantity()).isEqualTo(13);
  }

  @Test
  void deductExactlyAvailableQuantityLeavesZero() {
    item.setStock(3);
    item.deduct(3);
    assertThat(item.getStockQuantity()).isEqualTo(0);
  }

  @Test
  void deductMoreThanAvailableThrowsInsufficientStockException() {
    item.setStock(2);
    assertThatThrownBy(() -> item.deduct(5))
        .isInstanceOf(InsufficientStockException.class)
        .hasMessageContaining("Insufficient stock")
        .hasMessageContaining("Nasi Goreng")
        .hasMessageContaining("requested 5")
        .hasMessageContaining("available 2");
  }

  @Test
  void insufficientStockExceptionCarriesCorrectFields() {
    item.setStock(1);
    InsufficientStockException ex =
        org.assertj.core.api.Assertions.catchThrowableOfType(
            () -> item.deduct(10), InsufficientStockException.class);
    assertThat(ex.getMenuItemId()).isEqualTo(item.getId());
    assertThat(ex.getItemName()).isEqualTo("Nasi Goreng");
    assertThat(ex.getRequested()).isEqualTo(10);
    assertThat(ex.getAvailable()).isEqualTo(1);
  }

  // -----------------------------------------------------------------------
  // isOrderable matrix
  // -----------------------------------------------------------------------

  @Test
  void inactiveItemIsNotOrderable() {
    item.deactivate();
    assertThat(item.isOrderable()).isFalse();
  }

  @Test
  void unavailableItemIsNotOrderable() {
    item.markUnavailable();
    assertThat(item.isOrderable()).isFalse();
  }

  @Test
  void trackedItemWithStockGreaterThanZeroIsOrderable() {
    item.setStock(5);
    assertThat(item.isOrderable()).isTrue();
  }

  @Test
  void trackedItemWithZeroStockIsNotOrderable() {
    item.setStock(0);
    assertThat(item.isOrderable()).isFalse();
  }

  @Test
  void trackedItemWithStockButMarkedUnavailableIsNotOrderable() {
    item.setStock(10);
    item.markUnavailable();
    assertThat(item.isOrderable()).isFalse();
  }

  @Test
  void untrackedItemIsOrderableRegardlessOfAnyStock() {
    // untracked = null → orderable as long as active + available
    assertThat(item.isOrderable()).isTrue();
  }
}
