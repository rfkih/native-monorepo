package id.co.nativeapp.restaurant.bill.dto;

import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/bills/{id}/lines} — append a round of items to an OPEN bill.
 *
 * @param lines the lines to append; must be non-empty with qty &ge; 1 per line
 */
public record AppendLinesRequest(@NotEmpty @Valid List<OrderLineRequest> lines) {}
