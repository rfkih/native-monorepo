package id.co.nativeapp.carwash.ticket.dto;

import id.co.nativeapp.carwash.ticket.domain.ItemType;
import java.util.UUID;

/** One priced line in a {@link TicketResponse}. {@code priceMinor} is the UNIT price. */
public record TicketLineResponse(
    ItemType itemType, UUID itemId, String name, long priceMinor, String currency, int qty) {}
