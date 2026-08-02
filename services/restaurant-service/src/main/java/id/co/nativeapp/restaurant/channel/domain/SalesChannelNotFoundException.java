package id.co.nativeapp.restaurant.channel.domain;

import java.util.UUID;

/**
 * The referenced sales channel does not exist under the bound tenant (RLS makes a cross-tenant id
 * indistinguishable from a missing one — deliberately). Mapped to {@code 404 Not Found} ({@code
 * sales-channel-not-found}) by {@code config.ChannelAdvice}.
 */
public class SalesChannelNotFoundException extends RuntimeException {

  public SalesChannelNotFoundException(UUID id) {
    super("sales channel not found: " + id);
  }
}
