package id.co.nativeapp.barbershop.ticket.dto;

import id.co.nativeapp.barbershop.ticket.domain.ItemType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * One requested line on a {@link QuoteRequest} / {@link CheckoutRequest}: an item reference and a
 * quantity. The server resolves the item's price server-side — never trusts a client amount (rule
 * 8, the checkout's core invariant).
 *
 * @param itemType {@code SERVICE} or {@code ADDON}
 * @param itemId the {@code service_item}/{@code service_addon} row id, per {@code itemType}
 * @param qty the quantity; must be &ge; 1
 */
public record TicketLineInput(
    @NotNull ItemType itemType, @NotNull UUID itemId, @NotNull @Min(1) Integer qty) {}
