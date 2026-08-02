package id.co.nativeapp.restaurant.channel.domain;

/**
 * Thrown when a caller whose role set does not include {@code owner} or {@code manager} attempts a
 * sales-channel CREATE/PATCH ({@code SalesChannelWriter}). A channel is money-routing configuration
 * — it decides how an {@code ONLINE} sale's platform receivable is bucketed at finance — the same
 * bar as {@code ManualDiscountForbiddenException}/promotion admin CRUD; a cashier may still LIST
 * channels (the checkout picker) but never mutate the catalog.
 *
 * <p>Maps to {@code 403 Forbidden} via {@code config.ChannelAdvice} ({@code
 * sales-channel-forbidden}). Empty-roles-pass semantics, mirroring {@code
 * ManualDiscountForbiddenException}: an EMPTY role set is let through (the {@code X-Roles} header
 * only exists behind the gateway, so a real cashier token is always denied; a headerless request is
 * the gateway-less dev recipe or a direct service-layer test).
 */
public class SalesChannelForbiddenException extends RuntimeException {

  public SalesChannelForbiddenException() {
    this("Sales channel writes require the owner or manager role");
  }

  public SalesChannelForbiddenException(String message) {
    super(message);
  }
}
