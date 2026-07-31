package id.co.nativeapp.restaurant.selforder.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * One line of an ANONYMOUS self-order cart (Phase 6, ADR 0029). Structurally the shared {@link
 * id.co.nativeapp.restaurant.order.dto.OrderLineRequest}, but with a hard {@code qty} ceiling — the
 * self-order surface is unauthenticated, so an unbounded {@code qty} (up to {@code
 * Integer.MAX_VALUE}) reaching the {@code Math.multiplyExact} price arithmetic would throw an
 * uncaught {@code ArithmeticException} → 500 rather than a clean {@code 400} (security review F-2).
 * The authenticated checkout path keeps the unbounded {@code OrderLineRequest} unchanged; {@link
 * SelfOrderCreateRequest#toOrderLines()} maps these bounded lines onto it.
 *
 * @param menuItemId the menu item; resolved + priced server-side against the current menu
 * @param qty units, 1..{@value #MAX_QTY}
 * @param selectedOptionIds chosen modifier option ids (may be null/empty)
 */
public record SelfOrderLineBounds(
    @NotNull UUID menuItemId, @Min(1) @Max(MAX_QTY) int qty, List<UUID> selectedOptionIds) {

  /** Max units of a single item an anonymous diner may add — generous for a table, DoS-safe. */
  public static final int MAX_QTY = 99;
}
