package id.co.nativeapp.restaurant.channel.dto;

import java.util.UUID;

/** Wire shape for a {@code sales_channel} row (list/create/patch responses). */
public record SalesChannelResponse(UUID id, String code, String name, boolean active) {}
